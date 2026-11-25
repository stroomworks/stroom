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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;

/// [TargetSchemas] over Stroom's `XMLSchema` documents: what a node uses.
///
/// Everything a target is described by comes out of the XSD itself — its `version`, its namespace and
/// the element a document of it starts with — rather than out of the document's name, so that a schema
/// renamed or installed under another name still describes itself correctly.
@Singleton
public class XmlSchemaTargetSchemas implements TargetSchemas {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(XmlSchemaTargetSchemas.class);

    private static final String XS = XMLConstants.W3C_XML_SCHEMA_NS_URI;

    private final XmlSchemaStore schemaStore;
    private final SecurityContext securityContext;

    @Inject
    public XmlSchemaTargetSchemas(final XmlSchemaStore schemaStore, final SecurityContext securityContext) {
        this.schemaStore = schemaStore;
        this.securityContext = securityContext;
    }

    /// Reads the schema a document names.
    ///
    /// A schema that has been deleted, or that this node cannot read, gives empty rather than throwing:
    /// what to do about it belongs to the caller — the readiness check says so in words, and a run
    /// refuses before asking anything.
    ///
    /// @param docRef The `XMLSchema` document, or null.
    /// @return It resolved, or empty where it is null, gone or unreadable.
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

    /// The latest version of event-logging installed on this node, which a new document starts with
    /// (decision D1): latest by the version its XSD declares, not by its name, and never a deprecated
    /// one. It is saved on the document, so installing a newer version later does not change what a
    /// document that already exists writes.
    ///
    /// @return It, or empty where no event-logging schema is installed — in which case a new document
    ///         has no target until someone picks one, and says so.
    @Override
    public Optional<TargetSchema> latestEventLogging() {
        final FindXMLSchemaCriteria criteria = new FindXMLSchemaCriteria();
        criteria.setNamespaceURI(TargetSchema.EVENT_LOGGING);
        return securityContext.asProcessingUserResult(() -> schemaStore.find(criteria).getValues().stream()
                .filter(doc -> !doc.isDeprecated())
                .map(XmlSchemaTargetSchemas::of)
                .max(Comparator.comparing(TargetSchema::version, XmlSchemaTargetSchemas::compareVersions)));
    }

    /// Compares two declared versions as a run of numbers, e.g. `4.1.0` after `3.4.2`. A version that is
    /// absent or that is not numbers sorts first, so that a schema which does not say what it is never
    /// wins the default.
    ///
    /// @param left One version.
    /// @param right The other.
    /// @return The usual comparison result.
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
                    // A version this cannot read is one this cannot order: it sorts first, with -1 for
                    // the part that did not read, rather than failing the whole comparison.
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

    /// The element a document of this schema starts with: the XSD's global element declaration. Where a
    /// schema declares several, the first is taken — a schema with more than one way in has no single
    /// answer, and the first is the one its author put at the top.
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

    /// An XSD that does not parse describes itself as far as its document goes and no further: the
    /// namespace and system id are the document's own, and the validator has its own opinion of the
    /// schema's text.
    private static Optional<Element> parse(final String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            // Hardened as ContentModels hardens its own read of an XSD, with the same parser.
            return Optional.of(ConfinedXml.document(text).getDocumentElement());
        } catch (final ParserConfigurationException | SAXException | IOException e) {
            LOGGER.debug(() -> LogUtil.message("A target schema cannot be parsed: {}", e.getMessage()), e);
            return Optional.empty();
        }
    }
}
