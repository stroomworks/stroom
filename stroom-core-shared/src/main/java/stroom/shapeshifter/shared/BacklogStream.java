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

package stroom.shapeshifter.shared;

import stroom.docref.DocRef;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

/// One stream of the Supervisor's backlog (design 04 §6): a stream that is waiting on a session, with what
/// it was run through and fell short of, since when, how often it has been tried, and whether it has
/// waited past the document's maximum deferral age (A57, A78, A79) — raised, and still waiting.
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
    private final int tries;
    @JsonProperty
    private final boolean raised;

    @JsonCreator
    public BacklogStream(@JsonProperty("doc") final DocRef doc,
                         @JsonProperty("inputId") final long inputId,
                         @JsonProperty("feed") final String feed,
                         @JsonProperty("type") final String type,
                         @JsonProperty("failedAgainst") final String failedAgainst,
                         @JsonProperty("reason") final String reason,
                         @JsonProperty("failingRecords") final int failingRecords,
                         @JsonProperty("firstDeferredMs") final long firstDeferredMs,
                         @JsonProperty("tries") final int tries,
                         @JsonProperty("raised") final boolean raised) {
        this.doc = doc;
        this.inputId = inputId;
        this.feed = feed;
        this.type = type;
        this.failedAgainst = failedAgainst;
        this.reason = reason;
        this.failingRecords = failingRecords;
        this.firstDeferredMs = firstDeferredMs;
        this.tries = tries;
        this.raised = raised;
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

    /// The snapshot the stream was run through and fell short of; null where nothing bound its learner.
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

    /// How many times a task has decided the stream and deferred it.
    public int getTries() {
        return tries;
    }

    /// Whether the stream has waited past the document's maximum deferral age (A57).
    public boolean isRaised() {
        return raised;
    }
}
