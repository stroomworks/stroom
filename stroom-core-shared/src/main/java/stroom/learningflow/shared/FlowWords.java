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

import java.util.List;

/// The wording a person reads for a learning flow's steps and rules (transitions), e.g. "Write the parser" and
/// "if the records don't read all the input, go back to step 1 (Choose the elements)". It is shared between
/// client and server so that problems with a flow ([LearningFlow#check()]) and the reasons a learning session
/// stopped use the same words as the Setup tab, never a step's field names or its kind's enum name.
public final class FlowWords {

    private FlowWords() {
        // Static utility
    }

    /// @param kind The kind of question.
    /// @param role The role of a Configure step, or null.
    /// @return The name of a step of that kind and role, e.g. "Find what one record is".
    public static String stepName(final QuestionKind kind, final ConfigureRole role) {
        if (kind == null) {
            return "Step";
        }
        switch (kind) {
            case CHAIN:
                return "Choose the elements";
            case SPLIT:
                return "Find what one record is";
            case TARGET:
                return "Agree the target events";
            case ADMIT:
                return "Fallback: marked Unknowns";
            default:
                if (role == ConfigureRole.PARSER) {
                    return "Write the parser";
                }
                if (role == ConfigureRole.TRANSFORM) {
                    return "Write the transform";
                }
                return "Write every element";
        }
    }

    /// A step's name, e.g. "Write the parser". A second step of the same kind and role is "… again", so
    /// that two of them can be told apart.
    ///
    /// @param steps The flow's steps.
    /// @param index The step's index.
    /// @return Its name.
    public static String stepName(final List<FlowStep> steps, final int index) {
        final FlowStep step = steps.get(index);
        final String name = stepName(step.getKind(), step.getRole());
        for (int i = 0; i < index; i++) {
            final FlowStep earlier = steps.get(i);
            if (earlier.getKind() == step.getKind() && earlier.getRole() == step.getRole()
                && step.getKind() != QuestionKind.ADMIT) {
                return name + " again";
            }
        }
        return name;
    }

    /// A step as a sentence names it, by its number and name.
    ///
    /// @param steps The flow's steps.
    /// @param index The step's index.
    /// @return E.g. "step 2 (Write the parser)".
    public static String step(final List<FlowStep> steps, final int index) {
        return "step " + (index + 1) + " (" + stepName(steps, index) + ")";
    }

    /// The condition of a rule on a step, e.g. "the records don't read all the input", as "If … then …"
    /// reads it.
    ///
    /// @param outcome The outcome the rule fires on, or null for the step running out of tries.
    /// @return The condition.
    public static String condition(final StepOutcome outcome) {
        if (outcome == null) {
            return "it runs out of tries";
        }
        switch (outcome) {
            case PASSED:
                return "it passes";
            case REFUSED:
                return "the answer isn't in the reply format";
            case COMPILE_FAILED:
                return "the answer doesn't compile";
            case RUN_FAILED:
                return "the answer errors when run on the data";
            case COVERAGE_SHORT:
                return "the records don't read all the input";
            case COMPLETENESS_SHORT:
                return "some records became neither an event nor a declared skip";
            case YIELD_SHORT:
                return "it finds too few or too many records";
            case WHOLENESS_SHORT:
                return "the records are cut into pieces";
            case CONFORMANCE_SHORT:
                return "the events aren't valid against the event schema";
            case QUALITY_SHORT:
                return "the events are empty or meaningless";
            case RULES_SHORT:
                return "the events break a business rule";
            case PERMITTED_SHORT:
                return "the translation uses something it may not";
            case TIMES_SHORT:
                return "the events are dated far from the stream";
            case LITERALS_SHORT:
                return "this stream's own values are written into the translation";
            case PRESERVATION_SHORT:
                return "the records lose what the targets need";
            case FIDELITY_SHORT:
                return "the events don't match the agreed targets";
            default:
                return outcome.getDisplayValue();
        }
    }

    /// @param step A step.
    /// @return What it does when it runs out of tries and no rule says otherwise. A parser step stops
    ///         learning, because a stream that cannot be parsed is the one failure nothing can serve; any
    ///         other step pauses for a person. A step that writes every element does either, depending on
    ///         which element ran out.
    public static String usualExit(final FlowStep step) {
        if (step.getRole() == ConfigureRole.PARSER) {
            return "stop learning";
        }
        if (step.getKind() == QuestionKind.CONFIGURE && step.getRole() == null) {
            return "stop learning if the parser can't be written, or else pause for a person";
        }
        return "pause for a person";
    }

    /// Where a rule that leaves the flow takes it, e.g. "pause for a person", or "leave by its usual exit
    /// (stop learning)" where the rule names no exit of its own.
    ///
    /// @param step       The step the rule is on.
    /// @param transition The rule; one that [Transition#exits()].
    /// @return Where it goes.
    public static String exit(final FlowStep step, final Transition transition) {
        if (Transition.PARK.equals(transition.getGoTo())) {
            return "pause for a person";
        }
        if (Transition.GIVE_UP.equals(transition.getGoTo())) {
            return "stop learning";
        }
        return "leave by its usual exit (" + usualExit(step) + ")";
    }

    /// Where a rule on a step goes, e.g. "go back to step 1 (Choose the elements)", "use the fallback",
    /// "finish" or "pause for a person". A rule naming a step that doesn't exist is described as such, so
    /// that the mistake can be seen.
    ///
    /// @param steps      The flow's steps.
    /// @param index      The index of the step the rule is on.
    /// @param transition The rule.
    /// @return Where it goes.
    public static String destination(final List<FlowStep> steps, final int index, final Transition transition) {
        if (transition.exits()) {
            return exit(steps.get(index), transition);
        }
        final String goTo = transition.getGoTo();
        if (Transition.END.equals(goTo)) {
            return "finish";
        }
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).effectiveId().equals(goTo)) {
                if (steps.get(i).getKind() == QuestionKind.ADMIT) {
                    return "use the fallback";
                }
                if (i == index) {
                    return "start " + step(steps, i) + " again";
                }
                final String verb = i < index
                        ? "go back to "
                        : i == index + 1
                                ? "go on to "
                                : "skip to ";
                return verb + step(steps, i);
            }
        }
        return "go to '" + goTo + "', which isn't a step";
    }

    /// @param text Words to start a sentence with.
    /// @return The words with a capital first letter, e.g. "Step 2 (Write the parser)".
    public static String capitalise(final String text) {
        return text == null || text.isEmpty()
                ? text
                : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /// A rule on a step as a sentence reads it.
    ///
    /// @param steps      The flow's steps.
    /// @param index      The index of the step the rule is on.
    /// @param transition The rule.
    /// @return E.g. "if the records don't read all the input, go back to step 1 (Choose the elements)".
    public static String rule(final List<FlowStep> steps, final int index, final Transition transition) {
        return "if " + condition(transition.getOn()) + ", " + destination(steps, index, transition);
    }
}
