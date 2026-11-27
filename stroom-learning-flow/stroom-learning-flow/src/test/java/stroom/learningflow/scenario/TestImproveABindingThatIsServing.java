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

import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.learning.Question.Chain;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Decision.Kept;
import stroom.learningflow.stage.Decision.Rebound;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.RegressionSet.Accepted;
import stroom.learningflow.stage.RegressionSet.Kind;
import stroom.learningflow.stage.Stage;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests a person asking a serving binding to improve, when nothing is failing and nothing has flagged it.
///
/// An improvement does not wait for a stream to arrive. The records a binding was accepted on are kept in its
/// regression corpus, with the score each achieved, so an improvement session has both a sample to learn from
/// and a standard to beat without any new stream. This means a feed that arrives once a day can be improved
/// at any time. The existing binding serves throughout, and a candidate replaces it only by passing the
/// normal promotion gate.
class TestImproveABindingThatIsServing {

    private static final String FEED = "DOOR-ACCESS";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    /// A transform that produces no events from the records it is given. It compiles and runs, so the only
    /// check it fails is the one that counts the events produced.
    private static final String YIELDS_NOTHING = """
            <xsl:stylesheet xmlns="event-logging:3" xpath-default-namespace="records:2"
                            xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0">
              <xsl:template match="records"><Events/></xsl:template>
            </xsl:stylesheet>""";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");

    @Test
    void aBetterCandidateReboundsTheBindingAndTheMessageIsCarriedIntoTheAsking() {
        final Scenarios scenarios = new Scenarios();
        final Script learning = learn(scenarios);
        final Stage stage = scenarios.stage(document -> learning, scenarios.learnerBindings);
        final LearningFlowDoc doc = doc();

        final StageRun learned = scenarios.process(learning, doc, stream()).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(learned.produced()).isNotNull();

        // The improvement is requested with no stream arriving, so the regression corpus is the sample.
        final Script improving = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final StageRun improved = scenarios.improved(document -> improving, doc, bound.getUuid(),
                "The fourth column is a terminal id, not a user.", "jo");

        assertThat(improved.decision())
                .describedAs("a candidate that is no worse on what the binding was accepted on takes over")
                .isInstanceOf(Rebound.class);
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("the same binding, rebound: it keeps its uuid and its history (§7.3 rule 2)")
                .hasSize(1)
                .first()
                .satisfies(binding -> assertThat(binding.getUuid()).isEqualTo(bound.getUuid()));
        assertThat(scenarios.guidance.standing(Scenarios.LEARNER))
                .describedAs("the message is guidance for the learner, so it is carried into this attempt "
                             + "and stands for the relearning after it")
                .extracting(given -> given.message() + " (" + given.author() + ")")
                .containsExactly("The fourth column is a terminal id, not a user. (jo)");
    }

    @Test
    void aCandidateThatIsWorseOnAnAcceptedRecordIsRefusedAndTheIncumbentServesOn() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.learnerBindings);
        final LearningFlowDoc doc = doc();
        scenarios.process(learn(scenarios), doc, stream()).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);

        // The corpus is replaced by hand with one entry. A candidate that does worse than the binding on any
        // record the binding was accepted on is not an improvement. The simplest way to make a candidate do
        // worse is an entry claiming an event was produced from a record that holds nothing to produce one
        // from. The entry's text is an ordinary line, so the sample still parses and the candidate still
        // clears the score threshold; it is the corpus comparison alone that refuses it.
        scenarios.regressionSet.discard(bound.getUuid());
        final String[] lines = CSV.input().split("\n");
        scenarios.regressionSet.accept("doc-1", bound.getUuid(), FEED, "Raw Events", List.of(new Accepted(
                "<records xmlns=\"records:2\"/>", lines[1], lines[0] + "\n", "\n", "<Events><Event/></Events>",
                Kind.TRANSLATION, 1.0, 7L, 0L, null)), doc.getRegressionCap());

        final Script improving = learn(scenarios);
        final StageRun improved = scenarios.improved(document -> improving, doc, bound.getUuid(), null, "jo");

        assertThat(improved.decision())
                .describedAs("worse on a record the binding was accepted on is not an improvement, whatever "
                             + "else it does (A18)")
                .isInstanceOf(Kept.class);
        assertThat(((Kept) improved.decision()).reason())
                .contains("makes nothing of a record that yielded an event")
                .contains("record 1 of 1 in the corpus, accepted by attempt 7");
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0).getPipeline())
                .describedAs("and the incumbent is still bound, having served throughout")
                .isEqualTo(bound.getPipeline());
    }

    @Test
    void whatCannotBeImprovedSaysSoRatherThanGuessing() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.learnerBindings);
        final LearningFlowDoc doc = doc();
        scenarios.process(learn(scenarios), doc, stream()).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);

        assertThatThrownBy(() -> stage.improve(doc, "not-a-rule", null, "jo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a binding of this document");

        scenarios.regressionSet.discard(bound.getUuid());
        assertThatThrownBy(() -> stage.improve(doc, bound.getUuid(), null, "jo"))
                .describedAs("without a record it was accepted on there is nothing to learn from and "
                             + "nothing to beat: an improvement judged on nothing is a promotion in "
                             + "disguise")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no accepted records");
    }

    /// Checks that an improvement does not override decisions made elsewhere. It is refused when learning is
    /// disabled or a draft is already awaiting review, and it leaves a learner's mark for relearning in place
    /// until it has actually promoted.
    @Test
    void anImprovementRefusesWhatOtherDecisionsHaveSettled() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.learnerBindings);
        final LearningFlowDoc doc = doc();
        scenarios.process(learn(scenarios), doc, stream()).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);

        assertThatThrownBy(() -> stage.improve(doc().copy().learningMode(LearningMode.DISABLED).build(),
                bound.getUuid(), null, "jo"))
                .describedAs("the kill switch is a kill switch: a button that asked anyway would be a "
                             + "way round it")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");

        scenarios.states.awaitReview(Scenarios.LEARNER, "doc-1", "some-draft");
        assertThatThrownBy(() -> stage.improve(doc, bound.getUuid(), null, "jo"))
                .describedAs("one draft per learner: a second would overwrite the pointer that says which "
                             + "is awaiting review, leaving the first undecidable")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already awaiting review");
        scenarios.states.reset(Scenarios.LEARNER);

        // A learner marked for relearning counts as relearned once the improvement promotes, so the mark is
        // cleared then, not when the improvement is requested. An improvement that fails must leave the mark
        // in place, since whatever it was marked for has not been dealt with.
        scenarios.states.markForRelearning(Scenarios.LEARNER, "doc-1", "drifting");
        final Script improving = learn(scenarios);
        scenarios.stage(document -> improving, scenarios.learnerBindings)
                .improve(doc, bound.getUuid(), null, "jo");
        assertThat(scenarios.states.relearnReason(Scenarios.LEARNER))
                .describedAs("asking only opens the attempt: the model has not been asked yet, so nothing "
                             + "has been learned and the mark still stands")
                .contains("drifting");

        scenarios.worker(improving).advance(10);
        assertThat(scenarios.states.relearnReason(Scenarios.LEARNER))
                .describedAs("and once it has promoted the mark is spent, or the next stream would "
                             + "relearn the binding somebody has only now improved")
                .isEmpty();
    }

    /// Checks that requesting an improvement only opens an attempt awaiting the model, and the background
    /// worker asks the questions. A model conversation can take minutes. Run inside the web request, it would
    /// hold an HTTP thread throughout, would not survive the node restarting, and would be lost if the browser
    /// timed out.
    @Test
    void askingOpensAnAttemptForTheWorkerAndAsksTheModelNothing() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        scenarios.process(learn(scenarios), doc, stream()).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);

        final Script improving = learn(scenarios);
        scenarios.stage(document -> improving, scenarios.learnerBindings)
                .improve(doc, bound.getUuid(), null, "jo");

        assertThat(improving.asked())
                .describedAs("nothing was put to the model while the request was open")
                .isEmpty();
        final Recorded opened = scenarios.attempts.open(Scenarios.LEARNER, Scenarios.NOW.toEpochMilli())
                .orElseThrow();
        assertThat(opened.status())
                .describedAs("parked at its first question, for the worker to find")
                .isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(opened.attempt().isImprovement())
                .describedAs("and the attempt says it is an improvement, which is the one thing the "
                             + "bindings cannot tell the worker")
                .isTrue();
        assertThat(opened.attempt().improving()).isEqualTo(bound.getUuid());
        assertThat(opened.attempt().inputId())
                .describedAs("there is no stream behind it: its sample is the corpus")
                .isNull();

        // The worker then resumes the attempt, taking its sample from the regression corpus.
        assertThat(scenarios.worker(improving).advance(10)).isEqualTo(1);
        improving.verifyExhausted();
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("the same binding, rebound by the improvement the worker carried")
                .hasSize(1)
                .first()
                .satisfies(binding -> assertThat(binding.getUuid()).isEqualTo(bound.getUuid()));
    }

    /// An improvement whose corpus records have gone while it waited is not resumed and the model is not
    /// asked. A candidate judged against no records would be promoted without having beaten anything.
    @Test
    void anImprovementWhoseRecordsHaveGoneIsClosedRatherThanAsked() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        scenarios.process(learn(scenarios), doc, stream()).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        final Script improving = learn(scenarios);
        scenarios.stage(document -> improving, scenarios.learnerBindings)
                .improve(doc, bound.getUuid(), null, "jo");

        // This stands for the binding being retracted, relearned or pruned while the attempt waited.
        scenarios.regressionSet.discard(bound.getUuid());

        assertThat(scenarios.worker(improving).advance(10))
                .describedAs("nothing was carried on")
                .isZero();
        assertThat(improving.asked()).describedAs("and the model was not asked").isEmpty();
    }

    /// An improvement's questions show the corpus alone. There is no stream to take a feed and stream type
    /// from, and a learner is not tied to one feed, so no header is shown that might not hold for every
    /// stream the learner serves.
    @Test
    void theQuestionsAreAboutTheCorpusAndNoFeed() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.learnerBindings);
        final LearningFlowDoc doc = doc();
        scenarios.process(learn(scenarios), doc, stream()).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);

        final Script asking = learn(scenarios);
        final StageRun improved = scenarios.improved(document -> asking, doc, bound.getUuid(), null, "jo");

        assertThat(improved.transcript()).isNotEmpty();
        assertThat(asking.asked()).first()
                .describedAs("a learner does not know its feed (A126), and an improvement has no stream to "
                             + "read one off, so the corpus is shown with no feed or type")
                .isInstanceOfSatisfying(Chain.class, chain -> assertThat(chain.sample().headers()).isEmpty());
    }

    private Script learn(final Scenarios scenarios) {
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
                // The checks are set explicitly, because a document with no scorers scores every chain 1.0,
                // and then no candidate could ever be worse than the binding it would replace.
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        CheckSetting.of(Check.COVERAGE, CheckState.OFF),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.8,
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
