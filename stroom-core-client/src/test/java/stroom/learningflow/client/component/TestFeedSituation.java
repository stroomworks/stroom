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

package stroom.learningflow.client.component;

import stroom.docref.DocRef;
import stroom.learningflow.client.component.FeedSituation.Tone;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearnerRef;
import stroom.learningflow.shared.LearnerState;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.StopCause;
import stroom.learningflow.shared.StopReport;
import stroom.learningflow.shared.Suggestion;
import stroom.learningflow.shared.SuggestionAction;
import stroom.learningflow.shared.SupervisorAttempt;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.shared.SupervisorTurn;
import stroom.learningflow.shared.TimeZoneSource;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestFeedSituation {

    private static final long NOW = 10L * 3_600_000L;
    private static final DocRef DOC_REF = new DocRef(LearningFlowDoc.TYPE, "ss-1", "Firewall logs");
    private static final DocRef FRAGMENT = new DocRef("Pipeline", "f-1", "Firewall fragment");
    private static final LearningFlowDoc DOC = LearningFlowDoc.builder()
            .uuid("ss-1")
            .name("Firewall logs")
            .relearnThreshold(0.8)
            .maxAttempts(3)
            .flow(new LearningFlow(FlowExampleFixtures.steps(FlowExample.DIRECT), null, null))
            .build();
    private static final List<SupervisorTurn> STOPPED_AT_PARSER = List.of(
            turn(1, "chain", QuestionKind.CHAIN, 1, "DSParser", StepOutcome.PASSED),
            turn(2, "parser", QuestionKind.CONFIGURE, 1, "<dataSplitter/>", StepOutcome.COVERAGE_SHORT),
            turn(3, "parser", QuestionKind.CONFIGURE, 2, null, null));

    @Test
    void aDraftAsksForApproval() {
        final FeedSituation situation = FeedSituation.of(learner(LearnerState.AWAITING_PERSON, null, 2),
                run(AttemptStatus.AWAITING_REVIEW, List.of()), DOC, NOW);
        assertThat(situation.getChip()).isEqualTo("Approve");
        assertThat(situation.getTone()).isEqualTo(Tone.WARNING);
        assertThat(situation.getHeadline()).isEqualTo("A new translation is ready for you to check");
        assertThat(situation.getDetail()).isEqualTo("It scored 94%. Approving it puts it to "
                                                    + "use and tries the 2 waiting streams again.");
        assertThat(situation.getPrimary()).containsExactly(FeedAction.APPROVE, FeedAction.REJECT);
        // While a draft awaits review, Give up is not offered; rejecting the draft is the way to stop
        assertThat(situation.getMore()).contains(FeedAction.RELEARN).doesNotContain(FeedAction.GIVE_UP);
    }

    @Test
    void aPausedRunOffersMoreTheFallbackAndAnAnswer() {
        final FeedSituation situation = FeedSituation.of(learner(LearnerState.AWAITING_PERSON, null, 2),
                run(AttemptStatus.PARKED, STOPPED_AT_PARSER), DOC, NOW);
        assertThat(situation.getChip()).isEqualTo("Paused");
        assertThat(situation.getHeadline()).isEqualTo("Paused: out of time");
        assertThat(situation.getDetail()).isEqualTo("This run has used 12,000 tokens. 2 streams are waiting, the "
                                                    + "oldest for 3 hours.");
        assertThat(situation.getPrimary()).containsExactly(FeedAction.GRANT, FeedAction.EXHAUST, FeedAction.ANSWER);
        assertThat(situation.getMore()).containsExactly(FeedAction.RELEARN, FeedAction.GIVE_UP);
    }

    @Test
    void aPausedRunWhoseTargetHasNoMarkedUnknownOffersNoFallback() {
        // Finish with Unknowns writes event-logging's marked Unknown events, which another target schema
        // doesn't have, so it isn't offered. The server refuses it too.
        final SupervisorLearner learner = learner(LearnerState.AWAITING_PERSON, null, 2);
        final SupervisorLearner noFallback = learner.named(learner.getPipeline(), learner.getDoc(),
                learner.isRaised(), learner.isBelowThreshold(), true, null);

        final FeedSituation situation = FeedSituation.of(noFallback, run(AttemptStatus.PARKED, STOPPED_AT_PARSER),
                DOC, NOW);

        assertThat(situation.getPrimary()).containsExactly(FeedAction.GRANT, FeedAction.ANSWER);
    }

    @Test
    void aSmartPipelineHeldBackByASpendCeilingSaysWhoseAndThatItClears() {
        // When a spend ceiling holds learning back, its own reason is shown, both in the list row and on the
        // Learning Status tab
        final SupervisorLearner learner = learner(LearnerState.LEARNING, null, 2);
        final SupervisorLearner paused = learner.named(learner.getPipeline(), learner.getDoc(),
                learner.isRaised(), learner.isBelowThreshold(), false,
                "This Learning Flow spent 1500 token(s) in the last hour, which is its ceiling of 1000.");

        final FeedSituation situation = FeedSituation.of(paused, null, DOC, NOW);

        assertThat(situation.getChip()).isEqualTo("Spend limit");
        assertThat(situation.getTone()).isEqualTo(Tone.WARNING);
        assertThat(situation.getHeadline()).isEqualTo("Not learning until a spend ceiling clears");
        assertThat(situation.getDetail()).startsWith("This Learning Flow spent 1500 token(s)");
        assertThat(FeedSituation.listChip(paused)).isEqualTo("Spend limit");
        assertThat(FeedSituation.listTone(paused)).isEqualTo(Tone.WARNING);
        assertThat(FeedSituation.listChip(learner)).describedAs("and nothing said where nothing holds it")
                .isEqualTo("Learning");
    }

    @Test
    void theTimeZoneSaysWhereItComesFromAndWhatIsWrongWithIt() {
        // Say which setting the time zone came from, so a person can trace a surprising date to its cause
        final SupervisorLearner learner = learner(LearnerState.SERVING, "b-1", 0);

        assertThat(FeedSituation.timeZone(learner.withTimeZone("Europe/London", TimeZoneSource.PIPELINE, null)))
                .isEqualTo("Timestamps with no offset are read in Europe/London, this pipeline's time zone, "
                           + "unless a stream's TimeZone header says otherwise.");
        assertThat(FeedSituation.timeZone(learner.withTimeZone("UTC", TimeZoneSource.DEFAULT,
                "The pipeline's time zone is \"Mars/Olympus\", which is not a time zone. Times with no offset "
                + "are being read as UTC.")))
                .startsWith("Timestamps with no offset are read as UTC, as this pipeline sets no time zone, ")
                .endsWith("which is not a time zone. Times with no offset are being read as UTC.");
        assertThat(FeedSituation.timeZone(learner)).describedAs("a row that wasn't asked says nothing").isNull();
    }

    @Test
    void aPausedRunWithNoQuestionOpenOffersNoAnswer() {
        final List<SupervisorTurn> answered = List.of(STOPPED_AT_PARSER.get(0), STOPPED_AT_PARSER.get(1));
        final FeedSituation situation = FeedSituation.of(learner(LearnerState.AWAITING_PERSON, null, 0),
                run(AttemptStatus.PARKED, answered), DOC, NOW);
        assertThat(situation.getPrimary()).containsExactly(FeedAction.GRANT, FeedAction.EXHAUST);
        assertThat(situation.getDetail()).endsWith("No streams are waiting.");
    }

    @Test
    void aLearningRunSaysWhereItIs() {
        final FeedSituation situation = FeedSituation.of(learner(LearnerState.LEARNING, null, 1),
                run(AttemptStatus.AWAITING_MODEL, STOPPED_AT_PARSER), DOC, NOW);
        assertThat(situation.getChip()).isEqualTo("Learning");
        assertThat(situation.getHeadline()).isEqualTo("Learning: writing the parser, try 2 of 3");
        assertThat(situation.getDetail()).isEqualTo("1 stream is waiting, for 3 hours.");
        assertThat(situation.getPrimary()).isEmpty();
    }

    @Test
    void aStoppedFeedOffersLearningAgain() {
        final SupervisorLearner learner = new SupervisorLearner(new LearnerRef("p-1", "learningFlow"), null,
                DOC_REF, LearnerState.GIVEN_UP, "Rejected by admin", null, null, null, null, 0, 0, null,
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
        final FeedSituation situation = FeedSituation.of(learner(LearnerState.SERVING, "b-1", 0),
                run(AttemptStatus.PROMOTED, List.of()), DOC, NOW);
        assertThat(situation.getChip()).isEqualTo("Translating");
        assertThat(situation.getDetail()).isEqualTo("It scores 98% lately, over 1,204 records. It scored 95% when "
                                                    + "it was put to use.");
        assertThat(situation.getPrimary()).containsExactly(FeedAction.IMPROVE);
        assertThat(situation.getMore()).containsExactly(FeedAction.RELEARN, FeedAction.GIVE_UP, FeedAction.RETRACT);
    }

    /// A person who may only view the pipeline is offered no action that changes its learning. The text shown
    /// is the same, and the More menu is empty (the translation in use has its own link instead).
    @Test
    void readOnlyOffersOnlyWhatOpensSomethingToRead() {
        final FeedSituation serving = FeedSituation.of(learner(LearnerState.SERVING, "b-1", 0),
                run(AttemptStatus.PROMOTED, List.of()), DOC, NOW);
        final FeedSituation read = serving.readOnly();

        assertThat(read.getHeadline()).isEqualTo(serving.getHeadline());
        assertThat(read.getDetail()).isEqualTo(serving.getDetail());
        assertThat(read.getPrimary()).isEmpty();
        assertThat(read.getMore()).isEmpty();

        final FeedSituation paused = FeedSituation.of(learner(LearnerState.AWAITING_PERSON, null, 2),
                run(AttemptStatus.PARKED, STOPPED_AT_PARSER), DOC, NOW).readOnly();
        assertThat(paused.getPrimary()).isEmpty();
        assertThat(paused.getMore()).isEmpty();
    }

    @Test
    void learningOffSendsAPersonToSettings() {
        final FeedSituation situation = FeedSituation.of(learner(LearnerState.LEARNING_OFF, null, 3),
                null, DOC, NOW);
        assertThat(situation.getChip()).isEqualTo("Off");
        assertThat(situation.getPrimary()).containsExactly(FeedAction.OPEN_SETTINGS);
    }

    @Test
    void aDeletedPipelineOffersNothingThatWouldChangeIt() {
        final SupervisorLearner learner = new SupervisorLearner(new LearnerRef("p-2", "learningFlow"), null,
                DOC_REF, LearnerState.GIVEN_UP, null, null, FRAGMENT, null, null, 0, 0, null, false, null, 0,
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
        final SupervisorLearner serving = learner(LearnerState.SERVING, "b-1", 4);
        assertThat(FeedSituation.listChip(serving)).isEqualTo("Translating");
        assertThat(FeedSituation.listTone(serving)).isEqualTo(Tone.GOOD);
        assertThat(FeedSituation.listSummary(serving)).isEqualTo("Scores 98% · 4 waiting");
        final SupervisorLearner waiting = learner(LearnerState.AWAITING_PERSON, null, 2);
        assertThat(FeedSituation.listChip(waiting)).isEqualTo("Needs you");
        assertThat(FeedSituation.listTone(waiting)).isEqualTo(Tone.WARNING);
    }

    @Test
    void theListSaysHowManyRecordsTheTranslationDeclaredItSkipped() {
        // There is no limit on how many records a translation may declare skipped, so the count must be
        // visible in the list without opening the pipeline
        final SupervisorLearner skipping = learner(LearnerState.SERVING, "b-1", 0, 96);

        assertThat(FeedSituation.listSummary(skipping)).isEqualTo("Scores 98% · 96 of 1,204 skipped");
    }

    @Test
    void aTranslationThatSkippedNothingSaysNothingAboutSkips() {
        assertThat(FeedSituation.listSummary(learner(LearnerState.SERVING, "b-1", 0, 0)))
                .isEqualTo("Scores 98%");
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

        final FeedSituation situation = FeedSituation.of(learner(LearnerState.AWAITING_PERSON, null, 2),
                run(AttemptStatus.PARKED, STOPPED_AT_PARSER, report), DOC, NOW);

        assertThat(situation.getHeadline()).isEqualTo("Paused: ran out of time at \"Write the parser\" (question 3).");
        assertThat(situation.getDetails())
                .containsExactly("Each run may take 5 minutes; this one had taken 5 minutes 2 seconds.");
        assertThat(situation.getSuggestions()).hasSize(3);
        // Of the three suggestions, only changing the limits can be done from here: granting more is already
        // a button of its own, and the document names no model to open
        assertThat(situation.getSuggestions()).filteredOn(situation::canDo).extracting(Suggestion::getAction)
                .containsExactly(SuggestionAction.CHANGE_LIMITS);
    }

    @Test
    void aFailedRunSaysSoRatherThanLookingAsIfItIsStillLearning() {
        final StopReport report = new StopReport(StopCause.MODEL_TIMEOUT, null,
                "The model \"GPT-4o\" didn't reply in time.", List.of(),
                List.of(new Suggestion("Raise the timeout.", SuggestionAction.OPEN_MODEL)));
        final LearningFlowDoc withModel = DOC.copy().model(new DocRef("OpenAIModel", "m-1", "GPT-4o")).build();

        final FeedSituation situation = FeedSituation.of(learner(LearnerState.LEARNING, null, 0),
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
        final FeedSituation situation = FeedSituation.of(learner(LearnerState.LEARNING, null, 0),
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
        final SupervisorLearner learning = learner(LearnerState.LEARNING, null, 0);
        assertThat(FeedSituation.needsHistory(learning, run(AttemptStatus.IN_PROGRESS, List.of()))).isTrue();
        assertThat(FeedSituation.needsHistory(learning, run(AttemptStatus.AWAITING_MODEL, List.of()))).isTrue();
        assertThat(FeedSituation.needsHistory(learning, run(AttemptStatus.PARKED, STOPPED_AT_PARSER))).isTrue();
        assertThat(FeedSituation.needsHistory(learning, run(AttemptStatus.AWAITING_REVIEW, List.of()))).isTrue();
    }

    @Test
    void historyIsFoldedWhileItIsJustTranslating() {
        final SupervisorLearner serving = learner(LearnerState.SERVING, "b-1", 0);
        assertThat(FeedSituation.needsHistory(serving, run(AttemptStatus.PROMOTED, List.of()))).isFalse();
        assertThat(FeedSituation.needsHistory(serving, null)).isFalse();
    }

    @Test
    void historyIsWantedWhereStreamsWait() {
        final SupervisorLearner waiting = learner(LearnerState.SERVING, "b-1", 2);
        assertThat(FeedSituation.needsHistory(waiting, run(AttemptStatus.PROMOTED, List.of()))).isTrue();
    }

    @Test
    void historyIsFoldedForAStoppedRunWhoseReportIsInSight() {
        final SupervisorLearner stopped = learner(LearnerState.GIVEN_UP, null, 0);
        assertThat(FeedSituation.needsHistory(stopped, run(AttemptStatus.ABANDONED, STOPPED_AT_PARSER))).isFalse();
    }

    @Test
    void historyIsNeverWantedForADeletedPipeline() {
        final SupervisorLearner deleted = new SupervisorLearner(new LearnerRef("p-2", "learningFlow"), null,
                DOC_REF, LearnerState.LEARNING, null, null, null, null, null, 0, 3, null, false, null, 0,
                true, false);
        assertThat(FeedSituation.needsHistory(deleted, run(AttemptStatus.PARKED, STOPPED_AT_PARSER))).isFalse();
    }

    private static SupervisorLearner learner(final LearnerState state,
                                             final String binding,
                                             final int waiting) {
        return learner(state, binding, waiting, 0);
    }

    /// @param state   The learner's state.
    /// @param binding The UUID of the binding that serves it, or null.
    /// @param waiting How many streams are waiting on it.
    /// @param skips   How many of its records the translation declared skipped.
    /// @return The learner row.
    private static SupervisorLearner learner(final LearnerState state,
                                             final String binding,
                                             final int waiting,
                                             final int skips) {
        return new SupervisorLearner(new LearnerRef("p-1", "learningFlow"),
                new DocRef("Pipeline", "p-1", "Firewall pipeline"), DOC_REF, state, null, binding,
                binding == null
                        ? null
                        : FRAGMENT,
                binding == null
                        ? null
                        : 0.95, binding == null
                        ? null
                        : 0.98, 1204, skips, waiting,
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
        return new SupervisorAttempt(7, DOC_REF, new LearnerRef("p-1", "learningFlow"), "FIREWALL", "Raw Events",
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
