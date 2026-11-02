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

package stroom.shapeshifter.ai.fragment;

import stroom.pipeline.xml.event.Event;
import stroom.pipeline.xml.event.simple.SimpleEventList;
import stroom.pipeline.xml.event.simple.SimpleEventListBuilder;
import stroom.shapeshifter.ai.scoring.Verdict;
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

/// The bar at the chain's tail (design 04 §3): each record of the output is judged as it is produced and
/// handed on only if it clears, and the first that does not stops the run. A record is one child of the
/// output's root; it is held — a few events — until its verdict, and nothing larger than a record is ever
/// held. The root's opening is replayed before the first record that passes, so that a run whose first
/// record falls short hands the downstream nothing at all.
///
/// A fragment that splits its input gives its transform one record at a time, and the tail then emits
/// one document per record — each with the root around it. The gate reads them as one document: the
/// root is taken from the first and the records from all of them, and the root is closed once, when the
/// run is [#finish]ed.
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
    private double totals;
    private String rootUri;
    private String rootLocalName;
    private String rootQName;
    private final List<String> prefixes = new ArrayList<>();

    /// @param judge      The bar.
    /// @param downstream Where records that clear it go; null where nothing is to be handed on.
    /// @param parsedAt   The record as the parser cut it, by the output record's index, for the judge to
    ///                   ask for; empty where the runner cannot say.
    public RecordGate(final RecordJudge judge,
                      final ContentHandler downstream,
                      final IntFunction<Optional<String>> parsedAt) {
        this.judge = judge;
        this.downstream = downstream;
        this.parsedAt = parsedAt;
    }

    /// How many records were judged, the short one included.
    public int records() {
        return records;
    }

    /// The mean of the judged records' weighted totals, or zero where none was.
    public double meanTotal() {
        return records == 0
                ? 0.0
                : totals / records;
    }

    @Override
    public void setDocumentLocator(final Locator locator) {
        // The locator is the fragment's, not the downstream's: not passed on.
    }

    @Override
    public void startDocument() throws SAXException {
        if (!seenRoot) {
            opening.startDocument();
        }
    }

    @Override
    public void endDocument() {
        // One document per record is how a split fragment emits; the one document the downstream gets
        // ends when the run does.
    }

    /// The run is over: the root is closed for the downstream, where anything was handed on.
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
        if (record != null) {
            record.startPrefixMapping(prefix, uri);
        } else if (depth == 0 && !seenRoot) {
            prefixes.add(prefix);
            opening.startPrefixMapping(prefix, uri);
        }
    }

    @Override
    public void endPrefixMapping(final String prefix) throws SAXException {
        if (record != null) {
            record.endPrefixMapping(prefix);
        }
        // The root's own mappings end with the root, at the end of the document.
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
        } else {
            if (depth == 2) {
                record = new SimpleEventListBuilder();
            }
            record.startElement(uri, localName, qName, atts);
        }
    }

    @Override
    public void endElement(final String uri, final String localName, final String qName) throws SAXException {
        if (depth >= 2) {
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
        if (record != null) {
            record.characters(ch, start, length);
        }
        // Text between records — the whitespace of an indented document — is nobody's.
    }

    @Override
    public void ignorableWhitespace(final char[] ch, final int start, final int length) throws SAXException {
        if (record != null) {
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

    /// One record complete: judged as a document of its own, then handed on or the run stopped.
    private void judged(final List<Event> events) throws SAXException {
        final int index = records;
        final String document = alone(events);
        final Verdict verdict = judge.judge(index, document, () -> parsedAt.apply(index));
        records++;
        totals += verdict.weightedTotal();
        if (!RecordJudge.clears(verdict)) {
            if (judge.stops()) {
                throw new RecordShortfall(index, verdict, document);
            }
            // A full pass goes on: the record is not handed on, and the judge has kept what it needs.
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

    /// The record as a document of its own: the output's opening, the record, and the root closed.
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
