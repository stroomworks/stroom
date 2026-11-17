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

package stroom.shapeshifter.client.component;

import stroom.docref.DocRef;
import stroom.shapeshifter.client.component.FeedSituation.Tone;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.LearnerRef;
import stroom.shapeshifter.shared.LearnerState;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.StopCause;
import stroom.shapeshifter.shared.StopReport;
import stroom.shapeshifter.shared.Suggestion;
import stroom.shapeshifter.shared.SuggestionAction;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.shapeshifter.shared.SupervisorLearner;
import stroom.shapeshifter.shared.SupervisorTurn;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestFeedSituation {

    private static final long NOW = 10L * 3_600_000L;
    private static final DocRef DOC_REF = new DocRef(ShapeshifterAiDoc.TYPE, "ss-1", "Firewall logs");
    private static final DocRef FRAGMENT = new DocRef("Pipeline", "f-1", "Firewall fragment");
    private static final ShapeshifterAiDoc DOC = ShapeshifterAiDoc.builder()
            .uuid("ss-1")
            .name("Firewall logs")
            .promotionFloor(0.85)
            .relearnThreshold(0.8)
            .maxAttempts(3)
            .flow(LearningFlow.of(FlowExample.DIRECT))
            .build();
    private static final List<SupervisorTurn> STOPPED_AT_PARSER = List.of(
            turn(1, "chain", QuestionKind.CHAIN, 1, "DSParser", StepOutcome.PASSED),
            turn(2, "parser", QuestionKind.CONFIGURE, 1, "<dataSplitter/>", StepOutcome.COVERAGE_SHORT),
            turn(3, "parser", QuestionKind.CONFIGURE, 2, null, null));

    @Test
    void aDraftAsksForApproval() {
        final FeedSituation situation = FeedSituation.of(learner(LearnerState.AWAITING_PERSON, null, 2, false),
                run(AttemptStatus.AWAITING_REVIEW, List.of()), DOC, NOW);
        assertThat(situation.getChip()).isEqualTo("Approve");
        assertThat(situation.getTone()).isEqualTo(Tone.WARNING);
        assertThat(situation.getHeadline()).isEqualTo("A new translation is ready for you to check");
        assertThat(situation.getDetail()).isEqualTo("It scored 94% (the pass mark is 85%). Approving it puts it to "
                                                    + "use and tries the 2 waiting streams again.");
        assertThat(situation.getPrimary()).containsExactly(FeedAction.APPROVE, FeedAction.REJECT);
        // Rejecting is the way to give up while a draft awaits review
        assertThat(situation.getMore()).contains(FeedAction.RELEARN).doesNotContain(FeedAction.GIVE_UP);
    }

    @Test
    void aPausedRunOffersMoreTheFallbackAndAnAnswer() {
        final FeedSituation situation = FeedSituation.of(learner(LearnerState.AWAITING_PERSON, null, 2, false),
                run(AttemptStatus.PARKED, STOPPED_AT_PARSER), DOC, NOW);
        assertThat(situation.getChip()).isEqualTo("Paused");
        assertThat(situation.getHeadline()).isEqualTo("Paused: out of time");
        assertThat(situation.getDetail()).isEqualTo("This run has used 12,000 tokens. 2 streams are waiting, the "
                                                    + "oldest for 3 hours.");
        assertThat(situation.getPrimary()).containsExactly(FeedAction.GRANT, FeedAction.EXHAUST, FeedAction.ANSWER);
        assertThat(situation.getMore()).containsExactly(FeedAction.RELEARN, FeedAction.GIVE_UP);
    }

    @Test
    void aPausedRunWithNoQuestionOpenOffersNoAnswer() {
        final List<SupervisorTurn> answered = List.of(STOPPED_AT_PARSER.get(0), STOPPED_AT_PARSER.get(1));
        final FeedSituation situation = FeedSituation.of(learner(LearnerState.AWAITING_PERSON, null, 0, false),
                run(AttemptStatus.PARKED, answered), DOC, NOW);
        assertThat(situation.getPrimary()).containsExactly(FeedAction.GRANT, FeedAction.EXHAUST);
        assertThat(situation.getDetail()).endsWith("No streams are waiting.");
    }

    @Test
    void aLearningRunSaysWhereItIs() {
        final FeedSituation situation = FeedSituation.of(learner(LearnerState.LEARNING, null, 1, false),
                run(AttemptStatus.AWAITING_MODEL, STOPPED_AT_PARSER), DOC, NOW);
        assertThat(situation.getChip()).isEqualTo("Learning");
        assertThat(situation.getHeadline()).isEqualTo("Learning: writing the parser, try 2 of 3");
        assertThat(situation.getDetail()).isEqualTo("1 stream is waiting, for 3 hours.");
        assertThat(situation.getPrimary()).isEmpty();
    }

    @Test
    void aStoppedFeedOffersLearningAgain() {
        final SupervisorLearner learner = new SupervisorLearner(new LearnerRef("p-1", "shapeshifterAi"), null,
                DOC_REF, LearnerState.GIVEN_UP, "Rejected by admin", null, null, false, null, null, 0, 0, null,
                false, null, 0, false, false);
        final FeedSituation situation = FeedSituation.of(learner, null, DOC, NOW);
        assertThat(situation.getChip()).isEqualTo("Stopped");
        assertThat(situation.getTone()).isEqualTo(Tone.DANGER);
        assertThat(situation.getHeadline()).isEqualTo("Stopped learning: rejected by admin");
        assertThat(situation.getPrimary()).containsExactly(FeedAction.RELEARN);
        assertThat(situation.getMore()).doesNotContain(FeedAction.GIVE_UP);
    }

    @Test
    void aTranslationInUseSaysWhatItScores() {
        final FeedSituation situation = FeedSituation.of(learner(LearnerState.SERVING, "b-1", 0, false),
                run(AttemptStatus.PROMOTED, List.of()), DOC, NOW);
        assertThat(situation.getChip()).isEqualTo("Translating");
        assertThat(situation.getDetail()).isEqualTo("It scores 98% lately, over 1,204 records. It scored 95% when "
                                                    + "it was put to use.");
        assertThat(situation.getPrimary()).containsExactly(FeedAction.IMPROVE);
        assertThat(situation.getMore()).containsExactly(FeedAction.RELEARN, FeedAction.GIVE_UP, FeedAction.RETRACT);
    }

    /// A person who may only read the feed's pipeline is offered nothing that changes its learning: what it
    /// says is the same, and nothing is left under More (its translation has its own link).
    @Test
    void readOnlyOffersOnlyWhatOpensSomethingToRead() {
        final FeedSituation serving = FeedSituation.of(learner(LearnerState.SERVING, "b-1", 0, false),
                run(AttemptStatus.PROMOTED, List.of()), DOC, NOW);
        final FeedSituation read = serving.readOnly();

        assertThat(read.getHeadline()).isEqualTo(serving.getHeadline());
        assertThat(read.getDetail()).isEqualTo(serving.getDetail());
        assertThat(read.getPrimary()).isEmpty();
        assertThat(read.getMore()).isEmpty();

        final FeedSituation paused = FeedSituation.of(learner(LearnerState.AWAITING_PERSON, null, 2, false),
                run(AttemptStatus.PARKED, STOPPED_AT_PARSER), DOC, NOW).readOnly();
        assertThat(paused.getPrimary()).isEmpty();
        assertThat(paused.getMore()).isEmpty();
    }

    @Test
    void aTranslationOnTrialOffersAcceptance() {
        final FeedSituation situation = FeedSituation.of(learner(LearnerState.SERVING, "b-1", 0, true),
                null, DOC, NOW);
        assertThat(situation.getChip()).isEqualTo("On trial");
        assertThat(situation.getPrimary()).containsExactly(FeedAction.ACCEPT);
        assertThat(FeedSituation.listChip(learner(LearnerState.SERVING, "b-1", 0, true))).isEqualTo("On trial");
    }

    @Test
    void learningOffSendsAPersonToSettings() {
        final FeedSituation situation = FeedSituation.of(learner(LearnerState.LEARNING_OFF, null, 3, false),
                null, DOC, NOW);
        assertThat(situation.getChip()).isEqualTo("Off");
        assertThat(situation.getPrimary()).containsExactly(FeedAction.OPEN_SETTINGS);
    }

    @Test
    void aDeletedPipelineOffersNothingThatWouldChangeIt() {
        final SupervisorLearner learner = new SupervisorLearner(new LearnerRef("p-2", "shapeshifterAi"), null,
                DOC_REF, LearnerState.GIVEN_UP, null, null, FRAGMENT, false, null, null, 0, 0, null, false, null, 0,
                true, false);
        final SupervisorAttempt parked = new SupervisorAttempt(8, DOC_REF, learner.getLearner(), "FIREWALL",
                "Raw Events", 101L, "node1a", PromotionMode.AUTOMATIC, AttemptStatus.PARKED, "Out of time", null,
                null, 0, 0, 0, false, 0, 0, STOPPED_AT_PARSER, true);
        final FeedSituation situation = FeedSituation.of(learner, parked, DOC, NOW);
        assertThat(situation.getChip()).isEqualTo("Deleted");
        assertThat(situation.getPrimary()).isEmpty();
        assertThat(situation.getMore()).isEmpty();
        assertThat(FeedSituation.listChip(learner)).isEqualTo("Deleted");
    }

    @Test
    void theListSaysWhatEachRowNeeds() {
        final SupervisorLearner serving = learner(LearnerState.SERVING, "b-1", 4, false);
        assertThat(FeedSituation.listChip(serving)).isEqualTo("Translating");
        assertThat(FeedSituation.listTone(serving)).isEqualTo(Tone.GOOD);
        assertThat(FeedSituation.listSummary(serving)).isEqualTo("Scores 98% · 4 waiting");
        final SupervisorLearner waiting = learner(LearnerState.AWAITING_PERSON, null, 2, false);
        assertThat(FeedSituation.listChip(waiting)).isEqualTo("Needs you");
        assertThat(FeedSituation.listTone(waiting)).isEqualTo(Tone.WARNING);
    }

    @Test
    void theQuestionARunStoppedAtIsFound() {
        assertThat(FeedSituation.unanswered(run(AttemptStatus.PARKED, STOPPED_AT_PARSER)).getNumber()).isEqualTo(3);
        assertThat(FeedSituation.unanswered(run(AttemptStatus.PARKED, List.of()))).isNull();
        assertThat(FeedSituation.unanswered(null)).isNull();
    }

    @Test
    void aPausedRunWithAReportSaysItsErrorAndOffersOnlyWhatIsNotAlreadyAButton() {
        final StopReport report = new StopReport(StopCause.TIME_BUDGET, "parser",
                "Ran out of time at \"Write the parser\" (question 3).",
                List.of("Each run may take 5 minutes; this one had taken 5 minutes 2 seconds."),
                List.of(new Suggestion("Give it more time.", SuggestionAction.GIVE_MORE),
                        new Suggestion("Raise \"Time for each run\" on the Setup tab.",
                                SuggestionAction.CHANGE_LIMITS),
                        new Suggestion("Open the model's document.", SuggestionAction.OPEN_MODEL)));

        final FeedSituation situation = FeedSituation.of(learner(LearnerState.AWAITING_PERSON, null, 2, false),
                run(AttemptStatus.PARKED, STOPPED_AT_PARSER, report), DOC, NOW);

        assertThat(situation.getHeadline()).isEqualTo("Paused: ran out of time at \"Write the parser\" (question 3).");
        assertThat(situation.getDetails())
                .containsExactly("Each run may take 5 minutes; this one had taken 5 minutes 2 seconds.");
        assertThat(situation.getSuggestions()).hasSize(3);
        // Give it more is a button already; the document names no model to open
        assertThat(situation.getSuggestions()).filteredOn(situation::canDo).extracting(Suggestion::getAction)
                .containsExactly(SuggestionAction.CHANGE_LIMITS);
    }

    @Test
    void aFailedRunSaysSoRatherThanLookingAsIfItIsStillLearning() {
        final StopReport report = new StopReport(StopCause.MODEL_TIMEOUT, null,
                "The model \"GPT-4o\" didn't reply in time.", List.of(),
                List.of(new Suggestion("Raise the timeout.", SuggestionAction.OPEN_MODEL)));
        final ShapeshifterAiDoc withModel = DOC.copy().model(new DocRef("OpenAIModel", "m-1", "GPT-4o")).build();

        final FeedSituation situation = FeedSituation.of(learner(LearnerState.LEARNING, null, 0, false),
                run(AttemptStatus.ERROR, List.of(), report), withModel, NOW);

        assertThat(situation.getChip()).isEqualTo("Failed");
        assertThat(situation.getTone()).isEqualTo(Tone.DANGER);
        assertThat(situation.getHeadline()).isEqualTo("Failed: the model \"GPT-4o\" didn't reply in time.");
        assertThat(situation.getDetail())
                .isEqualTo("Learning tries again from the feed's next stream. No streams are waiting.");
        assertThat(situation.getSuggestions()).allMatch(situation::canDo);
    }

    @Test
    void aFailedRunWithNoReportStillSaysWhatItSaid() {
        final FeedSituation situation = FeedSituation.of(learner(LearnerState.LEARNING, null, 0, false),
                run(AttemptStatus.ERROR, List.of()), DOC, NOW);

        assertThat(situation.getHeadline()).isEqualTo("Failed: out of time");
        assertThat(situation.getDetails()).isEmpty();
        assertThat(situation.getSuggestions()).isEmpty();
    }

    @Test
    void aSuggestionsActionIsTheFeedsOwnWhereItHasOne() {
        assertThat(FeedSituation.feedAction(SuggestionAction.GIVE_MORE)).isEqualTo(FeedAction.GRANT);
        assertThat(FeedSituation.feedAction(SuggestionAction.LEARN_AGAIN)).isEqualTo(FeedAction.RELEARN);
        assertThat(FeedSituation.feedAction(SuggestionAction.CHANGE_LIMITS)).isNull();
    }

    @Test
    void historyIsWantedWhileARunIsGoingOrWaitsForSomeone() {
        final SupervisorLearner learning = learner(LearnerState.LEARNING, null, 0, false);
        assertThat(FeedSituation.needsHistory(learning, run(AttemptStatus.IN_PROGRESS, List.of()))).isTrue();
        assertThat(FeedSituation.needsHistory(learning, run(AttemptStatus.AWAITING_MODEL, List.of()))).isTrue();
        assertThat(FeedSituation.needsHistory(learning, run(AttemptStatus.PARKED, STOPPED_AT_PARSER))).isTrue();
        assertThat(FeedSituation.needsHistory(learning, run(AttemptStatus.AWAITING_REVIEW, List.of()))).isTrue();
    }

    @Test
    void historyIsFoldedWhileItIsJustTranslating() {
        final SupervisorLearner serving = learner(LearnerState.SERVING, "b-1", 0, false);
        assertThat(FeedSituation.needsHistory(serving, run(AttemptStatus.PROMOTED, List.of()))).isFalse();
        assertThat(FeedSituation.needsHistory(serving, null)).isFalse();
    }

    @Test
    void historyIsWantedWhereStreamsWait() {
        final SupervisorLearner waiting = learner(LearnerState.SERVING, "b-1", 2, false);
        assertThat(FeedSituation.needsHistory(waiting, run(AttemptStatus.PROMOTED, List.of()))).isTrue();
    }

    @Test
    void historyIsFoldedForAStoppedRunWhoseReportIsInSight() {
        final SupervisorLearner stopped = learner(LearnerState.GIVEN_UP, null, 0, false);
        assertThat(FeedSituation.needsHistory(stopped, run(AttemptStatus.ABANDONED, STOPPED_AT_PARSER))).isFalse();
    }

    @Test
    void historyIsNeverWantedForADeletedPipeline() {
        final SupervisorLearner deleted = new SupervisorLearner(new LearnerRef("p-2", "shapeshifterAi"), null,
                DOC_REF, LearnerState.LEARNING, null, null, null, false, null, null, 0, 3, null, false, null, 0,
                true, false);
        assertThat(FeedSituation.needsHistory(deleted, run(AttemptStatus.PARKED, STOPPED_AT_PARSER))).isFalse();
    }

    private static SupervisorLearner learner(final LearnerState state,
                                             final String binding,
                                             final int waiting,
                                             final boolean provisional) {
        return new SupervisorLearner(new LearnerRef("p-1", "shapeshifterAi"),
                new DocRef("Pipeline", "p-1", "Firewall pipeline"), DOC_REF, state, null, binding,
                binding == null
                        ? null
                        : FRAGMENT,
                provisional, binding == null
                        ? null
                        : 0.95, binding == null
                        ? null
                        : 0.98, 1204, waiting,
                waiting == 0
                        ? null
                        : NOW - 3 * 3_600_000L,
                false, null, 0, false, false);
    }

    private static SupervisorAttempt run(final AttemptStatus status, final List<SupervisorTurn> turns) {
        return run(status, turns, null);
    }

    private static SupervisorAttempt run(final AttemptStatus status,
                                         final List<SupervisorTurn> turns,
                                         final StopReport report) {
        return new SupervisorAttempt(7, DOC_REF, new LearnerRef("p-1", "shapeshifterAi"), "FIREWALL", "Raw Events",
                101L, "node1a", PromotionMode.AUTOMATIC, status, "Out of time", null, 0.94, 12000, 0, 0, false, 0,
                0, turns, false, report);
    }

    private static SupervisorTurn turn(final int number,
                                       final String stepId,
                                       final QuestionKind kind,
                                       final int candidate,
                                       final String answer,
                                       final StepOutcome outcome) {
        return new SupervisorTurn(number, stepId, candidate, kind, "Question " + number, answer,
                answer == null
                        ? null
                        : "model", outcome, null);
    }
}
