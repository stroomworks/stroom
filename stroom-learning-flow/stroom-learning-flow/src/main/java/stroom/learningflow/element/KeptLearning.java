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

import java.util.List;
import java.util.Set;

/// The view of stored learning that the retention job ([AttemptRetention]) needs: which pipelines have
/// any learning, which of those were marked deleted before a given time, and a way to remove all of a
/// pipeline's learning. It spans every table a learner keeps rows in, so it is implemented by the
/// database module. Marking a pipeline deleted, rather than removing its learning, is done through
/// `stroom.learningflow.stage.DeletedPipelines`.
public interface KeptLearning {

    /// Lists every pipeline that has learning stored and is not marked deleted. The retention job checks
    /// that each of these still exists, to catch deletions that no node received an event for.
    ///
    /// @return The pipelines' UUIDs.
    Set<String> pipelines();

    /// Lists the pipelines that were marked deleted before a given time.
    ///
    /// @param timeMs The cut-off time, in milliseconds since the epoch.
    /// @return The UUIDs of the pipelines marked deleted before `timeMs`, oldest first.
    List<String> deletedBefore(long timeMs);

    /// Removes all of a deleted pipeline's learning: every row its learners keep (state, bindings,
    /// guidance, waiting streams, attempts and their turns, and the outputs, corpus and snapshots of its
    /// bindings), and the deleted mark itself. The documents learning wrote (fragments, XSLTs and so on)
    /// are ordinary content in the explorer tree and are kept.
    ///
    /// @param pipelineUuid The UUID of the deleted pipeline.
    void forget(String pipelineUuid);
}
