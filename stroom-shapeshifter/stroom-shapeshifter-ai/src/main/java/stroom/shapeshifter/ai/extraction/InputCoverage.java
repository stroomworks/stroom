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

package stroom.shapeshifter.ai.extraction;

import stroom.util.shared.Location;
import stroom.util.shared.TextRange;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * How much of an input the split actually consumed (ruling A11): the proportion of characters and of
 * lines that fell inside some record, as opposed to being skipped, ignored or left unmatched.
 * <p>
 * This is the primary guard against the dominant failure of a generated splitter — a configuration that
 * produces a believable number of records by quietly discarding everything it could not match. Yield alone
 * cannot see that; coverage can, and it is cheap, because the parser already reports where every record
 * began and ended.
 * <p>
 * Line breaks are never counted, on either side of the ratio: the Data Splitter excludes them from record
 * ranges, and a line consisting only of a line break carries nothing to extract. Blank lines likewise do
 * not count as lines.
 *
 * @param charsCovered Characters, other than line breaks, inside at least one record range.
 * @param charsTotal   Characters in the input, other than line breaks.
 * @param linesCovered   Non-blank lines with at least one covered character.
 * @param linesTotal     Non-blank lines in the input.
 * @param linesUncovered The one-based numbers of the non-blank lines nothing consumed, in order: what
 *                       the model is pointed at when coverage loses marks.
 */
public record InputCoverage(int charsCovered,
                            int charsTotal,
                            int linesCovered,
                            int linesTotal,
                            List<Integer> linesUncovered) {

    public InputCoverage {
        linesUncovered = List.copyOf(linesUncovered);
    }

    public static InputCoverage measure(final String rawInput, final List<TextRange> recordRanges) {
        // Positions are the Data Splitter's, and its reader drops carriage returns and every other
        // control character before it counts a column, so coverage is measured over the same text.
        final String input = asRead(rawInput);
        final int[] lineStarts = lineStarts(input);
        final BitSet covered = new BitSet(input.length());

        for (final TextRange range : recordRanges) {
            final int from = offset(input, lineStarts, range.getFrom());
            final int to = offset(input, lineStarts, range.getTo());
            for (int i = from; i <= to && i < input.length(); i++) {
                if (!isLineBreak(input.charAt(i))) {
                    covered.set(i);
                }
            }
        }

        int charsTotal = 0;
        int linesTotal = 0;
        int linesCovered = 0;
        final List<Integer> linesUncovered = new ArrayList<>();
        for (int line = 0; line < lineStarts.length; line++) {
            final int start = lineStarts[line];
            final int end = line + 1 < lineStarts.length
                    ? lineStarts[line + 1]
                    : input.length();

            boolean blank = true;
            boolean lineCovered = false;
            for (int i = start; i < end; i++) {
                final char c = input.charAt(i);
                if (isLineBreak(c)) {
                    continue;
                }
                charsTotal++;
                if (!Character.isWhitespace(c)) {
                    blank = false;
                }
                if (covered.get(i)) {
                    lineCovered = true;
                }
            }
            if (!blank) {
                linesTotal++;
                if (lineCovered) {
                    linesCovered++;
                } else {
                    linesUncovered.add(line + 1);
                }
            }
        }

        return new InputCoverage(covered.cardinality(), charsTotal, linesCovered, linesTotal, linesUncovered);
    }

    public double charRatio() {
        return ratio(charsCovered, charsTotal);
    }

    public double lineRatio() {
        return ratio(linesCovered, linesTotal);
    }

    private static double ratio(final int covered, final int total) {
        return total == 0
                ? 1.0
                : (double) covered / total;
    }

    /**
     * Offsets at which each line begins, so that a one-based line and column pair can be turned into an
     * index into the input. A trailing line break starts a final, empty line, which is harmless.
     */
    private static int[] lineStarts(final String input) {
        int lines = 1;
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) == '\n') {
                lines++;
            }
        }
        final int[] starts = new int[lines];
        int line = 1;
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) == '\n') {
                starts[line++] = i + 1;
            }
        }
        return starts;
    }

    private static int offset(final String input, final int[] lineStarts, final Location location) {
        final int line = Math.min(Math.max(location.getLineNo(), 1), lineStarts.length) - 1;
        final int offset = lineStarts[line] + Math.max(location.getColNo(), 1) - 1;
        return Math.min(offset, input.length());
    }

    private static boolean isLineBreak(final char c) {
        return c == '\n' || c == '\r';
    }

    static String asRead(final String input) {
        final StringBuilder read = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            final char c = input.charAt(i);
            if (c >= ' ' || c == '\n' || c == '\t') {
                read.append(c);
            }
        }
        return read.toString();
    }
}
