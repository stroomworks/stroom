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

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.transform.sax.TransformerHandler;

/// Joins the documents a transform wrote, one per record, back into one document. [PerRecord] uses it
/// after running a step one record at a time.
///
/// A pipeline does not join these: each record's output goes downstream on its own and the writer segments
/// them into a stream. The join is for what reads the result afterwards (the scorers, expected test outputs
/// and the person reviewing it), which want one document with the same events in the same order. The first
/// document's root becomes the root of the joined document, and each document contributes its root's
/// children.
public final class RecordJoin {

    private RecordJoin() {
    }

    /// Joins the documents into one.
    ///
    /// A single document is parsed like any number of them, rather than being returned as it came, so that
    /// output that is not XML (for example, bare text from a stylesheet whose templates match nothing) is
    /// caught even for a stream of one record. A null result tells the caller ([PerRecord]) that the step
    /// produced no document, so that it can tell the model why.
    ///
    /// @param documents What the transform wrote, one per record, in order.
    /// @return The joined document, or null if there is nothing to join or any piece will not parse. The
    /// scorers treat null as a step that produced nothing.
    public static String join(final List<String> documents) {
        final List<String> written = documents.stream()
                .filter(document -> document != null && !document.isBlank())
                .toList();
        if (written.isEmpty()) {
            return null;
        }
        final StringWriter writing = new StringWriter();
        try {
            // Use the same serialiser and indentation that the transform would use for the whole stream, so
            // that the output of a per-record run reads the same as a run over the whole text, and an
            // expected output written for one matches the other.
            final TransformerHandler handler = ConfinedXml.serialiser(writing, true, false);
            handler.startDocument();
            // The namespaces the first document declares stay in scope for all of them. A later document that
            // declares one of its own (a skip marker's namespace, for example) must declare it itself, or its
            // elements would be written with no binding and read back in whatever namespace was in scope.
            final Map<String, String> opened = new HashMap<>();
            for (int i = 0; i < written.size(); i++) {
                final XMLReader reader = ConfinedXml.reader();
                reader.setContentHandler(new Contents(handler, i == 0, i == written.size() - 1, opened));
                reader.parse(new InputSource(new StringReader(written.get(i))));
            }
            handler.endDocument();
        } catch (final Exception e) {
            return null;
        }
        return writing.toString();
    }


    // --------------------------------------------------------------------------------


    // Forwards one document's contents into the joined document. The first document supplies the root's
    // start tag, the last supplies its end tag, and every document contributes what is inside its root.
    private static final class Contents extends DefaultHandler {

        private final TransformerHandler joined;
        private final boolean first;
        private final boolean last;
        // The prefix mappings the first document declared, which stay in scope for the whole joined document.
        private final Map<String, String> opened;
        // The prefixes this document declared that the first did not. Each is ended again when this document
        // ends, because a prefix mapping started without its end would corrupt the joined document's
        // namespace scoping.
        private final Deque<String> ownPrefixes = new ArrayDeque<>();
        private int depth;

        private Contents(final TransformerHandler joined,
                         final boolean first,
                         final boolean last,
                         final Map<String, String> opened) {
            this.joined = joined;
            this.first = first;
            this.last = last;
            this.opened = opened;
        }

        @Override
        public void startPrefixMapping(final String prefix, final String uri) throws SAXException {
            if (first) {
                opened.put(prefix, uri);
                joined.startPrefixMapping(prefix, uri);
                return;
            }
            if (!uri.equals(opened.get(prefix))) {
                // A mapping of this document's own; without it the element would be written with no binding
                ownPrefixes.addLast(prefix);
                joined.startPrefixMapping(prefix, uri);
            }
        }

        @Override
        public void endPrefixMapping(final String prefix) throws SAXException {
            if (ownPrefixes.removeLastOccurrence(prefix)) {
                joined.endPrefixMapping(prefix);
                return;
            }
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
            // Forward all text inside the root, including text directly in it, since a record's content is
            // not only its elements. The exception is whitespace directly under the root, which is each
            // piece's indentation rather than content; the joined document is indented afresh.
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
