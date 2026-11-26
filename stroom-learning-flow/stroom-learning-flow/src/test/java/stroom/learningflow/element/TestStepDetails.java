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

package stroom.learningflow.element;

import stroom.docref.DocRef;
import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.scenario.QuestionMatcher;
import stroom.learningflow.scenario.Scenarios;
import stroom.learningflow.scenario.Script;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningFlowStepDetails;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StageScore;
import stroom.learningflow.shared.StageVerdict;
import stroom.learningflow.shared.SupervisorTurn;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Decision;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.Deferred;
import stroom.learningflow.stage.Decision.Drafted;
import stroom.learningflow.stage.Decision.GivenUp;
import stroom.learningflow.stage.Decision.Kept;
import stroom.learningflow.stage.Decision.Parked;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Decision.Rebound;
import stroom.learningflow.stage.Decision.Sentinel;
import stroom.learningflow.stage.Decision.Would;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Stage;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests what the stepper's Smart Pipeline pane shows, which is built from the decision the stage made.
///
/// Other elements show their document's code in the stepper, but the Smart Pipeline element has none to
/// show, so its pane shows the decision instead. The decision is also what the scenario tests assert on, so a
/// person stepping a stream sees the same facts the tests check.
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

        final LearningFlowStepDetails details = StepDetails.of(run, stream(), false);

        assertThat(details.isDryRun()).isFalse();
        assertThat(details.getDocument().getUuid()).isEqualTo("doc-1");
        assertThat(details.getStream())
                .describedAs("the feed and type are what the model is shown of the stream")
                .containsExactly(Map.entry("Feed", FEED), Map.entry("Type", "Raw Events"));
        assertThat(details.getDecision()).isEqualTo("Promoted");
        assertThat(details.getReason()).startsWith("Learned \"").contains("from this stream and served it");
        assertThat(details.getBindingUuid()).isEqualTo(scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0)
                .getUuid());
        assertThat(details.getFragment())
                .describedAs("carried as a reference so the pane can offer it as a link")
                .isEqualTo(run.produced().fragment());
        assertThat(details.getScore()).isEqualTo(run.produced().score());

        assertThat(details.getVerdicts())
                .describedAs("one per step of the chain, in chain order")
                .hasSize(2)
                .extracting(StageVerdict::getStep).containsExactly(1, 2);
        // Checked across the chain rather than per step, because which scorers apply depends on a step's
        // input: the scorers of meaning only apply to a step whose input was already records.
        assertThat(details.getVerdicts()).flatExtracting(StageVerdict::getScores)
                .describedAs("and every check that applied, with what it was measured against")
                .isNotEmpty()
                .allSatisfy(score -> assertThat(score.getCheck()).isNotNull());
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

        final LearningFlowStepDetails details = StepDetails.of(run, stream(), true);

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

    /// Checks that every kind of decision has a description.
    ///
    /// `StepDetails.describe` switches exhaustively over the sealed `Decision` type, so a new kind of decision
    /// will not compile until it has a description. The count below makes sure this test's list is updated
    /// too. A missing description matters: an earlier separate switch with a throwing `default` made every
    /// successful stream fail when it was first called for a decision that bound something.
    @Test
    void everyDecisionSaysWhatItWas() {
        final Binding binding = Binding.builder().uuid("rule-1")
                .pipeline(DocRef.builder().type("Pipeline").uuid("f-1").name("door-v1").build())
                .build();
        final List<Decision> all = List.of(
                new Bound(binding),
                new Bound(null),
                new Promoted(binding, 0.9),
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

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(1)
                // Checks are set explicitly, because a document with no scorers produces empty verdicts and
                // this test is about what the pane shows of them.
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        CheckSetting.of(Check.COVERAGE, CheckState.OFF),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.5,
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
