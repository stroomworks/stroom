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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// The path a recorded attempt took through a flow (A88): which step each turn was asked at, and which
/// edge its judging took the session along, named as the chart names its edges so that the Supervisor
/// can draw the route over the flow. A turn's edge is: `passed` on a pass, whether the flow declares
/// `on passed goto` or lets it fall through; the outcome's own name where the step declares a transition
/// on it; `reask` where the next turn is the same step again; and `spent` otherwise, since a shortfall
/// with no transition and no next candidate leaves by the step's spent edge. A step of several units — a
/// Configure over the whole chain, a Target over several kinds — records a turn per unit, and a unit's
/// pass followed by the step's next unit takes no edge: the step passes as a whole, at its last unit.
public final class FlowPath {

    public static final String PASSED = "passed";
    public static final String REASK = "reask";
    public static final String SPENT = "spent";

    private FlowPath() {
    }

    /// The key the chart gives an edge: the step's index and the edge's name.
    public static String edgeKey(final int step, final String edge) {
        return step + "|" + edge;
    }

    /// The visits an attempt's turns made, in turn order. A turn whose step the flow no longer has is
    /// left out; one not yet judged is a visit with no edge.
    public static List<Visit> visits(final List<FlowStep> steps, final List<SupervisorTurn> turns) {
        final Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            index.put(steps.get(i).effectiveId(), i);
        }
        final List<Visit> visits = new ArrayList<>();
        for (int t = 0; t < turns.size(); t++) {
            final SupervisorTurn turn = turns.get(t);
            final Integer step = turn.getStepId() == null
                    ? null
                    : index.get(turn.getStepId().toLowerCase());
            if (step == null) {
                continue;
            }
            final Integer next = t + 1 < turns.size() && turns.get(t + 1).getStepId() != null
                    ? index.get(turns.get(t + 1).getStepId().toLowerCase())
                    : null;
            visits.add(new Visit(step, turn.getNumber(), edge(steps.get(step), step, turn.getOutcome(), next)));
        }
        return visits;
    }

    /// The step a turn was asked at, by its index in the flow, as the chart numbers its steps.
    ///
    /// @param steps The flow's steps.
    /// @param turn  The turn.
    /// @return The step's index, or null where the turn names no step the flow still has.
    public static Integer stepOf(final List<FlowStep> steps, final SupervisorTurn turn) {
        if (turn == null || turn.getStepId() == null) {
            return null;
        }
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).effectiveId().equals(turn.getStepId().toLowerCase())) {
                return i;
            }
        }
        return null;
    }

    private static String edge(final FlowStep step, final int at, final StepOutcome outcome, final Integer next) {
        if (outcome == null) {
            return null;
        }
        if (outcome == StepOutcome.PASSED) {
            // The next turn at the same step is its next unit, and the step has not passed yet — unless the
            // flow sends a pass back to the step itself, where the next turn is that edge taken.
            final Transition onPassed = step.transitionOn(StepOutcome.PASSED);
            final boolean loopsBack = onPassed != null && step.effectiveId().equals(onPassed.getGoTo());
            return next != null && next == at && !loopsBack
                    ? null
                    : edgeKey(at, PASSED);
        }
        if (step.transitionOn(outcome) != null) {
            return edgeKey(at, outcome.getDisplayValue());
        }
        if (next != null && next == at) {
            return edgeKey(at, REASK);
        }
        return edgeKey(at, SPENT);
    }


    // --------------------------------------------------------------------------------


    /// One turn's place on the chart: the step it was asked at, its number, and the edge its judging took,
    /// or null where it has not been judged.
    public static final class Visit {

        private final int step;
        private final int turn;
        private final String edge;

        public Visit(final int step, final int turn, final String edge) {
            this.step = step;
            this.turn = turn;
            this.edge = edge;
        }

        public int getStep() {
            return step;
        }

        public int getTurn() {
            return turn;
        }

        public String getEdge() {
            return edge;
        }
    }
}
