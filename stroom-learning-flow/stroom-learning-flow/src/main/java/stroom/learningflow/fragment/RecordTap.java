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

/// Receives the parser's output during a serving run (via [FragmentTapFilter]) and holds the record
/// currently being transformed, so that when that record's output falls short of the bar the record
/// itself, as the parser produced it, can be shown to a learning session as a document of its own. It
/// also counts the records the parser produced. Nothing larger than one record is held.
///
/// The split filter passes a record to the transform as soon as the record's last event reaches it, and
/// the transform's output is judged at once. That happens before the next record's events arrive, and
/// possibly before the tap (which sits beside the split as a second child of the parser) has been told
/// the record ended. So at a judgement, the tap shows the record it is in the middle of, if any, closing
/// any open elements; at the record's last event, that is the whole record.
public final class RecordTap implements ContentHandler {

    /// The SAX element depth of a record: one more than the number of elements above it, which is the
    /// split depth a `SplitFilter` is set to. A child of the root is at depth 2.
    private final int recordDepth;
    private int records;
    private final SimpleEventListBuilder opening = new SimpleEventListBuilder();
    private SimpleEventListBuilder record;
    private List<Event> last;
    private int depth;
    private boolean seenRoot;
    private String rootUri;
    private String rootLocalName;
    private String rootQName;
    /// The elements open within the record being built, used to close a record that is only partly read.
    private final Deque<String[]> open = new ArrayDeque<>();

    /// @param splitDepth How deep a record sits, as `RecordBoundary#splitDepth` counts it, i.e. the number
    ///                   of elements above it. One for the children of a root; three for the items of an
    ///                   array under a key.
    public RecordTap(final int splitDepth) {
        this.recordDepth = splitDepth + 1;
    }

    /// How many records the parser produced. A served stream must account for every one of them, as
    /// either an event or a declared skip.
    public int records() {
        return records;
    }

    /// The record currently being read or, where none is open, the most recently completed record, as a
    /// document of its own; empty where there has been none.
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
        // Not needed.
    }

    @Override
    public void startDocument() throws SAXException {
        if (!seenRoot) {
            opening.startDocument();
        }
    }

    @Override
    public void endDocument() {
        // The parser emits one document, and nothing needs doing at its end.
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
        } else if (depth >= recordDepth) {
            if (depth == recordDepth) {
                record = new SimpleEventListBuilder();
            }
            record.startElement(uri, localName, qName, atts);
            open.push(new String[]{uri, localName, qName});
        }
    }

    @Override
    public void endElement(final String uri, final String localName, final String qName) throws SAXException {
        if (depth >= recordDepth) {
            record.endElement(uri, localName, qName);
            open.pop();
            if (depth == recordDepth) {
                last = ((SimpleEventList) record.getEventList()).getEvents();
                record = null;
                records++;
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
