/*
 * Copyright 2016-2026 Crown Copyright
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

import stroom.docref.DocRef;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/// One stream in the Supervisor's backlog: a stream waiting for its learner to learn a translation for it.
/// It records what the stream was run through and fell short of, since when it has waited, how often it has
/// been tried, and whether it has been raised, i.e. has waited past the document's maximum deferral age. A
/// raised stream is flagged for attention but goes on waiting.
@JsonInclude(Include.NON_NULL)
public class BacklogStream {

    @JsonProperty
    private final DocRef doc;
    @JsonProperty
    private final long inputId;
    @JsonProperty
    private final String feed;
    @JsonProperty
    private final String type;
    @JsonProperty
    private final String failedAgainst;
    @JsonProperty
    private final String reason;
    @JsonProperty
    private final int failingRecords;
    @JsonProperty
    private final long firstDeferredMs;
    @JsonProperty
    private final int timesDeferred;
    @JsonProperty
    private final boolean raised;

    @JsonCreator
    public BacklogStream(@JsonProperty("doc") final DocRef doc,
                         @JsonProperty("inputId") final Long inputId,
                         @JsonProperty("feed") final String feed,
                         @JsonProperty("type") final String type,
                         @JsonProperty("failedAgainst") final String failedAgainst,
                         @JsonProperty("reason") final String reason,
                         @JsonProperty("failingRecords") final Integer failingRecords,
                         @JsonProperty("firstDeferredMs") final Long firstDeferredMs,
                         @JsonProperty("timesDeferred") final Integer timesDeferred,
                         @JsonProperty("raised") final Boolean raised) {
        this.doc = doc;
        this.inputId = Objects.requireNonNullElse(inputId, 0L);
        this.feed = feed;
        this.type = type;
        this.failedAgainst = failedAgainst;
        this.reason = reason;
        this.failingRecords = Objects.requireNonNullElse(failingRecords, 0);
        this.firstDeferredMs = Objects.requireNonNullElse(firstDeferredMs, 0L);
        this.timesDeferred = Objects.requireNonNullElse(timesDeferred, 0);
        this.raised = Objects.requireNonNullElse(raised, false);
    }

    public DocRef getDoc() {
        return doc;
    }

    public long getInputId() {
        return inputId;
    }

    public String getFeed() {
        return feed;
    }

    public String getType() {
        return type;
    }

    /// @return The snapshot the stream was run through and fell short of; null where its learner had no
    /// binding.
    public String getFailedAgainst() {
        return failedAgainst;
    }

    public String getReason() {
        return reason;
    }

    public int getFailingRecords() {
        return failingRecords;
    }

    public long getFirstDeferredMs() {
        return firstDeferredMs;
    }

    /// @return How many times a processing task has tried the stream and deferred it.
    public int getTimesDeferred() {
        return timesDeferred;
    }

    /// @return Whether the stream has waited past the document's maximum deferral age.
    public boolean isRaised() {
        return raised;
    }
}
