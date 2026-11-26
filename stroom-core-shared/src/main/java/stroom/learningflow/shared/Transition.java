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

package stroom.learningflow.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/// Where a step of the learning flow goes on one outcome, shown to people as one of the step's rules.
///
/// A transition on an outcome fires at once, on the first candidate that has that outcome. A transition with
/// no outcome fires when the step's candidates are spent; without one, the step leaves the flow by its default
/// exit. Asking the same step again after a shortfall needs no transition, because a step does that until its
/// candidates are spent. A pass goes to the next step unless a transition on [StepOutcome#PASSED] says
/// otherwise. The step named [#END] is the end of the flow.
@JsonPropertyOrder({"on", "goTo"})
@JsonInclude(Include.NON_NULL)
public class Transition {

    /// The reserved step name a transition reaches when the flow is complete.
    public static final String END = "end";
    /// The reserved step name of the exit that parks the session for a person.
    public static final String PARK = "park";
    /// The reserved step name of the exit that gives the learner up.
    public static final String GIVE_UP = "give-up";

    /// The outcome this fires on, or null for `spent`.
    @JsonProperty
    private final StepOutcome on;
    /// The id of the step to go to, `end`, `park` or `give-up`; or null where the step's default exit is taken.
    @JsonProperty
    private final String goTo;

    @JsonCreator
    public Transition(@JsonProperty("on") final StepOutcome on,
                      @JsonProperty("goTo") final String goTo) {
        this.on = on;
        // A step's name is matched without regard to case, as the step's own id is kept.
        this.goTo = goTo == null || goTo.trim().isEmpty()
                ? null
                : goTo.trim().toLowerCase();
    }

    /// @return The outcome this fires on, or null where it fires when the candidates are spent.
    public StepOutcome getOn() {
        return on;
    }

    /// @return The step, `end` or exit to go to, or null where the step's default exit is taken.
    public String getGoTo() {
        return goTo;
    }

    /// @return Whether this fires when the candidates are spent rather than on an outcome.
    public boolean onSpent() {
        return on == null;
    }

    /// @return Whether this leaves the flow without a binding, rather than going to a step or to `end`. It
    /// does so when it names no step, which takes the step's own default exit, or when it names `park` or
    /// `give-up`.
    public boolean exits() {
        return goTo == null || PARK.equals(goTo) || GIVE_UP.equals(goTo);
    }

    /// @return The exit this names, or null where it names no exit. Where it names no step, the step's
    /// default exit applies: give-up for a parser, since a stream that cannot be parsed is the one failure
    /// nothing can serve, and park for any other step.
    public FlowExit namedExit() {
        if (PARK.equals(goTo)) {
            return FlowExit.PARK;
        }
        if (GIVE_UP.equals(goTo)) {
            return FlowExit.GIVE_UP;
        }
        return null;
    }

    /// Whether a name is reserved by the flow, so that no step may use it: `end` and the two exits.
    ///
    /// @param id A step id.
    /// @return Whether the id is reserved.
    public static boolean isReserved(final String id) {
        return END.equals(id) || PARK.equals(id) || GIVE_UP.equals(id);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final Transition that = (Transition) o;
        return on == that.on && Objects.equals(goTo, that.goTo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(on, goTo);
    }

    @Override
    public String toString() {
        return "Transition{on=" + on + ", goTo=" + goTo + '}';
    }
}
