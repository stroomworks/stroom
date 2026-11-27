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

import stroom.widget.form.client.InputChecks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/// Converts a Learning Flow document's scoring values to the text shown in form fields, and back again. It is
/// used by the Setup tab, the check editor and the advanced settings.
///
/// Fractions are shown as whole percentages, lists as one item per line, and whole numbers without a
/// trailing `.0`. It also provides the validation checks for numbers typed into those fields.
public final class ScoreValues {

    private static final int PERCENT = 100;

    private ScoreValues() {
        // Static utility
    }

    /// @param fraction A fraction in [0, 1].
    /// @return The fraction as a whole percentage, e.g. 80 for 0.8.
    public static long toPercent(final double fraction) {
        return Math.round(fraction * PERCENT);
    }

    /// @param percent A whole percentage.
    /// @return The percentage as a fraction, e.g. 0.8 for 80.
    public static double fromPercent(final int percent) {
        return percent / (double) PERCENT;
    }

    /// @param items The items, or null for none.
    /// @return The items, one per line.
    public static String toLines(final List<String> items) {
        return items == null
                ? ""
                : String.join("\n", items);
    }

    /// @param text Text with one item per line, or null.
    /// @return The items, trimmed, with blank lines dropped; never null.
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
    /// @return The number as text, with whole numbers shown without a decimal point, e.g. `1` rather than
    ///         `1.0`.
    public static String format(final double number) {
        return number == Math.rint(number) && !Double.isInfinite(number)
                ? String.valueOf((long) number)
                : String.valueOf(number);
    }

    /// @return A field check that the text is a number greater than zero, e.g. for a yield's expected ratio.
    ///         The check returns the reason the text is invalid, or null if it is valid.
    public static Function<String, String> aboveZero() {
        return text -> {
            final Double number = InputChecks.parseNumber(text);
            return number == null || number <= 0
                    ? "Must be a number above zero"
                    : null;
        };
    }
}
