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

package stroom.learningflow.fragment;

import stroom.learningflow.scoring.DeclaredSkips;
import stroom.learningflow.scoring.Verdict;
import stroom.pipeline.xml.event.Event;
import stroom.pipeline.xml.event.simple.SimpleEventList;
import stroom.pipeline.xml.event.simple.SimpleEventListBuilder;
import stroom.util.xml.XMLUtil;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

/// Applies the bar at the end of a fragment's chain during a serving run. Each record of the output is
/// judged as it is produced and passed downstream only if it clears the bar. With a judge that stops (as
/// a processing task's does), the first record that falls short stops the run by throwing a
/// [RecordShortfall]. A record is one child of the output's root element. Its events are held until it
/// has been judged, and nothing larger than a record is ever held. The root's opening is sent downstream
/// just before the first record that passes, so a run whose first record falls short sends nothing at all.
///
/// A fragment that splits its input gives its transform one record at a time, so the end of the chain
/// emits one document per record, each wrapped in the root element. The gate treats them as one
/// document: the root is taken from the first, the records from all of them, and the root is closed
/// once, when [#finish] is called.
///
/// Declared skips ([DeclaredSkips]) are counted and dropped here, so nothing downstream sees them.
public final class RecordGate implements ContentHandler {

    private final RecordJudge judge;
    private final ContentHandler downstream;
    private final IntFunction<Optional<String>> parsedAt;
    /// The document's opening: everything up to and including the root's start tag.
    private final SimpleEventListBuilder opening = new SimpleEventListBuilder();
    private SimpleEventListBuilder record;
    private int depth;
    private boolean seenRoot;
    private boolean opened;
    private int records;
    private int skips;
    private final List<String> skipReasons = new ArrayList<>();
    /// How many elements deep inside a declared skip marker the run is, or zero where it is not in one.
    private int skipping;
    private double totals;
    private String rootUri;
    private String rootLocalName;
    private String rootQName;
    private final List<String> prefixes = new ArrayList<>();

    /// @param judge      Applies the bar to each record.
    /// @param downstream Where records that clear the bar go; null where nothing is to be passed on.
    /// @param parsedAt   Gets the parser's version of a record from the output record's index, for the
    ///                   judge to ask for; returns empty where the runner cannot provide it.
    public RecordGate(final RecordJudge judge,
                      final ContentHandler downstream,
                      final IntFunction<Optional<String>> parsedAt) {
        this.judge = judge;
        this.downstream = downstream;
        this.parsedAt = parsedAt;
    }

    /// How many records were judged, including the one that fell short.
    public int records() {
        return records;
    }

    /// How many records the translation declared it deliberately skipped ([DeclaredSkips]). A skip is not
    /// an event and is not judged. It is counted so that the completeness check can add it to the events,
    /// and shown so that a person can see how much of a stream was skipped rather than translated.
    public int skips() {
        return skips;
    }

    /// The reasons the translation gave for each record it skipped, leaving out markers that gave no
    /// reason.
    public List<String> skipReasons() {
        return List.copyOf(skipReasons);
    }

    /// The mean of the judged records' weighted totals, or zero where none was.
    public double meanTotal() {
        return records == 0
                ? 0.0
                : totals / records;
    }

    @Override
    public void setDocumentLocator(final Locator locator) {
        // The locator belongs to the fragment, not the downstream, so it is not passed on.
    }

    @Override
    public void startDocument() throws SAXException {
        if (!seenRoot) {
            opening.startDocument();
        }
    }

    @Override
    public void endDocument() {
        // A split fragment emits one document per record, so this is ignored; the single document the
        // downstream receives is ended by finish().
    }

    /// Called when the run is over. It closes the root element and the document downstream, if anything
    /// was passed on.
    ///
    /// @throws SAXException If the downstream fails.
    public void finish() throws SAXException {
        if (opened) {
            downstream.endElement(rootUri, rootLocalName, rootQName);
            for (int i = prefixes.size() - 1; i >= 0; i--) {
                downstream.endPrefixMapping(prefixes.get(i));
            }
            downstream.endDocument();
        }
    }

    @Override
    public void startPrefixMapping(final String prefix, final String uri) throws SAXException {
        if (skipping > 0 || DeclaredSkips.NAMESPACE.equals(uri)) {
            // The skip marker's namespace is never declared on the output the downstream receives.
            return;
        }
        if (record != null) {
            record.startPrefixMapping(prefix, uri);
        } else if (depth == 0 && !seenRoot) {
            prefixes.add(prefix);
            opening.startPrefixMapping(prefix, uri);
        }
    }

    @Override
    public void endPrefixMapping(final String prefix) throws SAXException {
        if (record != null && skipping == 0) {
            record.endPrefixMapping(prefix);
        }
        // The root's own mappings are ended with the root, in finish().
    }

    @Override
    public void startElement(final String uri, final String localName, final String qName, final Attributes atts)
            throws SAXException {
        depth++;
        if (depth == 1) {
            if (!seenRoot) {
                rootUri = uri;
                rootLocalName = localName;
                rootQName = qName;
                opening.startElement(uri, localName, qName, atts);
                seenRoot = true;
            }
        } else if (skipping > 0) {
            // Inside a declared skip: none of it is kept, judged or passed on.
            skipping++;
        } else if (depth == 2 && DeclaredSkips.isMarker(uri, localName)) {
            skipping = 1;
            skips++;
            final String reason = DeclaredSkips.reason(atts);
            if (reason != null) {
                skipReasons.add(reason);
            }
        } else {
            if (depth == 2) {
                record = new SimpleEventListBuilder();
            }
            record.startElement(uri, localName, qName, atts);
        }
    }

    @Override
    public void endElement(final String uri, final String localName, final String qName) throws SAXException {
        if (skipping > 0) {
            skipping--;
        } else if (depth >= 2) {
            record.endElement(uri, localName, qName);
            if (depth == 2) {
                final List<Event> events = ((SimpleEventList) record.getEventList()).getEvents();
                record = null;
                judged(events);
            }
        }
        depth--;
    }

    @Override
    public void characters(final char[] ch, final int start, final int length) throws SAXException {
        if (record != null && skipping == 0) {
            record.characters(ch, start, length);
        }
        // Text between records (e.g. the whitespace of an indented document) belongs to no record and is
        // dropped.
    }

    @Override
    public void ignorableWhitespace(final char[] ch, final int start, final int length) throws SAXException {
        if (record != null && skipping == 0) {
            record.ignorableWhitespace(ch, start, length);
        }
    }

    @Override
    public void processingInstruction(final String target, final String data) throws SAXException {
        if (record != null) {
            record.processingInstruction(target, data);
        }
    }

    @Override
    public void skippedEntity(final String name) throws SAXException {
        if (record != null) {
            record.skippedEntity(name);
        }
    }

    /// Judges a completed record as a document of its own, then either passes it downstream or, where it
    /// falls short and the judge stops, stops the run.
    private void judged(final List<Event> events) throws SAXException {
        final int index = records;
        final String document = alone(events);
        final Verdict verdict = judge.judge(index, document, () -> parsedAt.apply(index));
        records++;
        totals += verdict.score();
        if (!RecordJudge.clears(verdict)) {
            if (judge.stops()) {
                throw new RecordShortfall(index, verdict, document);
            }
            // A full pass carries on: the record is not passed on, and the judge has kept what it needs.
            return;
        }
        if (downstream != null) {
            if (!opened) {
                fire(opening, downstream);
                opened = true;
            }
            for (final Event event : events) {
                event.fire(downstream);
            }
        }
    }

    /// Serialises the record as a document of its own: the output's opening, the record, and the root
    /// closed.
    private String alone(final List<Event> events) throws SAXException {
        try {
            final TransformerHandler handler = XMLUtil.createTransformerHandler(false);
            final StringWriter writer = new StringWriter();
            handler.setResult(new StreamResult(writer));
            fire(opening, handler);
            for (final Event event : events) {
                event.fire(handler);
            }
            handler.endElement(rootUri, rootLocalName, rootQName);
            handler.endDocument();
            return writer.toString();
        } catch (final TransformerConfigurationException e) {
            throw new SAXException(e);
        }
    }

    private static void fire(final SimpleEventListBuilder events, final ContentHandler handler) throws SAXException {
        for (final Event event : ((SimpleEventList) events.getEventList()).getEvents()) {
            event.fire(handler);
        }
    }
}
