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

package stroom.query.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A node in a query's explain/estimate plan tree (see
 * {@code docs/query-optimiser-implementation-plan.md}, Task 4.1) - one node per logical operator
 * ({@code Scan}/{@code Filter}/{@code Join}/...), with a cost estimate present only on the nodes a
 * {@code CostModel} actually costs (currently {@code Scan}s and {@code Join}s).
 *
 * <p>Deliberately a plain Jackson-annotated class with a {@code Builder}, not a record: this module is
 * GWT-compiled (used directly from {@code stroom-core-client}), and every existing wire type here
 * ({@link ExpressionOperator}, {@link ExpressionTerm}, {@link SearchRequest}, ...) follows this same
 * convention - matching it here rather than introducing the first record into a GWT-compiled module.</p>
 */
@JsonPropertyOrder({"description", "children", "estimatedRows", "estimatedDurationMs", "confidence", "notes"})
@JsonInclude(Include.NON_NULL)
@Schema(name = "ExplainPlan", description = "One node of a query's explain/estimate plan tree")
public class ExplainPlan {

    @Schema(description = "A human-readable description of this plan node, e.g. \"Scan Events (full scan)\"")
    @JsonProperty
    private final String description;

    @JsonProperty
    private final List<ExplainPlan> children;

    @Schema(description = "The estimated matching row count, present only on nodes a cost model actually costs")
    @JsonProperty
    private final Long estimatedRows;

    @JsonProperty
    private final Long estimatedDurationMs;

    @Schema(description = "In [0,1] - 1.0 when a cost port answered directly, lower when a heuristic or "
                           + "fallback was applied, 0.0 when no cost signal was available at all")
    @JsonProperty
    private final Double confidence;

    @Schema(description = "Human-readable provenance for the estimate, e.g. which heuristic/fallback was used")
    @JsonProperty
    private final List<String> notes;

    @JsonCreator
    public ExplainPlan(@JsonProperty("description") final String description,
                       @JsonProperty("children") final List<ExplainPlan> children,
                       @JsonProperty("estimatedRows") final Long estimatedRows,
                       @JsonProperty("estimatedDurationMs") final Long estimatedDurationMs,
                       @JsonProperty("confidence") final Double confidence,
                       @JsonProperty("notes") final List<String> notes) {
        this.description = Objects.requireNonNull(description, "description");
        this.children = children == null ? List.of() : List.copyOf(children);
        this.estimatedRows = estimatedRows;
        this.estimatedDurationMs = estimatedDurationMs;
        this.confidence = confidence;
        this.notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public String getDescription() {
        return description;
    }

    public List<ExplainPlan> getChildren() {
        return children;
    }

    public Long getEstimatedRows() {
        return estimatedRows;
    }

    public Long getEstimatedDurationMs() {
        return estimatedDurationMs;
    }

    public Double getConfidence() {
        return confidence;
    }

    public List<String> getNotes() {
        return notes;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof final ExplainPlan that)) {
            return false;
        }
        return Objects.equals(description, that.description)
               && Objects.equals(children, that.children)
               && Objects.equals(estimatedRows, that.estimatedRows)
               && Objects.equals(estimatedDurationMs, that.estimatedDurationMs)
               && Objects.equals(confidence, that.confidence)
               && Objects.equals(notes, that.notes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(description, children, estimatedRows, estimatedDurationMs, confidence, notes);
    }

    @Override
    public String toString() {
        return "ExplainPlan{" +
               "description='" + description + '\'' +
               ", children=" + children +
               ", estimatedRows=" + estimatedRows +
               ", estimatedDurationMs=" + estimatedDurationMs +
               ", confidence=" + confidence +
               ", notes=" + notes +
               '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String description;
        private List<ExplainPlan> children = new ArrayList<>();
        private Long estimatedRows;
        private Long estimatedDurationMs;
        private Double confidence;
        private List<String> notes = new ArrayList<>();

        private Builder() {
        }

        public Builder description(final String description) {
            this.description = description;
            return this;
        }

        public Builder children(final List<ExplainPlan> children) {
            this.children = new ArrayList<>(children);
            return this;
        }

        public Builder estimatedRows(final Long estimatedRows) {
            this.estimatedRows = estimatedRows;
            return this;
        }

        public Builder estimatedDurationMs(final Long estimatedDurationMs) {
            this.estimatedDurationMs = estimatedDurationMs;
            return this;
        }

        public Builder confidence(final Double confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder notes(final List<String> notes) {
            this.notes = new ArrayList<>(notes);
            return this;
        }

        public ExplainPlan build() {
            return new ExplainPlan(
                    description, children, estimatedRows, estimatedDurationMs, confidence, notes);
        }
    }
}
