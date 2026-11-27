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

/// Wakes the streams waiting on a learner, e.g. after a promotion. The deferred tasks of every processor
/// filter on the learner's pipeline are moved back to `CREATED` without waiting for the retry delay, and
/// ordinary task ordering by id then processes them oldest first.
///
/// Every filter on the pipeline is woken, because a reprocess filter a person made beside the filter that
/// feeds the pipeline is waiting for the same thing. A node uses the processor task service; tests record
/// what was woken.
public interface Wake {

    /// Wakes the deferred tasks of all of a pipeline's processor filters.
    ///
    /// @param pipelineUuid The UUID of the pipeline.
    /// @return How many tasks were woken.
    int wake(String pipelineUuid);
}
