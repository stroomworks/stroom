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

/// What the approach on the Setup tab ([ApproachSectionController]) needs to know of a learning flow's
/// text and its steps, worked out from the flow alone, so that it can be tested without a browser: the
/// texts a step is asked with and where their words come from, a step's problems, and the check of a
/// step's limits.
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

}
