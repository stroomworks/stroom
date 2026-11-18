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
import stroom.pipeline.shared.PipelineDoc;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.ai.stage.DeletedPipelines;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

/// Who may do what with a learner, as the Supervisor and a Smart Pipeline's Quality Assurance tab both ask it.
/// What is learned for a feed belongs to the feed's pipeline and is held to the pipeline's permissions. A pipeline
/// since deleted has none to ask: its learning is read by its Learning Flow's VIEW instead, and nothing is changed
/// for it (A137), so a change asked of one is refused.
public class LearnerAccess {

    private final Provider<SecurityContext> securityContextProvider;
    private final Provider<DeletedPipelines> deletedPipelinesProvider;

    /// @param securityContextProvider  Says what the person may do.
    /// @param deletedPipelinesProvider Says which pipelines have been deleted.
    @Inject
    public LearnerAccess(final Provider<SecurityContext> securityContextProvider,
                         final Provider<DeletedPipelines> deletedPipelinesProvider) {
        this.securityContextProvider = securityContextProvider;
        this.deletedPipelinesProvider = deletedPipelinesProvider;
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
                new DocRef(ShapeshifterAiDoc.TYPE, docUuid), permission);
    }

    /// @param pipelineUuid The pipeline.
    /// @return Whether it has been deleted, when what it learned is kept to be read and nothing more.
    public boolean isDeleted(final String pipelineUuid) {
        return deletedPipelinesProvider.get().isDeleted(pipelineUuid);
    }
}
