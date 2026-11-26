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

import stroom.docref.HasDisplayValue;
import stroom.learningflow.shared.BuiltInFlow;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.ConfigureRole;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.FlowWords;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StepGuard;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.Transition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// The operations behind the step cards that edit a learning flow in the Learning steps section of the Setup
/// tab ([ApproachSection]), where each card reads as sentences and every choice is made from a menu. They work
/// on the flow's steps alone, so that they can be tested without a browser. Each returns a new list of steps
/// and leaves the list it was given unchanged.
///
/// The structural rules that `LearningFlow.check` enforces are applied here as limits on what is offered, so
/// that a person can't build a flow the server would refuse. Choose the elements is fixed as the first step
/// and takes no rules. Find what one record is, and Agree the target events, can each be added only once. The
/// fallback (`ADMIT`) is switched on or off rather than added as a step, and is reached only by a rule for
/// running out of tries. Rules go only to steps that exist. A step added with the same name as another is
/// given an id of its own, so that a rule can say which one it means; ids are otherwise never shown.
public final class ApproachFlow {

    private ApproachFlow() {
        // Static utility
    }

    /// Finds the default setup whose steps exactly match a flow's steps.
    ///
    /// The default setups are defined on the server and fetched, so they are passed in rather than read from
    /// [FlowExample]. Until they have been fetched the list is empty and every document appears to have its
    /// own setup, which is why a screen that shows the setup's name fetches them before it draws.
    ///
    /// @param examples The default setups, as fetched.
    /// @param steps    A flow's steps.
    /// @return The matching setup, or null if the steps match none.
    public static FlowExample example(final List<BuiltInFlow> examples, final List<FlowStep> steps) {
        for (final BuiltInFlow example : examples) {
            if (example.getSteps().equals(steps)) {
                return example.getExample();
            }
        }
        return null;
    }

    /// The steps of one default setup.
    ///
    /// @param examples The default setups, as fetched.
    /// @param example  The setup wanted.
    /// @return Its steps, or empty if the setups have not been fetched.
    public static List<FlowStep> steps(final List<BuiltInFlow> examples, final FlowExample example) {
        for (final BuiltInFlow flow : examples) {
            if (flow.getExample() == example) {
                return flow.getSteps();
            }
        }
        return List.of();
    }

    /// @param steps A flow's steps.
    /// @param index A step's index.
    /// @param by    Plus one for down, minus one for up.
    /// @return Whether the step can be moved one place that way. Choose the elements must stay first.
    public static boolean canMove(final List<FlowStep> steps, final int index, final int by) {
        final int to = index + by;
        return index >= 0 && index < steps.size()
               && steps.get(index).getKind() != QuestionKind.CHAIN
               && to >= 0 && to < steps.size()
               && steps.get(to).getKind() != QuestionKind.CHAIN;
    }

    /// @param step A step.
    /// @return Whether it can be copied. Only a step that writes a configuration may appear more than once.
    public static boolean canCopy(final FlowStep step) {
        return step.getKind() == QuestionKind.CONFIGURE;
    }

    /// @param step A step.
    /// @return Whether it can be deleted. Every flow must have Choose the elements, and the fallback is
    ///         switched off rather than deleted.
    public static boolean canDelete(final FlowStep step) {
        return step.getKind() != QuestionKind.CHAIN && step.getKind() != QuestionKind.ADMIT;
    }

    /// @param step A step.
    /// @return Whether it can have rules. Choose the elements has none, as it is asked again until its answer
    ///         runs, and neither does the fallback.
    public static boolean takesRules(final FlowStep step) {
        return step.getKind() != QuestionKind.CHAIN && step.getKind() != QuestionKind.ADMIT;
    }

    /// @param steps A flow's steps.
    /// @return Whether the flow has the fallback step, which writes marked Unknown events for records that
    ///         still can't be translated.
    public static boolean hasFallback(final List<FlowStep> steps) {
        return steps.stream().anyMatch(step -> step.getKind() == QuestionKind.ADMIT);
    }

    /// Switches the fallback step on or off. Switching it on adds it at the end, and gives the last step that
    /// writes the transform a rule to go to it on running out of tries, unless that step already has a rule
    /// for running out of tries. Switching it off removes it and every rule that went to it.
    ///
    /// @param steps A flow's steps.
    /// @param on    Whether the flow should have the fallback step.
    /// @return The new steps.
    public static List<FlowStep> withFallback(final List<FlowStep> steps, final boolean on) {
        if (on == hasFallback(steps)) {
            return new ArrayList<>(steps);
        }
        if (!on) {
            final Set<String> gone = new HashSet<>();
            final List<FlowStep> kept = new ArrayList<>();
            for (final FlowStep step : steps) {
                if (step.getKind() == QuestionKind.ADMIT) {
                    gone.add(step.effectiveId());
                } else {
                    kept.add(step);
                }
            }
            return withoutRulesTo(kept, gone);
        }
        final List<FlowStep> changed = new ArrayList<>(steps);
        final FlowStep admit = FlowStep.of(QuestionKind.ADMIT);
        for (int i = changed.size() - 1; i >= 0; i--) {
            final FlowStep step = changed.get(i);
            if (step.getKind() == QuestionKind.CONFIGURE && step.getRole() != ConfigureRole.PARSER) {
                if (step.transitionOnSpent() == null) {
                    final List<Transition> rules = new ArrayList<>(step.getTransitions());
                    rules.add(new Transition(null, admit.effectiveId()));
                    changed.set(i, withRules(step, rules));
                }
                break;
            }
        }
        changed.add(admit);
        return changed;
    }

    /// Lists the kinds of step a person may add, and why any of them can't be added now.
    ///
    /// @param steps A flow's steps.
    /// @return Every kind of step, each with the reason it can't be added, or no reason if it can.
    public static List<Addable> addable(final List<FlowStep> steps) {
        final List<Addable> addable = new ArrayList<>();
        for (final AddOption option : AddOption.values()) {
            String reason = null;
            if (option.kind != QuestionKind.CONFIGURE) {
                for (int i = 0; i < steps.size(); i++) {
                    if (steps.get(i).getKind() == option.kind) {
                        reason = "Already step " + (i + 1) + ". A flow has it once.";
                    }
                }
            }
            addable.add(new Addable(option, reason));
        }
        return addable;
    }

    /// Adds a step. If it would have the same id as another step, it is given an id of its own.
    ///
    /// @param steps  A flow's steps.
    /// @param at     The index to add it at. It is never added before Choose the elements.
    /// @param option The kind of step to add.
    /// @return The new steps.
    public static List<FlowStep> insert(final List<FlowStep> steps, final int at, final AddOption option) {
        final List<FlowStep> changed = new ArrayList<>(steps);
        final int where = Math.max(firstMovable(steps), Math.min(at, steps.size()));
        final FlowStep added = new FlowStep(null, option.kind, option.role, null, null, null, null, null);
        changed.add(where, uniquelyNamed(steps, added));
        return changed;
    }

    /// Copies a step, adding the copy immediately after it with an id of its own.
    ///
    /// @param steps A flow's steps.
    /// @param index The step's index.
    /// @return The new steps.
    public static List<FlowStep> copy(final List<FlowStep> steps, final int index) {
        final FlowStep original = steps.get(index);
        final FlowStep copy = new FlowStep(null, original.getKind(), original.getRole(), original.getWhen(),
                original.getCandidates(), original.getKinds(), original.getTransitions(),
                original.getElements());
        final List<FlowStep> changed = new ArrayList<>(steps);
        changed.add(index + 1, uniquelyNamed(steps, copy));
        return changed;
    }

    /// Deletes a step and every rule that went to it. The steps that had those rules go back to their usual
    /// behaviour.
    ///
    /// @param steps A flow's steps.
    /// @param index The step's index.
    /// @return The new steps.
    public static List<FlowStep> delete(final List<FlowStep> steps, final int index) {
        final List<FlowStep> changed = new ArrayList<>(steps);
        final FlowStep gone = changed.remove(index);
        return withoutRulesTo(changed, Set.of(gone.effectiveId()));
    }

    /// @param steps A flow's steps.
    /// @param index A step's index.
    /// @param by    Plus one for down, minus one for up.
    /// @return The new steps, with the step moved if it can be.
    public static List<FlowStep> move(final List<FlowStep> steps, final int index, final int by) {
        final List<FlowStep> changed = new ArrayList<>(steps);
        if (canMove(steps, index, by)) {
            final FlowStep step = changed.remove(index);
            changed.add(index + by, step);
        }
        return changed;
    }

    /// The conditions a rule on a step may fire on: the outcomes the step's checks can produce, plus any its
    /// rules already use (so that a condition a flow uses never disappears from its menu), in the order the
    /// menu offers them, followed by running out of tries.
    ///
    /// @param step The step.
    /// @return The conditions, with null standing for running out of tries.
    public static List<StepOutcome> conditions(final FlowStep step) {
        final Set<StepOutcome> raised = new LinkedHashSet<>();
        raised.add(StepOutcome.PASSED);
        raised.add(StepOutcome.REFUSED);
        raised.add(StepOutcome.COMPILE_FAILED);
        raised.add(StepOutcome.RUN_FAILED);
        for (final Check check : checksFor(step)) {
            raised.add(check.shortfall());
        }
        for (final Transition rule : step.getTransitions()) {
            if (rule.getOn() != null) {
                raised.add(rule.getOn());
            }
        }
        final List<StepOutcome> conditions = new ArrayList<>();
        for (final StepOutcome outcome : StepOutcome.values()) {
            if (raised.contains(outcome)) {
                conditions.add(outcome);
            }
        }
        conditions.add(null);
        return conditions;
    }

    /// The checks that judge a step, which are those its kind of step can measure. A check applies to every
    /// step it can measure; steps do not choose their own checks. Whether a check is Off, Advice or Gate is set
    /// for the whole Learning Flow, not per step.
    ///
    /// @param step The step.
    /// @return The checks, in the order a menu offers them; empty for a step no check measures.
    public static List<Check> checksFor(final FlowStep step) {
        final List<Check> records = List.of(Check.COVERAGE, Check.YIELD, Check.WHOLENESS);
        final List<Check> events = List.of(Check.CONFORMANCE, Check.QUALITY, Check.RULES,
                Check.PERMITTED);
        final List<Check> judges = new ArrayList<>();
        switch (step.getKind()) {
            case SPLIT:
                judges.addAll(records);
                break;
            case TARGET:
                judges.addAll(events);
                break;
            case CONFIGURE:
                if (step.getRole() != ConfigureRole.TRANSFORM) {
                    judges.addAll(records);
                    judges.add(Check.PRESERVATION);
                }
                if (step.getRole() != ConfigureRole.PARSER) {
                    judges.addAll(events);
                    judges.add(Check.FIDELITY);
                }
                break;
            default:
                break;
        }
        return judges;
    }

    /// Where a rule on a step may go: the other steps, finishing, pausing for a person, stopping learning, or
    /// the step's usual exit. The fallback is offered only for running out of tries, which is the only way it
    /// may be reached. If the rule currently goes to a step that doesn't exist, that is offered too, marked as
    /// not a step, so that the mistake is visible rather than silently lost.
    ///
    /// @param steps     A flow's steps.
    /// @param index     The step's index.
    /// @param condition What the rule fires on, or null for running out of tries.
    /// @param current   Where the rule goes now, or null for the step's usual exit.
    /// @return The destinations, in the order the menu offers them.
    public static List<Destination> destinations(final List<FlowStep> steps,
                                                 final int index,
                                                 final StepOutcome condition,
                                                 final String current) {
        final List<Destination> destinations = new ArrayList<>();
        boolean found = current == null || Transition.isReserved(current);
        for (int i = 0; i < steps.size(); i++) {
            final FlowStep step = steps.get(i);
            final String id = step.effectiveId();
            if (i == index) {
                continue;
            }
            if (step.getKind() == QuestionKind.ADMIT) {
                if (condition == null) {
                    destinations.add(new Destination(id, "use the fallback"));
                    found |= id.equals(current);
                }
                continue;
            }
            final String place = (i + 1) + " · " + FlowWords.stepName(steps, i);
            final String verb = i < index
                    ? "go back to "
                    : i == index + 1
                            ? "go on to "
                            : "skip to ";
            destinations.add(new Destination(id, verb + place));
            found |= id.equals(current);
        }
        if (!found) {
            destinations.add(new Destination(current, "go to '" + current + "', which isn't a step"));
        }
        destinations.add(new Destination(Transition.END, "finish"));
        destinations.add(new Destination(Transition.PARK, "pause for a person"));
        destinations.add(new Destination(Transition.GIVE_UP, "stop learning"));
        destinations.add(new Destination(null,
                "leave by its usual exit (" + FlowWords.usualExit(steps.get(index)) + ")"));
        return destinations;
    }

    /// Describes what a step does in the cases its rules don't cover, for the grey lines on its card.
    ///
    /// @param steps A flow's steps.
    /// @param index The step's index.
    /// @return The sentences, one per line.
    public static List<String> usual(final List<FlowStep> steps, final int index) {
        final FlowStep step = steps.get(index);
        final List<String> lines = new ArrayList<>();
        if (step.getKind() == QuestionKind.CHAIN) {
            lines.add("It is asked again until it names elements that run. Nothing can run until then.");
            return lines;
        }
        if (step.getKind() == QuestionKind.ADMIT) {
            return lines;
        }
        lines.add("Each shortfall asks again, with what fell short, until it runs out of tries.");
        if (step.transitionOn(StepOutcome.PASSED) == null) {
            lines.add(index == steps.size() - 1 || nextIsFallbackOnly(steps, index)
                    ? "If it passes, the run finishes."
                    : "If it passes, it goes on to the next step.");
        }
        if (step.transitionOnSpent() == null) {
            lines.add("If it runs out of tries, it will " + FlowWords.usualExit(step) + ".");
        }
        return lines;
    }

    // Whether every step after this one is the fallback. The fallback is entered only by a rule, never by
    // passing on to it, so a step followed only by the fallback finishes the run when it passes
    private static boolean nextIsFallbackOnly(final List<FlowStep> steps, final int index) {
        for (int i = index + 1; i < steps.size(); i++) {
            if (steps.get(i).getKind() != QuestionKind.ADMIT) {
                return false;
            }
        }
        return true;
    }

    /// Adds a rule to a step, as a starting point for the person to edit. It fires on the first shortfall the
    /// step's rules don't already use (or the first unused condition if there is no such shortfall), and goes
    /// back to the step before. If the step before is Choose the elements, or there is none, it pauses for a
    /// person instead.
    ///
    /// @param steps A flow's steps.
    /// @param index The step's index.
    /// @return The new steps, or a copy of the same steps if every condition is already in use.
    public static List<FlowStep> addRule(final List<FlowStep> steps, final int index) {
        final FlowStep step = steps.get(index);
        final List<StepOutcome> free = unusedConditions(step, null);
        if (free.isEmpty()) {
            return new ArrayList<>(steps);
        }
        // Prefer a shortfall, as the rules a flow most often needs are about what fell short
        StepOutcome chosen = free.get(0);
        for (final StepOutcome outcome : free) {
            if (outcome != null && outcome.getDisplayValue().endsWith("-short")) {
                chosen = outcome;
                break;
            }
        }
        final String goTo = index > 1
                ? steps.get(index - 1).effectiveId()
                : Transition.PARK;
        final List<Transition> rules = new ArrayList<>(step.getTransitions());
        rules.add(new Transition(chosen, goTo));
        return replace(steps, index, withRules(step, rules));
    }

    /// @param step     A step.
    /// @param allowing A rule whose own condition is offered even though it uses it (the rule being edited),
    ///                 or null.
    /// @return The conditions that none of the step's other rules use.
    public static List<StepOutcome> unusedConditions(final FlowStep step, final Transition allowing) {
        final List<StepOutcome> free = new ArrayList<>();
        for (final StepOutcome condition : conditions(step)) {
            final boolean used = step.getTransitions().stream()
                    .anyMatch(rule -> rule != allowing && Objects.equals(rule.getOn(), condition));
            if (!used) {
                free.add(condition);
            }
        }
        return free;
    }

    /// @param steps A flow's steps.
    /// @param index The step's index.
    /// @param rule  The rule's index among the step's rules.
    /// @param to    The rule's new value.
    /// @return The new steps.
    public static List<FlowStep> setRule(final List<FlowStep> steps,
                                         final int index,
                                         final int rule,
                                         final Transition to) {
        final FlowStep step = steps.get(index);
        final List<Transition> rules = new ArrayList<>(step.getTransitions());
        rules.set(rule, to);
        return replace(steps, index, withRules(step, rules));
    }

    /// @param steps A flow's steps.
    /// @param index The step's index.
    /// @param rule  The rule's index among the step's rules.
    /// @return The new steps, without the rule.
    public static List<FlowStep> removeRule(final List<FlowStep> steps, final int index, final int rule) {
        final FlowStep step = steps.get(index);
        final List<Transition> rules = new ArrayList<>(step.getTransitions());
        rules.remove(rule);
        return replace(steps, index, withRules(step, rules));
    }

    /// @param step A step.
    /// @param when The guard that decides which input the step is asked for.
    /// @return A copy of the step with that guard.
    public static FlowStep withWhen(final FlowStep step, final StepGuard when) {
        return new FlowStep(step.getId(), step.getKind(), step.getRole(), when, step.getCandidates(),
                step.getKinds(), step.getTransitions(), step.getElements());
    }

    /// @param step  A step.
    /// @param tries The number of tries, or null for the document's default.
    /// @return A copy of the step with that number of tries.
    public static FlowStep withTries(final FlowStep step, final Integer tries) {
        return new FlowStep(step.getId(), step.getKind(), step.getRole(), step.getWhen(), tries,
                step.getKinds(), step.getTransitions(), step.getElements());
    }

    /// @param step  A Target step.
    /// @param kinds How many kinds of record to ask about, or null for the default.
    /// @return A copy of the step with that number of kinds.
    public static FlowStep withKinds(final FlowStep step, final Integer kinds) {
        return new FlowStep(step.getId(), step.getKind(), step.getRole(), step.getWhen(), step.getCandidates(),
                kinds, step.getTransitions(), step.getElements());
    }

    /// @param step  The step.
    /// @param rules The rules it should have.
    /// @return A copy of the step with those rules.
    public static FlowStep withRules(final FlowStep step, final List<Transition> rules) {
        return new FlowStep(step.getId(), step.getKind(), step.getRole(), step.getWhen(), step.getCandidates(),
                step.getKinds(), rules, step.getElements());
    }

    /// Lists, as short tags for the card's heading, the settings a step has under its card's "Fine-tune this
    /// step" section, so that none of them is hidden while the section is collapsed: its guard, its tries, its
    /// kinds of record and the element types it chooses from. Each is included only if the step sets it to
    /// something other than the default.
    ///
    /// @param step A step.
    /// @return The tags, e.g. "only for XML input", "5 tries", "chooses from DSParser and XSLTFilter"; empty
    ///         if there are none.
    public static List<String> tuning(final FlowStep step) {
        final List<String> tags = new ArrayList<>();
        if (step.getWhen() != null && step.getWhen() != StepGuard.ALWAYS) {
            tags.add(PlainWords.guard(step.getWhen()));
        }
        if (step.getCandidates() != null) {
            tags.add(step.getCandidates() == 1
                    ? "1 try"
                    : step.getCandidates() + " tries");
        }
        if (step.getKind() == QuestionKind.TARGET && step.getKinds() != null) {
            tags.add(step.getKinds() == 1
                    ? "1 kind of record"
                    : step.getKinds() + " kinds of record");
        }
        if (step.getKind() == QuestionKind.CHAIN && !step.getElements().isEmpty()) {
            tags.add("chooses from " + PlainWords.list(step.getElements()));
        }
        return tags;
    }

    /// The element types to show ticked on a Choose the elements step: those it names that the AI can learn,
    /// or every one the AI can learn if it names none. A run ignores any element type the AI can't learn, so
    /// the ticked types are exactly those a run offers the AI.
    ///
    /// @param step A Choose the elements step.
    /// @return The element types ticked, in the order of [FlowStep#LEARNABLE_ELEMENTS].
    public static List<String> tickedElements(final FlowStep step) {
        return learnableOf(step.effectiveElements());
    }

    /// The element types a Choose the elements step names that the AI can't learn, which a run ignores.
    ///
    /// @param step A Choose the elements step.
    /// @return The element types, in the order the step names them; empty if there are none.
    public static List<String> unlearnableElements(final FlowStep step) {
        final List<String> unlearnable = new ArrayList<>();
        for (final String element : step.getElements()) {
            if (!FlowStep.LEARNABLE_ELEMENTS.contains(element)) {
                unlearnable.add(element);
            }
        }
        return unlearnable;
    }

    /// The element types a Choose the elements step should name, given the boxes ticked. If every box is
    /// ticked it names none, which means every element type the AI can learn.
    ///
    /// @param ticked The element types ticked.
    /// @return The element types to name, in the order of [FlowStep#LEARNABLE_ELEMENTS]; empty for all.
    public static List<String> elements(final List<String> ticked) {
        final List<String> named = learnableOf(ticked);
        return named.equals(FlowStep.LEARNABLE_ELEMENTS)
                ? List.of()
                : named;
    }

    // Returns the element types in `chosen` that the AI can learn, in the order of
    // FlowStep.LEARNABLE_ELEMENTS
    private static List<String> learnableOf(final List<String> chosen) {
        final List<String> elements = new ArrayList<>();
        for (final String element : FlowStep.LEARNABLE_ELEMENTS) {
            if (chosen.contains(element)) {
                elements.add(element);
            }
        }
        return elements;
    }

    /// @param steps A flow's steps.
    /// @param index The step's index.
    /// @param step  The step that replaces it.
    /// @return The new steps.
    public static List<FlowStep> replace(final List<FlowStep> steps, final int index, final FlowStep step) {
        final List<FlowStep> changed = new ArrayList<>(steps);
        changed.set(index, step);
        return changed;
    }

    private static int firstMovable(final List<FlowStep> steps) {
        return !steps.isEmpty() && steps.get(0).getKind() == QuestionKind.CHAIN
                ? 1
                : 0;
    }

    private static List<FlowStep> withoutRulesTo(final List<FlowStep> steps, final Set<String> gone) {
        final List<FlowStep> changed = new ArrayList<>();
        for (final FlowStep step : steps) {
            final List<Transition> kept = new ArrayList<>();
            for (final Transition rule : step.getTransitions()) {
                if (rule.getGoTo() == null || !gone.contains(rule.getGoTo())) {
                    kept.add(rule);
                }
            }
            changed.add(kept.size() == step.getTransitions().size()
                    ? step
                    : withRules(step, kept));
        }
        return changed;
    }

    // Returns the step, or, if another step already has its id, a copy with an id of its own: the id with a
    // number appended
    private static FlowStep uniquelyNamed(final List<FlowStep> steps, final FlowStep step) {
        final Set<String> taken = new HashSet<>();
        for (final FlowStep other : steps) {
            taken.add(other.effectiveId());
        }
        if (!taken.contains(step.effectiveId())) {
            return step;
        }
        int n = 2;
        while (taken.contains(step.effectiveId() + "-" + n)) {
            n++;
        }
        return new FlowStep(step.effectiveId() + "-" + n, step.getKind(), step.getRole(), step.getWhen(),
                step.getCandidates(), step.getKinds(), step.getTransitions(), step.getElements());
    }


    // --------------------------------------------------------------------------------


    /// A kind of step a person may add, with its name in the Add step menu.
    public enum AddOption implements HasDisplayValue {
        PARSER("Write the parser", QuestionKind.CONFIGURE, ConfigureRole.PARSER),
        TRANSFORM("Write the transform", QuestionKind.CONFIGURE, ConfigureRole.TRANSFORM),
        EVERY("Write every element", QuestionKind.CONFIGURE, null),
        SPLIT("Find what one record is", QuestionKind.SPLIT, null),
        TARGET("Agree the target events", QuestionKind.TARGET, null);

        private final String displayValue;
        private final QuestionKind kind;
        private final ConfigureRole role;

        AddOption(final String displayValue, final QuestionKind kind, final ConfigureRole role) {
            this.displayValue = displayValue;
            this.kind = kind;
            this.role = role;
        }

        @Override
        public String getDisplayValue() {
            return displayValue;
        }
    }


    // --------------------------------------------------------------------------------


    /// A kind of step, and the reason it can't be added now, if it can't.
    public static final class Addable {

        private final AddOption option;
        private final String reason;

        Addable(final AddOption option, final String reason) {
            this.option = option;
            this.reason = reason;
        }

        /// @return The kind of step.
        public AddOption getOption() {
            return option;
        }

        /// @return Why it can't be added, or null if it can.
        public String getReason() {
            return reason;
        }
    }


    // --------------------------------------------------------------------------------


    /// What a rule fires on, as its menu offers it: a step outcome, or running out of tries.
    public static final class Condition implements HasDisplayValue {

        private final StepOutcome outcome;

        /// @param outcome The outcome, or null for running out of tries.
        public Condition(final StepOutcome outcome) {
            this.outcome = outcome;
        }

        /// @return The outcome, or null for running out of tries.
        public StepOutcome getOutcome() {
            return outcome;
        }

        @Override
        public String getDisplayValue() {
            return FlowWords.condition(outcome);
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            return outcome == ((Condition) o).outcome;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(outcome);
        }

        @Override
        public String toString() {
            return getDisplayValue();
        }
    }


    // --------------------------------------------------------------------------------


    /// Where a rule goes, as its menu offers it: a step's id, `end`, `park`, `give-up`, or null for the
    /// step's usual exit. Two destinations are equal if they go to the same place; the displayed words differ
    /// depending on which step the rule is on.
    public static final class Destination implements HasDisplayValue {

        private final String goTo;
        private final String displayValue;

        Destination(final String goTo, final String displayValue) {
            this.goTo = goTo;
            this.displayValue = displayValue;
        }

        /// @return Where the rule goes, or null for the step's usual exit.
        public String getGoTo() {
            return goTo;
        }

        @Override
        public String getDisplayValue() {
            return displayValue;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            return Objects.equals(goTo, ((Destination) o).goTo);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(goTo);
        }

        @Override
        public String toString() {
            return displayValue;
        }
    }
}
