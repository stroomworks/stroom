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

/**
 * The traversal direction of an {@link Expand}/{@link VarLengthExpand} hop, mirroring
 * {@code stroom.query.grammar.ast.cypher.AstEdgeDirection} (see
 * {@code docs/temporal-cypher-graph-implementation-plan.md}, Task PoC.2) - same values, same names, so
 * {@code CypherToLogicalPlan} (Task PoC.3) maps between them 1:1. Kept as a separate enum here rather than
 * reused directly: the logical IR is deliberately front-end-agnostic (both StroomQL and Cypher compile into it,
 * per {@link LogicalPlan}'s class Javadoc), so it must not reference a specific front-end's AST types even
 * though this module's build graph could technically permit it.
 */
public enum Direction {
    OUT,
    IN,
    BOTH
}
