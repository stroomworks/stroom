/*
 * Copyright 2026 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.shapeshifter.ai.scenario;

import stroom.docref.DocRef;
import stroom.meta.shared.MetaFields;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.learning.LearnedStep;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.scoring.Verdict;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Would;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.LearnerStates.Rolling;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;
import stroom.util.shared.DocPath;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 01 §11.7 and A30: **stepping is a dry run**. The stage serves, and does nothing
/// else — no model asked, no fragment written, no binding written, no output row, no rolling
/// score and no reprocess request. A learner nothing serves is reported rather than learned for.
///
/// The case that makes it matter is the ordinary one: a person opens the stepper on a feed nobody has
/// taught yet. Learning it by looking would spend a model call, write a fragment and write a binding, none
/// of which they asked for and all of which would then be serving live data.
class TestSteppingIsADryRun {

    private static final String FEED = "DOOR-ACCESS";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    /// A transform that makes nothing of the records it is given: it compiles and runs, so the gate it
    /// fails is the one that counts what came out.
    private static final String YIELDS_NOTHING = """
            <xsl:stylesheet xmlns="event-logging:3" xpath-default-namespace="records:2"
                            xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0">
              <xsl:template match="records"><Events/></xsl:template>
            </xsl:stylesheet>""";
    private static final Verdict UNSCORED = new Verdict(List.of(), 1.0);

    @Test
    void aLearnerNothingServesIsReportedRatherThanLearned() {
        final Scenarios scenarios = new Scenarios();
        final Script script = script(scenarios);
        final Stage stage = scenarios.stage(document -> script, scenarios.learnerBindings);

        final StageRun stepped = stage.dryRun(doc(LearningMode.AUTOMATIC), Scenarios.LEARNER, stream(), false);

        assertThat(stepped.decision()).isInstanceOf(Would.class);
        assertThat(((Would) stepped.decision()).what()).isEqualTo("Learn a fragment for this learner");
        assertThat(((Would) stepped.decision()).said())
                .describedAs("the hypothetical is in the decision, so that every surface reads it the "
                             + "same way and only a decision that is hypothetical reads as one")
                .isEqualTo("Would learn a fragment for this learner");
        assertThat(script.asked()).describedAs("the model was not asked").isEmpty();
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).describedAs("no binding was written")
                .isEmpty();
        assertThat(scenarios.attempts.forDocument("doc-1", 10)).describedAs("no attempt was recorded").isEmpty();
        assertThat(scenarios.outputs.asProcessed("doc-1", 1L, "pipeline-1"))
                .describedAs("nor recorded as having produced anything").isEmpty();
        assertThat(scenarios.states.isEmpty()).describedAs("nor anything the learner settled into").isTrue();
        assertThat(scenarios.deferrals.forPipeline(Scenarios.LEARNER.pipelineUuid()))
                .describedAs("nor left waiting").isEmpty();
        assertThat(scenarios.wake.pipelines()).describedAs("and no pipeline was woken").isEmpty();
    }

    /// Design 06 A121: a step is not the learner running. A pipeline stepped under another document than
    /// the one its learner's rows were written under leaves them there — only a stream processed moves
    /// them — and a stream served in a step is not scored into the learner's rolling score.
    @Test
    void aStepMovesNoRowsAndScoresNothing() {
        final Scenarios scenarios = new Scenarios();
        final Script script = script(scenarios);
        final Stage stage = scenarios.stage(document -> script, scenarios.learnerBindings);
        scenarios.process(script, doc(LearningMode.AUTOMATIC), stream()).learned();
        final String bindingUuid = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).getFirst().getUuid();
        final Optional<Rolling> scoredBefore = scenarios.states.rolling(Scenarios.LEARNER);

        final StageRun stepped = stage.dryRun(doc(LearningMode.AUTOMATIC).copy().uuid("doc-2").build(),
                Scenarios.LEARNER, stream(), false);

        assertThat(stepped.decision()).isInstanceOf(Bound.class);
        assertThat(scenarios.learnerBindings.byUuid(bindingUuid).orElseThrow().docUuid())
                .describedAs("the binding is still under the document it was learned under")
                .isEqualTo("doc-1");
        assertThat(scenarios.states.rolling(Scenarios.LEARNER))
                .describedAs("and the step added nothing to the rolling score")
                .isEqualTo(scoredBefore);
        assertThat(scenarios.wake.pipelines()).describedAs("the promotion's wake, and none from the step")
                .containsExactly(Scenarios.LEARNER.pipelineUuid());
    }

    @Test
    void aDisabledDocumentSaysWhatItWouldDoAndWritesNothing() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> Script.of(), scenarios.learnerBindings);

        final StageRun stepped = stage.dryRun(doc(LearningMode.DISABLED), Scenarios.LEARNER, stream(), false);

        assertThat(((Would) stepped.decision()).what())
                .isEqualTo("Wait: Shapeshifter AI is disabled for this document and nothing serves this "
                           + "learner");
        assertThat(scenarios.deferrals.forPipeline(Scenarios.LEARNER.pipelineUuid()))
                .describedAs("a wait a person only asked to see is not a wait").isEmpty();
        assertThat(scenarios.states.isEmpty()).isTrue();
    }

    @Test
    void aBoundLearnerIsServedAndNothingIsRecorded() {
        final Scenarios scenarios = new Scenarios();
        final Script script = script(scenarios);
        final Stage stage = scenarios.stage(document -> script, scenarios.learnerBindings);
        final ShapeshifterAiDoc doc = doc(LearningMode.AUTOMATIC);

        // Learned for real first, so that there is something to step over.
        final StageRun learned = scenarios.process(script, doc, stream()).learned();
        assertThat(learned.produced()).isNotNull();

        final StageRun stepped = stage.dryRun(doc, Scenarios.LEARNER, stream(), false);

        assertThat(stepped.decision()).isInstanceOf(Bound.class);
        assertThat(stepped.output())
                .describedAs("the fragment ran: a person steps a pipeline to see what it makes")
                .isEqualTo(learned.output());
        assertThat(stepped.verdicts()).describedAs("and what the scorers made of it").isNotEmpty();
        assertThat(scenarios.outputs.asProcessed("doc-1", 2L, "pipeline-1"))
                .describedAs("but the run is not an output: nothing was produced for anyone")
                .isEmpty();
    }

    /// A provisional binding is on trial (A14): a stream that brings enough records and clears the floor
    /// promotes it, and one that does not is served by it as it stands — the floor no longer retracts it
    /// (A101) — so a step shows the binding serving, and what it made of the stream.
    @Test
    void aProvisionalBindingBelowTheFloorIsShownServingAsItStands() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> Script.of(), scenarios.learnerBindings);
        final ShapeshifterAiDoc doc = doc(LearningMode.AUTOMATIC);

        // A binding on trial, bound to a transform that makes nothing of the records: enough records to be
        // judged on, and a score that cannot clear the floor.
        final DocRef fragment = scenarios.stores.writer().write(
                DocPath.fromParts("Shapeshifter", FEED), "on-trial-v1",
                List.of(new LearnedStep(scenarios.runners().getFirst(), CSV.configuration(),
                                new StepResult("<records/>", List.of()), UNSCORED),
                        new LearnedStep(new XsltStep(), YIELDS_NOTHING,
                                new StepResult("<Events/>", List.of()), UNSCORED)),
                null);
        scenarios.learnerBindings.append(Scenarios.LEARNER, "doc-1", Binding.builder()
                .uuid("rule-on-trial")
                .pipeline(fragment)
                .provisional(true)
                .build());

        final StageRun stepped = stage.dryRun(doc, Scenarios.LEARNER, stream(), false);

        assertThat(stepped.decision()).isInstanceOf(Bound.class);
        assertThat(((Bound) stepped.decision()).binding().getUuid()).isEqualTo("rule-on-trial");
        assertThat(stepped.produced().provisional()).describedAs("still on trial").isTrue();
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("the binding is still there: a step decides nothing").hasSize(1);
    }

    // A variant that would be bound is retired with routing (design 06 phase J slice 2a): a learner is one
    // element in one pipeline, so no fragment is learned for a neighbouring learner to be tried on this one.

    private Script script(final Scenarios scenarios) {
        return scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    /// @param mode Whether the document may learn.
    private static ShapeshifterAiDoc doc(final LearningMode mode) {
        return Scenarios.document()
                .uuid("doc-1")
                .name("door-access")
                .learningMode(mode)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(1)
                .promotionFloor(0.85)
                // Named, because a document with no scorers scores every chain 1.0 and nothing it binds
                // could ever fail the gate it is on trial for.
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.8, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build();
    }

    private static Input stream() {
        return new Input(1L, FEED, "Raw Events", Map.of("Format", "CSV"), CSV.input(), "pipeline-1");
    }
}
