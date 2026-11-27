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

import stroom.util.shared.SerialisationTestConstructor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/// One step of a learning flow: the kind of question it asks, and what governs the asking. That is an id for
/// a transition to name, a role for a `CONFIGURE` step, a guard on the kind of input, its limits, and where it
/// goes on each outcome. A step does not choose its own checks: every check that can measure a step judges it,
/// and whether each check is Off, Advice or Gate is set once on the Learning Flow document.
///
/// A step is read and written as JSON with Stroom's usual Jackson conversion, and built in code with
/// [#builder(QuestionKind)]. Where a person reads a step or a rule, the words come from [FlowWords].
@JsonPropertyOrder({"id", "kind", "role", "when", "candidates", "kinds", "elements", "transitions"})
@JsonInclude(Include.NON_NULL)
public class FlowStep {

    public static final int DEFAULT_KINDS = 3;
    /// A step id: a letter, then letters, digits, hyphens or underscores; matched without regard to case.
    public static final String ID = "[A-Za-z][A-Za-z0-9_-]*";
    /// The element types the model can configure, one for each of the server's step runners and in the same
    /// order (`TestStageFactory` in `stroom-learning-flow` checks that the two agree). The chain question
    /// chooses from these where the Chain step names none, and the Setup tab offers them as tick boxes. There
    /// is no `XMLParser`: a stream that is already XML needs no configured parser, so its chain begins with the
    /// transform and the pipeline puts an `XMLParser` in front of it.
    public static final List<String> LEARNABLE_ELEMENTS = List.of(
            "DSParser", "JSONParser", "XMLFragmentParser", "XSLTFilter");

    /// The name a transition uses to go to this step; null where the step uses its default name,
    /// [#effectiveId()].
    @JsonProperty
    private final String id;
    @JsonProperty
    private final QuestionKind kind;
    /// Which elements a `CONFIGURE` step configures; null for every element in chain order.
    @JsonProperty
    private final ConfigureRole role;
    @JsonProperty
    private final StepGuard when;
    @JsonProperty
    private final Integer candidates;
    @JsonProperty
    private final Integer kinds;
    @JsonProperty
    private final List<Transition> transitions;
    /// The element types the chain question chooses from. Only a Chain step names any; empty means it chooses
    /// from [#LEARNABLE_ELEMENTS]. With exactly one, the question is not asked.
    @JsonProperty
    private final List<String> elements;

    public FlowStep(final String id,
                    final QuestionKind kind,
                    final ConfigureRole role,
                    final StepGuard when,
                    final Integer candidates,
                    final Integer kinds,
                    final List<Transition> transitions) {
        this(id, kind, role, when, candidates, kinds, transitions, null);
    }

    @JsonCreator
    public FlowStep(@JsonProperty("id") final String id,
                    @JsonProperty("kind") final QuestionKind kind,
                    @JsonProperty("role") final ConfigureRole role,
                    @JsonProperty("when") final StepGuard when,
                    @JsonProperty("candidates") final Integer candidates,
                    @JsonProperty("kinds") final Integer kinds,
                    @JsonProperty("transitions") final List<Transition> transitions,
                    @JsonProperty("elements") final List<String> elements) {
        this.kind = Objects.requireNonNull(kind, "A step needs a question kind");
        if (role != null && kind != QuestionKind.CONFIGURE) {
            throw new IllegalArgumentException("Only CONFIGURE takes a role, in step " + kind.name());
        }
        if (elements != null && !elements.isEmpty() && kind != QuestionKind.CHAIN) {
            throw new IllegalArgumentException("Only CHAIN names elements, in step " + kind.name());
        }
        if (candidates != null && candidates < 1) {
            throw new IllegalArgumentException("'candidates' must be at least 1, in step " + kind.name());
        }
        if (kinds != null && kinds < 1) {
            throw new IllegalArgumentException("'kinds' must be at least 1, in step " + kind.name());
        }
        // Names are matched without regard to case, as kinds, roles and outcomes are read.
        this.id = id == null || id.trim().isEmpty()
                ? null
                : id.trim().toLowerCase();
        if (this.id != null && !this.id.matches(ID)) {
            throw new IllegalArgumentException("'" + id + "' is not a step id; an id is a word, in step "
                                               + kind.name());
        }
        this.role = role;
        this.when = when == null
                ? StepGuard.ALWAYS
                : when;
        this.candidates = candidates;
        this.kinds = kinds;
        this.transitions = transitions == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(transitions));
        final List<String> named = new ArrayList<>();
        if (elements != null) {
            for (final String element : elements) {
                if (element != null && !element.trim().isEmpty()) {
                    named.add(element.trim());
                }
            }
        }
        this.elements = Collections.unmodifiableList(named);
    }

    // The minimal valid step, for serialisation tests: the real constructors refuse a step with no kind.
    @SerialisationTestConstructor
    private FlowStep() {
        this(null, QuestionKind.CHAIN, null, null, null, null, null, null);
    }

    /// Creates a step with no id, role or transitions.
    ///
    /// @param kind       The question the step asks.
    /// @param when       The kind of input the step is asked for.
    /// @param candidates The candidates the step may spend, or null for the document's maximum.
    /// @param kinds      How many kinds of record a target step asks about, or null for [#DEFAULT_KINDS].
    public FlowStep(final QuestionKind kind,
                    final StepGuard when,
                    final Integer candidates,
                    final Integer kinds) {
        this(null, kind, null, when, candidates, kinds, null, null);
    }

    /// @param kind The question the step asks.
    /// @return A step of that kind, asked whatever the input, with the document's limits.
    public static FlowStep of(final QuestionKind kind) {
        return new FlowStep(kind, StepGuard.ALWAYS, null, null);
    }

    /// @param kind The question the step asks.
    /// @param when The kind of input the step is asked for.
    /// @return A step of that kind under that guard, with the document's limits.
    public static FlowStep of(final QuestionKind kind, final StepGuard when) {
        return new FlowStep(kind, when, null, null);
    }

    /// Starts building a step field by field.
    ///
    /// @param kind The question the step asks.
    /// @return A builder for a step of that kind.
    public static Builder builder(final QuestionKind kind) {
        return new Builder(kind);
    }

    /// @return The id it was given, or null where the step goes by [#effectiveId()].
    public String getId() {
        return id;
    }

    /// @return The question this step asks.
    public QuestionKind getKind() {
        return kind;
    }

    /// @return Which elements a `CONFIGURE` step configures, or null for every element in chain order.
    public ConfigureRole getRole() {
        return role;
    }

    /// @return The kind of input the step is asked for: any, raw text, XML or JSON.
    public StepGuard getWhen() {
        return when;
    }

    /// @return The candidates this step may spend, or null for the document's maximum.
    public Integer getCandidates() {
        return candidates;
    }

    /// @return How many kinds of record a target step asks about, or null for [#DEFAULT_KINDS].
    public Integer getKinds() {
        return kinds;
    }

    /// @return Where the step goes on each outcome, in the order they are declared.
    public List<Transition> getTransitions() {
        return transitions;
    }

    /// @return The element types a Chain step names, or empty where it chooses from every learnable one.
    public List<String> getElements() {
        return elements;
    }

    /// @return The element types the chain question chooses from: the ones named, else every learnable one.
    public List<String> effectiveElements() {
        return elements.isEmpty()
                ? LEARNABLE_ELEMENTS
                : elements;
    }

    /// @param elements The element types to name; only a Chain step may name any.
    /// @return A copy of this step naming those elements.
    public FlowStep withElements(final List<String> elements) {
        return new FlowStep(id, kind, role, when, candidates, kinds, transitions, elements);
    }

    /// @return The name a transition uses to go to this step: its id, or else the role's word for a `CONFIGURE`
    /// step with a role, or else the kind's name in lower case.
    public String effectiveId() {
        if (id != null) {
            return id;
        }
        return role != null
                ? role.getDisplayValue()
                : kind.name().toLowerCase();
    }

    /// @param outcome An outcome.
    /// @return The transition that fires at once on that outcome, or null if the step declares none.
    public Transition transitionOn(final StepOutcome outcome) {
        for (final Transition transition : transitions) {
            if (transition.getOn() == outcome) {
                return transition;
            }
        }
        return null;
    }

    /// @return The transition that fires when the candidates are spent, or null if the step declares none.
    public Transition transitionOnSpent() {
        for (final Transition transition : transitions) {
            if (transition.onSpent()) {
                return transition;
            }
        }
        return null;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final FlowStep that = (FlowStep) o;
        return Objects.equals(id, that.id) && kind == that.kind && role == that.role && when == that.when
               && Objects.equals(candidates, that.candidates) && Objects.equals(kinds, that.kinds)
               && transitions.equals(that.transitions)
               && elements.equals(that.elements);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, kind, role, when, candidates, kinds, transitions, elements);
    }

    @Override
    public String toString() {
        return "FlowStep{id=" + id + ", kind=" + kind + ", role=" + role + ", when=" + when
               + ", candidates=" + candidates + ", kinds=" + kinds + ", elements=" + elements
               + ", transitions=" + transitions + '}';
    }

    // --------------------------------------------------------------------------------


    /// A step built field by field, from [FlowStep#builder(QuestionKind)]. Everything but the kind is
    /// optional, and a step with nothing else set is asked always, with the document's limits, and goes
    /// on to the next step.
    public static final class Builder {

        private final QuestionKind kind;
        private final List<Transition> transitions = new ArrayList<>();
        private String id;
        private ConfigureRole role;
        private StepGuard when;
        private Integer candidates;
        private Integer kinds;
        private List<String> elements;

        private Builder(final QuestionKind kind) {
            this.kind = kind;
        }

        /// @param id The name a transition reaches this step by, in place of [FlowStep#effectiveId()].
        /// @return This builder.
        public Builder id(final String id) {
            this.id = id;
            return this;
        }

        /// @param role Which elements a `CONFIGURE` step configures.
        /// @return This builder.
        public Builder role(final ConfigureRole role) {
            this.role = role;
            return this;
        }

        /// @param when The input the step is asked for.
        /// @return This builder.
        public Builder when(final StepGuard when) {
            this.when = when;
            return this;
        }

        /// @param candidates The candidates the step may spend, in place of the document's maximum.
        /// @return This builder.
        public Builder candidates(final int candidates) {
            this.candidates = candidates;
            return this;
        }

        /// @param kinds How many kinds of record a target step asks about.
        /// @return This builder.
        public Builder kinds(final int kinds) {
            this.kinds = kinds;
            return this;
        }

        /// @param elements The element types a Chain step chooses from.
        /// @return This builder.
        public Builder elements(final String... elements) {
            this.elements = List.of(elements);
            return this;
        }

        /// Go to another step, [Transition#END] or an exit as soon as an outcome happens.
        ///
        /// @param on The outcome.
        /// @param goTo The step, `end` or exit to go to.
        /// @return This builder.
        public Builder goTo(final StepOutcome on, final String goTo) {
            transitions.add(new Transition(Objects.requireNonNull(on, "An outcome is needed"), goTo));
            return this;
        }

        /// Leave the flow, by the step's own exit, as soon as an outcome happens.
        ///
        /// @param on The outcome.
        /// @return This builder.
        public Builder abandonOn(final StepOutcome on) {
            transitions.add(new Transition(Objects.requireNonNull(on, "An outcome is needed"), null));
            return this;
        }

        /// Go to another step, [Transition#END] or an exit once the step's candidates are spent.
        ///
        /// @param goTo The step, `end` or exit to go to.
        /// @return This builder.
        public Builder whenSpentGoTo(final String goTo) {
            transitions.add(new Transition(null, goTo));
            return this;
        }

        /// Leave the flow, by the step's own exit, once the step's candidates are spent. A step does this
        /// anyway without a transition; this makes it explicit.
        ///
        /// @return This builder.
        public Builder abandonWhenSpent() {
            transitions.add(new Transition(null, null));
            return this;
        }

        /// @return The step.
        public FlowStep build() {
            return new FlowStep(id, kind, role, when, candidates, kinds, transitions, elements);
        }
    }
}
