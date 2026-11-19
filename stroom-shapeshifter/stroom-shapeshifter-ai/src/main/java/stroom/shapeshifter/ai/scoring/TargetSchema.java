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

package stroom.shapeshifter.ai.scoring;

import stroom.pipeline.xmlschema.FindXMLSchemaCriteria;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.StringReader;
import java.util.Optional;

/// The schema a document's translations must conform to (D1), as Stroom's schema filter is constrained to one:
/// its group, namespace and system id. All three name one schema, a single version: the EVENTS group holds
/// every event-logging version, all in one namespace, so only the system id tells them apart.
///
/// What the questions ask the AI to write follows it: the root a translation's events are wrapped in names
/// its namespace, its system id as the schema location, and its version.
///
/// @param schemaGroup  The schema group, e.g. `EVENTS`.
/// @param namespaceURI The namespace, e.g. `event-logging:3`.
/// @param systemId     The system id, e.g. `file://event-logging-v3.0.0.xsd`.
/// @param version      The schema's own version, its `xs:schema/@version`, e.g. `3.0.0`, or null where it
///                     declares none.
public record TargetSchema(String schemaGroup, String namespaceURI, String systemId, String version) {

    /// The namespace of every event-logging version, the only schemas that can be learned against until the
    /// questions are written from the schema itself (D2).
    public static final String EVENT_LOGGING_NAMESPACE_URI = "event-logging:3";
    /// The system id of event-logging v3.0.0, which every document saved before it could name a target
    /// schema was learned against.
    public static final String EVENT_LOGGING_V3_SYSTEM_ID = "file://event-logging-v3.0.0.xsd";

    /// Event-logging v3.0.0: what the built-in questions were written for, what documents saved before D1
    /// were learned against, and what the scenarios and the demo were proven on. Code that judges without a
    /// document, such as a test, judges against it.
    public static final TargetSchema DEFAULT = new TargetSchema(
            "EVENTS",
            EVENT_LOGGING_NAMESPACE_URI,
            EVENT_LOGGING_V3_SYSTEM_ID,
            "3.0.0");

    private static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String XSD = "http://www.w3.org/2001/XMLSchema";

    /// @return The constraint a schema filter validates against.
    public FindXMLSchemaCriteria criteria() {
        final FindXMLSchemaCriteria criteria = new FindXMLSchemaCriteria();
        criteria.setSchemaGroup(schemaGroup);
        criteria.setNamespaceURI(namespaceURI);
        criteria.setSystemId(systemId);
        return criteria;
    }

    /// The opening tag of the `Events` root a translation writes and a proposed target is wrapped in, naming
    /// this schema.
    ///
    /// @return The tag, e.g. `<Events xmlns="event-logging:3" ... Version="3.0.0">`.
    public String eventsOpen() {
        final StringBuilder open = new StringBuilder()
                .append("<Events xmlns=\"").append(namespaceURI).append("\" ")
                .append("xmlns:xsi=\"").append(XSI).append("\" ")
                .append("xsi:schemaLocation=\"").append(namespaceURI).append(' ').append(systemId)
                .append('"');
        if (version != null) {
            open.append(" Version=\"").append(version).append('"');
        }
        return open.append('>').toString();
    }

    /// The version a schema declares on its root, `xs:schema/@version`. Only the root is read.
    ///
    /// @param xsd The schema's text.
    /// @return Its version, empty where it declares none or does not parse.
    public static Optional<String> versionOf(final String xsd) {
        if (xsd == null || xsd.isBlank()) {
            return Optional.empty();
        }
        final RootVersion root = new RootVersion();
        try {
            final XMLReader reader = ConfinedXml.reader();
            reader.setContentHandler(root);
            reader.parse(new InputSource(new StringReader(xsd)));
        } catch (final RootRead e) {
            // The root was read, which is all that is wanted.
        } catch (final SAXException | IOException | RuntimeException e) {
            return Optional.empty();
        }
        return Optional.ofNullable(root.version);
    }

    /// Orders two schema versions by their dotted numbers, so that `4.1.0` is later than `3.10.0` and `4.1`
    /// is the same as `4.1.0`. A part that is not a number is compared as text; no version at all is the
    /// earliest.
    ///
    /// @param first  A version, or null.
    /// @param second Another, or null.
    /// @return Below zero where the first is earlier, zero where they are the same, above zero where later.
    public static int compareVersions(final String first, final String second) {
        if (first == null && second == null) {
            return 0;
        } else if (first == null) {
            return -1;
        } else if (second == null) {
            return 1;
        }
        final String[] firstParts = first.strip().split("\\.");
        final String[] secondParts = second.strip().split("\\.");
        for (int i = 0; i < Math.max(firstParts.length, secondParts.length); i++) {
            final int compared = comparePart(part(firstParts, i), part(secondParts, i));
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    // A missing part is a zero, so that 4.1 is 4.1.0
    private static String part(final String[] parts, final int index) {
        return index < parts.length
                ? parts[index]
                : "0";
    }

    private static int comparePart(final String first, final String second) {
        if (first.matches("\\d{1,9}") && second.matches("\\d{1,9}")) {
            return Integer.compare(Integer.parseInt(first), Integer.parseInt(second));
        }
        return first.compareTo(second);
    }


    // --------------------------------------------------------------------------------


    /// Reads the root's version and stops.
    private static final class RootVersion extends DefaultHandler {

        private String version;

        @Override
        public void startElement(final String uri,
                                 final String localName,
                                 final String qName,
                                 final Attributes attributes) throws SAXException {
            if (XSD.equals(uri) && "schema".equals(localName)) {
                final String declared = attributes.getValue("", "version");
                version = declared == null || declared.isBlank()
                        ? null
                        : declared.strip();
            }
            throw new RootRead();
        }
    }

    /// Thrown to stop reading once the root has been read.
    private static final class RootRead extends SAXException {

        private RootRead() {
            super("The root has been read");
        }
    }
}
