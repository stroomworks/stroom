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

import java.util.Objects;

/// Identifies a learner, the unit of learning: one Smart Pipeline element in one pipeline, identified by the
/// pipeline's UUID and the element's id in its chain. The pipeline is the one the processor runs, never the
/// template it inherits from; the element id distinguishes two Smart Pipeline elements in one pipeline.
///
/// The document a learner runs under is not part of its identity. Rows record the document beside the
/// learner, for the Supervisor to show and to decide who may see it, so that a pipeline whose element is
/// pointed at another document keeps what it learned.
///
/// @param pipelineUuid The UUID of the pipeline the element runs in.
/// @param elementId    The element's id in that pipeline's chain.
public record Learner(String pipelineUuid, String elementId) {

    /// The type of the entity event fired when a learner's rows change, so that every node discards its
    /// cached copies of them. It has its own type rather than the pipeline type, because a `CLEAR_CACHE` on a
    /// pipeline would also clear every node's merged pipeline data. The event's UUID is the learner's
    /// pipeline UUID.
    public static final String EVENT_TYPE = "LearningFlowLearner";

    public Learner {
        Objects.requireNonNull(pipelineUuid, "pipelineUuid");
        Objects.requireNonNull(elementId, "elementId");
    }

    @Override
    public String toString() {
        return pipelineUuid + "/" + elementId;
    }
}
