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

import java.util.List;
import java.util.Objects;

/**
 * A binary join of two plans. An N-way {@code from}/{@code join} chain binds to a left-deep chain of nested
 * {@code Join} nodes in source order (e.g. three sources bind to {@code Join(Join(A,B),C)}) - reordering that
 * chain is a Phase 3 (cost-based) concern, not the binder's.
 *
 * <p>Bound and validated from Phase 2 onward even though nothing executes a {@code Join} until Phase 6 - see
 * the design doc's instruction to make {@code Join} "shape-complete" from the start, and
 * which lowers a graph hop to an index-nested-loop join over this same
 * node.</p>
 *
 * @param left      never null.
 * @param right     never null.
 * @param joinType  never null.
 * @param equiKeys  never null; never empty (the grammar requires at least one {@code on} condition).
 * @param position  never null.
 */
public record Join(
        LogicalPlan left,
        LogicalPlan right,
        JoinType joinType,
        List<EquiKey> equiKeys,
        AstPosition position) implements LogicalPlan {

    public Join {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(joinType, "joinType");
        Objects.requireNonNull(equiKeys, "equiKeys");
        Objects.requireNonNull(position, "position");
        if (equiKeys.isEmpty()) {
            throw new IllegalArgumentException("equiKeys must not be empty");
        }
        equiKeys = List.copyOf(equiKeys);
    }
}
