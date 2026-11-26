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

package stroom.learningflow.stage;

import stroom.learningflow.scoring.Attempted;
import stroom.learningflow.scoring.OutputRecords;
import stroom.learningflow.scoring.Records;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.RecordBoundary;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import net.sf.saxon.s9api.XdmNode;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/// Decides what the model is shown of a stream, and counts how many records a stream brought.
///
/// The model is shown a prefix of the stream to learn from, cut at whole records and within the Learning
/// Flow's sample size limit. The remaining records are held out, so that a candidate is also judged on
/// records the model never saw. Used by both serving and learning.
public final class LearningPrefix {

    private static final JsonFactory JSON = new JsonFactory();

    private LearningPrefix() {
        // Static utility
    }

    /// Gets the first `1 - heldOutFraction` of the stream's records, which is what the model learns from.
    /// The candidate is judged on the whole stream, so the judgement for a promotion includes records the
    /// model never saw.
    ///
    /// **Markup and JSON are held out by record too.** They cannot simply be cut by lines, because a prefix
    /// of a document is not a valid document. Instead, records are dropped from the end and the root is
    /// closed again (see `wholeChildren` and `wholeItems`), which is also how they are cut to the size
    /// limit. Otherwise, for any XML or JSON feed within the limit, the model would be shown the whole
    /// stream and nothing would be held out.
    ///
    /// A document of one record is shown whole, because holding out the only record would leave nothing to
    /// learn from.
    ///
    /// @param data   The stream's text.
    /// @param policy The Learning Flow document, giving the sample size limit and held-out fraction.
    /// @return What to show the model.
    public static String learningPrefix(final String data, final LearningFlowDoc policy) {
        final int limit = policy.getSampleSizeLimit();
        // Markup that is one document is held out by record. Markup that is not (a fragment per line, with
        // no root) is held out and cut by lines, like any text.
        if (RecordSkeleton.isMarkup(data) && Records.parsed(data) >= 0) {
            return wholeChildren(data, limit, policy.getHeldOutFraction());
        }
        final List<String> lines = data.lines().filter(line -> !line.isBlank()).toList();
        if (isJsonDocument(data)) {
            // A prefix of a JSON document is not a valid document either. Cut by lines it would not parse,
            // and the split question would fail on a sample the parser cannot read. So it is cut between the
            // items of its widest array and closed again, as wholeChildren closes an XML root.
            return wholeItems(data, limit, policy.getHeldOutFraction());
        }
        if (lines.isEmpty()) {
            // A blank stream has nothing to learn from; the compile and coverage checks will report it.
            return "";
        }
        int keep = (int) Math.ceil(lines.size() * (1.0 - policy.getHeldOutFraction()));
        keep = Math.max(1, Math.min(keep, lines.size()));
        return wholeLines(lines, keep, limit);
    }

    /// Gets up to the first `keep` lines, as many as fit within the size limit; the first line is always
    /// included.
    private static String wholeLines(final List<String> lines, final int keep, final int limit) {
        int length = Input.bytes(lines.get(0));
        int within = 1;
        while (within < keep && length + 1 + Input.bytes(lines.get(within)) <= limit) {
            length += 1 + Input.bytes(lines.get(within));
            within++;
        }
        return String.join("\n", lines.subList(0, within)) + "\n";
    }

    /// Counts how many records a stream brought, which is the evidence a judgement rests on. It is counted
    /// on the input, so that a candidate that drops records is judged on them rather than excused. If a
    /// record boundary was settled, records are counted by it: the named elements in XML input, or the
    /// array's items in the XML the parser makes of JSON. Otherwise they are counted as
    /// [#recordsBrought(String)] counts them.
    public static int recordsBrought(final Attempted first) {
        final RecordBoundary boundary = first.boundary();
        if (boundary != null) {
            // A boundary over JSON applies to the parser's output; a boundary over XML applies to the input
            // itself.
            final String document = first.parser()
                    ? first.result().output()
                    : first.input();
            final int by = document == null
                    ? -1
                    : Records.parsed(document, boundary);
            // If the parser's output holds no records, the stream did not hold none: the parser failed to
            // split it. So the stream is counted from its input, rather than treated as having too few
            // records to judge.
            if (by > 0) {
                return by;
            }
        }
        return recordsBrought(first.input());
    }

    /// Counts how many records a stream brought when no record boundary was settled: the children of the
    /// root for a markup document; the lines of markup that is not one document (a fragment per line), as
    /// the yield scorer counts them; one for a JSON document, however many lines it spans; and the non-blank
    /// lines of text.
    public static int recordsBrought(final String input) {
        if (RecordSkeleton.isMarkup(input)) {
            final int parsed = Records.parsed(input);
            if (parsed >= 0) {
                return parsed;
            }
        } else if (isJsonDocument(input)) {
            return 1;
        }
        return (int) input.lines().filter(line -> !line.isBlank()).count();
    }

    /// Whether text is one JSON document spanning several lines (one object or array, with nothing after it,
    /// whose first line does not close it), as opposed to JSON lines, each a value of its own, which are cut
    /// like any text. A leading bracket is not enough, because a log whose lines start with
    /// `[Mon Sep 21 ...]` is text, so the value is parsed.
    public static boolean isJsonDocument(final String data) {
        final String trimmed = RecordSkeleton.withoutBom(data).stripLeading();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return false;
        }
        final String first = trimmed.lines().findFirst().orElse("").stripTrailing();
        if (first.endsWith("}") || first.endsWith("]")) {
            return false;
        }
        try (JsonParser parser = JSON.createParser(trimmed)) {
            parser.nextToken();
            parser.skipChildren();
            return parser.nextToken() == null;
        } catch (final IOException e) {
            return false;
        }
    }

    /// Cuts a JSON document to hold out records and to fit the sample size limit. It is cut between the
    /// items of its widest array (the one a split will name) and closed again, so that the model is still
    /// shown one valid JSON document. If it has no array, the whole document is shown: a sample the parser
    /// cannot read is worse than a long one, and the session's budgets stop runaway spending.
    private static String wholeItems(final String data, final int limit, final double heldOut) {
        final String trimmed = RecordSkeleton.withoutBom(data).stripLeading();
        final Optional<int[]> widest = widestArray(trimmed);
        if (widest.isEmpty()) {
            return data;
        }
        final int open = widest.get()[0];
        final int close = widest.get()[1];
        final String before = trimmed.substring(0, open + 1);
        final String after = trimmed.substring(close);
        final List<String> items = items(trimmed.substring(open + 1, close));
        final int show = shown(items.size(), heldOut);
        if (show == items.size() && Input.bytes(data) <= limit) {
            // Nothing held out and nothing over the limit, so the stream is returned unchanged
            return data;
        }
        final int room = limit - Input.bytes(before) - Input.bytes(after);
        final StringBuilder kept = new StringBuilder();
        int taken = 0;
        int used = 0;
        for (final String item : items) {
            final int cost = Input.bytes(item) + (kept.isEmpty()
                    ? 0
                    : 1);
            if (taken >= show || (!kept.isEmpty() && used + cost > room)) {
                break;
            }
            if (!kept.isEmpty()) {
                kept.append(',');
            }
            kept.append(item);
            used += cost;
            taken++;
        }
        return before + kept + after;
    }

    /// Finds the widest array in a JSON document (the one spanning the most characters), as the offsets of
    /// its brackets, ignoring brackets inside strings.
    private static Optional<int[]> widestArray(final String json) {
        int[] widest = null;
        final Deque<Integer> opens = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            final char c = json.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\' && inString) {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '[') {
                opens.push(i);
            } else if (!inString && c == ']' && !opens.isEmpty()) {
                final int open = opens.pop();
                if (widest == null || i - open > widest[1] - widest[0]) {
                    widest = new int[]{open, i};
                }
            }
        }
        return Optional.ofNullable(widest);
    }

    /// Splits an array's contents into its items as text, at the commas between items rather than those
    /// inside them.
    private static List<String> items(final String inside) {
        final List<String> items = new ArrayList<>();
        int depth = 0;
        int from = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < inside.length(); i++) {
            final char c = inside.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\' && inString) {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && (c == '{' || c == '[')) {
                depth++;
            } else if (!inString && (c == '}' || c == ']')) {
                depth--;
            } else if (!inString && c == ',' && depth == 0) {
                items.add(inside.substring(from, i).strip());
                from = i + 1;
            }
        }
        final String last = inside.substring(from).strip();
        if (!last.isEmpty()) {
            items.add(last);
        }
        return items;
    }

    /// Cuts an XML document to hold out records and to fit the sample size limit: the root's start tag, as
    /// many of its children as are to be shown and fit (at least one), and its end tag, so that the model is
    /// still shown a valid document. Anything that looks like markup but does not parse as one document is
    /// cut by whole lines to the size limit, with nothing held out; the split question will work out what it
    /// is.
    private static String wholeChildren(final String data, final int limit, final double heldOut) {
        final Optional<OutputRecords> document = OutputRecords.parse(data);
        if (document.isEmpty() || document.get().records().isEmpty()) {
            final List<String> lines = data.lines().filter(line -> !line.isBlank()).toList();
            return lines.isEmpty()
                    ? ""
                    : wholeLines(lines, lines.size(), limit);
        }
        final int show = shown(document.get().records().size(), heldOut);
        if (show == document.get().records().size() && Input.bytes(data) <= limit) {
            // Nothing held out and nothing over the limit, so the stream is returned unchanged rather than
            // rebuilt with its whitespace changed
            return data;
        }
        final String rootName = document.get().root().getNodeName().toString();
        final int open = data.indexOf('<' + rootName);
        final int close = open < 0
                ? -1
                : endOfTag(data, open);
        if (close < 0) {
            return data;
        }
        final StringBuilder cut = new StringBuilder(data.substring(open, close + 1)).append('\n');
        int kept = 0;
        // Counted in bytes, as the setting is labelled, and kept as a running total rather than measured
        // afresh per record, which would be quadratic over a long stream
        int used = Input.bytes(cut.toString());
        for (final XdmNode child : document.get().records()) {
            final String text = child.toString();
            final int cost = Input.bytes(text) + 1;
            if (kept >= show || (kept > 0 && used + cost > limit)) {
                break;
            }
            cut.append(text).append('\n');
            used += cost;
            kept++;
        }
        return cut.append("</").append(rootName).append('>').toString();
    }

    /// Works out how many of a document's records the model is shown; the rest are held back so that the
    /// promotion is also judged on records the model never saw.
    ///
    /// @param records  How many records the document holds.
    /// @param heldOut  The fraction to hold back.
    /// @return How many to show; at least one, because an empty sample teaches nothing.
    private static int shown(final int records, final double heldOut) {
        return Math.max(1, Math.min(records, (int) Math.ceil(records * (1.0 - heldOut))));
    }

    /// @return The index of the `>` that ends the tag starting at `from`, ignoring any inside quoted
    ///         attribute values, or -1 if there is none.
    private static int endOfTag(final String data, final int from) {
        char quote = 0;
        for (int i = from; i < data.length(); i++) {
            final char c = data.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }
}
