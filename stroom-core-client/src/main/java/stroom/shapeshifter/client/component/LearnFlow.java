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

import stroom.shapeshifter.shared.ConfigureRole;
import stroom.shapeshifter.shared.FlowProblem;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.StepGuard;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.Template;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// What the Learn tab ([LearnTabController]) says about a learning flow and its steps, worked out from
/// the flow alone, so that it can be tested without a browser: a step's title, what it does by
/// default, the texts it is asked with, the flow read from its text, and the check of a step's limits.
public final class LearnFlow {

    /// The texts that belong to no step: what every question is asked under, and what the questions
    /// include by variable (A86).
    public static final List<Template> SHARED_TEXTS = List.of(Template.SYSTEM, Template.SPLIT_RULES,
            Template.EXTRACTION_RULES, Template.TRANSFORMATION_RULES);

    private static final String LIMIT_REASON = "Must be a whole number of 1 or more, or empty for the kind's "
                                               + "own default";

    private LearnFlow() {
        // Static utility
    }

    /// Checks the text of one of a step's limits (its candidates or kinds): empty for the kind's own
    /// default, or a whole number of one or more.
    ///
    /// @param text The text typed.
    /// @return Why it can't be kept, or null if it can.
    public static String limitProblem(final String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        return parseLimit(text) == null
                ? LIMIT_REASON
                : null;
    }

    /// Reads one of a step's limits.
    ///
    /// @param text The text typed.
    /// @return The limit, or null where the text is empty (the kind's own default) or is not a whole
    ///         number of one or more.
    public static Integer parseLimit(final String text) {
        if (text == null) {
            return null;
        }
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            final int value = Integer.parseInt(trimmed);
            return value >= 1
                    ? value
                    : null;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /// @param value A step's limit, or null for the kind's own default.
    /// @return The limit as text, empty for null.
    public static String limitText(final Integer value) {
        return value == null
                ? ""
                : String.valueOf(value);
    }

    /// Reads the element types a Chain step names, one per line.
    ///
    /// @param text The lines.
    /// @return The element types, trimmed, without blank lines; empty for the default set.
    public static List<String> elements(final String text) {
        final List<String> named = new ArrayList<>();
        if (text != null) {
            for (final String line : text.split("\n")) {
                if (!line.trim().isEmpty()) {
                    named.add(line.trim());
                }
            }
        }
        return named;
    }

    /// @param step A step.
    /// @return Its title in the side panel: its kind, or the role a Configure step configures.
    public static String title(final FlowStep step) {
        return step.getKind() == QuestionKind.CONFIGURE && step.getRole() != null
                ? "Configure " + step.getRole().getDisplayValue()
                : step.getKind().getDisplayValue();
    }

    /// @param number The step's place, counted from one.
    /// @param of     How many steps the flow has.
    /// @param step   The step.
    /// @return The heading of the side panel showing the step.
    public static String heading(final int number, final int of, final FlowStep step) {
        return "Step " + number + " of " + of + "  ·  " + title(step);
    }

    /// What the step does where the flow says nothing (A84), said beneath its transitions.
    ///
    /// @param step A step.
    /// @return What it does by default, and that it is judged by its kind's own checks where none is
    ///         ticked.
    public static String defaults(final FlowStep step) {
        final List<String> parts = new ArrayList<>();
        if (step.transitionOn(StepOutcome.PASSED) == null) {
            parts.add("passed goes to the next step");
        }
        if (step.getCandidates() == null || step.getCandidates() != 1) {
            parts.add("a shortfall re-asks with the next candidate");
        }
        if (step.transitionOnSpent() == null) {
            parts.add("spent " + (step.getRole() == ConfigureRole.PARSER
                    ? "gives the learner up, the parser's default"
                    : "parks for a person"));
        }
        final String checks = step.getChecks().isEmpty()
                ? " No checks are ticked, so the step is judged by its kind's own."
                : "";
        return (parts.isEmpty()
                ? "Every outcome has a transition."
                : "By default: " + String.join("; ", parts) + ".") + checks;
    }

    /// The texts a step is asked with (A85): one for most kinds; for a split, the variant its guard
    /// selects, or all three where the guard is `always` and the stream decides.
    ///
    /// @param step A step.
    /// @return Its texts.
    public static List<Template> textsOf(final FlowStep step) {
        switch (step.getKind()) {
            case CHAIN:
                return List.of(Template.CHAIN);
            case SPLIT:
                if (step.getWhen() == StepGuard.XML) {
                    return List.of(Template.SPLIT_XML);
                }
                if (step.getWhen() == StepGuard.JSON) {
                    return List.of(Template.SPLIT_JSON);
                }
                if (step.getWhen() == StepGuard.TEXT) {
                    return List.of(Template.SPLIT);
                }
                return List.of(Template.SPLIT, Template.SPLIT_XML, Template.SPLIT_JSON);
            case TARGET:
                return List.of(Template.TARGET);
            case ADMIT:
                return List.of(Template.ADMISSION);
            default:
                return List.of(Template.CONFIGURATION);
        }
    }

    /// The built-ins a text may be loaded from: those whose variables it allows, its own first.
    ///
    /// @param slot The text being loaded into.
    /// @return The built-ins it may be loaded from.
    public static List<Template> suitable(final Template slot) {
        final Set<String> allowed = new HashSet<>(Arrays.asList(slot.getVariables()));
        final List<Template> fits = new ArrayList<>();
        fits.add(slot);
        for (final Template other : Template.values()) {
            if (other != slot && allowed.containsAll(Arrays.asList(other.getVariables()))) {
                fits.add(other);
            }
        }
        return fits;
    }

    /// @param template A text.
    /// @return The tooltip of its words: the variables it may use.
    public static String variablesTitle(final Template template) {
        final List<String> names = new ArrayList<>();
        for (final String variable : template.getVariables()) {
            names.add("${" + variable + "}");
        }
        return "Variables: " + (names.isEmpty()
                ? "none"
                : String.join(", ", names));
    }

    /// Where a text's words come from, said above them. Text is per kind of question (A85): the status
    /// says so where an edit reaches more than this step.
    ///
    /// @param template            The text.
    /// @param own                 Whether the document has its own words for it.
    /// @param fetched             Whether its built-in words have been fetched.
    /// @param builtInVersion      The version of the built-ins the node serves, or null if not known.
    /// @param savedAgainstVersion The built-in version the document was last saved against, or null.
    /// @return The status.
    public static String textStatus(final Template template,
                                    final boolean own,
                                    final boolean fetched,
                                    final Integer builtInVersion,
                                    final Integer savedAgainstVersion) {
        final StringBuilder status = new StringBuilder(own
                ? "This document's own text"
                : fetched
                        ? "The built-in text"
                        : "The built-in text has not been fetched");
        if (builtInVersion != null) {
            status.append(", built-in version ").append(builtInVersion);
            if (savedAgainstVersion != null && !savedAgainstVersion.equals(builtInVersion)) {
                status.append(", this document last saved against ").append(savedAgainstVersion);
            }
        }
        if (template == Template.CONFIGURATION) {
            status.append(". Asked at every Configure step, parser and transform alike");
        }
        status.append('.');
        return status.toString();
    }

    /// Reads the flow from its text (A87), one step a line; blank lines are skipped.
    ///
    /// @param text The flow as text.
    /// @return The steps.
    /// @throws IllegalArgumentException Where a line doesn't parse: `Line n: ` and why.
    public static List<FlowStep> parse(final String text) {
        final List<FlowStep> parsed = new ArrayList<>();
        int number = 0;
        for (final String line : (text == null
                ? ""
                : text).split("\n")) {
            number++;
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                parsed.add(FlowStep.parse(line));
            } catch (final RuntimeException e) {
                throw new IllegalArgumentException("Line " + number + ": " + e.getMessage(), e);
            }
        }
        return parsed;
    }

    /// @param steps The steps.
    /// @return The flow as text, one step a line.
    public static String format(final List<FlowStep> steps) {
        final List<String> lines = new ArrayList<>();
        for (final FlowStep step : steps) {
            lines.add(step.format());
        }
        return String.join("\n", lines);
    }

    /// @param problems What is wrong with the flow.
    /// @param index    A step's index.
    /// @return What is wrong with that step, or null for nothing.
    public static String problemsOf(final List<FlowProblem> problems, final int index) {
        final List<String> mine = new ArrayList<>();
        for (final FlowProblem problem : problems) {
            if (problem.getStep() != null && problem.getStep() == index) {
                mine.add(problem.getMessage());
            }
        }
        return mine.isEmpty()
                ? null
                : String.join("; ", mine);
    }

    /// @param problems What is wrong with the flow.
    /// @return What is wrong with the flow as one line, or null for nothing.
    public static String flowProblem(final List<FlowProblem> problems) {
        return problems.isEmpty()
                ? null
                : String.join("; ", FlowProblem.messages(problems));
    }

    /// The steps whose transitions go to one step: what would be left pointing at nothing were it
    /// removed.
    ///
    /// @param steps The flow's steps.
    /// @param index The step's index.
    /// @return The ids of the other steps that go to it.
    public static List<String> pointingAt(final List<FlowStep> steps, final int index) {
        final FlowStep step = steps.get(index);
        final String id = step.effectiveId();
        final List<String> pointing = new ArrayList<>();
        for (final FlowStep other : steps) {
            if (other != step && other.getTransitions().stream()
                    .anyMatch(transition -> id.equals(transition.getGoTo()))) {
                pointing.add(other.effectiveId());
            }
        }
        return pointing;
    }

    /// Without its id: two steps of one name is a flow whose transitions cannot say which they mean,
    /// and the copy is the one that should be renamed.
    ///
    /// @param original The step to copy.
    /// @return A copy of it with no id.
    public static FlowStep copyWithoutId(final FlowStep original) {
        return new FlowStep(null, original.getKind(), original.getRole(), original.getWhen(),
                original.getCandidates(), original.getKinds(), original.getChecks(), original.getTransitions(),
                original.getElements());
    }

    /// @param steps  The flow's steps.
    /// @param index  The step being edited, which may keep its own name.
    /// @param edited The step as edited.
    /// @return Why the edited step's name can't be kept, or null if it can: two of one name leaves a
    ///         transition unable to say which it means.
    public static String nameClash(final List<FlowStep> steps, final int index, final FlowStep edited) {
        for (int i = 0; i < steps.size(); i++) {
            if (i != index && steps.get(i).effectiveId().equals(edited.effectiveId())) {
                return "Another step is already called '" + edited.effectiveId()
                       + "'. Two of one name leaves a transition unable to say which it means.";
            }
        }
        return null;
    }
}
