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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/// Where a step goes on one outcome (A37; design 01 §10.2), written `on <outcome> goto <step>`,
/// `on <outcome> abandon`, `on spent goto <step>` or `on spent abandon`. A transition on an outcome
/// fires at once, on the first candidate that has it; a transition on `spent` fires when the step's
/// candidates are gone, where the default is to abandon. Self re-ask on a shortfall needs no transition:
/// it is what a step does until it is spent, and a pass goes to the next line unless `on passed goto`
/// says otherwise. The step named [#END] is the end of the flow.
@JsonPropertyOrder({"on", "goTo"})
@JsonInclude(Include.NON_NULL)
public class Transition {

    public static final String SPENT = "spent";
    public static final String ON = "on";
    public static final String GOTO = "goto";
    public static final String ABANDON = "abandon";
    /// The reserved step name a `goto` reaches when the flow is complete.
    public static final String END = "end";
    /// The reserved names of the two exits (A83): `goto park` and `goto give-up`.
    public static final String PARK = "park";
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

    /// Whether this fires when the candidates are spent rather than on an outcome.
    public boolean onSpent() {
        return on == null;
    }

    /// Whether this leaves the flow without binding, rather than going to a step or to `end`: written
    /// `abandon`, which is the step's own default exit, or `goto park` or `goto give-up` (A83).
    public boolean exits() {
        return goTo == null || PARK.equals(goTo) || GIVE_UP.equals(goTo);
    }

    /// The exit this names, or null where it is written `abandon` and the step's default applies: give-up
    /// for a parser, whose spent candidates are the one failure nothing can serve (A52, A54), and park for
    /// any other step (A65).
    public FlowExit namedExit() {
        if (PARK.equals(goTo)) {
            return FlowExit.PARK;
        }
        if (GIVE_UP.equals(goTo)) {
            return FlowExit.GIVE_UP;
        }
        return null;
    }

    /// Whether a name is one of the flow's own places, which no step may take: `end` and the two exits.
    public static boolean isReserved(final String id) {
        return END.equals(id) || PARK.equals(id) || GIVE_UP.equals(id);
    }

    /// The transition as it is written: `on coverage-short goto parser`, `on spent abandon`.
    public String format() {
        return ON + ' ' + (on == null
                ? SPENT
                : on.getDisplayValue()) + ' ' + (goTo == null
                ? ABANDON
                : GOTO + ' ' + goTo);
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
        return format();
    }
}
