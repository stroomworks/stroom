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

package stroom.shapeshifter.ai.element;

import stroom.docref.DocRef;
import stroom.pipeline.xmlschema.XmlSchemaStore;
import stroom.security.api.SecurityContext;
import stroom.shapeshifter.ai.scoring.TargetSchema;
import stroom.shapeshifter.ai.stage.TargetSchemas;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.xmlschema.shared.XmlSchemaDoc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// The target schemas of D1 in a node: the XML schema document a Learning Flow names, read from the schema
/// store as the processing user, since whoever's stream is being translated need not be able to read
/// schema documents to have it judged against one.
///
/// Until the questions are written from the schema itself (D2), they describe event-logging, so a schema in
/// any other namespace is refused rather than learned against: every answer would fail it.
@Singleton
public class StoreTargetSchemas implements TargetSchemas {

    private final XmlSchemaStore store;
    private final SecurityContext securityContext;
    /// The version each schema declares, by its document's uuid and version: read from the schema's text,
    /// which is large, so once for each version of the document rather than for every stream.
    private final Map<String, String> versions = new ConcurrentHashMap<>();

    @Inject
    public StoreTargetSchemas(final XmlSchemaStore store, final SecurityContext securityContext) {
        this.store = store;
        this.securityContext = securityContext;
    }

    @Override
    public TargetSchema of(final ShapeshifterAiDoc doc) {
        final DocRef named = doc.getTargetSchema();
        if (named == null) {
            throw new IllegalArgumentException("Learning Flow " + doc.getName() + " has no target schema. "
                                               + "Choose one under AI model and instructions on its Setup tab");
        }
        final XmlSchemaDoc schema = securityContext.asProcessingUserResult(() -> read(named));
        if (schema == null) {
            throw new IllegalArgumentException("Learning Flow " + doc.getName() + " names target schema "
                                               + named.getDisplayValue() + ", which cannot be read");
        }
        if (!TargetSchema.EVENT_LOGGING_NAMESPACE_URI.equals(schema.getNamespaceURI())) {
            throw new IllegalArgumentException("Learning Flow " + doc.getName() + " names target schema "
                                               + schema.getName() + ", in namespace " + schema.getNamespaceURI()
                                               + ". Only event-logging schemas ("
                                               + TargetSchema.EVENT_LOGGING_NAMESPACE_URI
                                               + ") can be learned against so far");
        }
        if (schema.getSystemId() == null || schema.getSystemId().isBlank()) {
            // Without one, the schema filter can't be held to this version, nor can a translation name it
            throw new IllegalArgumentException("Learning Flow " + doc.getName() + " names target schema "
                                               + schema.getName() + ", which has no system id");
        }
        return new TargetSchema(schema.getSchemaGroup(), schema.getNamespaceURI(), schema.getSystemId(),
                version(schema));
    }

    private XmlSchemaDoc read(final DocRef docRef) {
        try {
            return store.readDocument(docRef);
        } catch (final RuntimeException e) {
            // Deleted, or not readable: either way there is no schema to judge against
            return null;
        }
    }

    private String version(final XmlSchemaDoc schema) {
        final String key = schema.getUuid() + ":" + schema.getVersion();
        // An empty string stands for a schema that declares no version, which a map can't hold as null
        final String version = versions.computeIfAbsent(key,
                ignored -> TargetSchema.versionOf(schema.getData()).orElse(""));
        return version.isEmpty()
                ? null
                : version;
    }
}
