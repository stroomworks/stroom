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

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// The learning flow (A33, A34, A37; design 01 §10.2): the graph of questions, checks and transitions the
/// Shapeshifter AI document holds, and the templates it overrides. An attempt follows the flow; what it
/// says and hears is its conversation. A document naming no steps is read as [FlowExample#DIRECT]'s.
@JsonPropertyOrder({"steps", "templates", "builtInVersion"})
@JsonInclude(Include.NON_NULL)
public class LearningFlow {

    /// The document's own steps, in the order they are first entered.
    @JsonProperty
    private final List<FlowStep> steps;
    /// Only the templates this document changes.
    @JsonProperty
    private final Map<Template, String> templates;
    /// The version of the built-in text this document was last saved against, so that a run read back can be
    /// told apart from a later default; null until saved by a node that knows.
    @JsonProperty
    private final Integer builtInVersion;

    @JsonCreator
    public LearningFlow(@JsonProperty("steps") final List<FlowStep> steps,
                        @JsonProperty("templates") final Map<Template, String> templates,
                        @JsonProperty("builtInVersion") final Integer builtInVersion) {
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps == null
                ? FlowExample.DIRECT.steps()
                : steps));
        final Map<Template, String> overrides = new EnumMap<>(Template.class);
        if (templates != null) {
            // A blank override is no override: the document keeps following the built-in.
            templates.forEach((template, text) -> {
                if (text != null && !text.trim().isEmpty()) {
                    overrides.put(template, text);
                }
            });
        }
        this.templates = Collections.unmodifiableMap(overrides);
        this.builtInVersion = builtInVersion;
    }

    /// A flow with an example's steps as its own and the built-in text.
    public static LearningFlow of(final FlowExample example) {
        return new LearningFlow(example.steps(), null, null);
    }

    /// @return The steps in the order they are first entered.
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

    /// The step `goto` reaches by a name, or null where no step has it.
    public FlowStep step(final String id) {
        for (final FlowStep step : steps) {
            if (step.effectiveId().equals(id)) {
                return step;
            }
        }
        return null;
    }

    /// What is wrong with the steps, structurally: CHAIN first, once and without transitions, CONFIGURE or
    /// ADMIT last, SPLIT and TARGET at most once each, every id unique and none a reserved name, every `goto`
    /// naming a step, `end` or an exit. Empty where the steps are a flow the stage can hold. Each problem
    /// names the step it is about where it is about one (A88). The templates' variables are the server's
    /// to check, since it holds the built-in text.
    public List<FlowProblem> check() {
        final List<FlowProblem> problems = new ArrayList<>();
        if (steps.isEmpty()) {
            problems.add(new FlowProblem(null, "The flow has no steps"));
            return problems;
        }
        if (steps.get(0).getKind() != QuestionKind.CHAIN) {
            problems.add(new FlowProblem(0, "The first step must be CHAIN"));
        }
        final int last = steps.size() - 1;
        final QuestionKind lastKind = steps.get(last).getKind();
        if (lastKind != QuestionKind.CONFIGURE && lastKind != QuestionKind.ADMIT) {
            problems.add(new FlowProblem(last, "The last step must be CONFIGURE or ADMIT"));
        }
        for (final QuestionKind kind : QuestionKind.values()) {
            int count = 0;
            for (final FlowStep step : steps) {
                if (step.getKind() == kind) {
                    count++;
                }
            }
            if (count > 1 && kind != QuestionKind.CONFIGURE) {
                problems.add(new FlowProblem(null, kind.name() + " may appear once, not " + count + " times"));
            }
            if (count == 0 && (kind == QuestionKind.CHAIN || kind == QuestionKind.CONFIGURE)) {
                problems.add(new FlowProblem(null, kind.name() + " must appear"));
            }
        }
        final Map<String, FlowStep> ids = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            final FlowStep step = steps.get(i);
            if (Transition.END.equals(step.effectiveId())) {
                problems.add(new FlowProblem(i, "'" + Transition.END + "' is the end of the flow and cannot name a "
                                                + "step"));
            } else if (Transition.isReserved(step.effectiveId())) {
                problems.add(new FlowProblem(i, "'" + step.effectiveId() + "' is an exit of the flow and cannot "
                                                + "name a step"));
            }
            final FlowStep first = ids.putIfAbsent(step.effectiveId(), step);
            // Two of a kind that may appear once have been reported above, unless one was named on purpose.
            final boolean reported = first != null && first.getKind() == step.getKind()
                                     && step.getKind() != QuestionKind.CONFIGURE
                                     && first.getId() == null && step.getId() == null;
            if (first != null && !reported) {
                problems.add(new FlowProblem(i, "Two steps are named '" + step.effectiveId() + "'; give one an id"));
            }
        }
        for (int i = 0; i < steps.size(); i++) {
            final FlowStep step = steps.get(i);
            final Set<String> fired = new HashSet<>();
            for (final Transition transition : step.getTransitions()) {
                final String on = transition.onSpent()
                        ? Transition.SPENT
                        : transition.getOn().getDisplayValue();
                if (!fired.add(on)) {
                    problems.add(new FlowProblem(i, "Step '" + step.effectiveId() + "' says 'on " + on + "' twice; "
                                                    + "the first would be taken and the second never"));
                }
            }
            if (step.getKind() == QuestionKind.CHAIN && !step.getTransitions().isEmpty()) {
                // No later step can run without a chain: a refused chain is re-asked, and one whose candidates
                // are spent abandons.
                problems.add(new FlowProblem(i, "CHAIN takes no transition; nothing can run until the chain is "
                                                + "settled"));
            }
            for (final Transition transition : step.getTransitions()) {
                if (transition.exits() || Transition.END.equals(transition.getGoTo())) {
                    continue;
                }
                if (!ids.containsKey(transition.getGoTo())) {
                    problems.add(new FlowProblem(i, "'" + transition.format() + "' in step '" + step.effectiveId()
                                                    + "' names no step"));
                } else if (ids.get(transition.getGoTo()).getKind() == QuestionKind.ADMIT && !transition.onSpent()) {
                    // The exhaustion step is reached when the candidates are spent and by no other route
                    // (design 04 §4): a flow that sent a model there sooner would be asking for the
                    // admitted form before the trying was done.
                    problems.add(new FlowProblem(i, "'" + transition.format() + "' in step '" + step.effectiveId()
                                                    + "' goes to ADMIT, which only 'on spent' may"));
                }
            }
        }
        return problems;
    }

    /// The messages of [#check()], in order.
    public List<String> problems() {
        return FlowProblem.messages(check());
    }

    /// The element types the chain question chooses from (A21, A95): the Chain step's, or the default set
    /// where it names none or the flow has no Chain step.
    public List<String> allowedElements() {
        for (final FlowStep step : steps) {
            if (step.getKind() == QuestionKind.CHAIN) {
                return step.effectiveElements();
            }
        }
        return FlowStep.DEFAULT_ELEMENTS;
    }

    /// This flow with its Chain step naming other elements; a flow with no Chain step is returned as it is.
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

    /// This flow with other steps.
    public LearningFlow withSteps(final List<FlowStep> steps) {
        return new LearningFlow(steps, templates, builtInVersion);
    }

    /// This flow with other template overrides.
    public LearningFlow withTemplates(final Map<Template, String> templates) {
        return new LearningFlow(steps, templates, builtInVersion);
    }

    /// This flow stamped with the built-in text's version it is saved against.
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
