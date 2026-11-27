/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.learningflow.scenario;

/// Generates headerless door-access CSV for scenario tests, with four fields: time, user, place and action.
/// It is regular enough for a regular expression splitter to handle correctly, and can include alarm lines
/// that vary it enough for a splitter to get wrong.
public final class CsvLines {

    private CsvLines() {
    }

    /// Returns `records` access lines for users `prefix0`, `prefix1` and so on. When `alarmEvery` is
    /// positive, every `alarmEvery`th line is replaced by an alarm line of three fields.
    public static String lines(final int records, final String prefix, final int alarmEvery) {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < records; i++) {
            final String time = "2020-06-17T08:" + String.format("%02d", i) + ":00.000Z";
            text.append(alarmEvery > 0 && i % alarmEvery == alarmEvery - 1
                    ? time + ",ALARM,door " + i + " forced\n"
                    : time + "," + prefix + i + ",office,logon\n");
        }
        return text.toString();
    }

    public static String lines(final int records) {
        return lines(records, "user", 0);
    }

    /// Returns a stylesheet with one exact block removed, so that a scenario can state precisely what its
    /// flawed candidate lacks. The block must appear exactly once.
    public static String without(final String stylesheet, final String block) {
        if (stylesheet.indexOf(block) < 0 || stylesheet.indexOf(block) != stylesheet.lastIndexOf(block)) {
            throw new IllegalArgumentException("The block to cut must appear exactly once:\n" + block);
        }
        return stylesheet.replace(block, "");
    }

    public static String replacing(final String stylesheet, final String block, final String with) {
        if (stylesheet.indexOf(block) < 0 || stylesheet.indexOf(block) != stylesheet.lastIndexOf(block)) {
            throw new IllegalArgumentException("The block to replace must appear exactly once:\n" + block);
        }
        return stylesheet.replace(block, with);
    }
}
