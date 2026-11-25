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

/// Who may do what with a learner, as the Supervisor and a Smart Pipeline's Quality Assurance tab both ask it.
/// What is learned for a feed belongs to the feed's pipeline and is held to the pipeline's permissions. A pipeline
/// since deleted has none to ask: its learning is read by its Learning Flow's VIEW instead, and nothing is changed
/// for it (A137), so a change asked of one is refused.
public class LearnerAccess {

    private final Provider<SecurityContext> securityContextProvider;
    private final Provider<DeletedPipelines> deletedPipelinesProvider;
    private final Provider<LearnerDocuments> learnerDocumentsProvider;
    private final Provider<LearningPipelines> learningPipelinesProvider;

    /// @param securityContextProvider   Says what the person may do.
    /// @param deletedPipelinesProvider  Says which pipelines have been deleted.
    /// @param learnerDocumentsProvider  The Learning Flow a live pipeline's element names.
    /// @param learningPipelinesProvider The Learning Flow a deleted pipeline's learning is read under.
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

    /// @param learner    The learner.
    /// @param docUuid    The Learning Flow it is read under, for a deleted pipeline's.
    /// @param permission What the person would do: VIEW to read, EDIT to change.
    /// @return Whether they may: by the pipeline's permission, or for a deleted pipeline's learner, by the
    ///         Learning Flow's VIEW, and to read alone.
    public boolean mayLearner(final Learner learner, final String docUuid, final DocumentPermission permission) {
        if (deletedPipelinesProvider.get().isDeleted(learner.pipelineUuid())) {
            return permission == DocumentPermission.VIEW && mayDocument(docUuid, permission);
        }
        return mayPipeline(learner.pipelineUuid(), permission);
    }

    /// @param pipelineUuid The pipeline.
    /// @param permission   The permission.
    /// @return Whether the person has it on the pipeline.
    public boolean mayPipeline(final String pipelineUuid, final DocumentPermission permission) {
        return securityContextProvider.get().hasDocumentPermission(
                new DocRef(PipelineDoc.TYPE, pipelineUuid), permission);
    }

    /// @param docUuid    The Learning Flow.
    /// @param permission The permission.
    /// @return Whether the person has it on the Learning Flow; false for none named.
    public boolean mayDocument(final String docUuid, final DocumentPermission permission) {
        return docUuid != null && securityContextProvider.get().hasDocumentPermission(
                new DocRef(LearningFlowDoc.TYPE, docUuid), permission);
    }

    /// The Learning Flow a learner runs under: as its pipeline's element names it now, or for a pipeline since
    /// deleted, which names nothing, as the learning pipelines list says, so that what it learned can still be
    /// read from the Learning Flow it is listed under (A137).
    ///
    /// @param learner The learner.
    /// @return The Learning Flow's uuid, or empty where it runs under none.
    public Optional<String> documentOf(final Learner learner) {
        return deletedPipelinesProvider.get().isDeleted(learner.pipelineUuid())
                ? learningPipelinesProvider.get().documentOf(learner)
                : learnerDocumentsProvider.get().documentOf(learner);
    }
}
