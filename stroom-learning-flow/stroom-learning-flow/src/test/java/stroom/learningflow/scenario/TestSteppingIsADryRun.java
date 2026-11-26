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

package stroom.learningflow.scenario;

import stroom.docref.DocRef;
import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.learning.LearnedStep;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.scoring.Verdict;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.Would;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.LearnerStates.Rolling;
import stroom.learningflow.stage.Stage;
import stroom.learningflow.stage.StageRun;
import stroom.learningflow.transformation.StandInXsltStep;
import stroom.meta.shared.MetaFields;
import stroom.util.shared.DocPath;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that stepping through a pipeline in the stepper is a dry run.
///
/// When stepping, the stage serves and does nothing else: no model is asked, and no fragment, binding,
/// output row, rolling score or reprocess request is written. A learner that nothing serves is reported
/// rather than learned for. This matters because a person often opens the stepper on a feed nobody has
/// taught yet. Learning it just by looking would spend a model call and write a fragment and a binding, none
/// of which they asked for and all of which would then serve live data.
class TestSteppingIsADryRun {

    private static final String FEED = "DOOR-ACCESS";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    /// A transform that produces no events from the records it is given. It compiles and runs, so the only
    /// gate it fails is the one that counts the events produced.
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

    /// A step is not the learner running. Stepping a pipeline under a different document from the one its
    /// learner's rows were written under leaves them where they are (only a processed stream moves them), and
    /// a stream served in a step is not added to the learner's rolling score.
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
                .isEqualTo("Wait: Learning Flow is disabled for this document and nothing serves this "
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
        final LearningFlowDoc doc = doc(LearningMode.AUTOMATIC);

        // Learn for real first, so that there is something to step over.
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

    /// A binding whose transform fails its yield gate is still shown serving in a step, with what it made of
    /// the stream, because a step decides nothing and leaves the binding in place.
    @Test
    void aProvisionalBindingBelowTheFloorIsShownServingAsItStands() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> Script.of(), scenarios.learnerBindings);
        final LearningFlowDoc doc = doc(LearningMode.AUTOMATIC);

        // A binding added by hand, whose transform produces nothing from the records: there are enough
        // records to judge it on, and it cannot pass the yield gate.
        final DocRef fragment = scenarios.stores.writer().write(
                DocPath.fromParts("Learning Flow", FEED), "on-trial-v1",
                List.of(new LearnedStep(scenarios.runners().getFirst(), CSV.configuration(),
                                new StepResult("<records/>", List.of()), UNSCORED),
                        new LearnedStep(new StandInXsltStep(), YIELDS_NOTHING,
                                new StepResult("<Events/>", List.of()), UNSCORED)),
                null);
        scenarios.learnerBindings.append(Scenarios.LEARNER, "doc-1", Binding.builder()
                .uuid("rule-on-trial")
                .pipeline(fragment)
                .build());

        final StageRun stepped = stage.dryRun(doc, Scenarios.LEARNER, stream(), false);

        assertThat(stepped.decision()).isInstanceOf(Bound.class);
        assertThat(((Bound) stepped.decision()).binding().getUuid()).isEqualTo("rule-on-trial");
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("the binding is still there: a step decides nothing").hasSize(1);
    }

    // There is no test of stepping with another learner's fragment: a learner is one element in one pipeline,
    // so no fragment learned for another learner is tried on this one.

    private Script script(final Scenarios scenarios) {
        return scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    /// @param mode Whether the document may learn.
    /// @return A document allowing a Data Splitter and an XSLT, with yield as a gate.
    private static LearningFlowDoc doc(final LearningMode mode) {
        return Scenarios.document()
                .uuid("doc-1")
                .name("door-access")
                .learningMode(mode)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(1)
                // Yield is a gate, so that a binding that produces nothing from its records fails it.
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        CheckSetting.of(Check.COVERAGE, CheckState.OFF),
                        new CheckSetting(Check.YIELD, CheckState.GATE, 0.8,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        CheckSetting.of(Check.CONFORMANCE, CheckState.OFF),
                        CheckSetting.of(Check.QUALITY, CheckState.OFF),
                        CheckSetting.of(Check.RULES, CheckState.OFF),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
                .build();
    }

    private static Input stream() {
        return new Input(1L, FEED, "Raw Events", Map.of("Format", "CSV"), CSV.input(), "pipeline-1");
    }
}
