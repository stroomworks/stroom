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

package stroom.learningflow.extraction;

import stroom.learningflow.scoring.ConfinedXml;
import stroom.pipeline.filter.AbstractXMLFilter;
import stroom.pipeline.filter.SplitFilter;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.transform.sax.TransformerHandler;

/// Splits a document into the documents a fragment's `SplitFilter` gives its transform: one per record, each
/// with the parent element structure above it, exactly as the pipeline produces them. [PerRecord] uses it to
/// run a step one record at a time.
///
/// It uses Stroom's own `SplitFilter` rather than a copy of its logic, so that what is scored is what will
/// run. A stylesheet that reads the whole document (counting its siblings, or asking for its position)
/// behaves differently when it is given one record at a time, and the promotion gate must see that
/// difference before it promotes.
public final class RecordSplit {

    private RecordSplit() {
    }

    /// Splits a document into one document per record.
    ///
    /// @param document The XML document, as the element before the filter produced it.
    /// @param depth    The element depth the records sit at, as recorded in the binding's record boundary.
    /// @return One document per record, in order, or an *empty* list if nothing sits at that depth or the
    /// document will not parse. The filter emits the structure above the records whether it found any or
    /// not, so a document with nothing at the depth would otherwise come back as one empty envelope, and the
    /// chain would be run over an empty document. A stream with exactly one record comes back as one
    /// document. "One record" and "no records" must give different answers, or a stream with a single record
    /// would be learned from the whole document and then served one record at a time.
    public static List<String> split(final String document, final int depth) {
        final List<String> records = new ArrayList<>();
        final Capture capture = new Capture(records, depth);
        final SplitFilter split = new SplitFilter();
        split.setSplitDepth(depth);
        split.setSplitCount(1);
        split.setStoreLocations(false);
        split.setTarget(capture);
        split.startProcessing();
        try {
            split.startStream();
            final XMLReader reader = ConfinedXml.reader();
            reader.setContentHandler(split);
            reader.parse(new InputSource(new StringReader(document)));
            split.endStream();
        } catch (final Exception e) {
            // A document that will not parse cannot be split. The caller then runs the chain over the whole
            // of it, and the scorers report whatever is wrong with it.
            return List.of();
        } finally {
            split.endProcessing();
        }
        return List.copyOf(records);
    }


    // --------------------------------------------------------------------------------


    // Writes each document the filter emits back out as text, because step runners take their input as
    // text.
    private static final class Capture extends AbstractXMLFilter {

        private final List<String> records;
        private final int depth;
        private TransformerHandler handler;
        private StringWriter writing;
        private int open;
        private boolean cut;

        private Capture(final List<String> records, final int depth) {
            this.records = records;
            this.depth = depth;
        }

        @Override
        public void startDocument() throws SAXException {
            writing = new StringWriter();
            // Use Saxon's serialiser, as every other transform in this module does, so that all output follows
            // one set of serialisation rules.
            handler = ConfinedXml.serialiser(writing, false, true);
            handler.startDocument();
            open = 0;
            cut = false;
        }

        @Override
        public void endDocument() throws SAXException {
            handler.endDocument();
            // Keep the document only if something was found at the depth. The filter emits the structure
            // above the records whether there were any or not, and an empty envelope is not a record.
            if (cut) {
                records.add(writing.toString());
            }
            handler = null;
            writing = null;
        }

        @Override
        public void startPrefixMapping(final String prefix, final String uri) throws SAXException {
            handler.startPrefixMapping(prefix, uri);
        }

        @Override
        public void endPrefixMapping(final String prefix) throws SAXException {
            handler.endPrefixMapping(prefix);
        }

        @Override
        public void startElement(final String uri, final String localName, final String qName,
                                 final Attributes atts) throws SAXException {
            open++;
            cut = cut || open == depth + 1;
            handler.startElement(uri, localName, qName, atts);
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName)
                throws SAXException {
            open--;
            handler.endElement(uri, localName, qName);
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) throws SAXException {
            handler.characters(ch, start, length);
        }

        @Override
        public void ignorableWhitespace(final char[] ch, final int start, final int length)
                throws SAXException {
            handler.ignorableWhitespace(ch, start, length);
        }

        @Override
        public void processingInstruction(final String target, final String data) throws SAXException {
            handler.processingInstruction(target, data);
        }

        @Override
        public void skippedEntity(final String name) throws SAXException {
            handler.skippedEntity(name);
        }
    }
}
