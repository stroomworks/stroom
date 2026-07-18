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

import stroom.query.planner.logical.LogicalPlan;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * The result of {@link CypherToLogicalPlan#compile}: the bound {@link LogicalPlan} plus the query's resolved
 * temporal context, if it had one. Kept as a pair rather than folding {@link TemporalContext} into a
 * {@link LogicalPlan} node because it is a per-query execution context (design doc &sect;5.4), not a stage of
 * the plan tree.
 *
 * @param plan            never null.
 * @param temporalContext {@code null} if the query had no {@code AS OF}/{@code AROUND}/{@code BETWEEN} clause
 *                        (execution then reads the graph's latest state).
 */
public record CompiledCypherPlan(LogicalPlan plan, @Nullable TemporalContext temporalContext) {

    public CompiledCypherPlan {
        Objects.requireNonNull(plan, "plan");
    }
}
