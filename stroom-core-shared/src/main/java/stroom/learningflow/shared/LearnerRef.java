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

package stroom.learningflow.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/// Identifies a learner: the pipeline a Smart Pipeline element runs in and the element's id in that pipeline.
/// Each row the Supervisor lists carries the learner it belongs to, and a request about the row sends it back,
/// because everything the server keeps about a learner is keyed by these two values.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LearnerRef {

    @JsonProperty
    private final String pipelineUuid;
    @JsonProperty
    private final String elementId;

    @JsonCreator
    public LearnerRef(@JsonProperty("pipelineUuid") final String pipelineUuid,
                      @JsonProperty("elementId") final String elementId) {
        this.pipelineUuid = pipelineUuid;
        this.elementId = elementId;
    }

    /// @return The UUID of the pipeline the element runs in.
    public String getPipelineUuid() {
        return pipelineUuid;
    }

    /// @return The element's id in that pipeline's chain.
    public String getElementId() {
        return elementId;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final LearnerRef that = (LearnerRef) o;
        return Objects.equals(pipelineUuid, that.pipelineUuid) && Objects.equals(elementId, that.elementId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pipelineUuid, elementId);
    }

    @Override
    public String toString() {
        return pipelineUuid + "/" + elementId;
    }
}
