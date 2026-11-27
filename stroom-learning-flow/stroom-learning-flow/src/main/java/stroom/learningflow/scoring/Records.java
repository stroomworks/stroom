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

package stroom.learningflow.scoring;

import stroom.learningflow.shared.RecordBoundary;
import stroom.util.xml.SAXParserFactoryFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.parsers.ParserConfigurationException;

/// Counts the element children of an XML document's root, i.e. the records of a `records:2` document or
/// the events of an `event-logging:3` one, counting declared skips separately. It parses with Stroom's
/// secure-processing SAX parser; a document that does not parse counts as having no records.
public final class Records {

    private Records() {
    }

    public static int count(final String xml) {
        return Math.max(0, parsed(xml));
    }

    /// Counts the records a translation declared it deliberately skipped: the [DeclaredSkips] markers
    /// among the root's children. They are not events, so they are not counted as records; they are what
    /// makes the completeness check add up for a feed whose header lines are not events.
    ///
    /// @param xml The output.
    /// @return How many skips it declares; zero where it does not parse.
    public static int skips(final String xml) {
        final Counter counter = counted(xml);
        return counter == null
                ? 0
                : counter.skips;
    }

    /// The reasons the translation gave for each record it skipped.
    ///
    /// @param xml The output.
    /// @return The reasons given, in order, leaving out the markers that gave none.
    public static List<String> skipReasons(final String xml) {
        final Counter counter = counted(xml);
        return counter == null
                ? List.of()
                : List.copyOf(counter.reasons);
    }

    /// Counts the records a document holds according to a record boundary (the elements with that name,
    /// or the array's items), or the root's children where there is no boundary or it matches nothing.
    ///
    /// @param xml      The document.
    /// @param boundary The record boundary, or null.
    /// @return The count, or -1 where the text does not parse.
    public static int parsed(final String xml, final RecordBoundary boundary) {
        if (boundary == null) {
            return parsed(xml);
        }
        final Optional<OutputRecords> document = OutputRecords.parse(xml);
        if (document.isEmpty()) {
            return parsed(xml);
        }
        final int by = document.get().recordsBy(boundary).size();
        return by > 0
                ? by
                : document.get().records().size();
    }

    /// Counts the root's element children, excluding declared skips.
    ///
    /// @param xml The document.
    /// @return The number of records, or -1 where the text does not parse.
    public static int parsed(final String xml) {
        final Counter counter = counted(xml);
        return counter == null
                ? -1
                : counter.records;
    }

    /// @param xml The output.
    /// @return The record and skip counts, or null where the text does not parse.
    private static Counter counted(final String xml) {
        final Counter counter = new Counter();
        try {
            final XMLReader reader = SAXParserFactoryFactory.newInstance().newSAXParser().getXMLReader();
            reader.setContentHandler(counter);
            reader.parse(new InputSource(new StringReader(xml)));
        } catch (final SAXException | IOException | ParserConfigurationException e) {
            return null;
        }
        return counter;
    }

    private static final class Counter extends DefaultHandler {

        private final List<String> reasons = new ArrayList<>();
        private int depth;
        private int records;
        private int skips;

        @Override
        public void startElement(final String uri,
                                 final String localName,
                                 final String qName,
                                 final Attributes attributes) {
            depth++;
            if (depth == 2) {
                // A declared skip stands in place of an event but is not one. It is counted separately, so
                // that the completeness check can add the two together and a reviewer can see both.
                if (DeclaredSkips.isMarker(uri, localName)) {
                    skips++;
                    final String reason = DeclaredSkips.reason(attributes);
                    if (reason != null) {
                        reasons.add(reason);
                    }
                } else {
                    records++;
                }
            }
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) {
            depth--;
        }
    }
}
