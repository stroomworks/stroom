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

package stroom.egress.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// One aggregated blocked or would-be-blocked attempt.
@JsonPropertyOrder({
        "id",
        "kind",
        "destination",
        "sink",
        "pipeline",
        "feed",
        "outcome",
        "count",
        "firstSeenMs",
        "lastSeenMs",
        "reason"
})
@JsonInclude(Include.NON_NULL)
public class EgressAttemptDto {

    @JsonProperty
    private final String id;

    @JsonProperty
    private final EgressKind kind;

    @JsonProperty
    private final String destination;

    @JsonProperty
    private final String sink;

    @JsonProperty
    private final String pipeline;

    @JsonProperty
    private final String feed;

    @JsonProperty
    private final String outcome;

    @JsonProperty
    private final long count;

    @JsonProperty
    private final long firstSeenMs;

    @JsonProperty
    private final long lastSeenMs;

    @JsonProperty
    private final String reason;

    @JsonCreator
    public EgressAttemptDto(
            @JsonProperty("id") final String id,
            @JsonProperty("kind") final EgressKind kind,
            @JsonProperty("destination") final String destination,
            @JsonProperty("sink") final String sink,
            @JsonProperty("pipeline") final String pipeline,
            @JsonProperty("feed") final String feed,
            @JsonProperty("outcome") final String outcome,
            @JsonProperty("count") final long count,
            @JsonProperty("firstSeenMs") final long firstSeenMs,
            @JsonProperty("lastSeenMs") final long lastSeenMs,
            @JsonProperty("reason") final String reason) {
        this.id = id;
        this.kind = kind;
        this.destination = destination;
        this.sink = sink;
        this.pipeline = pipeline;
        this.feed = feed;
        this.outcome = outcome;
        this.count = count;
        this.firstSeenMs = firstSeenMs;
        this.lastSeenMs = lastSeenMs;
        this.reason = reason;
    }

    /// @return Identifies the row, for revealing the destination.
    public String getId() {
        return id;
    }

    /// @return The kind of destination.
    public EgressKind getKind() {
        return kind;
    }

    /// @return The normalised destination with its last path segment masked.
    public String getDestination() {
        return destination;
    }

    /// @return The sink that made the attempt.
    public String getSink() {
        return sink;
    }

    /// @return The pipeline, or null.
    public String getPipeline() {
        return pipeline;
    }

    /// @return The feed, or null.
    public String getFeed() {
        return feed;
    }

    /// @return DENIED or WOULD_DENY.
    public String getOutcome() {
        return outcome;
    }

    /// @return How many times it was seen.
    public long getCount() {
        return count;
    }

    /// @return When it was first seen.
    public long getFirstSeenMs() {
        return firstSeenMs;
    }

    /// @return When it was last seen.
    public long getLastSeenMs() {
        return lastSeenMs;
    }

    /// @return The latest reason.
    public String getReason() {
        return reason;
    }

}
