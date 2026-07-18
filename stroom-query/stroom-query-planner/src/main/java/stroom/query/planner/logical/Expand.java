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

package stroom.query.planner.logical;

import stroom.query.grammar.ast.AstPosition;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A single-hop graph traversal: for each row of {@code input}, follow edges of {@code edgeType} in
 * {@code direction} and bind the neighbour node to {@code targetVariable} - the bound form of one
 * {@code -[:TYPE]->}/{@code <-[:TYPE]-}/{@code -[:TYPE]-} step in a Cypher path pattern (see
 * {@code docs/temporal-cypher-graph-implementation-plan.md}, Task PoC.2/PoC.3).
 *
 * <p>Physically an index-nested-loop join over the graph's adjacency store (design doc &sect;5.5's
 * {@code expand} operator) - "native traversal" and "join execution" are the same plan, but this node is
 * deliberately distinct from {@link Join}: a hop's access path (an adjacency prefix-scan keyed by
 * {@code edgeType}/{@code direction}) has no equivalent in the relational equi-join shape {@link Join}/
 * {@link EquiKey} models.</p>
 *
 * @param input          never null; the plan producing the rows to expand from (typically a {@link NodeScan} or
 *                       another {@link Expand}).
 * @param edgeType       the relationship type to follow, or {@code null} to match any edge type (an untyped
 *                       pattern, e.g. bare {@code -->}).
 * @param direction      never null.
 * @param targetVariable never null; the Cypher pattern variable bound to the neighbour node reached by this hop.
 * @param position       never null.
 */
public record Expand(
        LogicalPlan input,
        @Nullable String edgeType,
        Direction direction,
        String targetVariable,
        AstPosition position) implements LogicalPlan {

    public Expand {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(targetVariable, "targetVariable");
        Objects.requireNonNull(position, "position");
    }
}
