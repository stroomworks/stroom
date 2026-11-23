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

import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.ConfigureRole;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.FlowWords;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StepGuard;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.TurnScore;

import java.util.ArrayList;
import java.util.List;

/// The words the Learning Flow screens use for what the engine calls by its own names: a flow's steps,
/// how an answer was judged, the checks, the example flows, and counts, percentages and durations. The
/// design terms (learner, binding, candidate, park …) stay in the code and the design documents; a person
/// reads these. Worked out from the shared model alone, so that it can be tested without a browser.
public final class PlainWords {

    private static final long SECOND_MS = 1000L;
    private static final long MINUTE_MS = 60 * SECOND_MS;
    private static final long HOUR_MS = 60 * MINUTE_MS;
    private static final long DAY_MS = 24 * HOUR_MS;

    private PlainWords() {
        // Static utility
    }

    /// A step's name as the step cards and the progress strip show it, e.g. "Write the parser". A
    /// second step of the same kind and role is "… again", so that two of them can be told apart.
    ///
    /// @param steps The flow's steps.
    /// @param index The step's index.
    /// @return Its name.
    public static String stepName(final List<FlowStep> steps, final int index) {
        return FlowWords.stepName(steps, index);
    }

    /// @param kind The kind of question.
    /// @param role The role of a Configure step, or null.
    /// @return The name of a step of that kind and role, e.g. "Find what one record is".
    public static String stepName(final QuestionKind kind, final ConfigureRole role) {
        return FlowWords.stepName(kind, role);
    }

    /// What a step is doing while a session is at it, e.g. "writing the parser", for "Learning: …".
    ///
    /// @param kind The kind of question.
    /// @param role The role of a Configure step, or null.
    /// @return What it is doing, in lower case.
    public static String doing(final QuestionKind kind, final ConfigureRole role) {
        if (kind == null) {
            return "asking the AI";
        }
        switch (kind) {
            case CHAIN:
                return "choosing the elements";
            case SPLIT:
                return "finding what one record is";
            case TARGET:
                return "agreeing the target events";
            case ADMIT:
                return "writing the fallback";
            default:
                if (role == ConfigureRole.PARSER) {
                    return "writing the parser";
                }
                if (role == ConfigureRole.TRANSFORM) {
                    return "writing the transform";
                }
                return "writing every element";
        }
    }

    /// How an answer was judged, as a short result, e.g. "Didn't read all the input".
    ///
    /// @param outcome The outcome, or null where the answer has not been judged.
    /// @return The result.
    public static String result(final StepOutcome outcome) {
        if (outcome == null) {
            return "Not judged";
        }
        switch (outcome) {
            case PASSED:
                return "Passed";
            case REFUSED:
                return "Not in the reply format";
            case COMPILE_FAILED:
                return "Doesn't compile";
            case RUN_FAILED:
                return "Errors on the data";
            case COVERAGE_SHORT:
                return "Didn't read all the input";
            case COMPLETENESS_SHORT:
                return "Records that became nothing";
            case YIELD_SHORT:
                return "Too few or too many records";
            case WHOLENESS_SHORT:
                return "Records cut into pieces";
            case CONFORMANCE_SHORT:
                return "Events not valid against the schema";
            case QUALITY_SHORT:
                return "Events empty or meaningless";
            case RULES_SHORT:
                return "Breaks a business rule";
            case PERMITTED_SHORT:
                return "Uses something it may not";
            case TIMES_SHORT:
                return "Events dated far from the stream";
            case LITERALS_SHORT:
                return "Data written into the translation";
            case PRESERVATION_SHORT:
                return "Lost what the targets need";
            case FIDELITY_SHORT:
                return "Doesn't match the agreed targets";
            default:
                return outcome.getDisplayValue();
        }
    }

    /// The condition of a rule on a step, e.g. "the records don't read all the input", as "If … then …"
    /// reads it.
    ///
    /// @param outcome The outcome the rule fires on, or null for the step running out of tries.
    /// @return The condition.
    public static String condition(final StepOutcome outcome) {
        return FlowWords.condition(outcome);
    }

    /// A check's name as the list of checks shows it, e.g. "Reads all the input".
    ///
    /// @param check The check.
    /// @return Its name.
    public static String checkName(final Check check) {
        if (check == null) {
            return "";
        }
        switch (check) {
            case COMPILE:
                return "Compiles";
            case COVERAGE:
                return "Reads all the input";
            case COMPLETENESS:
                return "Every record becomes an event or a declared skip";
            case YIELD:
                return "Gives the right number of records";
            case CONFORMANCE:
                return "Valid against the target schema";
            case QUALITY:
                return "Events carry real content";
            case RULES:
                return "Meets the business rules";
            case TIMES:
                return "Event times are plausible";
            case LITERALS:
                return "No data written into the translation";
            case WHOLENESS:
                return "Records are whole";
            case PRESERVATION:
                return "Keeps what the targets need";
            case FIDELITY:
                return "Matches the agreed targets";
            case REGRESSION:
                return "No worse on records it was accepted on";
            default:
                return check.getDisplayValue() + " (not built)";
        }
    }

    /// A score against its threshold, e.g. "Reading the input scored 42%. It needs 90%.".
    ///
    /// @param score The score that decided a turn.
    /// @return The sentence.
    public static String score(final TurnScore score) {
        final String name = scoreName(score.getCheck());
        final String scored = name + " scored " + percent(score.getScore()) + ".";
        return score.isMet()
                ? scored + " It needed " + percent(score.getThreshold()) + "."
                : scored + " It needs " + percent(score.getThreshold()) + ".";
    }

    private static String scoreName(final Check check) {
        if (check == null) {
            return "The answer";
        }
        switch (check) {
            case COMPILE:
                return "Compiling";
            case COVERAGE:
                return "Reading the input";
            case COMPLETENESS:
                return "Completeness";
            case YIELD:
                return "The record count";
            case CONFORMANCE:
                return "Schema validity";
            case QUALITY:
                return "Event content";
            case RULES:
                return "The business rules";
            case WHOLENESS:
                return "Whole records";
            case PRESERVATION:
                return "Keeping what the targets need";
            case FIDELITY:
                return "Matching the agreed targets";
            case REGRESSION:
                return "The regression corpus";
            default:
                return check.getDisplayValue();
        }
    }

    /// The name of an example flow as the Setup tab offers it as a starting point.
    ///
    /// @param example The example.
    /// @return Its name, e.g. "Quick, then careful".
    public static String example(final FlowExample example) {
        switch (example) {
            case DIRECT:
                return "Straight to it";
            case TARGET_FIRST:
                return "Agree the events first";
            case ESCALATING:
                return "Quick, then careful";
            default:
                return example.getDisplayValue();
        }
    }

    /// When a step is asked, as its card's menu and tags say it, e.g. "only for XML input".
    ///
    /// @param guard When it is asked, or null.
    /// @return The words, or empty for null.
    public static String guard(final StepGuard guard) {
        if (guard == null) {
            return "";
        }
        switch (guard) {
            case TEXT:
                return "only for raw text input";
            case XML:
                return "only for XML input";
            case JSON:
                return "only for JSON input";
            default:
                return "for every input";
        }
    }

    /// @param example An example flow.
    /// @return What it does and what it suits, in a sentence or two.
    public static String exampleDescription(final FlowExample example) {
        switch (example) {
            case DIRECT:
                return "Writes the parser, then the transform. The fastest and cheapest. Good for simple, "
                       + "regular feeds.";
            case TARGET_FIRST:
                return "Settles what a record is and what each kind should become, then writes to match. Good "
                       + "for complex feeds.";
            case ESCALATING:
                return "Tries the quick way first. If the transform keeps failing, agrees the events and tries "
                       + "again.";
            default:
                return "";
        }
    }

    /// A fraction as a whole percentage, e.g. "42%".
    ///
    /// @param fraction The fraction, in [0, 1].
    /// @return The percentage.
    public static String percent(final double fraction) {
        return Math.round(fraction * 100) + "%";
    }

    /// A whole number with its thousands grouped, e.g. "12,000".
    ///
    /// @param value The number.
    /// @return The number as text.
    public static String number(final long value) {
        final String digits = Long.toString(Math.abs(value));
        final StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                grouped.append(',');
            }
            grouped.append(digits.charAt(i));
        }
        return value < 0
                ? "-" + grouped
                : grouped.toString();
    }

    /// A count of something, e.g. "1 stream", "2 streams".
    ///
    /// @param count    How many.
    /// @param singular The thing, singular.
    /// @return The count.
    public static String count(final long count, final String singular) {
        return number(count) + " " + (count == 1
                ? singular
                : singular + "s");
    }

    /// A duration in the largest whole unit, e.g. "3 hours", "less than a minute".
    ///
    /// @param ms The duration, in milliseconds.
    /// @return The duration.
    public static String duration(final long ms) {
        if (ms >= DAY_MS) {
            return count(ms / DAY_MS, "day");
        }
        if (ms >= HOUR_MS) {
            return count(ms / HOUR_MS, "hour");
        }
        if (ms >= MINUTE_MS) {
            return count(ms / MINUTE_MS, "minute");
        }
        return "less than a minute";
    }

    /// Joins words as a list is spoken: "a", "a and b", "a, b and c".
    ///
    /// @param words The words.
    /// @return The list.
    public static String list(final List<String> words) {
        if (words.isEmpty()) {
            return "";
        }
        if (words.size() == 1) {
            return words.get(0);
        }
        final List<String> head = new ArrayList<>(words.subList(0, words.size() - 1));
        return String.join(", ", head) + " and " + words.get(words.size() - 1);
    }

    /// @param text Some text.
    /// @return The text with its first letter in lower case, for the middle of a sentence; empty for null.
    public static String lowerFirst(final String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // A name that begins with capitals, such as "XMLParser", is left as it is
        return text.length() > 1 && Character.isUpperCase(text.charAt(1))
                ? text
                : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
}
