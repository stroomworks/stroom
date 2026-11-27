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

package stroom.learningflow.stage;

import java.util.Collection;
import java.util.Set;

/// Records which pipelines with learning have been deleted. Deleting a pipeline deletes all its elements at
/// once, so the mark is per pipeline. Its learners' rows are kept, viewable in the Supervisor under a
/// filter, until the retention job removes them once the mark is older than the session retention period.
///
/// Tests keep the marks in memory; a node uses the `learning_flow_deleted_pipeline` table, and the retention
/// job's side of it is [stroom.learningflow.element.KeptLearning].
public interface DeletedPipelines {

    /// Marks a pipeline's learning as deleted, if it has any. If the pipeline is already marked, the
    /// original time is kept, so that every node handling the same delete event, and the retention job
    /// finding it later, age it from when the deletion was first seen.
    ///
    /// @param pipelineUuid The UUID of the deleted pipeline.
    /// @param timeMs       When it was found to be deleted.
    /// @return Whether this call marked it: false if it was already marked, or had no learning to mark.
    boolean deleted(String pipelineUuid, long timeMs);

    /// Removes the mark from a pipeline that exists again, e.g. because it was imported from an export,
    /// which keeps its UUID. Its learning is then no longer hidden, read-only or due to age out.
    ///
    /// @param pipelineUuid The UUID of the pipeline.
    /// @return Whether it was marked.
    boolean restored(String pipelineUuid);

    /// @param pipelineUuid The UUID of the pipeline.
    /// @return Whether its learning is marked as deleted.
    boolean isDeleted(String pipelineUuid);

    /// Finds which of these pipelines are marked as deleted, so that a page of the Supervisor's rows can be
    /// checked in one call.
    ///
    /// @param pipelineUuids The UUIDs of the pipelines.
    /// @return The UUIDs of those marked.
    Set<String> deletedOf(Collection<String> pipelineUuids);
}
