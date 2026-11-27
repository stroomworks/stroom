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

import stroom.learningflow.client.component.FeedProgress.Item;
import stroom.learningflow.client.component.FeedProgress.State;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.LearnerRef;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.SupervisorAttempt;
import stroom.learningflow.shared.SupervisorTurn;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestFeedProgress {

    private static final List<FlowStep> DIRECT = FlowExampleFixtures.steps(FlowExample.DIRECT);

    @Test
    void aRunThatWentBackSaysWhyAndWhereItIsNow() {
        // The same session as the Supervisor workbench story: the chain, a parser that didn't read all the
        // input, the chain again, then the parser again
        final FeedProgress progress = FeedProgress.of(DIRECT, run(AttemptStatus.PARKED, List.of(
                turn(1, "chain", QuestionKind.CHAIN, StepOutcome.PASSED),
                turn(2, "parser", QuestionKind.CONFIGURE, StepOutcome.COVERAGE_SHORT),
                turn(3, "chain", QuestionKind.CHAIN, StepOutcome.PASSED),
                turn(4, "parser", QuestionKind.CONFIGURE, null))));
        assertThat(progress.getItems()).containsExactly(
                new Item("Choose the elements", State.DONE, 2, false),
                new Item("Write the parser", State.NOW, 2, false),
                new Item("Write the transform", State.TODO, 0, false),
                new Item("Done", State.TODO, 0, true));
        assertThat(progress.getNote()).isEqualTo("Went back once, most recently because: didn't read all the "
                                                 + "input.");
        // The fallback isn't reached, so isn't counted
        assertThat(progress.getWhere()).isEqualTo("Step 2 of 3 · Write the parser");
    }

    @Test
    void eachStepSaysWhereItStandsInWordsAsWellAsByItsMark() {
        final FeedProgress progress = FeedProgress.of(DIRECT, run(AttemptStatus.PARKED, List.of(
                turn(1, "chain", QuestionKind.CHAIN, StepOutcome.PASSED),
                turn(2, "parser", QuestionKind.CONFIGURE, StepOutcome.COVERAGE_SHORT),
                turn(3, "chain", QuestionKind.CHAIN, StepOutcome.PASSED),
                turn(4, "parser", QuestionKind.CONFIGURE, null))));
        assertThat(progress.getItems()).extracting(Item::getMeta)
                .containsExactly("2 tries", "In progress · try 2", null, null);
        // A screen reader is told where a step stands only where its meta doesn't say so
        assertThat(progress.getItems()).extracting(Item::getStateWords)
                .containsExactly("Done", null, "Not reached", "Not reached");
        assertThat(progress.getItems()).extracting(Item::isReached)
                .containsExactly(true, true, false, false);
        assertThat(progress.getItems()).extracting(Item::isFinish)
                .containsExactly(false, false, false, true);
    }

    @Test
    void aStepTriedOnceSaysOnlyThatItDidntPass() {
        final FeedProgress progress = FeedProgress.of(DIRECT, run(AttemptStatus.PARKED, List.of(
                turn(1, "chain", QuestionKind.CHAIN, StepOutcome.PASSED),
                turn(2, "parser", QuestionKind.CONFIGURE, StepOutcome.COVERAGE_SHORT),
                turn(3, "chain", QuestionKind.CHAIN, null))));
        final Item parser = progress.getItems().get(1);
        assertThat(parser.getState()).isEqualTo(State.TRIED);
        assertThat(parser.getMeta()).isEqualTo("Not passed");
        assertThat(parser.getStateWords()).isNull();
        assertThat(progress.getItems().get(0).getMeta()).isEqualTo("In progress · try 2");
        assertThat(progress.getWhere()).isEqualTo("Step 1 of 3 · Choose the elements");
    }

    @Test
    void aRunThatReachedTheFallbackCountsItAndMarksTheTransformNotPassed() {
        // The transform was tried and the run moved on, not back, to the fallback
        final FeedProgress progress = FeedProgress.of(DIRECT, run(AttemptStatus.PARKED, List.of(
                turn(1, "chain", QuestionKind.CHAIN, StepOutcome.PASSED),
                turn(2, "parser", QuestionKind.CONFIGURE, StepOutcome.PASSED),
                turn(3, "transform", QuestionKind.CONFIGURE, StepOutcome.COVERAGE_SHORT),
                turn(4, "transform", QuestionKind.CONFIGURE, StepOutcome.COVERAGE_SHORT),
                turn(5, "admit", QuestionKind.ADMIT, null))));
        assertThat(progress.getItems()).containsExactly(
                new Item("Choose the elements", State.DONE, 1, false),
                new Item("Write the parser", State.DONE, 1, false),
                new Item("Write the transform", State.TRIED, 2, false),
                new Item("Fallback: marked Unknowns", State.NOW, 1, false),
                new Item("Done", State.TODO, 0, true));
        assertThat(progress.getItems().get(2).getMeta()).isEqualTo("Not passed · 2 tries");
        assertThat(progress.getWhere()).isEqualTo("Step 4 of 4 · Fallback: marked Unknowns");
        assertThat(progress.getNote()).isNull();
    }

    @Test
    void aRunThatHasAskedNothingIsAtNoStep() {
        final FeedProgress progress = FeedProgress.of(DIRECT, run(AttemptStatus.PARKED, List.of()));
        assertThat(progress.getItems()).extracting(Item::getState)
                .containsExactly(State.TODO, State.TODO, State.TODO, State.TODO);
        assertThat(progress.getWhere()).isNull();
    }

    @Test
    void aFinishedRunIsDoneAndTheFallbackShowsOnlyWhereReached() {
        final FeedProgress progress = FeedProgress.of(DIRECT, run(AttemptStatus.PROMOTED, List.of(
                turn(1, "chain", QuestionKind.CHAIN, StepOutcome.PASSED),
                turn(2, "parser", QuestionKind.CONFIGURE, StepOutcome.PASSED),
                turn(3, "transform", QuestionKind.CONFIGURE, StepOutcome.PASSED))));
        assertThat(progress.getItems()).extracting(Item::getState)
                .containsExactly(State.DONE, State.DONE, State.DONE, State.DONE);
        assertThat(progress.getItems()).extracting(Item::getName).doesNotContain("Fallback: marked Unknowns");
        assertThat(progress.getNote()).isNull();
        assertThat(progress.getWhere()).isEqualTo("Finished");
        final Item finish = progress.getItems().get(3);
        assertThat(finish.isFinish()).isTrue();
        assertThat(finish.getMeta()).isNull();
        assertThat(finish.getStateWords()).isEqualTo("Reached");
    }

    @Test
    void stepsTheFlowNoLongerHasAreShownAsTheRunMetThem() {
        final FeedProgress progress = FeedProgress.of(DIRECT, run(AttemptStatus.PARKED, List.of(
                turn(1, "chain", QuestionKind.CHAIN, StepOutcome.PASSED),
                turn(2, "parse", QuestionKind.CONFIGURE, null))));
        assertThat(progress.getItems()).extracting(Item::getName)
                .containsExactly("Choose the elements", "Write every element", "Done");
        assertThat(progress.getItems().get(1).getState()).isEqualTo(State.NOW);
    }

    @Test
    void noRunHasNoProgress() {
        final FeedProgress progress = FeedProgress.of(DIRECT, null);
        assertThat(progress.getItems()).isEmpty();
        assertThat(progress.getWhere()).isNull();
    }

    private static SupervisorAttempt run(final AttemptStatus status, final List<SupervisorTurn> turns) {
        return new SupervisorAttempt(7, null, new LearnerRef("p-1", "learningFlow"), "FIREWALL", "Raw Events",
                101L, "node1a", PromotionMode.AUTOMATIC, status, null, null, null, 0, 0, 0, false, 0, 0, turns,
                false);
    }

    private static SupervisorTurn turn(final int number,
                                       final String stepId,
                                       final QuestionKind kind,
                                       final StepOutcome outcome) {
        return new SupervisorTurn(number, stepId, 1, kind, "Question", outcome == null
                ? null
                : "answer", "model", outcome, null);
    }
}
