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

package stroom.learningflow.doc;

import stroom.docref.DocRef;
import stroom.docstore.api.AbstractDocumentStore;
import stroom.docstore.api.DependencyRemapFunction;
import stroom.docstore.api.StoreFactory;
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.learning.TargetSchema;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.learning.Templates;
import stroom.learningflow.shared.CheckSettings;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.PromotionMode;
import stroom.security.api.SecurityContext;
import stroom.security.shared.AppPermission;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PermissionException;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;

/// The document store for Learning Flow documents. It gives a new document a usable flow and target schema,
/// and validates a document's flow, checks and promotion mode whenever it is saved or imported.
@Singleton
public class LearningFlowStoreImpl
        extends AbstractDocumentStore<LearningFlowDoc>
        implements LearningFlowStore {

    private final SecurityContext securityContext;
    private final TargetSchemas targetSchemas;

    @Inject
    public LearningFlowStoreImpl(final StoreFactory storeFactory,
                                   final SecurityContext securityContext,
                                   final LearningFlowSerialiser serialiser,
                                   final TargetSchemas targetSchemas) {
        super(storeFactory,
                securityContext,
                serialiser,
                LearningFlowDoc.TYPE,
                LearningFlowDoc::builder,
                LearningFlowDoc::copy);
        this.securityContext = securityContext;
        this.targetSchemas = targetSchemas;
    }

    /// The example flow a new Learning Flow starts with.
    ///
    /// It is `DIRECT` because, on the feeds measured so far, that reached the same scores as `ESCALATING` for
    /// half the tokens. The intention is to switch to `ESCALATING` once a `TestLiveScenarios` run against a
    /// live model has confirmed it is better.
    static final FlowExample DEFAULT_EXAMPLE = FlowExample.DIRECT;

    /// Creates a new Learning Flow with the steps of [#DEFAULT_EXAMPLE], and the latest installed
    /// event-logging schema as its target schema.
    ///
    /// The built-in example flows are held as JSON on the server, so shared code cannot build a document
    /// with one, and `LearningFlowDoc` starts with no steps. The steps must be added here, because
    /// [#writeDocument] refuses a flow that cannot be run, and a flow with no steps is one of those.
    ///
    /// The target schema is fixed when the document is created, and saved on it rather than looked up each
    /// time, so that installing a newer event-logging schema later does not change what an existing document
    /// produces. Every question the document asks the model names this schema. If no event-logging schema is
    /// installed the document gets no target, and the document's readiness check reports that.
    ///
    /// @param name The document's name.
    /// @return The new document's reference.
    @Override
    public DocRef createDocument(final String name) {
        final DocRef docRef = super.createDocument(name);
        writeDocument(readDocument(docRef).copy()
                .flow(FlowExamples.flow(DEFAULT_EXAMPLE))
                .targetSchema(targetSchemas.latestEventLogging().map(TargetSchema::docRef).orElse(null))
                .build());
        return docRef;
    }

    /// Validates a document before it is saved, including on import, so that a fault is reported with a
    /// clear message at save time rather than found when the first stream arrives. The flow must be one
    /// that can be run, and the check settings must be valid (the compile and permitted checks must be
    /// gates, and every threshold must be between 0 and 1). Moving to automatic promotion needs an extra
    /// permission (see [#refuseUnpermittedAutomatic]). The document is stamped with the version of the
    /// built-in question text it was saved against.
    @Override
    public LearningFlowDoc writeDocument(final LearningFlowDoc document) {
        // The flow is validated both structurally and against the built-in question templates.
        final List<String> problems = new ArrayList<>(document.getFlow().problems());
        problems.addAll(Templates.problems(document.getFlow()));
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("The flow cannot be held: " + String.join("; ", problems));
        }
        final List<String> checkProblems = CheckSettings.problems(document.getChecks());
        if (!checkProblems.isEmpty()) {
            throw new IllegalArgumentException("The checks cannot be held: " + String.join(" ", checkProblems));
        }
        refuseUnpermittedAutomatic(document);
        return super.writeDocument(document.copy()
                .flow(document.getFlow().withBuiltInVersion(Templates.VERSION))
                .build());
    }

    /// Refuses to save a Learning Flow being switched to automatic promotion (*use new translations as soon
    /// as they pass*) unless the user has the `APPROVE_SMART_PIPELINES_AUTOMATICALLY` permission.
    ///
    /// Approving one translation needs EDIT on its pipeline, so the pipeline's owner decides. Setting the
    /// mode to automatic approves **every future translation, for every feed this Learning Flow serves, with
    /// nobody looking**. That is a wider act than editing the Learning Flow, and no existing permission fits
    /// it: OWNER usually belongs to whoever created the document, and ADMINISTRATOR is too broad.
    ///
    /// The check is made here rather than in the editor so that it also applies **on import**, in the same
    /// way that Content Templates check their run-as user. Only the change *to* automatic is checked.
    /// Switching back to review is the safer direction and needs only EDIT, and a user without the
    /// permission can still edit the rest of a Learning Flow that is already automatic.
    ///
    /// @param document The document as it is to be saved.
    private void refuseUnpermittedAutomatic(final LearningFlowDoc document) {
        if (document.getPromotionMode() != PromotionMode.AUTOMATIC
            || securityContext.hasAppPermission(AppPermission.APPROVE_SMART_PIPELINES_AUTOMATICALLY)) {
            return;
        }
        final boolean wasAutomatic = NullSafe.getOrElse(readDocumentQuietly(document.getUuid()),
                LearningFlowDoc::getPromotionMode, PromotionMode.REVIEW) == PromotionMode.AUTOMATIC;
        if (!wasAutomatic) {
            throw new PermissionException(securityContext.getUserRef(),
                    "Using new translations as soon as they pass approves every future translation for "
                    + "every feed this Learning Flow serves, with nobody checking them. That needs the '"
                    + AppPermission.APPROVE_SMART_PIPELINES_AUTOMATICALLY.getDisplayValue()
                    + "' permission.");
        }
    }

    /// Reads the document as currently stored, or returns null if there is none to read, i.e. a new document
    /// or one the read of which fails. The caller treats an unreadable document as not already automatic,
    /// which is the safe assumption.
    ///
    /// @param uuid The document's UUID, which may be null.
    /// @return The stored document, or null.
    private LearningFlowDoc readDocumentQuietly(final String uuid) {
        try {
            return uuid == null
                    ? null
                    : readDocument(LearningFlowDoc.buildDocRef().uuid(uuid).build());
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /// Declares the document's dependencies, which are its model document and nothing else.
    ///
    /// Bindings are stored as rows, and fragments are referenced by bindings, not by the document, so an
    /// exported document carries its configuration and none of what was learned under it (just as a
    /// processor filter's state is not part of the pipeline it runs). Declaring the model here makes a
    /// content-pack import re-point it at the imported copy, lets a copied document follow it, and shows the
    /// dependency in the explorer's dependency view. The inherited default (null) would silently disable all
    /// three.
    @Override
    protected DependencyRemapFunction<LearningFlowDoc> getDependencyRemapFunction() {
        return (doc, remapper) -> {
            final LearningFlowDoc.Builder builder = doc.copy();
            if (doc.getModel() != null) {
                builder.model(remapper.remap(doc.getModel()));
            }
            return builder.build();
        };
    }
}
