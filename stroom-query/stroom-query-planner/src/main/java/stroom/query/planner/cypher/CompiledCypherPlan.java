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
 * The result of {@link CypherToLogicalPlan#compile}: the bound {@link LogicalPlan}, the query's resolved
 * temporal context (if it had one), whether the {@code RETURN} was {@code DISTINCT}, and its aggregation
 * description (if the {@code RETURN} mixed an aggregate with other items). Kept alongside the plan rather than
 * folded into a {@link LogicalPlan} node because all three are per-query execution concerns (design doc
 * &sect;5.4), not stages of the plan tree - and {@code DISTINCT}/aggregation in particular are Cypher-only
 * concepts the sealed shared IR (used by the relational core too) has no node for.
 *
 * @param plan            never null.
 * @param temporalContext {@code null} if the query had no {@code AS OF}/{@code AROUND}/{@code BETWEEN} clause
 *                        (execution then reads the graph's latest state).
 * @param distinct        whether the {@code RETURN} clause was {@code RETURN DISTINCT} - the executor
 *                        de-duplicates the projected rows when {@code true}.
 * @param aggregation     {@code null} if the {@code RETURN} clause has no aggregate item (the executor's ordinary,
 *                        per-row projection applies unchanged); otherwise describes how to group the traversal's
 *                        rows and reduce each group to one output row - see {@link CypherAggregation}'s Javadoc.
 * @param diffContext     {@code null} unless the query had a {@code DIFF FROM ... TO ...} clause; when non-null the
 *                        executor runs the diff (two {@code AS OF} evaluations + classification) rather than an
 *                        ordinary traversal, and {@code temporalContext} is {@code null} (a query is a state query
 *                        or a diff, never both).
 * @param returnGraph     whether the query was {@code RETURN GRAPH} (see {@code docs/temporal-cypher-diff-operator
 *                        .md} &sect;4.4 / {@code docs/graphdb-cytoscape-visualisation.html} &sect;3) - the
 *                        element-row output mode. When {@code true}, {@code plan}'s terminal {@link
 *                        stroom.query.planner.logical.Project} carries the fixed element-row column schema
 *                        (synthesised by {@code CypherToLogicalPlan}, not user {@code RETURN} items), and the
 *                        executor emits one row per distinct matched node/edge (see {@code GraphElementExecutor})
 *                        instead of one row per scalar-projected match. Orthogonal to {@code diffContext}: either
 *                        can be set independently of the other, and both together is the annotated-subgraph mode.
 */
public record CompiledCypherPlan(
        LogicalPlan plan,
        @Nullable TemporalContext temporalContext,
        boolean distinct,
        @Nullable CypherAggregation aggregation,
        @Nullable DiffContext diffContext,
        boolean returnGraph) {

    public CompiledCypherPlan {
        Objects.requireNonNull(plan, "plan");
    }
}
