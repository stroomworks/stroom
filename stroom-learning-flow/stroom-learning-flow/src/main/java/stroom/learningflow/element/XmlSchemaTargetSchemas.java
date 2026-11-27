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

package stroom.learningflow.element;

import stroom.docref.DocRef;
import stroom.learningflow.learning.TargetSchema;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.scoring.ConfinedXml;
import stroom.pipeline.xmlschema.FindXMLSchemaCriteria;
import stroom.pipeline.xmlschema.XmlSchemaStore;
import stroom.security.api.SecurityContext;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.xmlschema.shared.XmlSchemaDoc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;

/// The node's implementation of [TargetSchemas], which reads the schema a Learning Flow document's
/// translations must conform to from Stroom's `XMLSchema` documents.
///
/// A target's version and root element are read from the XSD itself, rather than from the document's
/// name, so that a schema that is renamed or installed under another name is still described correctly.
@Singleton
public class XmlSchemaTargetSchemas implements TargetSchemas {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(XmlSchemaTargetSchemas.class);

    private static final String XS = XMLConstants.W3C_XML_SCHEMA_NS_URI;

    /// The system id of event-logging v3.0.0, which a Learning Flow saved before it could name a target was
    /// learned against.
    static final String EVENT_LOGGING_V3_SYSTEM_ID = "file://event-logging-v3.0.0.xsd";
    /// How long v3.0.0 is remembered for. Finding it reads every event-logging schema, and it is looked for
    /// whenever a Learning Flow saved before it could name a target is read.
    private static final long REMEMBERED_MS = Duration.ofMinutes(1).toMillis();

    private final XmlSchemaStore schemaStore;
    private final SecurityContext securityContext;
    private volatile Remembered v3;

    @Inject
    public XmlSchemaTargetSchemas(final XmlSchemaStore schemaStore, final SecurityContext securityContext) {
        this.schemaStore = schemaStore;
        this.securityContext = securityContext;
    }

    /// Reads the schema a document names.
    ///
    /// A schema that has been deleted, or that this node cannot read, gives empty rather than throwing,
    /// because what to do about it is up to the caller: the readiness check reports it to the user, and a
    /// session refuses to start before asking the model anything.
    ///
    /// @param docRef The `XMLSchema` document, or null.
    /// @return The resolved schema, or empty where the reference is null or the schema is missing or
    /// unreadable.
    @Override
    public Optional<TargetSchema> resolve(final DocRef docRef) {
        if (docRef == null) {
            return Optional.empty();
        }
        return securityContext.asProcessingUserResult(() -> {
            final XmlSchemaDoc doc;
            try {
                doc = schemaStore.readDocument(docRef);
            } catch (final RuntimeException e) {
                LOGGER.debug(() -> LogUtil.message("The target schema {} cannot be read: {}",
                        docRef, e.getMessage()), e);
                return Optional.empty();
            }
            return Optional.ofNullable(doc).map(XmlSchemaTargetSchemas::of);
        });
    }

    /// Finds the latest version of the event-logging schema installed on this node, which a new Learning
    /// Flow document uses as its target. "Latest" is by the version its XSD declares, not by its name, and
    /// a deprecated schema is never chosen. The choice is saved on the document, so installing a newer
    /// version later does not change what an existing document's translations write.
    ///
    /// @return The schema, or empty where no event-logging schema is installed, in which case a new
    ///         document has no target until someone picks one, and reports that.
    @Override
    public Optional<TargetSchema> latestEventLogging() {
        final FindXMLSchemaCriteria criteria = new FindXMLSchemaCriteria();
        criteria.setNamespaceURI(TargetSchema.EVENT_LOGGING);
        return securityContext.asProcessingUserResult(() -> schemaStore.find(criteria).getValues().stream()
                .filter(doc -> !doc.isDeprecated())
                .map(XmlSchemaTargetSchemas::of)
                .max(Comparator.comparing(TargetSchema::version, XmlSchemaTargetSchemas::compareVersions)));
    }

    /// Finds event-logging v3.0.0 by its system id, which is unique in Stroom, whether or not it is
    /// deprecated: a document saved before it could name a target was learned against it, deprecated or not.
    /// Where several documents somehow share the system id, the one whose name sorts first is taken, so that
    /// the choice is the same every time. The answer is remembered for a minute.
    ///
    /// @return The schema, or empty where v3.0.0 is not installed or cannot be read.
    @Override
    public Optional<TargetSchema> eventLoggingV3() {
        final Remembered remembered = v3;
        final long now = System.currentTimeMillis();
        if (remembered != null && now - remembered.atMs() < REMEMBERED_MS) {
            return remembered.schema();
        }
        final FindXMLSchemaCriteria criteria = new FindXMLSchemaCriteria();
        criteria.setNamespaceURI(TargetSchema.EVENT_LOGGING);
        criteria.setSystemId(EVENT_LOGGING_V3_SYSTEM_ID);
        Optional<TargetSchema> found;
        try {
            found = securityContext.asProcessingUserResult(() -> schemaStore.find(criteria).getValues().stream()
                    .min(Comparator.comparing(XmlSchemaDoc::getName,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(XmlSchemaTargetSchemas::of));
        } catch (final RuntimeException e) {
            // No schema is no target, which the document's readiness reports; it is not a reason to fail a read
            LOGGER.warn(() -> LogUtil.message("Event-logging v3.0.0 could not be read: {}", e.getMessage()), e);
            found = Optional.empty();
        }
        v3 = new Remembered(found, now);
        return found;
    }

    /// Compares two declared versions as sequences of numbers, e.g. `4.1.0` after `3.4.2`. A version that
    /// is absent or not made of numbers sorts first, so that a schema that does not declare its version
    /// is never chosen as the default.
    ///
    /// @param left One version.
    /// @param right The other.
    /// @return A negative number, zero or a positive number, as for [Comparator#compare].
    static int compareVersions(final String left, final String right) {
        final List<Integer> leftParts = parts(left);
        final List<Integer> rightParts = parts(right);
        for (int i = 0; i < Math.max(leftParts.size(), rightParts.size()); i++) {
            final int difference = Integer.compare(part(leftParts, i), part(rightParts, i));
            if (difference != 0) {
                return difference;
            }
        }
        return 0;
    }

    private static int part(final List<Integer> parts, final int index) {
        return index < parts.size()
                ? parts.get(index)
                : 0;
    }

    private static List<Integer> parts(final String version) {
        final List<Integer> parts = new ArrayList<>();
        if (version != null) {
            for (final String word : version.split("\\.")) {
                try {
                    parts.add(Integer.parseInt(word.trim()));
                } catch (final NumberFormatException e) {
                    // A part that is not a number is given -1, so the version sorts first rather than
                    // failing the whole comparison.
                    parts.add(-1);
                }
            }
        }
        return parts;
    }

    private static TargetSchema of(final XmlSchemaDoc doc) {
        final Optional<Element> schema = parse(doc.getData());
        return new TargetSchema(
                doc.asDocRef(),
                doc.getName(),
                doc.getNamespaceURI(),
                doc.getSystemId(),
                schema.map(element -> element.getAttribute("version")).filter(text -> !text.isEmpty())
                        .orElse(null),
                schema.flatMap(XmlSchemaTargetSchemas::rootElement).orElse(null),
                doc.getData());
    }

    /// Finds the root element of documents of this schema: the XSD's global element declaration. Where a
    /// schema declares several, the first is taken, since there is no single answer and the first is the
    /// one its author put at the top.
    private static Optional<String> rootElement(final Element schema) {
        final NodeList children = schema.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                && XS.equals(child.getNamespaceURI())
                && "element".equals(child.getLocalName())) {
                final String name = ((Element) child).getAttribute("name");
                if (!name.isEmpty()) {
                    return Optional.of(name);
                }
            }
        }
        return Optional.empty();
    }

    /// Parses an XSD's text. An XSD that does not parse gives empty, so the target is described only by
    /// what the `XMLSchema` document holds (its namespace and system id); the validator reports its own
    /// errors about the schema's text.
    private static Optional<Element> parse(final String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            // Uses the same hardened parser as ContentModels uses to read an XSD.
            return Optional.of(ConfinedXml.document(text).getDocumentElement());
        } catch (final ParserConfigurationException | SAXException | IOException e) {
            LOGGER.debug(() -> LogUtil.message("A target schema cannot be parsed: {}", e.getMessage()), e);
            return Optional.empty();
        }
    }


    // --------------------------------------------------------------------------------


    /// A lookup's answer, and when it was found.
    private record Remembered(Optional<TargetSchema> schema, long atMs) {

    }
}
