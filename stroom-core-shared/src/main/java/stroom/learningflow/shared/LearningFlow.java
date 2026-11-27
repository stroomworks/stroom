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

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// The learning flow a Learning Flow document holds: the graph of steps (questions and their transitions) that
/// a learning session walks, plus the prompt templates the document overrides. What a session asks the model
/// and hears back while following the flow is its conversation. A flow with no steps is invalid; it is not
/// given a default set of steps.
@JsonPropertyOrder({"steps", "templates", "builtInVersion"})
@JsonInclude(Include.NON_NULL)
public class LearningFlow {

    /// The document's own steps, in the order a session first reaches them.
    @JsonProperty
    private final List<FlowStep> steps;
    /// Only the templates this document changes.
    @JsonProperty
    private final Map<Template, String> templates;
    /// The version of the built-in template text this document was last saved against, so that a session read
    /// back later can be told apart from one run under a newer default; null until saved by a server that sets
    /// it.
    @JsonProperty
    private final Integer builtInVersion;

    @JsonCreator
    public LearningFlow(@JsonProperty("steps") final List<FlowStep> steps,
                        @JsonProperty("templates") final Map<Template, String> templates,
                        @JsonProperty("builtInVersion") final Integer builtInVersion) {
        // An empty flow stays empty rather than being given the Direct example's steps, because a flow that
        // silently gained steps would behave in a way nobody chose. An empty flow is reported as invalid by
        // [#check] ("The flow has no steps"), which the Setup tab, the readiness banner and the refusal to
        // save all show.
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps == null
                ? List.of()
                : steps));
        final Map<Template, String> overrides = new EnumMap<>(Template.class);
        if (templates != null) {
            // A blank override is dropped, so the document keeps using the built-in text.
            templates.forEach((template, text) -> {
                if (text != null && !text.trim().isEmpty()) {
                    overrides.put(template, text);
                }
            });
        }
        this.templates = Collections.unmodifiableMap(overrides);
        this.builtInVersion = builtInVersion;
    }

    /// @return The steps in the order a session first reaches them.
    public List<FlowStep> getSteps() {
        return steps;
    }

    /// @return Only the templates this document overrides, by template.
    public Map<Template, String> getTemplates() {
        return templates;
    }

    /// @return The built-in text's version this document was last saved against, or null until saved.
    public Integer getBuiltInVersion() {
        return builtInVersion;
    }

    /// @param id A step name, as a transition gives it.
    /// @return The step with that name, or null where no step has it.
    public FlowStep step(final String id) {
        for (final FlowStep step : steps) {
            if (step.effectiveId().equals(id)) {
                return step;
            }
        }
        return null;
    }

    /// Checks the structure of the steps. The rules are: there is at least one step; a CHAIN step comes first,
    /// once and without transitions; a CONFIGURE or ADMIT step comes last; there is at least one CONFIGURE step,
    /// and every other kind appears at most once; every id is unique and none is a reserved name; every
    /// transition goes to a step, `end` or an exit; and only a transition for spent candidates goes to the
    /// ADMIT (fallback) step.
    ///
    /// Each problem names the step it is about, where it is about one, so that the editor can mark that step,
    /// and uses the wording of [FlowWords], since a person reads it on the Setup tab and when a save is refused.
    /// The templates' variables are checked on the server, which holds the built-in text.
    ///
    /// @return The problems; empty where the steps make a valid flow.
    public List<FlowProblem> check() {
        final List<FlowProblem> problems = new ArrayList<>();
        if (steps.isEmpty()) {
            problems.add(new FlowProblem(null, "The flow has no steps"));
            return problems;
        }
        if (steps.get(0).getKind() != QuestionKind.CHAIN) {
            problems.add(new FlowProblem(0, "The first step must be '"
                                            + FlowWords.stepName(QuestionKind.CHAIN, null) + "'"));
        }
        final int last = steps.size() - 1;
        final QuestionKind lastKind = steps.get(last).getKind();
        if (lastKind != QuestionKind.CONFIGURE && lastKind != QuestionKind.ADMIT) {
            problems.add(new FlowProblem(last, "The last step must write the translation, or be the fallback"));
        }
        for (final QuestionKind kind : QuestionKind.values()) {
            int count = 0;
            for (final FlowStep step : steps) {
                if (step.getKind() == kind) {
                    count++;
                }
            }
            if (count > 1 && kind != QuestionKind.CONFIGURE) {
                problems.add(new FlowProblem(null, "The flow has " + count + " '" + FlowWords.stepName(kind, null)
                                                   + "' steps; it may have only one"));
            }
            if (count == 0 && kind == QuestionKind.CHAIN) {
                problems.add(new FlowProblem(null, "The flow needs a '" + FlowWords.stepName(kind, null)
                                                   + "' step"));
            }
            if (count == 0 && kind == QuestionKind.CONFIGURE) {
                problems.add(new FlowProblem(null, "The flow needs a step that writes the translation"));
            }
        }
        final Map<String, FlowStep> ids = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            final FlowStep step = steps.get(i);
            final String named = FlowWords.capitalise(FlowWords.step(steps, i));
            // A step's id is never shown or edited; a step added on the Setup tab is given one of its own,
            // so deleting the step and adding it again is how a person fixes a bad one.
            if (Transition.isReserved(step.effectiveId())) {
                problems.add(new FlowProblem(i, named + " has the id '" + step.effectiveId()
                                                + "', which the flow keeps for itself; delete the step and add it "
                                                + "again"));
            }
            final FlowStep first = ids.putIfAbsent(step.effectiveId(), step);
            // Two steps of a kind that may appear only once have already been reported above, unless one of
            // them was given an explicit id.
            final boolean reported = first != null && first.getKind() == step.getKind()
                                     && step.getKind() != QuestionKind.CONFIGURE
                                     && first.getId() == null && step.getId() == null;
            if (first != null && !reported) {
                problems.add(new FlowProblem(i, named + " has the same id as an earlier step, '"
                                                + step.effectiveId() + "'; delete it and add it again"));
            }
        }
        for (int i = 0; i < steps.size(); i++) {
            final FlowStep step = steps.get(i);
            final Set<String> fired = new HashSet<>();
            for (final Transition transition : step.getTransitions()) {
                final String on = FlowWords.condition(transition.getOn());
                if (!fired.add(on)) {
                    problems.add(new FlowProblem(i, FlowWords.capitalise(FlowWords.step(steps, i))
                                                    + " has two rules for when " + on
                                                    + "; only the first would be followed"));
                }
            }
            if (step.getKind() == QuestionKind.CHAIN && !step.getTransitions().isEmpty()) {
                // No later step can run without a chain, so a refused chain is asked again, and one whose
                // candidates are spent leaves the flow by its default exit.
                problems.add(new FlowProblem(i, "'" + FlowWords.stepName(QuestionKind.CHAIN, null)
                                                + "' can't have rules: nothing else can run until the elements are "
                                                + "chosen"));
            }
            for (final Transition transition : step.getTransitions()) {
                if (transition.exits() || Transition.END.equals(transition.getGoTo())) {
                    continue;
                }
                if (!ids.containsKey(transition.getGoTo())) {
                    problems.add(new FlowProblem(i, "The rule 'if " + FlowWords.condition(transition.getOn())
                                                    + "' on " + FlowWords.step(steps, i) + " goes to '"
                                                    + transition.getGoTo() + "', which isn't a step"));
                } else if (ids.get(transition.getGoTo()).getKind() == QuestionKind.ADMIT && !transition.onSpent()) {
                    // The fallback step may be reached only when a step's candidates are spent. A flow that
                    // went there sooner would let the model write marked Unknowns before it had finished
                    // trying to translate the records.
                    problems.add(new FlowProblem(i, "The rule 'if " + FlowWords.condition(transition.getOn())
                                                    + "' on " + FlowWords.step(steps, i) + " uses the fallback, "
                                                    + "which only a rule for running out of tries may"));
                }
            }
        }
        return problems;
    }

    /// Checks the flow as [#check()] does, and adds warnings that depend on the document's checks: a rule
    /// (transition) that fires when a check falls short can never fire if that check is Off. Each warning
    /// names its step, so that the step's card can show it; warnings do not stop the document being saved.
    ///
    /// @param checks The document's checks; null or empty for none, when only [#check()]'s problems are
    ///               returned.
    /// @return The problems and warnings, in order.
    public List<FlowProblem> check(final List<CheckSetting> checks) {
        final List<FlowProblem> problems = check();
        if (checks == null || checks.isEmpty()) {
            return problems;
        }
        final Map<Check, CheckState> states = new EnumMap<>(Check.class);
        for (final CheckSetting setting : checks) {
            states.put(setting.getCheck(), setting.getState());
        }
        for (int i = 0; i < steps.size(); i++) {
            final FlowStep step = steps.get(i);
            for (final Transition transition : step.getTransitions()) {
                final Check check = Check.ofShortfall(transition.getOn());
                if (check != null && states.get(check) == CheckState.OFF) {
                    problems.add(new FlowProblem(i, "The rule 'if " + FlowWords.condition(transition.getOn())
                                                    + "' on " + FlowWords.step(steps, i) + " never fires: the "
                                                    + check.getDisplayValue() + " check is off",
                            FlowProblem.Severity.WARNING));
                }
            }
        }
        return problems;
    }

    /// The messages of the problems [#check()] finds, in order, which are what a save is refused with.
    ///
    /// [#check()] raises no warnings, so the severity filter currently changes nothing; it is there so that
    /// a save is still refused only for problems if a warning ever reaches this path. Warnings that depend on
    /// the document's checks come from [#check(List)], which only the Setup tab calls.
    ///
    /// @return The problem messages; empty where the flow is valid.
    public List<String> problems() {
        return FlowProblem.messages(check(), FlowProblem.Severity.PROBLEM);
    }

    /// @return The element types the chain question chooses from: the Chain step's, or every learnable one
    /// where it names none or the flow has no Chain step.
    public List<String> allowedElements() {
        for (final FlowStep step : steps) {
            if (step.getKind() == QuestionKind.CHAIN) {
                return step.effectiveElements();
            }
        }
        return FlowStep.LEARNABLE_ELEMENTS;
    }

    /// @param elements The element types the Chain step should name.
    /// @return A copy of this flow with its Chain step naming those elements, or this flow unchanged where it
    /// has no Chain step.
    public LearningFlow withChainElements(final List<String> elements) {
        final List<FlowStep> changed = new ArrayList<>();
        boolean found = false;
        for (final FlowStep step : steps) {
            if (!found && step.getKind() == QuestionKind.CHAIN) {
                changed.add(step.withElements(elements));
                found = true;
            } else {
                changed.add(step);
            }
        }
        return found
                ? new LearningFlow(changed, templates, builtInVersion)
                : this;
    }

    /// @param steps The new steps.
    /// @return A copy of this flow with those steps.
    public LearningFlow withSteps(final List<FlowStep> steps) {
        return new LearningFlow(steps, templates, builtInVersion);
    }

    /// @param templates The new template overrides.
    /// @return A copy of this flow with those overrides.
    public LearningFlow withTemplates(final Map<Template, String> templates) {
        return new LearningFlow(steps, templates, builtInVersion);
    }

    /// @param version The version of the built-in template text the flow is being saved against.
    /// @return A copy of this flow recording that version.
    public LearningFlow withBuiltInVersion(final int version) {
        return new LearningFlow(steps, templates, version);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final LearningFlow that = (LearningFlow) o;
        return Objects.equals(steps, that.steps) && Objects.equals(templates, that.templates)
               && Objects.equals(builtInVersion, that.builtInVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(steps, templates, builtInVersion);
    }

    @Override
    public String toString() {
        return "LearningFlow{steps=" + steps + ", templates=" + templates.keySet()
               + ", builtInVersion=" + builtInVersion + '}';
    }
}
