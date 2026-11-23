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

/// What the model is shown of a stream: the part it learns from, the rest held out to judge it on (A14),
/// cut at whole records and within the Learning Flow's sample size (N2 option F), and how many records a
/// stream brought. Taken out of [Stage] (simplification option iii), where it sat between serving and
/// learning but belonged to neither.
public final class LearningPrefix {

    private static final JsonFactory JSON = new JsonFactory();

    private LearningPrefix() {
        // Static utility
    }

    /// The first `1 - heldOutFraction` of the stream's records: what the model learns from. The whole
    /// stream is what it is judged on, so what judges a promotion includes records the model never saw
    /// (A14).
    ///
    /// **Markup and JSON are held out by record, not left whole** (audit finding S5). A prefix of a
    /// document is not a document — which is why they cannot simply be cut by lines — but a document's
    /// records can be dropped from the end and the root closed again, which is what `wholeChildren` and
    /// `wholeItems` already did for the size limit. Until this they were cut only when they were *too
    /// big*, so for every XML or JSON feed within the limit the model was shown the whole stream and A14
    /// did not hold, though the editor said it did.
    ///
    /// A document of one record is shown whole: holding out the only record there is would leave nothing
    /// to learn from.
    ///
    /// @param data   The stream's text.
    /// @param policy The document.
    /// @return What to show the model.
    public static String learningPrefix(final String data, final LearningFlowDoc policy) {
        final int limit = policy.getSampleSizeLimit();
        // Markup that is one document is held out by record; markup that is not — a fragment per line, no
        // root — is lines, held out and cut like any text.
        if (RecordSkeleton.isMarkup(data) && Records.parsed(data) >= 0) {
            return wholeChildren(data, limit, policy.getHeldOutFraction());
        }
        final List<String> lines = data.lines().filter(line -> !line.isBlank()).toList();
        if (isJsonDocument(data)) {
            // A prefix of a JSON document is not a document either: cut by lines it would not parse, and the
            // split question would abandon the attempt on a sample the parser cannot read. It is cut at its
            // widest array's items and closed again, as wholeChildren closes an XML root.
            return wholeItems(data, limit, policy.getHeldOutFraction());
        }
        if (lines.isEmpty()) {
            // A blank stream has nothing to learn from; the compile gate and coverage will say so.
            return "";
        }
        int keep = (int) Math.ceil(lines.size() * (1.0 - policy.getHeldOutFraction()));
        keep = Math.max(1, Math.min(keep, lines.size()));
        return wholeLines(lines, keep, limit);
    }

    /**
     * The first {@code keep} lines, as many of them as the size limit allows; one line is always shown.
     */
    private static String wholeLines(final List<String> lines, final int keep, final int limit) {
        int length = Input.bytes(lines.get(0));
        int within = 1;
        while (within < keep && length + 1 + Input.bytes(lines.get(within)) <= limit) {
            length += 1 + Input.bytes(lines.get(within));
            within++;
        }
        return String.join("\n", lines.subList(0, within)) + "\n";
    }

    /**
     * How many records a stream brought, the evidence a judgement rests on (A14), counted on the input so
     * that a variant that drops records is judged, not excused. Where a boundary was settled (A35) it is
     * counted by that — the elements of the name in XML input, the array's items in the XML the parser
     * makes of JSON; otherwise as {@link #recordsBrought(String)} counts the input alone.
     */
    public static int recordsBrought(final Attempted first) {
        final RecordBoundary boundary = first.boundary();
        if (boundary != null) {
            // The parser's output is the document a JSON boundary applies to; XML input is its own.
            final String document = first.parser()
                    ? first.result().output()
                    : first.input();
            final int by = document == null
                    ? -1
                    : Records.parsed(document, boundary);
            // A parser whose output holds no records at all did not cut a stream of none: it failed to
            // cut what the stream brought, and the stream is judged as what it brought — not as too few
            // records to judge (A14).
            if (by > 0) {
                return by;
            }
        }
        return recordsBrought(first.input());
    }

    /**
     * How many records a stream brought where no boundary was settled: a document's root's children; the
     * lines of markup that is not one document — a fragment per line — as the yield scorer counts them; one
     * for a JSON document, however many lines it is printed over; the non-blank lines of text.
     */
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

    /**
     * Whether text is one JSON document spanning lines — one object or array, and nothing after it, whose
     * first line does not close it — rather than JSON lines, each a value of its own, which are cut like any
     * text. A bracket is not enough: a log whose lines open with {@code [Mon Sep 21 ...]} is text, so the
     * value is parsed.
     */
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

    /**
     * A JSON document over the sample size limit, cut at the items of its widest array — the one a split will
     * name — and closed again, so that what the model is shown is still one JSON document. Where no array can
     * be cut that way, the whole document is shown: a sample the parser cannot read is worse than a long one,
     * and the budget of A5 is what stops a runaway.
     */
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
            // Nothing held out and nothing over the limit: the stream as it came
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

    /**
     * The widest array in a JSON document — the outermost one holding the most — as the offsets of its
     * brackets, ignoring brackets inside strings.
     */
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

    /**
     * An array's items as text, split at the commas between them rather than those inside them.
     */
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

    /**
     * A document over the sample size limit, cut at a record boundary: the root's start tag, as many of its
     * children as fit — at least one — and its end tag, so that what the model is shown is still a document.
     * What looks like markup but is not one document — fragments, or text beginning with a bracketed word —
     * is cut by whole lines, as text is; the split question will find it what it is.
     */
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
            // Nothing held out and nothing over the limit: the stream as it came, rather than the same
            // document rebuilt with its whitespace changed
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
        // Counted in bytes, as the setting is labelled (audit finding S4), and kept running rather than
        // measured afresh per record, which would be quadratic over a long stream
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

    /// How many of a document's records the model is shown, the rest held back to judge the promotion on
    /// (A14, audit finding S5).
    ///
    /// @param records  How many the document holds.
    /// @param heldOut  The share to hold back.
    /// @return How many to show; at least one, because a sample of nothing teaches nothing.
    private static int shown(final int records, final double heldOut) {
        return Math.max(1, Math.min(records, (int) Math.ceil(records * (1.0 - heldOut))));
    }

    /**
     * @return The index of the {@code >} that ends the tag opening at {@code from}, outside any quoted
     * attribute value, or -1.
     */
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
