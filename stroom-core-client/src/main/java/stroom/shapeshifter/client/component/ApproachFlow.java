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

import stroom.docref.HasDisplayValue;
import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.ConfigureRole;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.FlowWords;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.StepGuard;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.Transition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// The learning flow as the Setup tab's approach ([ApproachSection]) edits it: a list of step cards, each
/// read as sentences, every choice made from a menu. These are the operations behind the cards, on the
/// flow's steps alone, so that they can be tested without a browser. Each returns new steps and leaves the
/// ones given alone.
///
/// The structure rules of `LearningFlow.check` (A88) become limits on what is offered: Choose the
/// elements is pinned first and takes no rules; Find records and Agree targets can be added once each;
/// the fallback (`ADMIT`) is a switch rather than a step one adds, and is reached only by a rule on
/// running out of tries; rules point only at steps that exist; and a step added beside another of its
/// name is given an id of its own, so that a rule can say which it means. Ids are otherwise never shown.
public final class ApproachFlow {

    private ApproachFlow() {
        // Static utility
    }

    /// @param steps A flow's steps.
    /// @return The example flow they are exactly, or null where they are the document's own.
    public static FlowExample example(final List<FlowStep> steps) {
        for (final FlowExample example : FlowExample.values()) {
            if (example.steps().equals(steps)) {
                return example;
            }
        }
        return null;
    }

    /// @param steps A flow's steps.
    /// @param index A step's index.
    /// @param by    Plus one for down, minus one for up.
    /// @return Whether the step can be moved by one place that way: Choose the elements stays first.
    public static boolean canMove(final List<FlowStep> steps, final int index, final int by) {
        final int to = index + by;
        return index >= 0 && index < steps.size()
               && steps.get(index).getKind() != QuestionKind.CHAIN
               && to >= 0 && to < steps.size()
               && steps.get(to).getKind() != QuestionKind.CHAIN;
    }

    /// @param step A step.
    /// @return Whether it can be copied: only a step that writes a configuration may appear twice.
    public static boolean canCopy(final FlowStep step) {
        return step.getKind() == QuestionKind.CONFIGURE;
    }

    /// @param step A step.
    /// @return Whether it can be deleted: every flow chooses its elements, and the fallback has a switch.
    public static boolean canDelete(final FlowStep step) {
        return step.getKind() != QuestionKind.CHAIN && step.getKind() != QuestionKind.ADMIT;
    }

    /// @param step A step.
    /// @return Whether it takes rules: the chain takes none (it is re-asked until it runs), nor the fallback.
    public static boolean takesRules(final FlowStep step) {
        return step.getKind() != QuestionKind.CHAIN && step.getKind() != QuestionKind.ADMIT;
    }

    /// @param steps A flow's steps.
    /// @return Whether the flow has the fallback, the exhaustion step.
    public static boolean hasFallback(final List<FlowStep> steps) {
        return steps.stream().anyMatch(step -> step.getKind() == QuestionKind.ADMIT);
    }

    /// Switches the fallback on or off. On, it is added at the end, and the last step that writes the
    /// transform goes to it when it runs out of tries, unless it already says where to go then. Off, it
    /// goes, and so does every rule that went to it.
    ///
    /// @param steps A flow's steps.
    /// @param on    Whether to have it.
    /// @return The steps.
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

    /// What can be added, and why what can't be isn't.
    ///
    /// @param steps A flow's steps.
    /// @return Each kind of step that may be added, with the reason it can't be where it can't.
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

    /// Adds a step. One that would share its name with another is given an id of its own.
    ///
    /// @param steps  A flow's steps.
    /// @param at     Where to add it; never before Choose the elements.
    /// @param option What to add.
    /// @return The steps.
    public static List<FlowStep> insert(final List<FlowStep> steps, final int at, final AddOption option) {
        final List<FlowStep> changed = new ArrayList<>(steps);
        final int where = Math.max(firstMovable(steps), Math.min(at, steps.size()));
        final FlowStep added = new FlowStep(null, option.kind, option.role, null, null, null, null, null);
        changed.add(where, uniquelyNamed(steps, added));
        return changed;
    }

    /// Copies a step after itself, under a name of its own.
    ///
    /// @param steps A flow's steps.
    /// @param index The step's index.
    /// @return The steps.
    public static List<FlowStep> copy(final List<FlowStep> steps, final int index) {
        final FlowStep original = steps.get(index);
        final FlowStep copy = new FlowStep(null, original.getKind(), original.getRole(), original.getWhen(),
                original.getCandidates(), original.getKinds(), original.getChecks(), original.getTransitions(),
                original.getElements());
        final List<FlowStep> changed = new ArrayList<>(steps);
        changed.add(index + 1, uniquelyNamed(steps, copy));
        return changed;
    }

    /// Deletes a step, and every rule that went to it, which goes back to the step's usual behaviour.
    ///
    /// @param steps A flow's steps.
    /// @param index The step's index.
    /// @return The steps.
    public static List<FlowStep> delete(final List<FlowStep> steps, final int index) {
        final List<FlowStep> changed = new ArrayList<>(steps);
        final FlowStep gone = changed.remove(index);
        return withoutRulesTo(changed, Set.of(gone.effectiveId()));
    }

    /// @param steps A flow's steps.
    /// @param index A step's index.
    /// @param by    Plus one for down, minus one for up.
    /// @return The steps, the step moved where it can be.
    public static List<FlowStep> move(final List<FlowStep> steps, final int index, final int by) {
        final List<FlowStep> changed = new ArrayList<>(steps);
        if (canMove(steps, index, by)) {
            final FlowStep step = changed.remove(index);
            changed.add(index + by, step);
        }
        return changed;
    }

    /// The conditions a rule on a step may fire on: what the step's checks can raise, in the order the
    /// menu offers them, then running out of tries; and any its rules already name, so that nothing a
    /// flow says disappears from its menu.
    ///
    /// @param step The step.
    /// @return The conditions, null standing for running out of tries.
    public static List<StepOutcome> conditions(final FlowStep step) {
        final Set<StepOutcome> raised = new LinkedHashSet<>();
        raised.add(StepOutcome.PASSED);
        raised.add(StepOutcome.REFUSED);
        raised.add(StepOutcome.COMPILE_FAILED);
        raised.add(StepOutcome.RUN_FAILED);
        for (final Check check : judges(step)) {
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

    // The checks a step can be judged by: its own where it names them, else what its kind can raise.
    // Checks with no scorer are offered only where named.
    private static List<Check> judges(final FlowStep step) {
        if (!step.getChecks().isEmpty()) {
            return step.getChecks();
        }
        final List<Check> records = List.of(Check.COVERAGE, Check.YIELD, Check.WHOLENESS);
        final List<Check> events = List.of(Check.CONFORMANCE, Check.QUALITY, Check.RULES);
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

    /// Where a rule on a step may go: the other steps (the fallback only on running out of tries, which is
    /// the only way it may be reached), finishing, pausing for a person, stopping, or the step's usual exit;
    /// and a step the rule names that isn't there, so that it is seen to be wrong rather than lost.
    ///
    /// @param steps     A flow's steps.
    /// @param index     The step's index.
    /// @param condition What the rule fires on, null for running out of tries.
    /// @param current   Where the rule goes now, null for its usual exit.
    /// @return The places, in the order the menu offers them.
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
            final String place = (i + 1) + " · " + PlainWords.stepName(steps, i);
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
        destinations.add(new Destination(null, "leave by its usual exit (" + usualExit(steps.get(index)) + ")"));
        return destinations;
    }

    /// @param step A step.
    /// @return What it does when it runs out of tries and no rule says otherwise ([FlowWords#usualExit]).
    public static String usualExit(final FlowStep step) {
        return FlowWords.usualExit(step);
    }

    /// What a step does where its rules say nothing, as the grey lines of its card.
    ///
    /// @param steps A flow's steps.
    /// @param index The step's index.
    /// @return The sentences, each a line.
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
            lines.add("If it runs out of tries, it will " + usualExit(step) + ".");
        }
        return lines;
    }

    // The fallback is entered only by a rule, never by falling through to it
    private static boolean nextIsFallbackOnly(final List<FlowStep> steps, final int index) {
        for (int i = index + 1; i < steps.size(); i++) {
            if (steps.get(i).getKind() != QuestionKind.ADMIT) {
                return false;
            }
        }
        return true;
    }

    /// Adds a rule to a step: the first condition its rules don't use, going back to the step before it, or
    /// pausing for a person where there is none to go back to.
    ///
    /// @param steps A flow's steps.
    /// @param index The step's index.
    /// @return The steps, or the same steps where every condition is in use.
    public static List<FlowStep> addRule(final List<FlowStep> steps, final int index) {
        final FlowStep step = steps.get(index);
        final List<StepOutcome> free = unusedConditions(step, null);
        if (free.isEmpty()) {
            return new ArrayList<>(steps);
        }
        // A shortfall first, as the rules a flow most wants are about what fell short
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
    /// @param allowing A condition to offer even though a rule uses it (the rule being edited's own).
    /// @return The conditions none of its other rules use.
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
    /// @param rule  The rule's index among the step's.
    /// @param to    What it becomes.
    /// @return The steps.
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
    /// @param rule  The rule's index among the step's.
    /// @return The steps, the rule gone.
    public static List<FlowStep> removeRule(final List<FlowStep> steps, final int index, final int rule) {
        final FlowStep step = steps.get(index);
        final List<Transition> rules = new ArrayList<>(step.getTransitions());
        rules.remove(rule);
        return replace(steps, index, withRules(step, rules));
    }

    /// @param step A step.
    /// @param when When it is asked.
    /// @return The step asked then.
    public static FlowStep withWhen(final FlowStep step, final StepGuard when) {
        return new FlowStep(step.getId(), step.getKind(), step.getRole(), when, step.getCandidates(),
                step.getKinds(), step.getChecks(), step.getTransitions(), step.getElements());
    }

    /// @param step  A step.
    /// @param tries Its tries, or null for the document's default.
    /// @return The step with those tries.
    public static FlowStep withTries(final FlowStep step, final Integer tries) {
        return new FlowStep(step.getId(), step.getKind(), step.getRole(), step.getWhen(), tries,
                step.getKinds(), step.getChecks(), step.getTransitions(), step.getElements());
    }

    /// @param step  A Target step.
    /// @param kinds How many kinds of record, or null for the default.
    /// @return The step asking about that many.
    public static FlowStep withKinds(final FlowStep step, final Integer kinds) {
        return new FlowStep(step.getId(), step.getKind(), step.getRole(), step.getWhen(), step.getCandidates(),
                kinds, step.getChecks(), step.getTransitions(), step.getElements());
    }

    /// @param step   A step.
    /// @param checks Its own checks, or empty for its kind's.
    /// @return The step judged by them.
    public static FlowStep withChecks(final FlowStep step, final List<Check> checks) {
        return new FlowStep(step.getId(), step.getKind(), step.getRole(), step.getWhen(), step.getCandidates(),
                step.getKinds(), checks, step.getTransitions(), step.getElements());
    }

    /// @param step The step.
    /// @param rules Its rules.
    /// @return The step with them.
    public static FlowStep withRules(final FlowStep step, final List<Transition> rules) {
        return new FlowStep(step.getId(), step.getKind(), step.getRole(), step.getWhen(), step.getCandidates(),
                step.getKinds(), step.getChecks(), rules, step.getElements());
    }

    /// What a step has of its own that its card's "Fine-tune this step" holds, as short tags its heading
    /// shows, so that nothing set there is out of sight: when it is asked, its tries, its kinds of record, its
    /// own checks and the element types it chooses from, each only where the step says other than the usual.
    ///
    /// @param step A step.
    /// @return Its tags, e.g. "only for XML input", "5 tries", "own checks", "chooses from DSParser and
    ///         XSLTFilter"; empty where it has none.
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
        if (step.getChecks() != null && !step.getChecks().isEmpty()) {
            tags.add("own checks");
        }
        if (step.getKind() == QuestionKind.CHAIN && !step.getElements().isEmpty()) {
            tags.add("chooses from " + PlainWords.list(step.getElements()));
        }
        return tags;
    }

    /// The element types ticked on a Choose the elements step: those it names that the AI can learn, or every
    /// one it can learn where it names none. A run passes over any it can't, so what is ticked is what a run
    /// offers the AI.
    ///
    /// @param step A Choose the elements step.
    /// @return The element types ticked, in the order of [FlowStep#LEARNABLE_ELEMENTS].
    public static List<String> tickedElements(final FlowStep step) {
        return learnableOf(step.effectiveElements());
    }

    /// The element types a Choose the elements step names that the AI can't learn: a run passes them over.
    ///
    /// @param step A Choose the elements step.
    /// @return The element types, in the order the step names them; empty where there are none.
    public static List<String> unlearnableElements(final FlowStep step) {
        final List<String> unlearnable = new ArrayList<>();
        for (final String element : step.getElements()) {
            if (!FlowStep.LEARNABLE_ELEMENTS.contains(element)) {
                unlearnable.add(element);
            }
        }
        return unlearnable;
    }

    /// The element types a Choose the elements step names, from the boxes ticked: none, which is every one
    /// the AI can learn, where all are ticked.
    ///
    /// @param ticked The element types ticked.
    /// @return The element types to name, in the order of [FlowStep#LEARNABLE_ELEMENTS]; empty for all.
    public static List<String> elements(final List<String> ticked) {
        final List<String> named = learnableOf(ticked);
        return named.equals(FlowStep.LEARNABLE_ELEMENTS)
                ? List.of()
                : named;
    }

    // The element types of `chosen` that the AI can learn, in the order of FlowStep.LEARNABLE_ELEMENTS
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
    /// @param step  What it becomes.
    /// @return The steps.
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

    // A step whose name another already has is given an id of its own: its name, numbered
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
                step.getCandidates(), step.getKinds(), step.getChecks(), step.getTransitions(), step.getElements());
    }


    // --------------------------------------------------------------------------------


    /// A kind of step a person may add.
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


    /// A kind of step, and why it can't be added where it can't.
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

        /// @return Why it can't be added, or null where it can.
        public String getReason() {
            return reason;
        }
    }


    // --------------------------------------------------------------------------------


    /// What a rule fires on, as its menu offers it: an outcome, or running out of tries.
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
            return PlainWords.condition(outcome);
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
    /// step's usual exit. Equal by where it goes; the words depend on which step the rule is on.
    public static final class Destination implements HasDisplayValue {

        private final String goTo;
        private final String displayValue;

        Destination(final String goTo, final String displayValue) {
            this.goTo = goTo;
            this.displayValue = displayValue;
        }

        /// @return Where it goes, or null for the step's usual exit.
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
