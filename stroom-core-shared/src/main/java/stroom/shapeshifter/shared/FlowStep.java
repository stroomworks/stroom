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

import stroom.docref.HasDisplayValue;
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

/// One step of a learning flow (A33, A37; design 01 §10.2): a question kind with what governs its asking —
/// an id for `goto`, a role for `CONFIGURE`, a guard, its limits, the checks that judge it and where it goes
/// on each outcome. Written and read as one line:
///
/// ```
/// [id:] KIND [parser|transform] [when always|text|xml|json] [candidates n] [kinds n] [elements a,b,…]
///       [checks a,b,…] [on <outcome> goto <id> | abandon]… [on spent goto <id> | abandon]
///
/// A `goto` may name the reserved step `end`, the end of the flow.
/// ```
@JsonPropertyOrder({"id", "kind", "role", "when", "candidates", "kinds", "elements", "checks", "transitions"})
@JsonInclude(Include.NON_NULL)
public class FlowStep {

    public static final int DEFAULT_KINDS = 3;
    /// A step id: a letter, then letters, digits, hyphens or underscores; matched without regard to case.
    public static final String ID = "[A-Za-z][A-Za-z0-9_-]*";
    /// Ruling A10's initial set: what the chain question chooses from until the Chain step says otherwise.
    public static final List<String> DEFAULT_ELEMENTS = List.of("DSParser", "JSONParser", "XMLParser", "XSLTFilter");
    private static final String WHEN = "when";
    private static final String ELEMENTS = "elements";
    private static final String CANDIDATES = "candidates";
    private static final String KINDS = "kinds";
    private static final String CHECKS = "checks";

    /// The step's name for `goto`; null where the step goes by its default name, [#effectiveId()].
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
    /// The checks that judge this step; empty for the kind's own.
    @JsonProperty
    private final List<Check> checks;
    @JsonProperty
    private final List<Transition> transitions;
    /// The element types the chain question chooses from (A21, A95): the Chain step's alone, and empty
    /// where it chooses from [#DEFAULT_ELEMENTS]. With exactly one the question is not asked.
    @JsonProperty
    private final List<String> elements;

    public FlowStep(final String id,
                    final QuestionKind kind,
                    final ConfigureRole role,
                    final StepGuard when,
                    final Integer candidates,
                    final Integer kinds,
                    final List<Check> checks,
                    final List<Transition> transitions) {
        this(id, kind, role, when, candidates, kinds, checks, transitions, null);
    }

    @JsonCreator
    public FlowStep(@JsonProperty("id") final String id,
                    @JsonProperty("kind") final QuestionKind kind,
                    @JsonProperty("role") final ConfigureRole role,
                    @JsonProperty("when") final StepGuard when,
                    @JsonProperty("candidates") final Integer candidates,
                    @JsonProperty("kinds") final Integer kinds,
                    @JsonProperty("checks") final List<Check> checks,
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
        // Names are matched without regard to case, as kinds, roles, checks and outcomes are read.
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
        this.checks = checks == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(checks));
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

    /// A step that is only enough to be one: what a serialisation test builds, since a step with no
    /// kind is not a step and the real constructor says so.
    @SerialisationTestConstructor
    private FlowStep() {
        this(null, QuestionKind.CHAIN, null, null, null, null, null, null, null);
    }

    /// A step with no id, role, checks or transitions: the form the A34 list had.
    public FlowStep(final QuestionKind kind,
                    final StepGuard when,
                    final Integer candidates,
                    final Integer kinds) {
        this(null, kind, null, when, candidates, kinds, null, null);
    }

    /// A step of a kind alone, asked always, with the document's limits.
    public static FlowStep of(final QuestionKind kind) {
        return new FlowStep(kind, StepGuard.ALWAYS, null, null);
    }

    /// A step of a kind under a guard, with the document's limits.
    public static FlowStep of(final QuestionKind kind, final StepGuard when) {
        return new FlowStep(kind, when, null, null);
    }

    /// @return The id as written, or null where the step goes by [#effectiveId()].
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

    /// @return When the step is asked: always, for raw text, or for input that is already records.
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

    /// @return The checks that judge this step, empty for the kind's own.
    public List<Check> getChecks() {
        return checks;
    }

    /// @return Where the step goes on each outcome, in the order written.
    public List<Transition> getTransitions() {
        return transitions;
    }

    /// The element types a Chain step names, or empty where it chooses from the default set.
    public List<String> getElements() {
        return elements;
    }

    /// The element types the chain question chooses from: the ones named, else the default set.
    public List<String> effectiveElements() {
        return elements.isEmpty()
                ? DEFAULT_ELEMENTS
                : elements;
    }

    /// This step with other elements named; only a Chain step takes any.
    public FlowStep withElements(final List<String> elements) {
        return new FlowStep(id, kind, role, when, candidates, kinds, checks, transitions, elements);
    }

    /// The name `goto` reaches this step by: its id, or the role's word for a `CONFIGURE` with a role, or the
    /// kind's name in lower case.
    public String effectiveId() {
        if (id != null) {
            return id;
        }
        return role != null
                ? role.getDisplayValue()
                : kind.name().toLowerCase();
    }

    /// The transition that fires at once on an outcome, if the step declares one.
    public Transition transitionOn(final StepOutcome outcome) {
        for (final Transition transition : transitions) {
            if (transition.getOn() == outcome) {
                return transition;
            }
        }
        return null;
    }

    /// The transition that fires when the candidates are spent, if the step declares one.
    public Transition transitionOnSpent() {
        for (final Transition transition : transitions) {
            if (transition.onSpent()) {
                return transition;
            }
        }
        return null;
    }

    /// The step as one line, the form the Learning tab edits.
    public String format() {
        final StringBuilder text = new StringBuilder();
        if (id != null) {
            text.append(id).append(": ");
        }
        text.append(kind.name());
        if (role != null) {
            text.append(' ').append(role.getDisplayValue());
        }
        if (when != StepGuard.ALWAYS) {
            text.append(' ').append(WHEN).append(' ').append(when.getDisplayValue());
        }
        if (candidates != null) {
            text.append(' ').append(CANDIDATES).append(' ').append(candidates);
        }
        if (kinds != null) {
            text.append(' ').append(KINDS).append(' ').append(kinds);
        }
        if (!elements.isEmpty()) {
            text.append(' ').append(ELEMENTS).append(' ').append(String.join(",", elements));
        }
        if (!checks.isEmpty()) {
            text.append(' ').append(CHECKS).append(' ');
            for (int i = 0; i < checks.size(); i++) {
                text.append(i == 0
                        ? ""
                        : ",").append(checks.get(i).getDisplayValue());
            }
        }
        for (final Transition transition : transitions) {
            text.append(' ').append(transition.format());
        }
        return text.toString();
    }

    /// One line back into a step.
    ///
    /// @throws IllegalArgumentException Naming what is wrong with the line.
    public static FlowStep parse(final String line) {
        final String trimmed = line.trim();
        final String[] words = trimmed.split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            throw new IllegalArgumentException("A step needs a question kind");
        }
        int i = 0;
        String id = null;
        if (words[0].endsWith(":")) {
            id = words[0].substring(0, words[0].length() - 1);
            if (id.isEmpty() || !id.matches(ID)) {
                throw new IllegalArgumentException("'" + words[0] + "' is not a step id; an id is a word, then a "
                                                   + "colon, in step '" + trimmed + "'");
            }
            i = 1;
        }
        if (i >= words.length) {
            throw new IllegalArgumentException("A step needs a question kind, in step '" + trimmed + "'");
        }
        final QuestionKind kind = kind(words[i++]);
        ConfigureRole role = null;
        if (i < words.length && ConfigureRole.parse(words[i]) != null) {
            if (kind != QuestionKind.CONFIGURE) {
                throw new IllegalArgumentException("Only CONFIGURE takes a role, in step '" + trimmed + "'");
            }
            role = ConfigureRole.parse(words[i++]);
        }
        StepGuard when = StepGuard.ALWAYS;
        Integer candidates = null;
        Integer kinds = null;
        final List<Check> checks = new ArrayList<>();
        final List<Transition> transitions = new ArrayList<>();
        final List<String> elements = new ArrayList<>();
        while (i < words.length) {
            final String key = words[i].toLowerCase();
            if (Transition.ON.equals(key)) {
                i = transition(words, i, trimmed, transitions);
                continue;
            }
            if (i + 1 >= words.length) {
                throw new IllegalArgumentException("'" + words[i] + "' needs a value, in step '" + trimmed + "'");
            }
            final String value = words[i + 1];
            switch (key) {
                case WHEN -> {
                    when = StepGuard.parse(value);
                    if (when == null) {
                        throw new IllegalArgumentException("'when " + value + "' is not one of always, text, xml or "
                                                           + "json, in step '" + trimmed + "'");
                    }
                }
                case CANDIDATES -> candidates = positive(key, value, trimmed);
                case KINDS -> kinds = positive(key, value, trimmed);
                case ELEMENTS -> {
                    for (final String word : value.split(",")) {
                        if (!word.trim().isEmpty()) {
                            elements.add(word.trim());
                        }
                    }
                }
                case CHECKS -> {
                    for (final String word : value.split(",")) {
                        final Check check = Check.parse(word);
                        if (check == null) {
                            throw new IllegalArgumentException("'" + word + "' is not a check; the checks are "
                                                               + words(Check.values()) + ", in step '" + trimmed
                                                               + "'");
                        }
                        checks.add(check);
                    }
                }
                default -> throw new IllegalArgumentException("'" + words[i] + "' is not when, candidates, kinds, "
                                                              + "elements, checks or on, in step '" + trimmed + "'");
            }
            i += 2;
        }
        return new FlowStep(id, kind, role, when, candidates, kinds, checks, transitions, elements);
    }

    /// Reads `on <outcome|spent> goto <id>` or `on <outcome|spent> abandon` from `words[at]`.
    ///
    /// @return The index after the transition.
    private static int transition(final String[] words,
                                  final int at,
                                  final String line,
                                  final List<Transition> transitions) {
        if (at + 2 >= words.length) {
            throw new IllegalArgumentException("'on' needs an outcome and then 'goto <step>' or 'abandon', in step '"
                                               + line + "'");
        }
        final String word = words[at + 1];
        StepOutcome outcome = null;
        if (!Transition.SPENT.equalsIgnoreCase(word)) {
            outcome = StepOutcome.parse(word);
            if (outcome == null) {
                throw new IllegalArgumentException("'on " + word + "' is not an outcome; the outcomes are spent, "
                                                   + words(StepOutcome.values()) + ", in step '" + line + "'");
            }
        }
        final String action = words[at + 2].toLowerCase();
        if (Transition.ABANDON.equals(action)) {
            transitions.add(new Transition(outcome, null));
            return at + 3;
        }
        if (Transition.GOTO.equals(action) && at + 3 < words.length) {
            transitions.add(new Transition(outcome, words[at + 3]));
            return at + 4;
        }
        throw new IllegalArgumentException("'on " + word + "' must be followed by 'goto <step>' or 'abandon', in step '"
                                           + line + "'");
    }

    private static String words(final HasDisplayValue... values) {
        final StringBuilder text = new StringBuilder();
        for (final HasDisplayValue value : values) {
            text.append(text.length() == 0
                    ? ""
                    : ", ").append(value.getDisplayValue());
        }
        return text.toString();
    }

    private static QuestionKind kind(final String word) {
        for (final QuestionKind kind : QuestionKind.values()) {
            if (kind.name().equalsIgnoreCase(word)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("'" + word + "' is not a question kind; the kinds are CHAIN, SPLIT, "
                                           + "TARGET, CONFIGURE and ADMIT");
    }

    private static int positive(final String key, final String value, final String line) {
        final int number;
        try {
            number = Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("'" + key + " " + value + "' is not a number, in step '" + line + "'");
        }
        if (number < 1) {
            throw new IllegalArgumentException("'" + key + "' must be at least 1, in step '" + line + "'");
        }
        return number;
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
               && checks.equals(that.checks) && transitions.equals(that.transitions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, kind, role, when, candidates, kinds, checks, transitions);
    }

    @Override
    public String toString() {
        return format();
    }
}
