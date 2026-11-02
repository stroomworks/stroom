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
import stroom.util.xml.XMLUtil;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

/// The parser's output, one record at a time: the record being transformed, held until the next begins,
/// so that when the record's output falls short the record itself — as the parser cut it, as a document
/// of its own — can be shown to the session. Nothing larger than one record is held.
///
/// The split hands a record to the transform as the record's last event reaches it, and the transform's
/// output is judged there and then — before the next record's events arrive, and before the tap, which
/// sits beside the split on the parser's fork, has necessarily been told the record ended. So the record
/// the tap shows at a judgement is the one it is in the middle of, where it is in one: what it has of it,
/// closed, which at the record's last event is the whole record.
public final class RecordTap implements ContentHandler {

    private final SimpleEventListBuilder opening = new SimpleEventListBuilder();
    private SimpleEventListBuilder record;
    private List<Event> last;
    private int depth;
    private boolean seenRoot;
    private String rootUri;
    private String rootLocalName;
    private String rootQName;
    /// The elements open within the record being built, innermost last, for closing a record read mid-way.
    private final Deque<String[]> open = new ArrayDeque<>();

    /// The record being cut, or where none is open the record most recently cut, as a document of its
    /// own; empty where there has been none.
    public Optional<String> current() {
        final List<Event> events = record != null
                ? ((SimpleEventList) record.getEventList()).getEvents()
                : last;
        if (events == null) {
            return Optional.empty();
        }
        try {
            final TransformerHandler handler = XMLUtil.createTransformerHandler(false);
            final StringWriter writer = new StringWriter();
            handler.setResult(new StreamResult(writer));
            for (final Event event : ((SimpleEventList) opening.getEventList()).getEvents()) {
                event.fire(handler);
            }
            for (final Event event : events) {
                event.fire(handler);
            }
            if (record != null) {
                for (final Iterator<String[]> it = open.descendingIterator(); it.hasNext(); ) {
                    final String[] element = it.next();
                    handler.endElement(element[0], element[1], element[2]);
                }
            }
            handler.endElement(rootUri, rootLocalName, rootQName);
            handler.endDocument();
            return Optional.of(writer.toString());
        } catch (final SAXException | TransformerConfigurationException e) {
            return Optional.empty();
        }
    }

    @Override
    public void setDocumentLocator(final Locator locator) {
        // Not kept.
    }

    @Override
    public void startDocument() throws SAXException {
        if (!seenRoot) {
            opening.startDocument();
        }
    }

    @Override
    public void endDocument() {
        // The parser's one document; nothing to do.
    }

    @Override
    public void startPrefixMapping(final String prefix, final String uri) throws SAXException {
        if (record != null) {
            record.startPrefixMapping(prefix, uri);
        } else if (depth == 0 && !seenRoot) {
            opening.startPrefixMapping(prefix, uri);
        }
    }

    @Override
    public void endPrefixMapping(final String prefix) throws SAXException {
        if (record != null) {
            record.endPrefixMapping(prefix);
        }
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
            open.push(new String[]{uri, localName, qName});
        }
    }

    @Override
    public void endElement(final String uri, final String localName, final String qName) throws SAXException {
        if (depth >= 2) {
            record.endElement(uri, localName, qName);
            open.pop();
            if (depth == 2) {
                last = ((SimpleEventList) record.getEventList()).getEvents();
                record = null;
            }
        }
        depth--;
    }

    @Override
    public void characters(final char[] ch, final int start, final int length) throws SAXException {
        if (record != null) {
            record.characters(ch, start, length);
        }
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
}
