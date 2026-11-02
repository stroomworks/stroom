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

import java.util.Objects;

/// What learns (design 06 §4.7, A114): a Shapeshifter AI element in a pipeline, named by the pipeline's
/// UUID and the element's id in its chain. The pipeline is the one the processor runs, never the template
/// it inherits; the element id keeps two AI elements in one pipeline apart.
///
/// The document a learner runs under is not part of it. A row records the document beside the learner,
/// for the Supervisor to show and to decide who may see it, so a pipeline whose element is pointed at
/// another document keeps what it learned.
///
/// @param pipelineUuid The UUID of the pipeline the element runs in.
/// @param elementId    The element's id in that pipeline's chain.
public record Learner(String pipelineUuid, String elementId) {

    /// The type of the entity event that says a learner's rows have changed, so that every node drops what
    /// it holds of them. A type of its own rather than the pipeline's, since a `CLEAR_CACHE` on a pipeline
    /// clears every node's merged pipeline data; the event's uuid is the learner's pipeline.
    public static final String EVENT_TYPE = "ShapeshifterAiLearner";

    public Learner {
        Objects.requireNonNull(pipelineUuid, "pipelineUuid");
        Objects.requireNonNull(elementId, "elementId");
    }

    @Override
    public String toString() {
        return pipelineUuid + "/" + elementId;
    }
}
