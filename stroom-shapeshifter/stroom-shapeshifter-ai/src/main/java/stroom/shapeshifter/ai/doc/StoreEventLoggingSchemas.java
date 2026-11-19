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

package stroom.shapeshifter.ai.doc;

import stroom.docref.DocRef;
import stroom.pipeline.xmlschema.FindXMLSchemaCriteria;
import stroom.pipeline.xmlschema.XmlSchemaStore;
import stroom.security.api.SecurityContext;
import stroom.shapeshifter.ai.scoring.TargetSchema;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.xmlschema.shared.XmlSchemaDoc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/// The event-logging schemas installed, read from the schema store as the processing user, as the target a
/// Learning Flow names is read when its streams are judged (`StoreTargetSchemas`).
///
/// Finding them reads every schema document, so v3.0.0, which is looked for whenever a Learning Flow saved
/// before D1 is read, is remembered for a minute.
@Singleton
public class StoreEventLoggingSchemas implements EventLoggingSchemas {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(StoreEventLoggingSchemas.class);
    private static final long REMEMBERED_MS = Duration.ofMinutes(1).toMillis();

    private final XmlSchemaStore store;
    private final SecurityContext securityContext;
    private volatile Remembered v3;

    @Inject
    public StoreEventLoggingSchemas(final XmlSchemaStore store, final SecurityContext securityContext) {
        this.store = store;
        this.securityContext = securityContext;
    }

    @Override
    public Optional<DocRef> latest() {
        return latest(find(null));
    }

    @Override
    public Optional<DocRef> v3() {
        final Remembered remembered = v3;
        final long now = System.currentTimeMillis();
        if (remembered != null && now - remembered.atMs() < REMEMBERED_MS) {
            return remembered.schema();
        }
        final Optional<DocRef> found = find(TargetSchema.EVENT_LOGGING_V3_SYSTEM_ID).stream()
                .min(Comparator.comparing(XmlSchemaDoc::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(StoreEventLoggingSchemas::docRef);
        v3 = new Remembered(found, now);
        return found;
    }

    /// The latest of some schemas, by the version each declares on its root. Where two declare the same, or
    /// none, the one whose name sorts last wins, so that the choice is the same every time.
    ///
    /// @param schemas Event-logging schemas.
    /// @return The latest, or empty where there are none.
    static Optional<DocRef> latest(final Collection<XmlSchemaDoc> schemas) {
        return schemas.stream()
                .map(schema -> new Versioned(schema, TargetSchema.versionOf(schema.getData()).orElse(null)))
                .max(Comparator.<Versioned, String>comparing(Versioned::version, TargetSchema::compareVersions)
                        .thenComparing(versioned -> versioned.schema().getName(),
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(versioned -> docRef(versioned.schema()));
    }

    // The event-logging schemas installed, or those of them with a system id
    private List<XmlSchemaDoc> find(final String systemId) {
        final FindXMLSchemaCriteria criteria = new FindXMLSchemaCriteria();
        criteria.setNamespaceURI(TargetSchema.EVENT_LOGGING_NAMESPACE_URI);
        criteria.setSystemId(systemId);
        try {
            return securityContext.asProcessingUserResult(() -> store.find(criteria).getValues());
        } catch (final RuntimeException e) {
            // No schema is no target, which the Learning Flow's readiness says; it is not a reason to fail
            LOGGER.warn(() -> LogUtil.message("The event-logging schemas could not be read: {}",
                    e.getMessage()), e);
            return List.of();
        }
    }

    private static DocRef docRef(final XmlSchemaDoc schema) {
        return XmlSchemaDoc.buildDocRef()
                .uuid(schema.getUuid())
                .name(schema.getName())
                .build();
    }


    // --------------------------------------------------------------------------------


    /// A schema and the version it declares, or null.
    private record Versioned(XmlSchemaDoc schema, String version) {

    }


    // --------------------------------------------------------------------------------


    /// What was found, and when.
    private record Remembered(Optional<DocRef> schema, long atMs) {

    }
}
