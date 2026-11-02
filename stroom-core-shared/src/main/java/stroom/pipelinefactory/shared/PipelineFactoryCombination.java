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

package stroom.pipelinefactory.shared;

import stroom.docref.DocRef;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// One combination a Pipeline Factory has met, as its screen shows it (design 06 §3.5): its key values,
/// how far making its pipeline got, and what was made.
@JsonPropertyOrder({
        "values",
        "state",
        "tries",
        "pipeline",
        "pipelineExists",
        "filterUuid",
        "message",
        "createTimeMs",
        "updateTimeMs"})
@JsonInclude(Include.NON_NULL)
public final class PipelineFactoryCombination {

    /// Making its pipeline has begun and not finished.
    public static final String PENDING = "PENDING";
    /// Its pipeline and filter were made.
    public static final String MADE = "MADE";
    /// Making them failed, and will be tried again.
    public static final String FAILED = "FAILED";

    @JsonProperty
    private final Map<String, String> values;
    @JsonProperty
    private final String state;
    @JsonProperty
    private final int tries;
    @JsonProperty
    private final DocRef pipeline;
    @JsonProperty
    private final boolean pipelineExists;
    @JsonProperty
    private final String filterUuid;
    @JsonProperty
    private final String message;
    @JsonProperty
    private final long createTimeMs;
    @JsonProperty
    private final long updateTimeMs;

    /// The arguments are the combination's fields, each described on its getter.
    @JsonCreator
    public PipelineFactoryCombination(@JsonProperty("values") final Map<String, String> values,
                                      @JsonProperty("state") final String state,
                                      @JsonProperty("tries") final int tries,
                                      @JsonProperty("pipeline") final DocRef pipeline,
                                      @JsonProperty("pipelineExists") final boolean pipelineExists,
                                      @JsonProperty("filterUuid") final String filterUuid,
                                      @JsonProperty("message") final String message,
                                      @JsonProperty("createTimeMs") final long createTimeMs,
                                      @JsonProperty("updateTimeMs") final long updateTimeMs) {
        this.values = values == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.state = state;
        this.tries = tries;
        this.pipeline = pipeline;
        this.pipelineExists = pipelineExists;
        this.filterUuid = filterUuid;
        this.message = message;
        this.createTimeMs = createTimeMs;
        this.updateTimeMs = updateTimeMs;
    }

    /// @return Each key field and the combination's value for it; a pipeline is its UUID.
    public Map<String, String> getValues() {
        return values;
    }

    /// @return [#PENDING], [#MADE] or [#FAILED].
    public String getState() {
        return state;
    }

    /// @return How many times the factory has tried to make its pipeline.
    public int getTries() {
        return tries;
    }

    /// @return The pipeline made for it; null until one is.
    public DocRef getPipeline() {
        return pipeline;
    }

    /// @return Whether the pipeline made for it still exists; false once a person deletes it.
    public boolean isPipelineExists() {
        return pipelineExists;
    }

    /// @return The UUID of the filter made for it; null until one is.
    public String getFilterUuid() {
        return filterUuid;
    }

    /// @return Why the last try failed; null if it did not.
    public String getMessage() {
        return message;
    }

    /// @return When the factory first met it.
    public long getCreateTimeMs() {
        return createTimeMs;
    }

    /// @return When the factory last changed what it knows about it.
    public long getUpdateTimeMs() {
        return updateTimeMs;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final PipelineFactoryCombination that = (PipelineFactoryCombination) o;
        return tries == that.tries
               && pipelineExists == that.pipelineExists
               && createTimeMs == that.createTimeMs
               && updateTimeMs == that.updateTimeMs
               && Objects.equals(values, that.values)
               && Objects.equals(state, that.state)
               && Objects.equals(pipeline, that.pipeline)
               && Objects.equals(filterUuid, that.filterUuid)
               && Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values, state, tries, pipeline, pipelineExists, filterUuid, message, createTimeMs,
                updateTimeMs);
    }

    @Override
    public String toString() {
        return values + " " + state;
    }
}
