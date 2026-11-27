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

package stroom.learningflow.doc;

import stroom.docref.DocRef;
import stroom.learningflow.element.LearnerDocuments;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.stage.DeletedPipelines;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearningPipelines;
import stroom.pipeline.shared.PipelineDoc;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.Optional;

/// Decides whether the current user may view or change a learner, for the Supervisor and for a Smart
/// Pipeline's Quality Assurance tab.
///
/// What is learned for a feed belongs to the feed's pipeline, so access follows the pipeline's permissions.
/// A deleted pipeline has no permissions to check, so its learning may be viewed by anyone with VIEW on its
/// Learning Flow document instead. A deleted pipeline's learning is read-only, so any request to change it is
/// refused.
public class LearnerAccess {

    private final Provider<SecurityContext> securityContextProvider;
    private final Provider<DeletedPipelines> deletedPipelinesProvider;
    private final Provider<LearnerDocuments> learnerDocumentsProvider;
    private final Provider<LearningPipelines> learningPipelinesProvider;

    /// @param securityContextProvider   Checks the current user's document permissions.
    /// @param deletedPipelinesProvider  Says which pipelines have been deleted.
    /// @param learnerDocumentsProvider  Finds the Learning Flow that a live pipeline's element names.
    /// @param learningPipelinesProvider Finds the Learning Flow a deleted pipeline's learning is listed under.
    @Inject
    public LearnerAccess(final Provider<SecurityContext> securityContextProvider,
                         final Provider<DeletedPipelines> deletedPipelinesProvider,
                         final Provider<LearnerDocuments> learnerDocumentsProvider,
                         final Provider<LearningPipelines> learningPipelinesProvider) {
        this.securityContextProvider = securityContextProvider;
        this.deletedPipelinesProvider = deletedPipelinesProvider;
        this.learnerDocumentsProvider = learnerDocumentsProvider;
        this.learningPipelinesProvider = learningPipelinesProvider;
    }

    /// Checks whether the current user may act on a learner.
    ///
    /// @param learner    The learner to be viewed or changed.
    /// @param docUuid    The UUID of the Learning Flow it is listed under; only used for a deleted pipeline.
    /// @param permission What the user wants to do: VIEW to read, EDIT to change.
    /// @return Whether they may. For a live pipeline this is the user's permission on the pipeline. For a
    ///         deleted pipeline only VIEW can be granted, and only if the user has VIEW on the Learning Flow.
    public boolean mayLearner(final Learner learner, final String docUuid, final DocumentPermission permission) {
        if (deletedPipelinesProvider.get().isDeleted(learner.pipelineUuid())) {
            return permission == DocumentPermission.VIEW && mayDocument(docUuid, permission);
        }
        return mayPipeline(learner.pipelineUuid(), permission);
    }

    /// @param pipelineUuid The UUID of the pipeline to check.
    /// @param permission   The permission required.
    /// @return Whether the current user has the permission on the pipeline.
    public boolean mayPipeline(final String pipelineUuid, final DocumentPermission permission) {
        return securityContextProvider.get().hasDocumentPermission(
                new DocRef(PipelineDoc.TYPE, pipelineUuid), permission);
    }

    /// @param docUuid    The UUID of the Learning Flow document to check, which may be null.
    /// @param permission The permission required.
    /// @return Whether the current user has the permission on the Learning Flow; false if `docUuid` is null.
    public boolean mayDocument(final String docUuid, final DocumentPermission permission) {
        return docUuid != null && securityContextProvider.get().hasDocumentPermission(
                new DocRef(LearningFlowDoc.TYPE, docUuid), permission);
    }

    /// Finds the Learning Flow a learner runs under. For a live pipeline this is the document its element
    /// names now. A deleted pipeline names nothing, so the document is taken from the learning pipelines
    /// list instead, so that what it learned can still be viewed under the Learning Flow it is listed under.
    ///
    /// @param learner The learner to look up.
    /// @return The Learning Flow's UUID, or empty if it runs under none.
    public Optional<String> documentOf(final Learner learner) {
        return deletedPipelinesProvider.get().isDeleted(learner.pipelineUuid())
                ? learningPipelinesProvider.get().documentOf(learner)
                : learnerDocumentsProvider.get().documentOf(learner);
    }
}
