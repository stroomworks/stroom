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

package stroom.shapeshifter.shared;

import stroom.shapeshifter.shared.FlowPath.Visit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class TestFlowPath {

    @Test
    void aTurnTakesTheEdgeItsJudgingTook() {
        // A88: the target-first flow, walked as scenario 57 does: round one passes down the spine, the
        // transform's preservation shortfall takes the declared edge back to the parser, the parser passes
        // again, the transform re-asks on a shortfall it has no transition for, and passes.
        final List<FlowStep> steps = FlowExample.TARGET_FIRST.steps();
        final List<SupervisorTurn> turns = List.of(
                turn(1, "chain", StepOutcome.PASSED),
                turn(2, "split", StepOutcome.PASSED),
                turn(3, "target", StepOutcome.PASSED),
                turn(4, "parser", StepOutcome.PASSED),
                turn(5, "transform", StepOutcome.PRESERVATION_SHORT),
                turn(6, "parser", StepOutcome.PASSED),
                turn(7, "transform", StepOutcome.COVERAGE_SHORT),
                turn(8, "transform", StepOutcome.PASSED),
                turn(9, "nowhere", StepOutcome.PASSED),
                turn(10, "transform", null));
        assertThat(FlowPath.visits(steps, turns)).extracting(Visit::getStep, Visit::getTurn, Visit::getEdge)
                .containsExactly(
                        tuple(0, 1, "0|passed"),
                        tuple(1, 2, "1|passed"),
                        tuple(2, 3, "2|passed"),
                        tuple(3, 4, "3|passed"),
                        tuple(4, 5, "4|preservation-short"),
                        tuple(3, 6, "3|passed"),
                        tuple(4, 7, "4|reask"),
                        tuple(4, 8, "4|passed"),
                        tuple(4, 10, null));
    }

    @Test
    void aShortfallWithNoTransitionAndNoNextCandidateLeavesBySpent() {
        final List<FlowStep> steps = FlowExample.DIRECT.steps();
        final List<SupervisorTurn> turns = List.of(
                turn(1, "chain", StepOutcome.PASSED),
                turn(2, "parser", StepOutcome.PASSED),
                turn(3, "transform", StepOutcome.QUALITY_SHORT),
                turn(4, "admit", StepOutcome.PASSED));
        assertThat(FlowPath.visits(steps, turns)).extracting(Visit::getEdge)
                .containsExactly("0|passed", "1|passed", "2|spent", "3|passed");
    }

    /// ISSUES item 9: a step of several units records a turn per unit, and only its last unit's pass is
    /// the step's pass; a unit that falls short re-asks the step as any candidate does.
    @Test
    void aStepOfSeveralUnitsPassesAtItsLastUnit() {
        final List<FlowStep> steps = FlowExample.TARGET_FIRST.steps();
        final List<SupervisorTurn> turns = List.of(
                turn(1, "chain", StepOutcome.PASSED),
                turn(2, "split", StepOutcome.PASSED),
                turn(3, "target", StepOutcome.PASSED),
                turn(4, "target", StepOutcome.CONFORMANCE_SHORT),
                turn(5, "target", StepOutcome.PASSED),
                turn(6, "target", StepOutcome.PASSED),
                turn(7, "parser", null));
        assertThat(FlowPath.visits(steps, turns)).extracting(Visit::getTurn, Visit::getEdge)
                .containsExactly(
                        tuple(1, "0|passed"),
                        tuple(2, "1|passed"),
                        tuple(3, null),
                        tuple(4, "2|reask"),
                        tuple(5, null),
                        tuple(6, "2|passed"),
                        tuple(7, null));
    }

    /// A flow may send a pass back to the step itself: the turn after it is that edge taken, not a unit.
    @Test
    void aPassSentBackToItsOwnStepTakesThatEdge() {
        final List<FlowStep> steps = List.of(
                FlowStep.parse("CHAIN"),
                FlowStep.parse("again: CONFIGURE transform candidates 2 on passed goto again"));
        final List<SupervisorTurn> turns = List.of(
                turn(1, "chain", StepOutcome.PASSED),
                turn(2, "again", StepOutcome.PASSED),
                turn(3, "again", null));
        assertThat(FlowPath.visits(steps, turns)).extracting(Visit::getEdge)
                .containsExactly("0|passed", "1|passed", null);
    }

    @Test
    void aTurnIsAtTheStepItNames() {
        final List<FlowStep> steps = FlowExample.TARGET_FIRST.steps();

        assertThat(FlowPath.stepOf(steps, turn(1, "Parser", null))).isEqualTo(3);
        assertThat(FlowPath.stepOf(steps, turn(1, "nowhere", null))).isNull();
        assertThat(FlowPath.stepOf(steps, turn(1, null, null))).isNull();
        assertThat(FlowPath.stepOf(steps, null)).isNull();
    }

    private static SupervisorTurn turn(final int number, final String step, final StepOutcome outcome) {
        return new SupervisorTurn(number, step, 1, QuestionKind.CONFIGURE, "q", "a", null, outcome);
    }
}
