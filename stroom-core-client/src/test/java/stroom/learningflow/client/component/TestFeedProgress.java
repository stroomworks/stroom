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
                new Item("Choose the elements", State.DONE, 2),
                new Item("Write the parser", State.NOW, 2),
                new Item("Write the transform", State.TODO, 0),
                new Item("Done", State.TODO, 0));
        assertThat(progress.getItems().get(1).getLabel()).isEqualTo("Write the parser · 2 tries");
        assertThat(progress.getNote()).isEqualTo("Went back once, most recently because: didn't read all the "
                                                 + "input.");
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
        assertThat(FeedProgress.of(DIRECT, null).getItems()).isEmpty();
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
