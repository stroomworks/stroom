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
import stroom.util.shared.SerialisationTestConstructor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/// What serves a learner: a learned pipeline fragment, referenced by its DocRef, which the Smart Pipeline
/// element runs as a nested pipeline. A learner is served by its one binding, or waits for its draft.
///
/// A binding carries the little history rebinding needs: when the fragment was promoted, the score it was
/// promoted on, and whether it is still a *draft*. Every learned binding starts as a draft, which serves
/// nothing until it is approved, by a person under review mode or by the system at once under automatic
/// promotion. A binding has a `uuid` so that the runtime tables can refer to it reliably.
@JsonPropertyOrder({
        "uuid",
        "pipeline",
        "draft",
        "promotedTimeMs",
        "score",
        "version"})
@JsonInclude(Include.NON_NULL)
public class Binding {

    /// Null until the server assigns one; the client never generates it.
    @JsonProperty
    private final String uuid;
    /// The fragment: a Pipeline document with no destination.
    @JsonProperty
    private final DocRef pipeline;
    /// Whether this binding is a draft awaiting approval; streams nothing else serves wait until it is
    /// approved. This flag is authoritative; the learner's own state mirrors it.
    @JsonProperty
    private final boolean draft;
    @JsonProperty
    private final Long promotedTimeMs;
    @JsonProperty
    private final Double score;
    /// What one record is in the stream, as the binding was learned. The Smart Pipeline element counts a
    /// stream's records and judges yield by it. Null for raw text, whose parser cuts the records, and for
    /// bindings written by an earlier version that did not record it, for which the root's children are
    /// counted.
    @JsonProperty
    private final RecordBoundary recordBoundary;
    /// The snapshot of the fragment's configuration this binding serves with. A deferred stream is stamped
    /// with it and each output records it, so that a stream can later be reprocessed exactly as it was first
    /// processed. Null for a binding written by an earlier version without snapshots, which is snapshotted
    /// the first time it serves.
    @JsonProperty
    private final String snapshot;
    /// The version of the database row this binding was read at, so that a `replace` can check the row has
    /// not changed since (optimistic locking). Null for a binding built rather than read, which `append` and
    /// an unchecked `replace` treat as "don't check".
    ///
    /// Without it, `replace` would be last-writer-wins however carefully it took the row lock: a node writing
    /// a binding from its cache would write the whole row back, reverting a rebind another node had made to
    /// the pipeline and the snapshot in between.
    @JsonProperty
    private final Integer version;

    @JsonCreator
    public Binding(@JsonProperty("uuid") final String uuid,
                       @JsonProperty("pipeline") final DocRef pipeline,
                       @JsonProperty("draft") final Boolean draft,
                       @JsonProperty("promotedTimeMs") final Long promotedTimeMs,
                       @JsonProperty("score") final Double score,
                       @JsonProperty("recordBoundary") final RecordBoundary recordBoundary,
                       @JsonProperty("snapshot") final String snapshot,
                       @JsonProperty("version") final Integer version) {
        this.uuid = uuid;
        this.pipeline = pipeline;
        this.draft = Objects.requireNonNullElse(draft, false);
        this.promotedTimeMs = promotedTimeMs;
        this.score = score;
        this.recordBoundary = recordBoundary;
        this.snapshot = snapshot;
        this.version = version;
    }

    /// Creates a binding with no version, i.e. one built rather than read from a row.
    ///
    /// @param uuid           Its uuid.
    /// @param pipeline       The fragment it runs.
    /// @param draft          Whether it awaits review.
    /// @param promotedTimeMs When it was promoted.
    /// @param score          What it scored.
    /// @param recordBoundary What one record is.
    /// @param snapshot       The configuration it serves with.
    public Binding(final String uuid,
                   final DocRef pipeline,
                   final Boolean draft,
                   final Long promotedTimeMs,
                   final Double score,
                   final RecordBoundary recordBoundary,
                   final String snapshot) {
        this(uuid, pipeline, draft, promotedTimeMs, score, recordBoundary, snapshot, null);
    }

    @SerialisationTestConstructor
    private Binding() {
        this(null, null, null, null, null, null, null, null);
    }

    public String getUuid() {
        return uuid;
    }

    public DocRef getPipeline() {
        return pipeline;
    }

    public boolean isDraft() {
        return draft;
    }

    public Long getPromotedTimeMs() {
        return promotedTimeMs;
    }

    public Double getScore() {
        return score;
    }

    public RecordBoundary getRecordBoundary() {
        return recordBoundary;
    }

    /// @return The snapshot this binding serves with, or null for a binding written before snapshots existed.
    public String getSnapshot() {
        return snapshot;
    }

    /// @return The version of the row this was read at, for a `replace` to check it has not changed since;
    ///         null for a binding built rather than read.
    public Integer getVersion() {
        return version;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final Binding that = (Binding) o;
        return draft == that.draft &&
               Objects.equals(uuid, that.uuid) &&
               Objects.equals(pipeline, that.pipeline) &&
               Objects.equals(promotedTimeMs, that.promotedTimeMs) &&
               Objects.equals(score, that.score) &&
               Objects.equals(recordBoundary, that.recordBoundary) &&
               Objects.equals(snapshot, that.snapshot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid, pipeline, draft, promotedTimeMs, score,
                recordBoundary, snapshot);
    }

    @Override
    public String toString() {
        return "Binding{" +
               "uuid='" + uuid + '\'' +
               ", pipeline=" + pipeline +
               ", draft=" + draft +
               ", promotedTimeMs=" + promotedTimeMs +
               ", score=" + score +
               ", recordBoundary=" + recordBoundary +
               '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder copy() {
        return new Builder(this);
    }


    // --------------------------------------------------------------------------------


    public static final class Builder {

        private Integer version;
        private String uuid;
        private DocRef pipeline;
        private boolean draft;
        private Long promotedTimeMs;
        private Double score;
        private RecordBoundary recordBoundary;
        private String snapshot;

        private Builder() {
        }

        private Builder(final Binding binding) {
            this.uuid = binding.uuid;
            this.pipeline = binding.pipeline;
            this.draft = binding.draft;
            this.promotedTimeMs = binding.promotedTimeMs;
            this.score = binding.score;
            this.recordBoundary = binding.recordBoundary;
            this.snapshot = binding.snapshot;
            this.version = binding.version;
        }

        public Builder uuid(final String uuid) {
            this.uuid = uuid;
            return this;
        }

        public Builder pipeline(final DocRef pipeline) {
            this.pipeline = pipeline;
            return this;
        }

        public Builder draft(final boolean draft) {
            this.draft = draft;
            return this;
        }

        public Builder promotedTimeMs(final Long promotedTimeMs) {
            this.promotedTimeMs = promotedTimeMs;
            return this;
        }

        public Builder score(final Double score) {
            this.score = score;
            return this;
        }

        public Builder recordBoundary(final RecordBoundary recordBoundary) {
            this.recordBoundary = recordBoundary;
            return this;
        }

        /// @param snapshot The snapshot of the fragment's configuration the binding serves with.
        /// @return This builder.
        public Builder snapshot(final String snapshot) {
            this.snapshot = snapshot;
            return this;
        }

        /// The version of the row the binding was read at, for optimistic locking.
        ///
        /// @param version The version, or null for a binding built rather than read.
        /// @return This builder.
        public Builder version(final Integer version) {
            this.version = version;
            return this;
        }

        public Binding build() {
            return new Binding(uuid, pipeline, draft, promotedTimeMs, score,
                    recordBoundary, snapshot, version);
        }
    }
}
