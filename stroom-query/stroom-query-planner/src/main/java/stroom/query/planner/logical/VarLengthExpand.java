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

import stroom.query.api.ExpressionOperator;
import stroom.query.grammar.ast.AstPosition;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * A bounded variable-length graph traversal: for each row of {@code input}, follow between {@code minHops} and
 * {@code maxHops} edges of {@code edgeType} in {@code direction} and bind each reachable neighbour node to
 * {@code targetVariable} - the bound form of a Cypher {@code -[:TYPE*min..max]->} pattern (see
 * Task PoC.2; {@code Cypher.g4} makes {@code maxHops}
 * mandatory at parse time, so an unbounded path can never reach this node).
 *
 * <p>Task P3.3 is the first thing that actually produces/consumes this node - see this node's own history: it
 * existed from PoC.2 purely so PoC.3's compiled plans type-checked, with every compiled plan containing it
 * rejected at compile time until P3.3 landed the bounded transitive-closure (BFS with a visited-set cycle guard)
 * operator that executes it (design doc &sect;5.5 item 4 - the one operator the equi-join core does not already
 * provide).</p>
 *
 * <p>{@code targetLabels}/{@code targetPropertyPredicate} (Task P3.1) mirror {@link Expand}'s own fields of the
 * same name/purpose - added here alongside {@link Expand}'s rather than as a later, separate change, since both
 * IR types are structural siblings and every rewrite-rule call site reconstructing one already needed touching
 * for the other.</p>
 *
 * @param input          never null; the plan producing the rows to expand from.
 * @param edgeType       the relationship type to follow, or {@code null} to match any edge type.
 * @param direction      never null.
 * @param minHops        &ge; 0 (Cypher's own default when omitted is 1, resolved by the compiler, not this node).
 * @param maxHops        &ge; {@code minHops}; always a concrete, finite bound.
 * @param targetVariable never null; the Cypher pattern variable bound to each neighbour node reached.
 * @param targetLabels   never null; possibly empty (no label constraint on a reached node); in source order.
 * @param targetPropertyPredicate a reached node's inline property map, lowered to an equality predicate tree
 *                       exactly as {@link Expand#targetPropertyPredicate} is, or {@code null} if none.
 * @param position       never null.
 */
public record VarLengthExpand(
        LogicalPlan input,
        @Nullable String edgeType,
        Direction direction,
        int minHops,
        int maxHops,
        String targetVariable,
        List<String> targetLabels,
        @Nullable ExpressionOperator targetPropertyPredicate,
        AstPosition position) implements LogicalPlan {

    public VarLengthExpand {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(targetVariable, "targetVariable");
        Objects.requireNonNull(targetLabels, "targetLabels");
        Objects.requireNonNull(position, "position");
        if (minHops < 0) {
            throw new IllegalArgumentException("minHops must not be negative, was " + minHops);
        }
        if (maxHops < minHops) {
            throw new IllegalArgumentException(
                    "maxHops (" + maxHops + ") must not be less than minHops (" + minHops + ")");
        }
        targetLabels = List.copyOf(targetLabels);
    }
}
