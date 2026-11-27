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

package stroom.learningflow.scoring;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

import java.util.ArrayDeque;
import java.util.Deque;

/// Defines how a translation declares that a record is deliberately not an event, and provides helpers to
/// recognise and remove those declarations.
///
/// Plenty of feeds legitimately produce fewer events than records (e.g. a header line, a column banner, a
/// heartbeat or a comment), and without a way to declare this, the completeness check
/// (`records in = events out + declared skips`) would fail every one of them and people would switch it
/// off.
///
/// In place of an event, the translation writes a marker:
///
/// ```xml
/// <Skipped xmlns="learningflow:skips" reason="header line"/>
/// ```
///
/// It is **data, not a side effect**, which is why it is used rather than `xsl:message`. In Stroom, a
/// message whose text is not wrapped in a severity element is logged as an `ERROR`, and an `ERROR` drops
/// records from the output, which is the very fault the completeness check exists to catch. A marker
/// written wrongly simply does not count, and nothing is lost.
///
/// The marker never leaves the module: it is counted beside the events, ignored by the Schema-valid
/// check, and dropped before the writer, so nothing downstream sees it.
///
/// **It is a gap in the completeness check that the model controls**, and there is deliberately no limit
/// on how much of a stream may be skipped. The count is shown instead (in the conversation, on the
/// Learning Status tab and beside the event count on the stream's output row), so that a person approving
/// a draft reads "312 events, 4 skipped" and judges for themselves.
public final class DeclaredSkips {

    /// The marker's namespace. It has its own namespace so that it cannot collide with a target schema's
    /// elements, and so that the Schema-valid check can tell it apart from an event without knowing the
    /// schema.
    public static final String NAMESPACE = "learningflow:skips";
    /// The marker element's local name.
    public static final String ELEMENT = "Skipped";
    /// The attribute holding why the record was skipped, as free text.
    public static final String REASON = "reason";

    /// The marker as the prompt describes it and messages quote it.
    public static final String MARKUP = "<" + ELEMENT + " xmlns=\"" + NAMESPACE + "\" reason=\"...\"/>";

    private DeclaredSkips() {
    }

    /// Whether an element is a declared skip marker.
    ///
    /// @param uri       An element's namespace, which may be null or empty.
    /// @param localName Its local name.
    /// @return Whether it is a declared skip.
    public static boolean isMarker(final String uri, final String localName) {
        return NAMESPACE.equals(uri) && ELEMENT.equals(localName);
    }

    /// Gets the reason a marker gives for skipping the record.
    ///
    /// @param attributes The marker's attributes.
    /// @return The reason given, or null where none was.
    public static String reason(final Attributes attributes) {
        final String reason = attributes.getValue(REASON);
        return reason == null || reason.isBlank()
                ? null
                : reason.strip();
    }

    /// Creates a handler that passes everything on except the markers, which it drops along with anything
    /// inside them. This keeps declared skips away from the Schema-valid check and from the writer.
    ///
    /// @param downstream Where everything else goes.
    /// @return The handler to feed.
    public static ContentHandler stripping(final ContentHandler downstream) {
        return new Stripper(downstream);
    }


    // --------------------------------------------------------------------------------


    /// Forwards a document with the skip markers removed. A marker should be an empty element, but the
    /// depth is tracked rather than assumed, so that anything written inside one is removed with it.
    private static final class Stripper implements ContentHandler {

        private final ContentHandler downstream;
        /// The prefixes whose mappings were dropped, so that the matching end is dropped too. Passing on
        /// a start without its end, or the other way round, would leave the downstream's namespace stack
        /// out of step, and the elements after the first marker would lose their namespace.
        private final Deque<String> dropped = new ArrayDeque<>();
        private int skipping;

        private Stripper(final ContentHandler downstream) {
            this.downstream = downstream;
        }

        @Override
        public void setDocumentLocator(final Locator locator) {
            downstream.setDocumentLocator(locator);
        }

        @Override
        public void startDocument() throws SAXException {
            downstream.startDocument();
        }

        @Override
        public void endDocument() throws SAXException {
            downstream.endDocument();
        }

        @Override
        public void startPrefixMapping(final String prefix, final String uri) throws SAXException {
            if (skipping > 0 || NAMESPACE.equals(uri)) {
                dropped.addLast(prefix);
                return;
            }
            downstream.startPrefixMapping(prefix, uri);
        }

        @Override
        public void endPrefixMapping(final String prefix) throws SAXException {
            if (dropped.removeLastOccurrence(prefix)) {
                return;
            }
            downstream.endPrefixMapping(prefix);
        }

        @Override
        public void startElement(final String uri,
                                 final String localName,
                                 final String qName,
                                 final Attributes attributes) throws SAXException {
            if (skipping > 0 || isMarker(uri, localName)) {
                skipping++;
                return;
            }
            downstream.startElement(uri, localName, qName, attributes);
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) throws SAXException {
            if (skipping > 0) {
                skipping--;
                return;
            }
            downstream.endElement(uri, localName, qName);
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) throws SAXException {
            if (skipping == 0) {
                downstream.characters(ch, start, length);
            }
        }

        @Override
        public void ignorableWhitespace(final char[] ch, final int start, final int length) throws SAXException {
            if (skipping == 0) {
                downstream.ignorableWhitespace(ch, start, length);
            }
        }

        @Override
        public void processingInstruction(final String target, final String data) throws SAXException {
            if (skipping == 0) {
                downstream.processingInstruction(target, data);
            }
        }

        @Override
        public void skippedEntity(final String name) throws SAXException {
            if (skipping == 0) {
                downstream.skippedEntity(name);
            }
        }
    }
}
