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

package stroom.shapeshifter.ai.learning;

import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.ai.scoring.OutputRecords;
import stroom.shapeshifter.ai.scoring.Scorecard;
import stroom.shapeshifter.ai.scoring.Verdict;
import stroom.shapeshifter.ai.stage.RecordSkeleton;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import net.sf.saxon.s9api.Axis;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The checks a target brings to the conversation (design 01 §10.1, ruling A31), beside the document's
 * scorers: whether a proposed target is an event worth aiming at; whether the parser's records
 * <i>preserve</i> what the targets need; whether the transform's events <i>reproduce</i> them. Each
 * says what fell short in the terms the model can act on.
 */
public final class TargetChecks {

    /**
     * The most record kinds a sample is asked about: enough to see every kind of record a feed carries, few enough
     * to keep the attempt short.
     */
    public static final int REPRESENTATIVES = 3;
    public static final String NONE = "none";
    private static final ElementId TARGET = new ElementId("Target");
    private static final int VALUE_LENGTH_COUNTED = 2;
    private static final String EVENTS_OPEN = "<Events xmlns=\"event-logging:3\" "
            + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
            + "xsi:schemaLocation=\"event-logging:3 file://event-logging-v3.0.0.xsd\" "
            + "Version=\"3.0.0\">";
    private static final String EVENTS_CLOSE = "</Events>";

    private TargetChecks() {
    }

    /**
     * One record of each kind the split yielded, first seen first, at most {@link #REPRESENTATIVES}. A text
     * record's kind is its text skeleton, values reduced to their classes; a markup record's is its
     * element skeleton with its naming attributes ({@link RecordSkeleton}), since records
     * of one vocabulary — named {@code Data}, or a JSON map — are told apart by what they are called.
     */
    public static List<String> representatives(final List<String> records) {
        return representatives(records, REPRESENTATIVES);
    }

    public static List<String> representatives(final List<String> records, final int atMost) {
        final Map<String, String> byKind = new LinkedHashMap<>();
        for (final String record : records) {
            if (record == null || record.isBlank()) {
                continue;
            }
            byKind.putIfAbsent(kindOf(record), record);
            if (byKind.size() == atMost) {
                break;
            }
        }
        return List.copyOf(byKind.values());
    }

    /// The elements of a document with this local name, each as its text — the records where that element is
    /// one record (A35). Only the outermost occurrences count: an element of the name inside another is part
    /// of that record, not a record of its own.
    public static List<String> elementsNamed(final OutputRecords document, final String name) {
        return document.recordsBy(RecordBoundary.ofElement(name)).stream().map(XdmNode::toString).toList();
    }

    /// The records of JSON where every top-level value is one ([Boundary#ROOT]): the root map's children —
    /// or, where the document is one top-level array, that array's items, since the parser wraps such a
    /// document in a keyless array under the root map, which no key could name.
    public static List<String> rootRecords(final OutputRecords document) {
        return document.recordsBy(RecordBoundary.ofArray(RecordBoundary.ROOT)).stream()
                .map(XdmNode::toString)
                .toList();
    }

    /// The items of the JSON array with this key, in the XSL/json vocabulary the parser produces, each as its
    /// text — the records where that array holds them (A31). The outermost such array counts.
    public static List<String> arrayItems(final OutputRecords document, final String key) {
        return document.recordsBy(RecordBoundary.ofArray(key)).stream().map(XdmNode::toString).toList();
    }

    /// The text a markup record carries: its element and attribute values, for wholeness against the document.
    public static String textOf(final String record) {
        return OutputRecords.parse(record)
                .map(parsed -> parsed.root().getStringValue())
                .orElse(record);
    }

    private static List<XdmNode> occurrences(final OutputRecords document, final String name) {
        return document.recordsBy(RecordBoundary.ofElement(name));
    }

    /// Whether an element that occurs is one record: it must not be the root, must not be a container of a
    /// repeated child, and its occurrences together must hold the document whole, as a split's records must.
    /// That it occurs at all is the caller's to check first.
    ///
    /// @return The shortfall, empty where the element will do.
    public static Optional<StoredError> recordElement(final OutputRecords document, final String name) {
        if (name.equals(document.root().getNodeName().getLocalName())) {
            return Optional.of(new StoredError(Severity.ERROR, null, TARGET, name + " is the root, which holds every "
                                                                             + "record; name the element that is one "
                                                                             + "record"));
        }
        final List<XdmNode> found = occurrences(document, name);
        if (found.size() == 1) {
            // One occurrence holding a repeated child that has structure of its own is the records' container,
            // not a record. A repeated child that is a leaf — a field, as <data> in a one-record sample — is not.
            final Map<String, Long> children = new LinkedHashMap<>();
            document.evaluate(found.get(0), "*[*]")
                    .forEach(child -> children.merge(((XdmNode) child).getNodeName().getLocalName(), 1L, Long::sum));
            final Optional<Map.Entry<String, Long>> repeated = children.entrySet().stream()
                    .filter(entry -> entry.getValue() > 1)
                    .max(Map.Entry.comparingByValue());
            if (repeated.isPresent()) {
                return Optional.of(new StoredError(Severity.ERROR, null, TARGET, name + " occurs once and holds "
                        + repeated.get().getValue() + " <" + repeated.get().getKey() + "> elements; a container of "
                        + "records is not a record — name the element that repeats"));
            }
        }
        final long documentChars = document.root().getStringValue().chars().filter(c -> !Character.isWhitespace(c))
                .count();
        final long recordChars = found.stream()
                .mapToLong(item -> item.getStringValue().chars().filter(c -> !Character.isWhitespace(c)).count())
                .sum();
        if (documentChars > 0 && (double) recordChars / documentChars < SPLIT_WHOLENESS) {
            return Optional.of(new StoredError(Severity.ERROR, null, TARGET, "The " + found.size() + " element(s) "
                    + "named " + name + " hold " + recordChars + " of the document's " + documentChars
                    + " characters; the element that is one record holds nearly all of them between its "
                    + "occurrences"));
        }
        return Optional.empty();
    }

    /**
     * How many of the records are of the representative's kind.
     */
    public static int count(final List<String> records, final String representative) {
        final String kind = kindOf(representative);
        return (int) records.stream()
                .filter(record -> record != null && !record.isBlank())
                .filter(record -> kind.equals(kindOf(record)))
                .count();
    }

    /**
     * The kind of one record, by which representatives are chosen and counted: the text skeleton of a line,
     * the record skeleton of markup.
     */
    public static String kindOf(final String record) {
        return RecordSkeleton.isMarkup(record)
                ? RecordSkeleton.recordSkeleton(record)
                : RecordSkeleton.textSkeleton(record);
    }

    /**
     * The share of the input's non-blank characters that a split must emit as record text: coverage of
     * what was <i>kept</i>, since the Data Splitter counts a whole group as consumed once it takes it,
     * however little of it a record carries.
     */
    public static final double SPLIT_WHOLENESS = 0.9;

    /**
     * Whether a split emitted the input whole: the record texts, together, must carry nearly every
     * non-blank character of the input. A split that keeps the first line of each block passes coverage —
     * the block was consumed — and fails this.
     *
     * @return The shortfall, empty where the records carry the input.
     */
    public static Optional<StoredError> wholeness(final String input, final List<String> recordTexts) {
        final long inputChars = input.chars().filter(c -> !Character.isWhitespace(c)).count();
        final long recordChars = recordTexts.stream()
                .mapToLong(text -> text.chars().filter(c -> !Character.isWhitespace(c)).count())
                .sum();
        if (inputChars == 0 || (double) recordChars / inputChars >= SPLIT_WHOLENESS) {
            return Optional.empty();
        }
        return Optional.of(new StoredError(Severity.ERROR, null, TARGET, "The split emitted " + recordChars + " of "
                                                                          + inputChars + " characters as record text; "
                                                                          + "each record must be emitted whole, as one "
                                                                          + "field, however many lines it spans"));
    }

    /**
     * A proposed target as a whole document the scorers can judge: the event wrapped in the events root
     * where the model gave the event alone.
     */
    public static String asDocument(final String event) {
        String stripped = event.strip();
        if (stripped.startsWith("<?xml")) {
            final int end = stripped.indexOf("?>");
            stripped = end < 0
                    ? stripped
                    : stripped.substring(end + 2).strip();
        }
        return stripped.contains("<Events")
                ? stripped
                : EVENTS_OPEN + stripped + EVENTS_CLOSE;
    }

    /**
     * Whether a proposed target is an event worth aiming at: it must parse as one event, and it is judged
     * by the document's scorers of meaning — conformance, extraction quality, business rules — over the
     * wrapped document, as a transform's output would be. The stream-level scorers do not apply to one
     * event on its own, and a document that does not parse is refused rather than passed unscored.
     *
     * @return The shortfalls, empty where the target is accepted.
     */
    public static List<StoredError> judge(final Scorecard scorecard, final String record, final String document) {
        final Optional<StoredError> refusal = refusal(document);
        if (refusal.isPresent()) {
            return List.of(refusal.get());
        }
        final Verdict verdict = verdict(scorecard, record, document);
        return verdict.passed()
                ? List.of()
                : verdict.feedback();
    }

    /// Why a proposed target is not an event to judge at all: not well-formed, or more than one event.
    ///
    /// @return The refusal, empty where the document is one event.
    public static Optional<StoredError> refusal(final String document) {
        final Optional<OutputRecords> parsed = OutputRecords.parse(document);
        if (parsed.isEmpty() || parsed.get().records().size() != 1) {
            return Optional.of(new StoredError(Severity.FATAL_ERROR, null, TARGET, parsed.isEmpty()
                    ? "The event is not well-formed XML. Reply with the Event element alone, without an XML "
                      + "declaration or an Events wrapper"
                    : "The reply holds " + parsed.get().records().size() + " events; one record becomes one event"));
        }
        return Optional.empty();
    }

    /// The scorers of meaning over one proposed event, as a transform's output would be judged.
    public static Verdict verdict(final Scorecard scorecard, final String record, final String document) {
        return scorecard.meaning()
                .judge(new Attempted("Target", false, record, new StepResult(document, List.of())));
    }

    /**
     * Whether the parser's records carry every value the targets need: each leaf value of a target's
     * event that appears in its source record must appear among the data values of some emitted record.
     * Constants the instructions supplied are not in the source and are not demanded.
     *
     * @return The shortfalls, empty where every value is preserved.
     */
    public static List<StoredError> preservation(final String recordsDocument, final List<Target> targets) {
        final Optional<OutputRecords> parsed = OutputRecords.parse(recordsDocument);
        if (parsed.isEmpty()) {
            return List.of(new StoredError(Severity.ERROR, null, TARGET,
                    "The parser produced no records to check the targets against"));
        }
        final List<Set<String>> recordValues = new ArrayList<>();
        for (final XdmNode record : parsed.get().records()) {
            recordValues.add(dataValues(parsed.get(), record));
        }
        final List<StoredError> shortfalls = new ArrayList<>();
        int kind = 0;
        for (final Target target : targets) {
            kind++;
            if (target.event().isEmpty()) {
                continue;
            }
            final Set<String> needed = sourceValues(target);
            final Set<String> missing = new TreeSet<>();
            for (final String value : needed) {
                final boolean carried = recordValues.stream()
                        .anyMatch(values -> values.stream().anyMatch(v -> v.contains(value)));
                if (!carried) {
                    missing.add(value);
                }
            }
            if (!missing.isEmpty()) {
                shortfalls.add(new StoredError(Severity.ERROR, null, TARGET, "Record kind " + kind
                        + ": the records carry no value for " + missing + ", which the target event needs from the "
                        + "record \"" + target.record() + "\". Extract every field the event uses."));
            }
        }
        return shortfalls;
    }

    /**
     * Whether the transform reproduced each target: some emitted event must equal the target's event as
     * a canonical tree. Where none does, the nearest — the event at the target's position — is shown
     * beside it.
     *
     * @return The shortfalls, empty where every target is reproduced.
     */
    public static List<StoredError> fidelity(final String eventsDocument, final List<Target> targets) {
        final Optional<OutputRecords> produced = OutputRecords.parse(eventsDocument);
        final List<String> events = produced.map(records -> records.records().stream()
                .map(TargetChecks::canonical)
                .toList()).orElse(List.of());
        final List<StoredError> shortfalls = new ArrayList<>();
        int kind = 0;
        for (final Target target : targets) {
            kind++;
            if (target.event().isEmpty()) {
                continue;
            }
            final Optional<OutputRecords> wanted = OutputRecords.parse(asDocument(target.event().get()));
            if (wanted.isEmpty() || wanted.get().records().isEmpty()) {
                continue;
            }
            final String expected = canonical(wanted.get().records().get(0));
            if (!events.contains(expected)) {
                final String nearest = produced.flatMap(records -> records.records().stream()
                        .max(Comparator.comparingInt(event -> agreement(canonical(event), expected)))
                        .map(XdmNode::toString)).orElse("nothing");
                shortfalls.add(new StoredError(Severity.ERROR, null, TARGET, "Record kind " + kind
                        + ": no event produced equals the target for the record \"" + target.record()
                        + "\". Produce exactly:\n" + target.event().get().strip()
                        + "\nThe nearest event produced was:\n" + nearest.strip()));
            }
        }
        return shortfalls;
    }

    /**
     * The event's values that came from the record: every leaf and attribute value that occurs in the
     * record's text, long enough to mean something.
     */
    static Set<String> sourceValues(final Target target) {
        final Set<String> values = new TreeSet<>();
        OutputRecords.parse(asDocument(target.event().orElseThrow())).ifPresent(document -> {
            for (final XdmNode event : document.records()) {
                document.evaluate(event, "descendant::text() | descendant::*/@*").forEach(item -> {
                    final String value = item.getStringValue().strip();
                    if (value.length() >= VALUE_LENGTH_COUNTED && target.record().contains(value)) {
                        values.add(value);
                    }
                });
            }
        });
        return values;
    }

    private static Set<String> dataValues(final OutputRecords records, final XdmNode record) {
        final Set<String> values = new TreeSet<>();
        records.evaluate(record, "descendant::*[local-name() = 'data']/@value | descendant::text()")
                .forEach(item -> values.add(item.getStringValue()));
        return values;
    }

    /**
     * An element as one line naming every element and attribute by namespace and local name, attributes
     * sorted, whitespace-only text dropped and other text trimmed — so two trees that say the same thing
     * compare equal whatever prefixes, declarations or indentation each serialisation chose.
     */
    static String canonical(final XdmNode node) {
        final StringBuilder text = new StringBuilder();
        canonical(node, text);
        return text.toString();
    }

    private static void canonical(final XdmNode node, final StringBuilder text) {
        switch (node.getNodeKind()) {
            case ELEMENT -> {
                text.append('<').append(qualified(node.getNodeName()));
                final Set<String> attributes = new TreeSet<>();
                node.axisIterator(Axis.ATTRIBUTE).forEachRemaining(attribute ->
                        attributes.add(qualified(attribute.getNodeName()) + "=\"" + attribute.getStringValue() + '"'));
                attributes.forEach(attribute -> text.append(' ').append(attribute));
                text.append('>');
                node.axisIterator(Axis.CHILD).forEachRemaining(child -> canonical(child, text));
                text.append("</").append(qualified(node.getNodeName())).append('>');
            }
            case TEXT -> text.append(node.getStringValue().strip());
            default -> {
                // Comments and processing instructions say nothing about the event.
            }
        }
    }

    /**
     * How far two canonical forms agree from the start: the produced event that agrees longest is the
     * nearest to the target, and the point where they part is where the transform went wrong.
     */
    private static int agreement(final String produced, final String expected) {
        final int limit = Math.min(produced.length(), expected.length());
        int i = 0;
        while (i < limit && produced.charAt(i) == expected.charAt(i)) {
            i++;
        }
        return i;
    }

    private static String qualified(final QName name) {
        return name.getNamespaceURI().isEmpty()
                ? name.getLocalName()
                : '{' + name.getNamespaceURI() + '}' + name.getLocalName();
    }
}
