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

/**
 * A node in the logical query plan tree (see {@code docs/query-optimiser-implementation-plan.md}, Task 2.1).
 * Every node type mirrors a stage of the design doc's target compilation pipeline: {@link Scan} (a datasource
 * read), {@link Filter} (row-level {@code where}/{@code filter} predicates), {@link Project} (the {@code eval} +
 * {@code select} columns), {@link Join}, {@link Aggregate} ({@code group by}), {@link Having} (a post-aggregation
 * predicate), {@link Window}, {@link Sort}, and {@link Limit}. Nodes with an input form a tree rooted at the
 * final clause applied (typically {@link Limit} or {@link Project}), with {@link Scan} at the leaves.
 *
 * <p>{@link NodeScan}, {@link Expand}, and {@link VarLengthExpand} are the graph front-end's leaf/hop nodes (see
 * {@code docs/temporal-cypher-graph-implementation-plan.md}, Task PoC.2) - Cypher's analogue of {@link Scan} and
 * the graph's traversal operators, added to this same IR (rather than a forked one) so the graph reuses the
 * shared planner, rewrite rules, and cost model unchanged.</p>
 */
public sealed interface LogicalPlan
        permits Scan, Filter, Project, Join, Aggregate, Having, Window, Sort, Limit,
        NodeScan, Expand, VarLengthExpand {

    /**
     * @return never null; the position of the StroomQL clause this node was bound from, for error/EXPLAIN
     *         reporting.
     */
    AstPosition position();
}
