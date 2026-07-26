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

package stroom.query.planner.cypher;

import stroom.query.api.ExpressionOperator;
import stroom.query.planner.logical.Direction;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * A compiled {@code [NOT] EXISTS { (x)-[:TYPE]->(y) }} correlated existence subquery, carried on
 * {@link CompiledCypherPlan} as a graph-local {@code WHERE} predicate (like {@link FieldComparison}) because the
 * shared {@code ExpressionTerm} IR cannot express a traversal. For each traversal row the executor resolves
 * {@link #anchorVariable}'s bound node and tests whether it has an edge of {@link #edgeType} in {@link #direction}
 * to a neighbour satisfying {@link #targetLabels} / {@link #targetPropertyPredicate}; {@link #negated} inverts the
 * result ({@code NOT EXISTS}).
 *
 * @param anchorVariable          never null; a node variable bound by the outer {@code MATCH}.
 * @param edgeType                never null; the required edge type.
 * @param direction               never null; edge direction from the anchor.
 * @param targetLabels            never null (possibly empty); labels the neighbour must carry.
 * @param targetPropertyPredicate {@code null} if the inner target node has no inline {@code {k: v}} constraint.
 * @param negated                 {@code true} for {@code NOT EXISTS} (row kept when the pattern does NOT exist).
 */
public record CypherExists(
        String anchorVariable,
        String edgeType,
        Direction direction,
        List<String> targetLabels,
        @Nullable ExpressionOperator targetPropertyPredicate,
        boolean negated) {

    public CypherExists {
        Objects.requireNonNull(anchorVariable, "anchorVariable");
        Objects.requireNonNull(edgeType, "edgeType");
        Objects.requireNonNull(direction, "direction");
        targetLabels = targetLabels == null ? List.of() : List.copyOf(targetLabels);
    }
}
