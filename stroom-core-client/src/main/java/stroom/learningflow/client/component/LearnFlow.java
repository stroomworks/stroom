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

import stroom.learningflow.shared.ConfigureRole;
import stroom.learningflow.shared.FlowProblem;
import stroom.learningflow.shared.FlowProblem.Severity;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StepGuard;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.Template;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// Helpers for the Learning steps section of the Setup tab ([ApproachSectionController]) that depend only on
/// the flow, so that they can be tested without a browser. They work out which question texts a step is asked
/// with and where their words come from, which built-in texts may be loaded into a text, what is wrong with a
/// step, and whether a step's limits are valid.
public final class LearnFlow {

    /// The texts that belong to no particular step: the system text every question is sent with, and the rules
    /// texts that questions include through variables. They are shown when the user asks for the text every
    /// step shares.
    public static final List<Template> SHARED_TEXTS = List.of(Template.SYSTEM, Template.SPLIT_RULES,
            Template.EXTRACTION_RULES, Template.TRANSFORMATION_RULES);

    private static final String LIMIT_REASON = "Must be a whole number of 1 or more, or empty for the kind's "
                                               + "own default";

    private LearnFlow() {
        // Static utility
    }

    /// Checks the text typed for one of a step's limits (its tries or kinds). It must be empty, for the
    /// default, or a whole number of one or more.
    ///
    /// @param text The text typed.
    /// @return Why the text is invalid, or null if it is valid.
    public static String limitProblem(final String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        return parseLimit(text) == null
                ? LIMIT_REASON
                : null;
    }

    /// Parses one of a step's limits.
    ///
    /// @param text The text typed.
    /// @return The limit, or null if the text is empty (meaning the default) or is not a whole number of one
    ///         or more.
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

    /// @param value A step's limit, or null for the default.
    /// @return The limit as text, or empty for null.
    public static String limitText(final Integer value) {
        return value == null
                ? ""
                : String.valueOf(value);
    }

    /// The question texts a step is asked with. Texts are per kind of step, not per step, so every step of a
    /// kind uses the same text. Most kinds have one text. A split step uses the variant its guard selects (text,
    /// XML or JSON), or all three if its guard lets any stream through, since the stream then decides which
    /// is used.
    ///
    /// @param step A step.
    /// @return The step's question texts.
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

    /// The built-in texts that may be loaded into a text: its own built-in first, then any other whose
    /// variables are all allowed in it.
    ///
    /// @param slot The text being loaded into.
    /// @return The built-in texts it may be loaded from.
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

    /// @param template A question text.
    /// @return The tooltip for the text box, listing the variables the text may use.
    public static String variablesTitle(final Template template) {
        final List<String> names = new ArrayList<>();
        for (final String variable : template.getVariables()) {
            names.add("${" + variable + "}");
        }
        return "Variables: " + (names.isEmpty()
                ? "none"
                : String.join(", ", names));
    }

    /// Describes where a text's words come from, for the status line above them. Texts are per kind of step,
    /// so for the one text shared by parser and transform Configure steps the status warns that an edit
    /// applies to all of them.
    ///
    /// @param template            The question text.
    /// @param own                 Whether the document has its own words for it.
    /// @param fetched             Whether its built-in words have been fetched.
    /// @param builtInVersion      The version of the built-in texts the server provides, or null if not known.
    /// @param savedAgainstVersion The built-in version the document was last saved against, or null.
    /// @return The status line.
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

    /// @param problems The problems and warnings found in the flow.
    /// @param index    A step's index.
    /// @return The problems with that step, joined into one string, or null if there are none.
    public static String problemsOf(final List<FlowProblem> problems, final int index) {
        return problemsOf(problems, index, Severity.PROBLEM);
    }

    /// Collects what is reported about a step at one severity: problems, which stop the flow being saved, or
    /// warnings, which are only advice.
    ///
    /// @param problems The problems and warnings found in the flow.
    /// @param index    A step's index.
    /// @param severity The severity wanted.
    /// @return The messages about that step at that severity, joined into one string, or null if there are
    ///         none.
    public static String problemsOf(final List<FlowProblem> problems,
                                    final int index,
                                    final Severity severity) {
        final List<String> mine = new ArrayList<>();
        for (final FlowProblem problem : problems) {
            if (problem.getStep() != null && problem.getStep() == index && problem.getSeverity() == severity) {
                mine.add(problem.getMessage());
            }
        }
        return mine.isEmpty()
                ? null
                : String.join("; ", mine);
    }

}
