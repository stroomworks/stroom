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

package stroom.query.grammar.ast.cypher;

import stroom.query.grammar.ast.AstPosition;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * The root AST node for a whole Cypher query: an optional leading {@code from "X"} datasource selector (Workstream
 * A), followed by one or more reading clauses ({@code MATCH}/
 * {@code WITH}, in source order) and exactly one {@code RETURN} clause. The grammar accepts the full locked v1
 * subset (multiple stages, chains, variable-length paths); {@code CypherToLogicalPlan} compiles fixed-length
 * multi-hop chains and bounded variable-length paths within a single reading clause (Tasks P3.2/P3.3), but a query
 * with more than one reading clause is still rejected with a clear "not yet supported" error at compile time.
 * {@code CypherToLogicalPlan} does not read {@link #dataSourceName} at all - it is purely a datasource selector,
 * consumed instead by {@code CypherCompiler} when the target graph is not already pre-set on the incoming request.
 *
 * @param dataSourceName nullable; the unescaped name from an optional leading {@code from "X"} clause, or
 *                        {@code null} if the query has none.
 * @param readingClauses never null; possibly empty in theory but never empty in practice - the grammar requires
 *                        at least one.
 * @param returnClause    never null.
 * @param position        never null.
 */
public record AstCypherQuery(
        @Nullable String dataSourceName,
        List<AstReadingClause> readingClauses,
        AstReturnClause returnClause,
        AstPosition position) {

    public AstCypherQuery {
        Objects.requireNonNull(readingClauses, "readingClauses");
        Objects.requireNonNull(returnClause, "returnClause");
        Objects.requireNonNull(position, "position");
        readingClauses = List.copyOf(readingClauses);
    }
}
