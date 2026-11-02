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

package stroom.shapeshifter.ai.stage;

import java.util.Collection;
import java.util.Set;

/// The learning of pipelines that have been deleted (design 06 A120, A134–A136). A pipeline's deletion is
/// every one of its elements' at once, so the mark is the pipeline's: its learners' rows stay, to be read
/// in the Supervisor under a filter, until the retention job forgets them once the mark is older than
/// attempts are kept. In memory in scenarios; the `shapeshifter_deleted_pipeline` table in a node, where
/// the retention job's side of it is [stroom.shapeshifter.ai.element.KeptLearning].
public interface DeletedPipelines {

    /// Mark a pipeline's learning deleted, where any is kept: the time of a mark already made stands, so
    /// that every node hearing the same delete, and the retention job finding it later, age it from when
    /// it was first heard.
    ///
    /// @param pipelineUuid The pipeline.
    /// @param timeMs       When it was found deleted.
    /// @return Whether this marked it: false where it was marked already, or kept nothing to mark.
    boolean deleted(String pipelineUuid, long timeMs);

    /// A pipeline marked deleted is there again — imported from an export of it, which keeps its uuid —
    /// and its learning is its own again: no longer marked, so not hidden, refused or aged out.
    ///
    /// @param pipelineUuid The pipeline.
    /// @return Whether it was marked.
    boolean restored(String pipelineUuid);

    /// @param pipelineUuid The pipeline.
    /// @return Whether its learning is marked deleted.
    boolean isDeleted(String pipelineUuid);

    /// Which of these pipelines are marked deleted, for a page of the Supervisor's rows at once.
    ///
    /// @param pipelineUuids The pipelines.
    /// @return Those marked.
    Set<String> deletedOf(Collection<String> pipelineUuids);
}
