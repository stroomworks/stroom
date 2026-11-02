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

/// What a promotion does for the streams waiting on its learner (design 04 §5.3, design 06 §4.3): the
/// deferred tasks of every filter on the learner's pipeline go back to `CREATED` ahead of the retry
/// delay, and ordinary FIFO by task id processes them oldest first. Every filter, since a reprocess
/// filter a person made beside the one that feeds the pipeline waits on the same thing. In a node this is
/// the processor task service's wake; in a scenario it is a record of what was woken.
public interface Wake {

    /// Wake the deferred tasks of a pipeline's filters.
    ///
    /// @param pipelineUuid The pipeline.
    /// @return How many tasks were woken.
    int wake(String pipelineUuid);
}
