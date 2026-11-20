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
import stroom.util.shared.SerialisationTestConstructor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/**
 * What serves a learner (A124): a pipeline fragment, referenced by DocRef (A20), learned by it and bound
 * to it. Nothing selects among bindings any more: a learner is served by its binding, or waits on its
 * draft.
 * <p>
 * A binding carries the little history rebinding needs: when the fragment was promoted, the score it was
 * promoted on, and the one state a learned binding passes through before it is simply bound — a
 * <em>draft</em> the learner's streams wait on until it is approved (A25), whether by a person or, on an
 * automatic Learning Flow, by the system at once (simplification option A).
 * A binding has a {@code uuid} so that the runtime state of A26 can name it stably.
 */
@JsonPropertyOrder({
        "uuid",
        "pipeline",
        "draft",
        "promotedTimeMs",
        "score",
        "version"})
@JsonInclude(Include.NON_NULL)
public class Binding {

    /**
     * Null until the store or the supervisor assigns one; the client never generates it.
     */
    @JsonProperty
    private final String uuid;
    /**
     * The fragment: a Pipeline document with no destination.
     */
    @JsonProperty
    private final DocRef pipeline;
    /**
     * Written by a stage in review mode; the learner's streams wait on it until Approve clears it (A25).
     * This flag is authoritative; the learner's own state mirrors it.
     */
    @JsonProperty
    private final boolean draft;
    @JsonProperty
    private final Long promotedTimeMs;
    @JsonProperty
    private final Double score;
    /**
     * What one record is in the stream, as the variant was learned (A35): the stage counts a stream's
     * records and judges yield by it. Null for raw text, whose parser cuts the records, and for bindings
     * written before it was carried, which count the root's children as before.
     */
    @JsonProperty
    private final RecordBoundary recordBoundary;
    /// The snapshot of the fragment's configuration this binding serves with (A61): what a deferral row is
    /// stamped with and what an output records. Null for a binding written before snapshots existed, which
    /// is snapshotted the first time it serves.
    @JsonProperty
    private final String snapshot;
    /**
     * The version of the row this binding was read at, for a {@code replace} to check it has not moved
     * since (audit finding W7-3); null for a binding built rather than read, which {@code append} and an
     * unchecked {@code replace} take as "no opinion".
     * <p>
     * Without it {@code replace} was last-writer-wins however carefully it took the row lock: a node
     * writing a binding from its cache would write the whole row back, reverting a rebind another node
     * had made to the pipeline and the snapshot in between.
     */
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

    /// A binding with no version: one built rather than read from a row.
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

    /// The snapshot this binding serves with (A61), or null for a binding written before snapshots existed.
    public String getSnapshot() {
        return snapshot;
    }

    /// @return The version of the row this was read at, for a `replace` to check it has not moved since
    ///         (W7-3); null for a binding built rather than read.
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

        /// The snapshot the binding serves with (A61).
        public Builder snapshot(final String snapshot) {
            this.snapshot = snapshot;
            return this;
        }

        /// The version of the row it was read at (W7-3).
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
