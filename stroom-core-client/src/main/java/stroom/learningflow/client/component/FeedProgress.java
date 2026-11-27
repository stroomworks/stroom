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

import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.FlowWords;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.SupervisorAttempt;
import stroom.learningflow.shared.SupervisorTurn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Works out how far a learning run has got through its document's flow, for the progress strip on a
/// pipeline's Learning Status tab. The strip shows each step in order, marked as done, current, tried or still
/// to come, with the number of tries it took, and a note of how often the run went back to an earlier step and
/// why it most recently did.
///
/// If the run's turns name steps that the flow no longer has (because the flow was edited after the run),
/// the strip shows instead the steps the turns name, in the order the run reached them.
public final class FeedProgress {

    private final List<Item> items;
    private final String note;
    private final boolean finished;

    private FeedProgress(final List<Item> items, final String note, final boolean finished) {
        this.items = Collections.unmodifiableList(items);
        this.note = note;
        this.finished = finished;
    }

    /// Works out a run's progress.
    ///
    /// @param steps The document's steps, or empty if they are not known.
    /// @param run   The run, with its turns, or null for no run, which gives an empty strip.
    /// @return The run's progress.
    public static FeedProgress of(final List<FlowStep> steps, final SupervisorAttempt run) {
        final List<SupervisorTurn> turns = run == null || run.getTurns() == null
                ? List.of()
                : run.getTurns();
        if (run == null) {
            return new FeedProgress(new ArrayList<>(), null, false);
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
                                 || run.getStatus() == AttemptStatus.AWAITING_REVIEW;
        final List<Item> items = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            final Stop stop = stops.get(i);
            // The fallback step is shown only if the run reached it, as most runs never do
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
            items.add(new Item(stop.name, state, tries[i], false));
        }
        items.add(new Item("Done", finished
                ? State.DONE
                : State.TODO, 0, true));

        final String note = wentBack == 0
                ? null
                : "Went back " + (wentBack == 1
                        ? "once"
                        : wentBack + " times") + (why == null
                        ? "."
                        : ", most recently because: " + why + ".");
        return new FeedProgress(items, note, finished);
    }

    // Returns the flow's steps. If any turn names a step the flow doesn't have, returns instead the steps the
    // turns name, in the order they were reached
    private static List<Stop> stops(final List<FlowStep> steps, final List<SupervisorTurn> turns) {
        final List<Stop> stops = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            stops.add(new Stop(steps.get(i).effectiveId(), FlowWords.stepName(steps, i), steps.get(i).getKind()));
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
                met.add(new Stop(id, FlowWords.stepName(turn.getKind(), null), turn.getKind()));
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

    /// @return How many times the run went back to an earlier step, and why it most recently did, or null if
    ///         it never did.
    public String getNote() {
        return note;
    }

    /// Where the run is, in words, for beside the strip's heading: e.g. "Step 3 of 5 · Agree the target events",
    /// counting the fallback only where the run reached it, as the strip does; or "Finished".
    ///
    /// @return Where the run is, or null where it is at no step, e.g. it has asked nothing yet.
    public String getWhere() {
        if (finished) {
            return "Finished";
        }
        final List<Item> steps = items.stream()
                .filter(item -> !item.isFinish())
                .toList();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).getState() == State.NOW) {
                return "Step " + (i + 1) + " of " + steps.size() + " · " + steps.get(i).getName();
            }
        }
        return null;
    }


    // --------------------------------------------------------------------------------


    /// The state of a step in a run.
    public enum State {
        /// Passed, or tried by a run that has finished.
        DONE,
        /// The step the run is at now, or stopped at.
        NOW,
        /// Tried and not passed, and the run has moved on: back to an earlier step, or on to the fallback.
        TRIED,
        /// Not reached.
        TODO
    }


    // --------------------------------------------------------------------------------


    /// One step on the strip.
    public static final class Item {

        private final String name;
        private final State state;
        private final int tries;
        private final boolean finish;

        Item(final String name, final State state, final int tries, final boolean finish) {
            this.name = name;
            this.state = state;
            this.tries = tries;
            this.finish = finish;
        }

        /// @return The step's name.
        public String getName() {
            return name;
        }

        /// @return The step's state in the run.
        public State getState() {
            return state;
        }

        /// @return How many times it was asked in this run.
        public int getTries() {
            return tries;
        }

        /// @return True for the strip's last item, Done, which is where the run finishes rather than a step.
        public boolean isFinish() {
            return finish;
        }

        /// @return True where the run has got as far as it, i.e. it is anything but [State#TODO].
        public boolean isReached() {
            return state != State.TODO;
        }

        /// What the strip says under the step's name: where it stands, for the step the run is at or one it
        /// tried and didn't pass, and how many tries it took where that was more than one.
        ///
        /// @return E.g. "In progress · try 2", "Not passed · 3 tries" or "2 tries"; null where there is nothing to
        /// say beyond its mark.
        public String getMeta() {
            if (finish) {
                return null;
            }
            switch (state) {
                case NOW:
                    return tries > 1
                            ? "In progress · try " + tries
                            : "In progress";
                case TRIED:
                    return tries > 1
                            ? "Not passed · " + tries + " tries"
                            : "Not passed";
                case DONE:
                    return tries > 1
                            ? tries + " tries"
                            : null;
                default:
                    return null;
            }
        }

        /// Where the step stands, for a screen reader, which is shown neither its colour nor its mark, where
        /// [#getMeta()] doesn't already say so.
        ///
        /// @return E.g. "Done" or "Not reached"; null where its meta says where it stands.
        public String getStateWords() {
            if (finish) {
                return state == State.DONE
                        ? "Reached"
                        : "Not reached";
            }
            switch (state) {
                case DONE:
                    return "Done";
                case TODO:
                    return "Not reached";
                default:
                    return null;
            }
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
            return tries == item.tries
                   && finish == item.finish
                   && Objects.equals(name, item.name)
                   && state == item.state;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, state, tries, finish);
        }

        @Override
        public String toString() {
            return name + "(" + state + ", " + tries + (finish
                    ? ", finish"
                    : "") + ")";
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
