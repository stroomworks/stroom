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
 * Carried on {@link Query#getJoinSpec} when a compiled query is a join (see
 * Task 6.1a) - the two sides' independently-compiled,
 * otherwise perfectly ordinary {@link SearchRequest}s plus the equi-keys/join type to combine them by.
 * {@code Query.dataSource} on the <b>outer</b> request carries the sentinel {@code "StroomQLJoin"} {@link
 * stroom.docref.DocRef} type that routes execution to a dedicated {@code SearchProvider} - this class is the
 * payload that provider reads to actually run the join; the outer request's own {@code expression}/
 * {@code TableSettings} still carry the query's post-join {@code where}/{@code select}/{@code group}/
 * {@code having}/{@code sort}/{@code limit} exactly as for any single-source query.
 *
 * <p>Deliberately a plain Jackson-annotated class with a {@code Builder}, not a record - matching every other
 * wire type in this GWT-compiled module (see {@link ExplainPlan}'s Javadoc for why).</p>
 */
@JsonPropertyOrder({"left", "right", "joinType", "equiKeys"})
@JsonInclude(Include.NON_NULL)
@Schema(name = "JoinSpec", description = "How to combine two independently-compiled sub-requests into a join")
public class JoinSpec {

    @Schema(description = "The left side's fully-compiled, single-source SearchRequest", required = true)
    @JsonProperty
    private final SearchRequest left;

    @Schema(description = "The right side's fully-compiled, single-source SearchRequest", required = true)
    @JsonProperty
    private final SearchRequest right;

    @JsonProperty
    private final JoinType joinType;

    @Schema(description = "The equi-key field pairs joining the two sides, evaluated as an AND of equalities")
    @JsonProperty
    private final List<JoinEquiKey> equiKeys;

    @JsonCreator
    public JoinSpec(@JsonProperty("left") final SearchRequest left,
                    @JsonProperty("right") final SearchRequest right,
                    @JsonProperty("joinType") final JoinType joinType,
                    @JsonProperty("equiKeys") final List<JoinEquiKey> equiKeys) {
        this.left = Objects.requireNonNull(left, "left");
        this.right = Objects.requireNonNull(right, "right");
        this.joinType = Objects.requireNonNull(joinType, "joinType");
        Objects.requireNonNull(equiKeys, "equiKeys");
        if (equiKeys.isEmpty()) {
            throw new IllegalArgumentException("equiKeys must not be empty");
        }
        this.equiKeys = List.copyOf(equiKeys);
    }

    public SearchRequest getLeft() {
        return left;
    }

    public SearchRequest getRight() {
        return right;
    }

    public JoinType getJoinType() {
        return joinType;
    }

    public List<JoinEquiKey> getEquiKeys() {
        return equiKeys;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof final JoinSpec that)) {
            return false;
        }
        return Objects.equals(left, that.left)
               && Objects.equals(right, that.right)
               && joinType == that.joinType
               && Objects.equals(equiKeys, that.equiKeys);
    }

    @Override
    public int hashCode() {
        return Objects.hash(left, right, joinType, equiKeys);
    }

    @Override
    public String toString() {
        return "JoinSpec{" +
               "left=" + left +
               ", right=" + right +
               ", joinType=" + joinType +
               ", equiKeys=" + equiKeys +
               '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mirrors {@code stroom.query.planner.logical.JoinType} - a deliberate, independent copy at the wire-api
     *  layer, the same way the grammar's {@code AstJoin.JoinType} is also its own independent copy. */
    public enum JoinType {
        INNER,
        LEFT
    }

    /** One {@code left.field = right.field} equi-key, each side already alias-qualified. */
    @JsonPropertyOrder({"leftAlias", "leftField", "rightAlias", "rightField"})
    @JsonInclude(Include.NON_NULL)
    @Schema(name = "JoinEquiKey", description = "One equi-key field pair joining a JoinSpec's two sides")
    public static class JoinEquiKey {

        @JsonProperty
        private final String leftAlias;
        @JsonProperty
        private final String leftField;
        @JsonProperty
        private final String rightAlias;
        @JsonProperty
        private final String rightField;

        @JsonCreator
        public JoinEquiKey(@JsonProperty("leftAlias") final String leftAlias,
                          @JsonProperty("leftField") final String leftField,
                          @JsonProperty("rightAlias") final String rightAlias,
                          @JsonProperty("rightField") final String rightField) {
            this.leftAlias = Objects.requireNonNull(leftAlias, "leftAlias");
            this.leftField = Objects.requireNonNull(leftField, "leftField");
            this.rightAlias = Objects.requireNonNull(rightAlias, "rightAlias");
            this.rightField = Objects.requireNonNull(rightField, "rightField");
        }

        public String getLeftAlias() {
            return leftAlias;
        }

        public String getLeftField() {
            return leftField;
        }

        public String getRightAlias() {
            return rightAlias;
        }

        public String getRightField() {
            return rightField;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof final JoinEquiKey that)) {
                return false;
            }
            return Objects.equals(leftAlias, that.leftAlias)
                   && Objects.equals(leftField, that.leftField)
                   && Objects.equals(rightAlias, that.rightAlias)
                   && Objects.equals(rightField, that.rightField);
        }

        @Override
        public int hashCode() {
            return Objects.hash(leftAlias, leftField, rightAlias, rightField);
        }

        @Override
        public String toString() {
            return leftAlias + "." + leftField + " = " + rightAlias + "." + rightField;
        }
    }

    public static final class Builder {

        private SearchRequest left;
        private SearchRequest right;
        private JoinType joinType;
        private List<JoinEquiKey> equiKeys = new ArrayList<>();

        private Builder() {
        }

        public Builder left(final SearchRequest left) {
            this.left = left;
            return this;
        }

        public Builder right(final SearchRequest right) {
            this.right = right;
            return this;
        }

        public Builder joinType(final JoinType joinType) {
            this.joinType = joinType;
            return this;
        }

        public Builder equiKeys(final List<JoinEquiKey> equiKeys) {
            this.equiKeys = new ArrayList<>(equiKeys);
            return this;
        }

        public Builder addEquiKey(final JoinEquiKey equiKey) {
            this.equiKeys.add(equiKey);
            return this;
        }

        public JoinSpec build() {
            return new JoinSpec(left, right, joinType, equiKeys);
        }
    }
}
