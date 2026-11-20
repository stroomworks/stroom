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

package stroom.shapeshifter.ai.doc;

import stroom.docref.DocRef;
import stroom.docstore.api.AbstractDocumentStore;
import stroom.docstore.api.DependencyRemapFunction;
import stroom.docstore.api.StoreFactory;
import stroom.security.api.SecurityContext;
import stroom.shapeshifter.ai.learning.Templates;
import stroom.shapeshifter.shared.CheckSettings;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;

/// The Learning Flow documents.
///
/// Each has one target schema version (D1). A new one is given the latest event-logging version installed,
/// saved on it, so moving up to a later version is a deliberate edit. One saved before the setting existed
/// reads as event-logging v3.0.0, which it was learned against, and keeps it once saved. Where neither is
/// installed the field is empty, and the document's readiness says so.
@Singleton
public class ShapeshifterAiStoreImpl
        extends AbstractDocumentStore<ShapeshifterAiDoc>
        implements ShapeshifterAiStore {

    /// The built-in text's version (`Templates.VERSION`) from which a saved document can name its target
    /// schema. One stamped with an earlier version, or none, was saved before the setting existed.
    static final int FIRST_VERSION_WITH_TARGET_SCHEMA = 7;

    private final EventLoggingSchemas eventLogging;

    @Inject
    public ShapeshifterAiStoreImpl(final StoreFactory storeFactory,
                                   final SecurityContext securityContext,
                                   final ShapeshifterAiSerialiser serialiser,
                                   final EventLoggingSchemas eventLogging) {
        super(storeFactory,
                securityContext,
                serialiser,
                ShapeshifterAiDoc.TYPE,
                ShapeshifterAiDoc::builder,
                ShapeshifterAiDoc::copy);
        this.eventLogging = eventLogging;
    }

    /// A new document has the latest event-logging version installed as its target schema, or none where none
    /// is installed, and is stamped as able to name one.
    @Override
    public DocRef createDocument(final String name) {
        final DocRef latest = eventLogging.latest().orElse(null);
        return getStore().createDocument(name,
                (uuid, docName, version, createTime, updateTime, createUser, updateUser) -> {
                    final ShapeshifterAiDoc doc = ShapeshifterAiDoc.builder()
                            .uuid(uuid)
                            .name(docName)
                            .version(version)
                            .createTimeMs(createTime)
                            .updateTimeMs(updateTime)
                            .createUser(createUser)
                            .updateUser(updateUser)
                            .targetSchema(latest)
                            .build();
                    return doc.copy()
                            .flow(doc.getFlow().withBuiltInVersion(Templates.VERSION))
                            .build();
                });
    }

    /// A document saved before it could name a target schema reads as event-logging v3.0.0, where that is
    /// installed: what it was learned against, so that what serves it is judged as it was.
    @Override
    public ShapeshifterAiDoc readDocument(final DocRef docRef) {
        final ShapeshifterAiDoc doc = super.readDocument(docRef);
        if (doc == null || doc.getTargetSchema() != null || !savedBeforeTargetSchema(doc)) {
            return doc;
        }
        return eventLogging.v3()
                .map(v3 -> doc.copy().targetSchema(v3).build())
                .orElse(doc);
    }

    private static boolean savedBeforeTargetSchema(final ShapeshifterAiDoc doc) {
        final Integer builtInVersion = doc.getFlow().getBuiltInVersion();
        return builtInVersion == null || builtInVersion < FIRST_VERSION_WITH_TARGET_SCHEMA;
    }

    /**
     * What the editor cannot check is checked on save, with the fault named, rather than at run time by the
     * supervisor: the flow must be one the stage can hold (A33), and the checks must keep to their rules
     * (the one model of checks: compile always a gate, nothing turned on that isn't built, thresholds from 0
     * to 1).
     */
    @Override
    public ShapeshifterAiDoc writeDocument(final ShapeshifterAiDoc document) {
        // A33: a flow the stage cannot hold is refused here, naming what is wrong, rather than found when
        // the first stream arrives; and the document is stamped with the built-in text it was saved against.
        final List<String> problems = new ArrayList<>(document.getFlow().problems());
        problems.addAll(Templates.problems(document.getFlow()));
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("The flow cannot be held: " + String.join("; ", problems));
        }
        final List<String> checkProblems = CheckSettings.problems(document.getChecks());
        if (!checkProblems.isEmpty()) {
            throw new IllegalArgumentException("The checks cannot be held: " + String.join(" ", checkProblems));
        }
        return super.writeDocument(document.copy()
                .flow(document.getFlow().withBuiltInVersion(Templates.VERSION))
                .build());
    }

    /**
     * A Shapeshifter AI document depends on its model document and its target schema (D1), and on nothing
     * else: since A41 the bindings are rows and the fragments are named by them, not by the document, so an
     * exported document carries its configuration and none of what it learned — as a processor filter's state
     * is not part of the pipeline it runs. Declaring the model and the schema here is what makes a content-pack import
     * re-point them at the imported copies, what lets a copied document follow them, and what draws the edges
     * in the explorer's dependency view; the inherited null would silently disable all three.
     */
    @Override
    protected DependencyRemapFunction<ShapeshifterAiDoc> getDependencyRemapFunction() {
        return (doc, remapper) -> {
            final ShapeshifterAiDoc.Builder builder = doc.copy();
            if (doc.getModel() != null) {
                builder.model(remapper.remap(doc.getModel()));
            }
            if (doc.getTargetSchema() != null) {
                builder.targetSchema(remapper.remap(doc.getTargetSchema()));
            }
            return builder.build();
        };
    }
}
