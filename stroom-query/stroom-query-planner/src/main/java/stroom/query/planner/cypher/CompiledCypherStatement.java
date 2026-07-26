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

import java.util.List;
import java.util.Objects;

/**
 * The result of {@link CypherToLogicalPlan#compileStatement}: one or more compiled query branches combined with
 * {@code UNION} / {@code UNION ALL}. A plain (non-UNION) query is a single-branch statement whose {@link #unionAll}
 * is empty. All branches are guaranteed (checked at compile time) to expose the same output column names, so the
 * executor can advertise {@link #first()}'s columns and fold every branch's rows into one result.
 *
 * @param branches never null, never empty; the compiled single-query branches in source order.
 * @param unionAll never null; size {@code branches.size() - 1}. Entry {@code i} is {@code true} when the union
 *                 between branch {@code i} and branch {@code i + 1} keeps duplicates ({@code UNION ALL}),
 *                 {@code false} for a de-duplicating {@code UNION}.
 */
public record CompiledCypherStatement(
        List<CompiledCypherPlan> branches,
        List<Boolean> unionAll) {

    public CompiledCypherStatement {
        Objects.requireNonNull(branches, "branches");
        Objects.requireNonNull(unionAll, "unionAll");
        if (branches.isEmpty()) {
            throw new IllegalArgumentException("a statement must have at least one branch");
        }
        if (unionAll.size() != branches.size() - 1) {
            throw new IllegalArgumentException("unionAll must have one fewer entry than branches");
        }
        branches = List.copyOf(branches);
        unionAll = List.copyOf(unionAll);
    }

    /** The first (and, for a non-UNION statement, only) branch. Never null. */
    public CompiledCypherPlan first() {
        return branches.getFirst();
    }

    /** True when this is a plain single query (no {@code UNION}). */
    public boolean isSingle() {
        return branches.size() == 1;
    }
}
