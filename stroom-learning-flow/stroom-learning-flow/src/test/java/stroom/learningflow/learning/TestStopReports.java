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

package stroom.learningflow.learning;

import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.ConfigureRole;
import stroom.learningflow.shared.FlowExit;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.StopCause;
import stroom.learningflow.shared.StopReport;
import stroom.learningflow.shared.Suggestion;
import stroom.learningflow.shared.SuggestionAction;
import stroom.learningflow.shared.TurnScore;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestStopReports {

    private static final FlowStep CHAIN = FlowStep.of(QuestionKind.CHAIN);
    private static final FlowStep SPLIT = FlowStep.of(QuestionKind.SPLIT);
    private static final FlowStep PARSER = FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.PARSER).build();
    private static final FlowStep TRANSFORM = FlowStep.builder(QuestionKind.CONFIGURE)
            .role(ConfigureRole.TRANSFORM)
            .build();
    private static final List<FlowStep> STEPS = List.of(CHAIN, SPLIT, PARSER, TRANSFORM);
    private static final TurnScore WHOLE_41 = new TurnScore(Check.YIELD, 0.41, 0.9);
    private static final TurnScore WHOLE_20 = new TurnScore(Check.YIELD, 0.2, 0.9);

    @Test
    void aStepOutOfTriesSaysHowEachAnswerFellShortAndQuotesARefusal() {
        final List<Exchange> transcript = new ArrayList<>();
        transcript.add(answer(CHAIN, 1, StepOutcome.PASSED, null, "DSParser"));
        transcript.add(answer(SPLIT, 1, StepOutcome.WHOLENESS_SHORT, WHOLE_20, "batch"));
        transcript.add(answer(SPLIT, 2, StepOutcome.WHOLENESS_SHORT, WHOLE_41, "authentication"));
        transcript.add(answer(SPLIT, 3, StepOutcome.REFUSED, null,
                "No single element name fits. The records are the mixed children of batch."));

        final StopReport report = StopReports.ofStep(StopCause.STEP_SPENT, STEPS, 1, null,
                List.of(error("The reply was not an element name")), transcript, FlowExit.PARK);

        assertThat(report.getCause()).isEqualTo(StopCause.STEP_SPENT);
        assertThat(report.getStep()).isEqualTo(SPLIT.effectiveId());
        assertThat(report.getError()).isEqualTo("Couldn't find what one record is: all 3 answers fell short.");
        assertThat(report.getDetails()).containsExactly(
                "2 fell short because the records are cut into pieces.",
                "1 fell short because the answer isn't in the reply format.",
                "The closest scored 41%; 90% was needed.",
                "The AI's last answer: \"No single element name fits. The records are the mixed children of "
                + "batch.\"");
        assertThat(report.getSuggestions()).extracting(Suggestion::getAction).containsExactly(
                SuggestionAction.LEAVE_NOTE, SuggestionAction.ANSWER, SuggestionAction.CHANGE_STEPS);
        assertThat(report.getSuggestions().get(0).getText())
                .isEqualTo("Leave the AI a note saying what one record is.");
    }

    @Test
    void aRefusedReplyBeforeTheLastAnswerIsStillQuoted() {
        // The model explained what was wrong and then tried again. Its explanation is the best diagnosis
        // available, so it is quoted even though it was not the last answer.
        final List<Exchange> transcript = List.of(
                answer(SPLIT, 1, StepOutcome.WHOLENESS_SHORT, WHOLE_20, "batch"),
                answer(SPLIT, 2, StepOutcome.REFUSED, null,
                        "No single element name fits. The records are the mixed children of batch."),
                answer(SPLIT, 3, StepOutcome.WHOLENESS_SHORT, WHOLE_41, "authentication"));

        final StopReport report = StopReports.ofStep(StopCause.STEP_SPENT, STEPS, 1, null,
                List.of(error("Element authentication holds 41% of the document's text; 90% is needed")),
                transcript, FlowExit.PARK);

        assertThat(report.getDetails()).containsExactly(
                "2 fell short because the records are cut into pieces.",
                "1 fell short because the answer isn't in the reply format.",
                "The closest scored 41%; 90% was needed.",
                "The last answer fell short on: Element authentication holds 41% of the document's text; 90% is "
                + "needed.",
                "An earlier answer was refused, as not in the reply format: \"No single element name fits. The "
                + "records are the mixed children of batch.\"");
    }

    @Test
    void everyAnswerFailingTheSameWayIsSaidAndMoreTriesAreNotSuggested() {
        final List<Exchange> transcript = List.of(
                answer(SPLIT, 1, StepOutcome.WHOLENESS_SHORT, WHOLE_20, "batch"),
                answer(SPLIT, 2, StepOutcome.WHOLENESS_SHORT, WHOLE_41, "export"));

        final StopReport report = StopReports.ofStep(StopCause.STEP_SPENT, STEPS, 1, null,
                List.of(error("Element export holds 41% of the document's text; 90% is needed")), transcript,
                FlowExit.PARK);

        assertThat(report.getDetails()).containsExactly(
                "2 fell short because the records are cut into pieces.",
                "The closest scored 41%; 90% was needed.",
                "The last answer fell short on: Element export holds 41% of the document's text; 90% is needed.");
        assertThat(report.getSuggestions().get(0).getText()).startsWith(
                "Every answer fell short the same way, so more tries may not help.");
        assertThat(report.getSuggestions()).extracting(Suggestion::getAction)
                .doesNotContain(SuggestionAction.CHANGE_STEPS);
    }

    @Test
    void aStepThatGivesUpSuggestsLearningAgainRatherThanAnswering() {
        final List<Exchange> transcript = List.of(answer(PARSER, 1, StepOutcome.COMPILE_FAILED, null, "<x/>"));

        final StopReport report = StopReports.ofStep(StopCause.STEP_SPENT, STEPS, 2, "It was writing DSParser.",
                List.of(error("Unexpected element x")), transcript, FlowExit.GIVE_UP);

        assertThat(report.getError()).isEqualTo("Couldn't write the parser: its 1 answer fell short.");
        assertThat(report.getDetails()).first().isEqualTo("It was writing DSParser.");
        assertThat(report.getSuggestions()).extracting(Suggestion::getAction)
                .contains(SuggestionAction.LEARN_AGAIN)
                .doesNotContain(SuggestionAction.ANSWER);
    }

    @Test
    void onlyTheAnswersSinceTheStepWasLastEnteredCount() {
        final List<Exchange> transcript = List.of(
                answer(SPLIT, 1, StepOutcome.WHOLENESS_SHORT, WHOLE_20, "batch"),
                answer(PARSER, 1, StepOutcome.PASSED, null, "<config/>"),
                answer(SPLIT, 1, StepOutcome.YIELD_SHORT, null, "event"));

        assertThat(StopReports.answers(SPLIT, transcript)).extracting(Exchange::reply).containsExactly("event");
    }

    @Test
    void aStepsRuleSaysWhatItStoppedOn() {
        final List<Exchange> transcript = List.of(answer(TRANSFORM, 1, StepOutcome.COMPILE_FAILED, null, "<x"));

        final StopReport report = StopReports.ofStep(StopCause.STEP_RULE, STEPS, 3, null, List.of(), transcript,
                FlowExit.PARK);

        assertThat(report.getError())
                .isEqualTo("Stopped at \"Write the transform\", as its rule says to when the answer doesn't compile.");
        assertThat(report.getDetails())
                .containsExactly("The rule says to pause for a person when the answer doesn't compile.");
        assertThat(report.getSuggestions()).extracting(Suggestion::getAction)
                .containsExactly(SuggestionAction.ANSWER, SuggestionAction.CHANGE_STEPS);
    }

    @Test
    void finishingWithUnknownsWithNoFallbackSaysHowToHaveOne() {
        final StopReport report = StopReports.ofStep(StopCause.FALLBACK_MISSING, STEPS, 3, null, List.of(),
                List.of(), FlowExit.PARK);

        assertThat(report.getError())
                .isEqualTo("Couldn't finish with Unknowns: \"Write the transform\" has no rule to use the fallback.");
        assertThat(report.getSuggestions().get(0).getAction()).isEqualTo(SuggestionAction.CHANGE_STEPS);
    }

    @Test
    void runningOutOfTimeSaysWhereAndForHowLongInMinutes() {
        final StopReport report = StopReports.outOfTime(PARSER, 6, 300_000L, 302_400L);

        assertThat(report.getCause()).isEqualTo(StopCause.TIME_BUDGET);
        assertThat(report.getError()).isEqualTo("Ran out of time at \"Write the parser\" (question 6).");
        assertThat(report.getDetails())
                .containsExactly("Each run may take 5 minutes; this one had taken 5 minutes 2 seconds.");
        assertThat(report.getSuggestions()).extracting(Suggestion::getAction)
                .containsExactly(SuggestionAction.GIVE_MORE, SuggestionAction.CHANGE_LIMITS);
    }

    @Test
    void runningOutOfTokensGroupsTheirThousands() {
        final StopReport report = StopReports.outOfTokens(TRANSFORM, 9, 100_000L, 101_234L);

        assertThat(report.getError()).isEqualTo("Ran out of tokens at \"Write the transform\" (question 9).");
        assertThat(report.getDetails())
                .containsExactly("A run may spend 100,000 tokens; this one had spent 101,234.");
    }

    @Test
    void aModelTimeoutIsToldFromItsCauseAndPointsAtTheModelsTimeout() {
        final StopReport report = StopReports.failure(
                new RuntimeException("wrapped", new SocketTimeoutException("Read timed out")), "GPT-4o");

        assertThat(report.getCause()).isEqualTo(StopCause.MODEL_TIMEOUT);
        assertThat(report.getError()).isEqualTo("The model \"GPT-4o\" didn't reply in time.");
        assertThat(report.getSuggestions()).extracting(Suggestion::getAction)
                .containsExactly(SuggestionAction.OPEN_MODEL, SuggestionAction.CHANGE_MODEL);
    }

    @Test
    void otherModelFailuresAreToldApart() {
        assertThat(StopReports.failure(new AuthenticationException("401 Unauthorized"), "GPT-4o").getError())
                .isEqualTo("The model \"GPT-4o\" refused the request: it couldn't sign in.");
        assertThat(StopReports.failure(new RateLimitException("slow down"), null).getError())
                .isEqualTo("The model turned the request away: too many requests.");
        assertThat(StopReports.failure(new RuntimeException(new ConnectException("refused")), "Local").getCause())
                .isEqualTo(StopCause.MODEL_UNREACHABLE);
        assertThat(StopReports.failure(new HttpException(502, "Bad gateway"), "Local").getError())
                .isEqualTo("The model \"Local\" returned an error (HTTP 502).");
        assertThat(StopReports.failure(new ModelNotChosenException("none"), null).getCause())
                .isEqualTo(StopCause.NO_MODEL);
    }

    @Test
    void anUnknownFailureSaysWhatItWasAndThatLearningTriesAgain() {
        final StopReport report = StopReports.failure(new IllegalArgumentException("Bad element"), "GPT-4o");

        assertThat(report.getCause()).isEqualTo(StopCause.OTHER);
        assertThat(report.getError()).isEqualTo("The learning run failed: Bad element.");
        assertThat(report.getDetails()).containsExactly("It failed with IllegalArgumentException.");
        assertThat(report.getSuggestions()).extracting(Suggestion::getAction).containsOnlyNulls();
    }

    @Test
    void theSetupsProblemsPointAtTheLearningSteps() {
        final StopReport report = StopReports.invalidSteps(List.of("The flow has no steps"));

        assertThat(report.getCause()).isEqualTo(StopCause.SETUP);
        assertThat(report.getDetails()).containsExactly("The flow has no steps.");
        assertThat(report.getSuggestions()).extracting(Suggestion::getAction)
                .containsExactly(SuggestionAction.CHANGE_STEPS);
        assertThat(StopReports.notWritten("XSLTFilter").getError())
                .isEqualTo("The learning steps ended without writing XSLTFilter.");
    }

    @Test
    void aLongReplyIsQuotedOnOneLineAndCut() {
        final String quoted = StopReports.quote("line one\nline two " + "x".repeat(300));

        assertThat(quoted).startsWith("line one line two x").endsWith("…");
        assertThat(quoted).hasSize(StopReports.QUOTE + 1);
    }

    @Test
    void durationsAndNumbersReadAsPeopleSayThem() {
        assertThat(StopReports.duration(45_000L)).isEqualTo("45 seconds");
        assertThat(StopReports.duration(60_000L)).isEqualTo("1 minute");
        assertThat(StopReports.duration(121_000L)).isEqualTo("2 minutes 1 second");
        assertThat(StopReports.number(1_234_567L)).isEqualTo("1,234,567");
    }

    @Test
    void aTranslationRefusedByTheListSaysWhatItUsedAndWhatToDo() {
        // The list of permitted functions is checked again at promotion, so a pipeline edited by hand since
        // it was learned, or one promoted by a route that judged no stylesheet, is still refused. The person
        // reading the Learning Status tab is told which element and which function caused it
        final StopReport report = StopReports.refusedByTheList(List.of(
                "xsltFilter: A learned stylesheet may not call 'stroom:lookup': it reads reference data "
                + "beyond this stream."));

        assertThat(report.getError()).contains("may not").contains("wasn't put in use");
        assertThat(report.getDetails()).first().asString()
                .contains("xsltFilter")
                .contains("stroom:lookup");
        assertThat(report.getSuggestions()).isNotEmpty();
    }

    private static Exchange answer(final FlowStep step,
                                   final int candidate,
                                   final StepOutcome outcome,
                                   final TurnScore score,
                                   final String reply) {
        return new Exchange(null, reply, step.effectiveId(), candidate, outcome, score, List.of());
    }

    private static StoredError error(final String message) {
        return new StoredError(Severity.ERROR, null, null, message);
    }
}
