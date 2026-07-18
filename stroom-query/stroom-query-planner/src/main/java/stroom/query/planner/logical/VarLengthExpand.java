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
 * A bounded variable-length graph traversal: for each row of {@code input}, follow between {@code minHops} and
 * {@code maxHops} edges of {@code edgeType} in {@code direction} and bind each reachable neighbour node to
 * {@code targetVariable} - the bound form of a Cypher {@code -[:TYPE*min..max]->} pattern (see
 * {@code docs/temporal-cypher-graph-implementation-plan.md}, Task PoC.2; {@code Cypher.g4} makes {@code maxHops}
 * mandatory at parse time, so an unbounded path can never reach this node).
 *
 * <p>This IR node exists from Task PoC.2 so PoC.3's compiled plans type-check even though nothing executes it
 * until P3's bounded transitive-closure (BFS/DFS with a visited-set cycle guard) operator lands - the one
 * operator the equi-join core does not already provide (design doc &sect;5.5 item 4). Until then, a compiled
 * plan containing this node is rejected at compile time with a "not in PoC subset" error (Task PoC.3), not
 * silently mis-executed.</p>
 *
 * @param input          never null; the plan producing the rows to expand from.
 * @param edgeType       the relationship type to follow, or {@code null} to match any edge type.
 * @param direction      never null.
 * @param minHops        &ge; 0 (Cypher's own default when omitted is 1, resolved by the compiler, not this node).
 * @param maxHops        &ge; {@code minHops}; always a concrete, finite bound.
 * @param targetVariable never null; the Cypher pattern variable bound to each neighbour node reached.
 * @param position       never null.
 */
public record VarLengthExpand(
        LogicalPlan input,
        @Nullable String edgeType,
        Direction direction,
        int minHops,
        int maxHops,
        String targetVariable,
        AstPosition position) implements LogicalPlan {

    public VarLengthExpand {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(targetVariable, "targetVariable");
        Objects.requireNonNull(position, "position");
        if (minHops < 0) {
            throw new IllegalArgumentException("minHops must not be negative, was " + minHops);
        }
        if (maxHops < minHops) {
            throw new IllegalArgumentException(
                    "maxHops (" + maxHops + ") must not be less than minHops (" + minHops + ")");
        }
    }
}
