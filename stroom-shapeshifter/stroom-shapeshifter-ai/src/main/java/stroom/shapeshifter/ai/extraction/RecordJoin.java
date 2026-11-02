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

import stroom.shapeshifter.ai.scoring.ConfinedXml;

import net.sf.saxon.TransformerFactoryImpl;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

/// What a transform wrote for each record, back as one document (§12 item 25).
///
/// A pipeline does not join these: each record's output goes downstream on its own and the writer
/// segments them into a stream. This is for what comes *after* running — the scorers, the goldens and
/// what a person reads — which want one document, and the events in it are the same events in the same
/// order. The first record's root is the document's root; each record's output contributes its root's
/// children.
public final class RecordJoin {

    private RecordJoin() {
    }

    /// A stream of one record goes through the same parse as a stream of many, and is not handed back
    /// as it came. Null means "this step produced nothing that is a document", and a caller reads it as
    /// the reason to stop and say so ([PerRecord]); short-circuiting a single record past the parse let
    /// a stylesheet that wrote bare text report success with non-XML output and no diagnostic at all —
    /// the blind re-ask that check exists to stop, met on the one stream shape where it is easiest to
    /// miss.
    ///
    /// @param documents What the transform wrote, one per record, in order.
    /// @return The one document they make between them, or null where there is nothing to join or a
    /// piece of it will not parse — which the scorers see as a step that produced nothing.
    public static String join(final List<String> documents) {
        final List<String> written = documents.stream()
                .filter(document -> document != null && !document.isBlank())
                .toList();
        if (written.isEmpty()) {
            return null;
        }
        final StringWriter writing = new StringWriter();
        try {
            final TransformerHandler handler = newHandler(writing);
            handler.startDocument();
            for (int i = 0; i < written.size(); i++) {
                final XMLReader reader = ConfinedXml.reader();
                reader.setContentHandler(new Contents(handler, i == 0, i == written.size() - 1));
                reader.parse(new InputSource(new StringReader(written.get(i))));
            }
            handler.endDocument();
        } catch (final Exception e) {
            return null;
        }
        return writing.toString();
    }

    private static TransformerHandler newHandler(final StringWriter writing)
            throws TransformerConfigurationException {
        final SAXTransformerFactory factory = new TransformerFactoryImpl();
        final TransformerHandler handler = factory.newTransformerHandler();
        // Laid out as the transform would have laid out the one document, had it been given the stream
        // whole: the same serialiser with the same indentation, so that what a chain run per record
        // produces reads the same as what the chain run over the whole text did, and a golden written
        // against the one holds against the other.
        handler.getTransformer().setOutputProperty(OutputKeys.INDENT, "yes");
        handler.getTransformer().setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        handler.setResult(new StreamResult(writing));
        return handler;
    }


    // --------------------------------------------------------------------------------


    /// One document's contents forwarded into the joined one: the first keeps its root's start, the last
    /// its root's end, and every document contributes what is inside it.
    private static final class Contents extends DefaultHandler {

        private final TransformerHandler joined;
        private final boolean first;
        private final boolean last;
        private int depth;

        private Contents(final TransformerHandler joined, final boolean first, final boolean last) {
            this.joined = joined;
            this.first = first;
            this.last = last;
        }

        @Override
        public void startPrefixMapping(final String prefix, final String uri) throws SAXException {
            if (first) {
                joined.startPrefixMapping(prefix, uri);
            }
        }

        @Override
        public void endPrefixMapping(final String prefix) throws SAXException {
            if (last) {
                joined.endPrefixMapping(prefix);
            }
        }

        @Override
        public void startElement(final String uri, final String localName, final String qName,
                                 final Attributes atts) throws SAXException {
            depth++;
            if (depth > 1 || first) {
                joined.startElement(uri, localName, qName, atts);
            }
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName)
                throws SAXException {
            if (depth > 1 || last) {
                joined.endElement(uri, localName, qName);
            }
            depth--;
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) throws SAXException {
            // Everything inside the root, including the text directly in it: a record's own content is
            // not only its elements. The one exception is the whitespace directly under the root, which
            // is each piece's indentation and not content: the joined document is indented afresh.
            if (depth >= 1 && !(depth == 1 && blank(ch, start, length))) {
                joined.characters(ch, start, length);
            }
        }

        private static boolean blank(final char[] ch, final int start, final int length) {
            for (int i = start; i < start + length; i++) {
                if (!Character.isWhitespace(ch[i])) {
                    return false;
                }
            }
            return true;
        }
    }
}
