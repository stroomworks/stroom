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

import java.util.List;
import java.util.Set;

/// The learning a node keeps, as the retention job sees it (design 06 A134, A136): which pipelines have
/// any, which of those were marked deleted long enough ago, and forgetting one. Across every table a
/// learner keeps rows in, so only the node's database has it; the stage's side of a deletion is
/// [stroom.shapeshifter.ai.stage.DeletedPipelines].
public interface KeptLearning {

    /// Every pipeline whose learning is kept and not marked deleted: what the retention job checks is
    /// still there, for a delete no node heard (A134).
    ///
    /// @return The pipelines' uuids.
    Set<String> pipelines();

    /// @param timeMs The time.
    /// @return The pipelines marked deleted before it, oldest first.
    List<String> deletedBefore(long timeMs);

    /// Forget a deleted pipeline's learning (A136): every row its learners keep — state, bindings,
    /// guidance, waiting streams, attempts and their turns, and the outputs, corpus and snapshots of its
    /// bindings — and the mark. The documents it wrote are content in the tree, and stay.
    ///
    /// @param pipelineUuid The pipeline.
    void forget(String pipelineUuid);
}
