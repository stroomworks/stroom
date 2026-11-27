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

package stroom.learningflow.learning;

import stroom.learningflow.scoring.Attempted;
import stroom.learningflow.scoring.OutputRecords;
import stroom.learningflow.scoring.Scorecard;
import stroom.learningflow.scoring.Verdict;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.stage.RecordSkeleton;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// The learning-only checks used by [Conversation] alongside the document's scorers, mostly concerned with
/// targets and record boundaries.
///
/// They decide whether a proposed target is an event worth aiming at, whether the parser's records
/// *preserve* what the targets need, whether the transform's events *reproduce* the targets, and whether a
/// split's records hold the input whole. Each reports what fell short in terms the model can act on.
public final class TargetChecks {

    /// The maximum number of record kinds a sample is asked about: enough to see every kind of record a typical
    /// feed carries, but few enough to keep the attempt short.
    public static final int REPRESENTATIVES = 3;
    public static final String NONE = "none";
    private static final ElementId TARGET = new ElementId("Target");
    private static final int VALUE_LENGTH_COUNTED = 2;
    /// The local name of the event-logging root element. A target whose root has this name is already a
    /// whole document, where the target schema names no root of its own.
    private static final String EVENTS_ROOT = "Events";
    private static final String EVENTS_VERSION = "3.0.0";
    private static final String EVENTS_SYSTEM_ID = "file://event-logging-v3.0.0.xsd";
    /// Matches the first start tag in a piece of XML, capturing its local name without any prefix.
    private static final Pattern ROOT = Pattern.compile("\\s*<(?:[\\w.-]+:)?([\\w.-]+)[\\s/>]");

    private TargetChecks() {
    }

    /// Returns one record of each kind the split produced, in the order first seen, and at most
    /// [#REPRESENTATIVES]. A text record's kind is its text skeleton, with values reduced to their character
    /// classes. An XML record's kind is its element skeleton with its naming attributes ([RecordSkeleton]),
    /// because records in a generic vocabulary (elements all named `Data`, or a JSON map) are told apart by
    /// the names in their attributes.
    ///
    /// @param records The records the split produced.
    /// @return One representative record per kind.
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

    /// Returns the elements of a document with this local name, each as its text: the records, where that
    /// element is one record. Only the outermost occurrences count, because an element of the same name inside
    /// another is part of that record, not a record of its own.
    ///
    /// @param document The parsed document.
    /// @param name     The local name of the record element.
    /// @return The records' text.
    public static List<String> elementsNamed(final OutputRecords document, final String name) {
        return document.recordsBy(RecordBoundary.ofElement(name)).stream().map(XdmNode::toString).toList();
    }

    /// Returns the records of JSON in which every top-level value is one record ([Boundary#ROOT]). These are
    /// the root map's children, or, where the document is one top-level array, that array's items, because
    /// the parser puts such a document in a keyless array under the root map, which no key could name.
    ///
    /// @param document The JSON parser's XML output, parsed.
    /// @return The records' text.
    public static List<String> rootRecords(final OutputRecords document) {
        return document.recordsBy(RecordBoundary.ofArray(RecordBoundary.ROOT)).stream()
                .map(XdmNode::toString)
                .toList();
    }

    /// Returns the items of the JSON array with this key, in the XSL/json vocabulary the parser produces,
    /// each as its text: the records, where that array holds them. Only the outermost such array counts.
    ///
    /// @param document The JSON parser's XML output, parsed.
    /// @param key      The array's key.
    /// @return The records' text.
    public static List<String> arrayItems(final OutputRecords document, final String key) {
        return document.recordsBy(RecordBoundary.ofArray(key)).stream().map(XdmNode::toString).toList();
    }

    /// Returns the text content of an XML record, for comparing with the document's text in the wholeness
    /// check. A record that will not parse is returned as it is.
    ///
    /// @param record The record's XML.
    /// @return Its text content.
    public static String textOf(final String record) {
        return OutputRecords.parse(record)
                .map(parsed -> parsed.root().getStringValue())
                .orElse(record);
    }

    private static List<XdmNode> occurrences(final OutputRecords document, final String name) {
        return document.recordsBy(RecordBoundary.ofElement(name));
    }

    /// Checks whether a named element is a good record boundary. It must not be the root; if it occurs only
    /// once it must not be a container of a repeated structured child; and its occurrences together must hold
    /// nearly all of the document's text, as a split's records must. The caller checks first that it occurs
    /// at all.
    ///
    /// @param document  The sample, parsed.
    /// @param name      The element's local name.
    /// @param wholeness What share of the document's text the records must hold between them.
    /// @return The shortfall, empty where the element will do.
    public static Optional<StoredError> recordElement(final OutputRecords document,
                                                     final String name,
                                                     final double wholeness) {
        if (name.equals(document.root().getNodeName().getLocalName())) {
            return Optional.of(new StoredError(Severity.ERROR, null, TARGET, name + " is the root, which holds every "
                                                                             + "record; name the element that is one "
                                                                             + "record"));
        }
        final List<XdmNode> found = occurrences(document, name);
        if (found.size() == 1) {
            // One occurrence holding a repeated child with structure of its own is the records' container, not
            // a record. A repeated leaf child (a field, such as <data> in a one-record sample) does not count.
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
        final long documentChars = text(document.root());
        final long recordChars = found.stream().mapToLong(TargetChecks::text).sum();
        if (documentChars > 0 && (double) recordChars / documentChars < wholeness) {
            return Optional.of(new StoredError(Severity.ERROR, null, TARGET, "The " + found.size() + " element(s) "
                    + "named " + name + " hold " + recordChars + " of the document's " + documentChars
                    + " characters; the element that is one record holds nearly all of them between its "
                    + "occurrences"));
        }
        return Optional.empty();
    }

    /// Checks whether the children of a named element are a good record boundary. This is for a feed that
    /// mixes several record types as siblings under one container, where no single element name describes
    /// a record.
    ///
    /// Unlike [#recordElement], this does **not** refuse a container that occurs once. A container that
    /// repeats in the stream may well appear only once in a sample, because the sample is cut from the start
    /// of the stream, so refusing it would make such feeds impossible to learn.
    ///
    /// @param document  The sample, parsed.
    /// @param parent    The container's local name, or [RecordBoundary#ROOT] for the document's own root.
    /// @param wholeness What share of the document's text the records must hold between them.
    /// @return The shortfall, empty where the children will do.
    public static Optional<StoredError> recordChildren(final OutputRecords document,
                                                       final String parent,
                                                       final double wholeness) {
        final boolean ofRoot = RecordBoundary.ROOT.equalsIgnoreCase(parent)
                               || parent.equals(document.root().getNodeName().getLocalName());
        final List<XdmNode> found = document.recordsBy(ofRoot
                ? RecordBoundary.ofChildrenOf(RecordBoundary.ROOT)
                : RecordBoundary.ofChildrenOf(parent));
        if (found.isEmpty()) {
            return Optional.of(new StoredError(Severity.ERROR, null, TARGET, ofRoot
                    ? "The root holds no element children to be records"
                    : "No element named " + parent + " holds any element children here; name the element "
                      + "whose children are the records"));
        }
        if (found.size() == 1) {
            // A single child means the named element is a wrapper, not a container of records. The records
            // are inside it, so the model is told to look one level deeper.
            return Optional.of(new StoredError(Severity.ERROR, null, TARGET, (ofRoot
                    ? "The root"
                    : parent) + " holds one element child, so its children are not a sequence of records; "
                    + "name the element whose children repeat, or the element that is one record"));
        }
        final long documentChars = text(document.root());
        final long recordChars = found.stream().mapToLong(TargetChecks::text).sum();
        if (documentChars > 0 && (double) recordChars / documentChars < wholeness) {
            return Optional.of(new StoredError(Severity.ERROR, null, TARGET, "The " + found.size()
                    + " children of " + parent + " hold " + recordChars + " of the document's "
                    + documentChars + " characters; the records hold nearly all of them between them"));
        }
        return Optional.empty();
    }

    /// @param node A node.
    /// @return How many non-whitespace characters its text holds.
    private static long text(final XdmNode node) {
        return node.getStringValue().chars().filter(c -> !Character.isWhitespace(c)).count();
    }

    /// Counts how many of the records are of the same kind as a representative.
    ///
    /// @param records        The records.
    /// @param representative The representative record.
    /// @return How many are of its kind.
    public static int count(final List<String> records, final String representative) {
        final String kind = kindOf(representative);
        return (int) records.stream()
                .filter(record -> record != null && !record.isBlank())
                .filter(record -> kind.equals(kindOf(record)))
                .count();
    }

    /// Returns the kind of one record, by which representatives are chosen and counted: the text skeleton of
    /// a text record, or the record skeleton of an XML record.
    ///
    /// @param record The record.
    /// @return A key that is the same for records of the same kind.
    public static String kindOf(final String record) {
        return RecordSkeleton.isMarkup(record)
                ? RecordSkeleton.recordSkeleton(record)
                : RecordSkeleton.textSkeleton(record);
    }

    /// Checks whether a split emitted the input whole: the record texts together must contain nearly every
    /// non-whitespace character of the input. A split that keeps only the first line of each block passes
    /// the coverage check, because the block was consumed, but fails this one.
    ///
    /// @param input       The input that was split.
    /// @param recordTexts The text of each record.
    /// @param threshold   The share of the input's characters the records must hold.
    /// @return The shortfall, empty where the records hold the input.
    public static Optional<StoredError> wholeness(final String input,
                                                 final List<String> recordTexts,
                                                 final double threshold) {
        final long inputChars = input.chars().filter(c -> !Character.isWhitespace(c)).count();
        final long recordChars = recordTexts.stream()
                .mapToLong(text -> text.chars().filter(c -> !Character.isWhitespace(c)).count())
                .sum();
        if (inputChars == 0 || (double) recordChars / inputChars >= threshold) {
            return Optional.empty();
        }
        return Optional.of(new StoredError(Severity.ERROR, null, TARGET, "The split emitted " + recordChars + " of "
                                                                          + inputChars + " characters as record text; "
                                                                          + "each record must be emitted whole, as one "
                                                                          + "field, however many lines it spans"));
    }

    /// Turns a proposed target into a whole document the scorers can judge, by wrapping the event in the
    /// event-logging `Events` root where the model gave the event alone.
    ///
    /// Whether it is already a document is decided by its **root element's local name**, not by whether the
    /// text contains `<Events` somewhere. A substring test would wrap a prefixed root such as `<evt:Events>`
    /// a second time, and would leave unwrapped an event that has those characters in an attribute or a
    /// comment.
    ///
    /// This form always uses event-logging's root; [#asDocument(String, TargetSchema)] uses the root of
    /// another target schema.
    ///
    /// @param event What the model gave.
    /// @return The event as a document.
    public static String asDocument(final String event) {
        return asDocument(event, null);
    }

    /// The same, wrapping the event in the root of the schema the document's translations must produce.
    ///
    /// @param event  The proposed event, which may already be a whole document.
    /// @param target The target schema, or null where the document names none, in which case the wrapper
    ///               is event-logging 3.0.0.
    /// @return The event as a document.
    public static String asDocument(final String event, final TargetSchema target) {
        final String root = target == null || target.rootElement() == null
                ? EVENTS_ROOT
                : target.rootElement();
        String stripped = event.strip();
        if (stripped.startsWith("<?xml")) {
            final int end = stripped.indexOf("?>");
            stripped = end < 0
                    ? stripped
                    : stripped.substring(end + 2).strip();
        }
        final Matcher opened = ROOT.matcher(stripped);
        return opened.lookingAt() && root.equals(opened.group(1))
                ? stripped
                : open(root, target) + stripped + "</" + root + ">";
    }

    // Returns the start tag a proposed event is wrapped in: the target's root, namespace, schema location and
    // version, or event-logging 3.0.0's where the document names no target.
    private static String open(final String root, final TargetSchema target) {
        final String namespace = target == null
                ? TargetSchema.EVENT_LOGGING
                : target.namespaceURI();
        final String schemaLocation = target == null
                ? TargetSchema.EVENT_LOGGING + " " + EVENTS_SYSTEM_ID
                : target.schemaLocation();
        final String version = target == null || target.version() == null
                ? EVENTS_VERSION
                : target.version();
        return "<" + root + " xmlns=\"" + namespace + "\" "
               + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
               + "xsi:schemaLocation=\"" + schemaLocation + "\" "
               + "Version=\"" + version + "\">";
    }

    /// Judges whether a proposed target is an event worth aiming at. It must parse as exactly one event, and
    /// it is then judged by the document's scorers of meaning (schema conformance, extraction quality,
    /// business rules) over the wrapped document, as a transform's output would be. The stream-level scorers
    /// do not apply to one event on its own, and a document that does not parse is refused rather than
    /// passed unscored.
    ///
    /// @param scorecard The document's scorers.
    /// @param record    The record the target is for.
    /// @param document  The proposed target, wrapped as a document.
    /// @return The shortfalls, empty where the target is accepted.
    public static List<StoredError> judge(final Scorecard scorecard, final String record, final String document) {
        final Optional<StoredError> refusal = refusal(document, scorecard.target());
        if (refusal.isPresent()) {
            return List.of(refusal.get());
        }
        final Verdict verdict = verdict(scorecard, record, document);
        return Scorecard.outcome(verdict) == StepOutcome.PASSED
                ? List.of()
                : verdict.feedback();
    }

    /// Checks whether a proposed target can be judged at all. It cannot if it is not well-formed, or holds
    /// other than exactly one event.
    ///
    /// @param document The proposed target, wrapped as a document.
    /// @param target   The schema it must be in, which says which element to reply with; null for
    ///                 event-logging.
    /// @return The refusal, empty where the document is one event.
    public static Optional<StoredError> refusal(final String document, final TargetSchema target) {
        final Optional<OutputRecords> parsed = OutputRecords.parse(document);
        if (parsed.isEmpty() || parsed.get().records().size() != 1) {
            return Optional.of(new StoredError(Severity.FATAL_ERROR, null, TARGET, parsed.isEmpty()
                    ? "The event is not well-formed XML. Reply with " + replyWith(target) + " alone, without an "
                      + "XML declaration or " + wrapper(target)
                    : "The reply holds " + parsed.get().records().size() + " events; one record becomes one event"));
        }
        return Optional.empty();
    }

    // Names the element one record becomes, for the refusal message: event-logging's Event, or the target's
    private static String replyWith(final TargetSchema target) {
        if (TargetSchema.isEventLoggingOrNone(target)) {
            return "the Event element";
        }
        return TargetStructure.recordElement(target)
                .map(element -> "the " + element + " element")
                .orElse("the one record's element");
    }

    // Names the document's root, which the reply must leave out
    private static String wrapper(final TargetSchema target) {
        if (TargetSchema.isEventLoggingOrNone(target)) {
            return "an Events wrapper";
        }
        return target.rootElement() == null
                ? "a wrapper"
                : "a " + target.rootElement() + " wrapper";
    }

    /// Runs the scorers of meaning over one proposed event, as a transform's output would be judged.
    ///
    /// @param scorecard The document's scorers.
    /// @param record    The record the target is for.
    /// @param document  The proposed target, wrapped as a document.
    /// @return The verdict.
    public static Verdict verdict(final Scorecard scorecard, final String record, final String document) {
        return scorecard.meaning()
                .judge(new Attempted("Target", false, record, new StepResult(document, List.of())));
    }

    /// Checks whether the parser's records carry every value the targets need (the preservation check). Each
    /// text or attribute value of a target's event that appears in its source record must appear within the
    /// values of some emitted record. Constants supplied by the instructions are not in the source record,
    /// so they are not required.
    ///
    /// @param recordsDocument The parser's output.
    /// @param targets         The targets.
    /// @return The shortfalls, empty where every value is preserved.
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

    /// Checks whether the transform reproduced each target (the fidelity check): some emitted event must
    /// equal the target's event once both are in canonical form. Where none does, the produced event that
    /// agrees with the target for longest from the start is shown beside it, as the nearest.
    ///
    /// @param eventsDocument The transform's output.
    /// @param targets        The targets.
    /// @return The shortfalls, empty where every target is reproduced.
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

    /// Returns the event's values that came from the record: every text and attribute value of the event, of
    /// at least two characters, that occurs within one of the record's values. Where the record is XML, its
    /// values are its text and attribute values, never its element or attribute names, so a JSON key that the
    /// event repeats (as a field name, for example) is not required of the parser. Where the record is text,
    /// the whole record is its one value.
    ///
    /// @param target The target, which must have an event.
    /// @return The values the parser's records must carry.
    static Set<String> sourceValues(final Target target) {
        final Set<String> recordValues = recordValues(target.record());
        final Set<String> values = new TreeSet<>();
        OutputRecords.parse(asDocument(target.event().orElseThrow())).ifPresent(document -> {
            for (final XdmNode event : document.records()) {
                document.evaluate(event, "descendant::text() | descendant::*/@*").forEach(item -> {
                    final String value = item.getStringValue().strip();
                    if (value.length() >= VALUE_LENGTH_COUNTED
                        && recordValues.stream().anyMatch(recordValue -> recordValue.contains(value))) {
                        values.add(value);
                    }
                });
            }
        });
        return values;
    }

    // Returns the values a record carries: its text and attribute values where it is XML that parses, or else
    // the record itself.
    private static Set<String> recordValues(final String record) {
        return OutputRecords.parse(record)
                .map(parsed -> markupValues(parsed, parsed.root()))
                .orElse(Set.of(record));
    }

    // Returns the values the parser emitted in one of its records.
    private static Set<String> dataValues(final OutputRecords records, final XdmNode record) {
        return markupValues(records, record);
    }

    // Returns the text and attribute values in and under an element. A `key` attribute of the XSL/json
    // vocabulary is a field's name (as `RecordSkeleton` treats it), so it is not a value. It is recognised by
    // its element's local name, because a record cut from the parser's output may not declare the
    // vocabulary's namespace.
    private static Set<String> markupValues(final OutputRecords records, final XdmNode element) {
        final Set<String> values = new TreeSet<>();
        records.evaluate(element, "descendant::text() | descendant-or-self::*/@*[not(local-name() = 'key' "
                                  + "and local-name(..) = ('map', 'array', 'string', 'number', 'boolean', "
                                  + "'null'))]")
                .forEach(item -> values.add(item.getStringValue()));
        return values;
    }

    /// Returns an element in a canonical one-line form: every element and attribute named by namespace and
    /// local name, attributes sorted, whitespace-only text dropped and other text trimmed. Two trees that say
    /// the same thing then compare equal, whatever prefixes, declarations or indentation each one used.
    ///
    /// @param node The element.
    /// @return Its canonical form.
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

    // Returns how many characters two canonical forms agree on from the start. The produced event that agrees
    // longest is the nearest to the target, and the point where they differ is where the transform went
    // wrong.
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
