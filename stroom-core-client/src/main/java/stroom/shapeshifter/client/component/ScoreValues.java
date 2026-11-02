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

import stroom.widget.form.client.InputChecks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/// How the Score tab and the scorer editor show the document's values in their fields, and read
/// them back: fractions as whole percentages, lists as one item per line, numbers without a needless
/// `.0`, and the checks of a number typed.
public final class ScoreValues {

    private static final int PERCENT = 100;

    private ScoreValues() {
        // Static utility
    }

    /// @param fraction A fraction in [0, 1].
    /// @return It as a whole percentage, e.g. 80 for 0.8.
    public static long toPercent(final double fraction) {
        return Math.round(fraction * PERCENT);
    }

    /// @param percent A whole percentage.
    /// @return It as a fraction, e.g. 0.8 for 80.
    public static double fromPercent(final int percent) {
        return percent / (double) PERCENT;
    }

    /// @param days The regression retention in days, or null when the feed's own retention applies.
    /// @return What the field shows: the days, or zero for the feed's own.
    public static long toDays(final Integer days) {
        return days == null
                ? 0
                : days;
    }

    /// @param days What the field shows.
    /// @return The regression retention in days, or null for zero, the feed's own.
    public static Integer fromDays(final int days) {
        return days == 0
                ? null
                : days;
    }

    /// @param items The items, or null for none.
    /// @return The items, one per line.
    public static String toLines(final List<String> items) {
        return items == null
                ? ""
                : String.join("\n", items);
    }

    /// @param text Text, one item per line, or null.
    /// @return The items, trimmed, without blank lines; never null.
    public static List<String> fromLines(final String text) {
        final List<String> lines = new ArrayList<>();
        if (text != null) {
            for (final String line : text.split("\n")) {
                final String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    lines.add(trimmed);
                }
            }
        }
        return lines;
    }

    /// @param number A number.
    /// @return It as text, `1` rather than `1.0`.
    public static String format(final double number) {
        return number == Math.rint(number) && !Double.isInfinite(number)
                ? String.valueOf((long) number)
                : String.valueOf(number);
    }

    /// @return A check that text is a number of zero or more, e.g. a scorer's weight.
    public static Function<String, String> zeroOrMore() {
        return text -> {
            final Double number = InputChecks.parseNumber(text);
            return number == null || number < 0
                    ? "Must be a number of zero or more"
                    : null;
        };
    }

    /// @return A check that text is a number above zero, e.g. a yield's expected ratio.
    public static Function<String, String> aboveZero() {
        return text -> {
            final Double number = InputChecks.parseNumber(text);
            return number == null || number <= 0
                    ? "Must be a number above zero"
                    : null;
        };
    }
}
