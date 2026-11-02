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

package stroom.shapeshifter.ai.element;

import stroom.docref.DocRef;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.scenario.QuestionMatcher;
import stroom.shapeshifter.ai.scenario.Scenarios;
import stroom.shapeshifter.ai.scenario.Script;
import stroom.shapeshifter.ai.stage.Decision;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Deferred;
import stroom.shapeshifter.ai.stage.Decision.Drafted;
import stroom.shapeshifter.ai.stage.Decision.GivenUp;
import stroom.shapeshifter.ai.stage.Decision.Kept;
import stroom.shapeshifter.ai.stage.Decision.Parked;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Provisional;
import stroom.shapeshifter.ai.stage.Decision.Rebound;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.Decision.Would;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiStepDetails;
import stroom.shapeshifter.shared.StageScore;
import stroom.shapeshifter.shared.StageVerdict;
import stroom.shapeshifter.shared.SupervisorTurn;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 01 §11.7 and A30: what the stepper's stage pane shows, from what the stage decided.
///
/// A supervisor has no document to show as code, so the pane is the decision — and the decision is
/// already everything a scenario asserts on (design 02 §1). This holds the mapping to that: what a
/// person reads in the stepper is what the tests read.
class TestStepDetails {

    private static final String FEED = "DOOR-ACCESS";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");

    @Test
    void aLearnedStreamShowsItsFeedDecisionFragmentScoresAndTranscript() {
        final Scenarios scenarios = new Scenarios();
        final Script script = script(scenarios);
        final Stage stage = scenarios.stage(document -> script, scenarios.learnerBindings);
        final StageRun run = scenarios.process(script, doc(), stream()).learned();

        final ShapeshifterAiStepDetails details = StepDetails.of(run, stream(), false);

        assertThat(details.isDryRun()).isFalse();
        assertThat(details.getDocument().getUuid()).isEqualTo("doc-1");
        assertThat(details.getStream())
                .describedAs("the feed and type are what the model is shown of the stream")
                .containsExactly(Map.entry("Feed", FEED), Map.entry("Type", "Raw Events"));
        assertThat(details.getDecision()).isEqualTo("Promoted");
        assertThat(details.getReason()).startsWith("Promoted binding ");
        assertThat(details.getBindingUuid()).isEqualTo(scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0)
                .getUuid());
        assertThat(details.getFragment())
                .describedAs("carried as a reference so the pane can offer it as a link")
                .isEqualTo(run.produced().fragment());
        assertThat(details.isProvisional()).isFalse();
        assertThat(details.getScore()).isEqualTo(run.produced().score());

        assertThat(details.getVerdicts())
                .describedAs("one per step of the chain, in chain order")
                .hasSize(2)
                .extracting(StageVerdict::getStep).containsExactly(1, 2);
        // Across the chain rather than per step, because which scorers apply depends on what a step was
        // given: the scorers of meaning apply to a step whose input was already records (design 01 §4).
        assertThat(details.getVerdicts()).flatExtracting(StageVerdict::getScores)
                .describedAs("and every scorer that applied, with what it was measured against")
                .isNotEmpty()
                .allSatisfy(score -> assertThat(score.getScorer()).isNotNull());
        assertThat(details.getVerdicts()).flatExtracting(StageVerdict::getScores)
                .extracting(StageScore::isMet)
                .describedAs("a promoted chain met what it was measured against").doesNotContain(false);

        assertThat(details.getTranscript())
                .describedAs("every exchange with the model, as the Supervisor view carries a turn")
                .isNotEmpty()
                .extracting(SupervisorTurn::getKind)
                .startsWith(QuestionKind.CHAIN);
        assertThat(details.getTranscript())
                .extracting(SupervisorTurn::getNumber).startsWith(1);
    }

    @Test
    void aSteppedStreamNothingServesSaysWhatItWouldDoAndNamesNoBinding() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> Script.of(), scenarios.learnerBindings);
        final StageRun run = stage.dryRun(doc(), Scenarios.LEARNER, stream(), false);

        final ShapeshifterAiStepDetails details = StepDetails.of(run, stream(), true);

        assertThat(details.isDryRun())
                .describedAs("the pane has to say that this is what would happen, not what did")
                .isTrue();
        assertThat(details.getDecision()).isEqualTo("Would");
        assertThat(details.getReason())
                .describedAs("the hypothetical is in the decision itself, not put there by the pane: a "
                             + "step over a learner a binding serves really does run the fragment")
                .isEqualTo("Would learn a fragment for this learner");
        assertThat(details.getBindingUuid()).isNull();
        assertThat(details.getFragment()).isNull();
        assertThat(details.getVerdicts()).isEmpty();
        assertThat(details.getTranscript()).isEmpty();
    }

    /// Every decision gets a line, and this is the test that says so.
    ///
    /// It exists because the error stream once had a switch of its own with a `default` that threw, and
    /// it was only ever called for a decision that bound nothing — until it was called for one that did,
    /// and every successful stream fataled. One description now, exhaustive over a sealed type, so a new
    /// outcome will not compile until it has one; the count below is what stops a new outcome being
    /// added to that switch and forgotten here.
    @Test
    void everyDecisionSaysWhatItWas() {
        final Binding binding = Binding.builder().uuid("rule-1")
                .pipeline(DocRef.builder().type("Pipeline").uuid("f-1").name("door-v1").build())
                .build();
        final List<Decision> all = List.of(
                new Bound(binding),
                new Bound(null),
                new Promoted(binding, 0.9),
                new Provisional(binding, 0.9, 3, 5),
                new Rebound(binding, binding, 0.95),
                new Kept(binding, "the candidate was worse"),
                new Drafted(binding, 0.9),
                new GivenUp("nothing passed", List.of()),
                new Sentinel("nothing serves this learner"),
                new Deferred("waiting on a session"),
                new Parked("the attempt's budget is spent"),
                new Would("learn a fragment for this learner"));

        assertThat(all).allSatisfy(decision -> assertThat(StepDetails.describe(decision))
                .describedAs(decision.getClass().getSimpleName() + " says nothing for itself")
                .isNotBlank());
        assertThat(all.stream().map(decision -> decision.getClass()).distinct().count())
                .describedAs("one of every kind of decision there is: a kind with no line is a surface "
                             + "that goes blank where somebody most needs it to speak")
                .isEqualTo(Decision.class.getPermittedSubclasses().length);
    }

    private Script script(final Scenarios scenarios) {
        return scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    private static ShapeshifterAiDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(1)
                // Named, because a document with no scorers produces verdicts with nothing in them and
                // this is about what the pane shows of them.
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build();
    }

    private static Input stream() {
        return new Input(1L, FEED, "Raw Events", Map.of("Format", "CSV"), CSV.input(), "pipeline-1");
    }
}
