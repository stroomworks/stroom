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

package stroom.shapeshifter.ai.extraction;

import stroom.pipeline.filter.AbstractXMLFilter;
import stroom.pipeline.filter.SplitFilter;
import stroom.shapeshifter.ai.scoring.ConfinedXml;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

/// The documents a fragment's `SplitFilter` hands its transform: one per record, each with the parent
/// element structure replicated above it, exactly as the pipeline gives them (§12 item 25).
///
/// Driven by stroom's own `SplitFilter` rather than by a copy of what it does, because the point of
/// running the chain this way is that what is scored is what will run: a stylesheet that reads the whole
/// document — counting its siblings, or asking for its position — behaves differently when it is given
/// one record at a time, and the promotion gate must see that difference before it promotes.
public final class RecordSplit {

    private RecordSplit() {
    }

    /// @param document The parsed document, as the element before the filter produced it.
    /// @param depth    The element depth the records sit at, as the binding's boundary carries it.
    /// @return One document per record, in order — and *empty* where nothing sits at that depth. The
    /// filter emits the structure above the records whether it found any or not, so a document with
    /// nothing at the depth comes back as one empty envelope; that is not a record, and handing it on as
    /// one would run the chain over an empty document. A stream with exactly one record comes back as one
    /// document, and is run as the one record it is: "one record" and "no records" must not be the same
    /// answer, or a stream carrying a single record would be learned from the whole of its envelope and
    /// then run without it.
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
            // A document that will not parse is not one this can cut; the caller runs the chain over the
            // whole of it, and whatever is wrong with it is the scorers' to say.
            return List.of();
        } finally {
            split.endProcessing();
        }
        return List.copyOf(records);
    }


    // --------------------------------------------------------------------------------


    /// Each document the filter emits, written back out as text: the transform takes text, so the split
    /// has to be given back in the form the step runner reads.
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
            handler = newHandler(writing);
            handler.startDocument();
            open = 0;
            cut = false;
        }

        @Override
        public void endDocument() throws SAXException {
            handler.endDocument();
            // Only where something was actually found at the depth: the filter gives back the structure
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

        private static TransformerHandler newHandler(final StringWriter writing) throws SAXException {
            try {
                // Saxon's, as every other transform in this module uses: one processor family, one set
                // of serialisation rules.
                final SAXTransformerFactory factory = new net.sf.saxon.TransformerFactoryImpl();
                final TransformerHandler handler = factory.newTransformerHandler();
                handler.getTransformer().setOutputProperty(OutputKeys.INDENT, "no");
                handler.getTransformer().setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
                handler.setResult(new StreamResult(writing));
                return handler;
            } catch (final TransformerConfigurationException e) {
                throw new SAXException(e);
            }
        }
    }
}
