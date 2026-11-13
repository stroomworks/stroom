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

import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.shapeshifter.shared.SupervisorTurn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// How far a learning run has got over its document's flow, as the Feeds tab's progress strip shows it:
/// each step in order, done, where the run is now, or still to come, with how many tries it took; and,
/// where the run went back to an earlier step, why. Replaces the Supervisor's path over the flow chart,
/// which a person had to read at a third of its size.
///
/// A run whose turns name steps the flow no longer has (the flow was edited since) is shown by the steps
/// its turns name, in the order it met them.
public final class FeedProgress {

    private final List<Item> items;
    private final String note;

    private FeedProgress(final List<Item> items, final String note) {
        this.items = Collections.unmodifiableList(items);
        this.note = note;
    }

    /// Works out a run's progress.
    ///
    /// @param steps The document's steps, or empty where not known.
    /// @param run   The run, with its turns; null for none, which has no progress.
    /// @return Its progress.
    public static FeedProgress of(final List<FlowStep> steps, final SupervisorAttempt run) {
        final List<SupervisorTurn> turns = run == null || run.getTurns() == null
                ? List.of()
                : run.getTurns();
        if (run == null) {
            return new FeedProgress(new ArrayList<>(), null);
        }
        final List<Stop> stops = stops(steps, turns);
        final Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < stops.size(); i++) {
            index.put(stops.get(i).id, i);
        }

        final int[] tries = new int[stops.size()];
        final StepOutcome[] last = new StepOutcome[stops.size()];
        int at = -1;
        int wentBack = 0;
        String why = null;
        SupervisorTurn previous = null;
        for (final SupervisorTurn turn : turns) {
            final Integer stop = index.get(lower(turn.getStepId()));
            if (stop == null) {
                continue;
            }
            if (previous != null && stop < at) {
                wentBack++;
                why = PlainWords.lowerFirst(PlainWords.result(previous.getOutcome()));
            }
            tries[stop]++;
            last[stop] = turn.getOutcome();
            at = stop;
            previous = turn;
        }

        final boolean finished = run.getStatus() == AttemptStatus.PROMOTED
                                 || run.getStatus() == AttemptStatus.PROVISIONAL
                                 || run.getStatus() == AttemptStatus.AWAITING_REVIEW;
        final List<Item> items = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            final Stop stop = stops.get(i);
            // The fallback is shown only where the run reached it: most never do
            if (stop.kind == QuestionKind.ADMIT && tries[i] == 0) {
                continue;
            }
            final State state;
            if (i == at && !finished) {
                state = State.NOW;
            } else if (last[i] == StepOutcome.PASSED || (finished && tries[i] > 0)) {
                state = State.DONE;
            } else if (tries[i] > 0) {
                state = State.TRIED;
            } else {
                state = State.TODO;
            }
            items.add(new Item(stop.name, state, tries[i]));
        }
        items.add(new Item("Done", finished
                ? State.DONE
                : State.TODO, 0));

        final String note = wentBack == 0
                ? null
                : "Went back " + (wentBack == 1
                        ? "once"
                        : wentBack + " times") + (why == null
                        ? "."
                        : ", most recently because: " + why + ".");
        return new FeedProgress(items, note);
    }

    // The flow's steps, or where the turns name steps it doesn't have, the steps the turns name
    private static List<Stop> stops(final List<FlowStep> steps, final List<SupervisorTurn> turns) {
        final List<Stop> stops = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            stops.add(new Stop(steps.get(i).effectiveId(), PlainWords.stepName(steps, i), steps.get(i).getKind()));
        }
        boolean known = true;
        for (final SupervisorTurn turn : turns) {
            if (turn.getStepId() != null && stops.stream().noneMatch(stop -> stop.id.equals(lower(turn.getStepId())))) {
                known = false;
            }
        }
        if (known) {
            return stops;
        }
        final List<Stop> met = new ArrayList<>();
        for (final SupervisorTurn turn : turns) {
            final String id = lower(turn.getStepId());
            if (id != null && met.stream().noneMatch(stop -> stop.id.equals(id))) {
                met.add(new Stop(id, PlainWords.stepName(turn.getKind(), null), turn.getKind()));
            }
        }
        return met;
    }

    private static String lower(final String id) {
        return id == null
                ? null
                : id.toLowerCase();
    }

    /// @return The steps in order, ending with Done.
    public List<Item> getItems() {
        return items;
    }

    /// @return Where and why the run went back to an earlier step, or null where it never did.
    public String getNote() {
        return note;
    }


    // --------------------------------------------------------------------------------


    /// Where a step stands in a run.
    public enum State {
        /// Passed, or the run finished past it.
        DONE,
        /// The step the run is at, or stopped at.
        NOW,
        /// Tried and not passed, and the run has moved on, e.g. back to an earlier step.
        TRIED,
        /// Not reached.
        TODO
    }


    // --------------------------------------------------------------------------------


    /// One step of the strip.
    public static final class Item {

        private final String name;
        private final State state;
        private final int tries;

        Item(final String name, final State state, final int tries) {
            this.name = name;
            this.state = state;
            this.tries = tries;
        }

        /// @return The step's name.
        public String getName() {
            return name;
        }

        /// @return Where it stands.
        public State getState() {
            return state;
        }

        /// @return How many times it was asked in this run.
        public int getTries() {
            return tries;
        }

        /// @return What the strip says of it, e.g. "Write the parser · 2 tries".
        public String getLabel() {
            return tries > 1
                    ? name + " · " + tries + " tries"
                    : name;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final Item item = (Item) o;
            return tries == item.tries && Objects.equals(name, item.name) && state == item.state;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, state, tries);
        }

        @Override
        public String toString() {
            return name + "(" + state + ", " + tries + ")";
        }
    }


    // --------------------------------------------------------------------------------


    private static final class Stop {

        private final String id;
        private final String name;
        private final QuestionKind kind;

        private Stop(final String id, final String name, final QuestionKind kind) {
            this.id = id;
            this.name = name;
            this.kind = kind;
        }
    }
}
