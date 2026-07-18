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

package stroom.graphdb.impl;

import stroom.query.api.GraphSpec;
import stroom.query.api.Query;
import stroom.query.api.SearchRequest;
import stroom.query.grammar.parse.CypherQueryParser;
import stroom.query.language.functions.ExpressionContext;
import stroom.query.planner.cypher.CypherToLogicalPlan;

import java.util.Objects;

/**
 * The Cypher analogue of {@code stroom.query.language.QueryCompiler}/{@code OptimisingQueryCompiler} (Task
 * PoC.6): turns Cypher source text into a {@link SearchRequest} that {@link GraphSearchProvider} can execute.
 *
 * <p>Deliberately does <b>not</b> implement the {@code QueryCompiler} interface (which lives in
 * {@code stroom-query-common} and also declares {@code extractDataSourceOnly}/{@code explain}) - routing a
 * submitted query to this seam vs. the StroomQL one, and an {@code ExplainPlan} rendering for Cypher, are later-
 * phase concerns (see the implementation plan's P5+ outline) out of scope for a single-hop PoC.</p>
 */
public final class CypherCompiler {

    /**
     * <b>Preconditions:</b> none of the three parameters is null; {@code in.getQuery()} is not null and its
     * {@code dataSource} already names the target {@code GraphDbDoc} - a Cypher query has no {@code FROM}-
     * equivalent clause of its own, so the target graph is always the doc the query was submitted against.
     * <b>Postconditions:</b> the returned request's {@code Query.dataSource} is unchanged from {@code in}, and
     * its {@code Query.graphSpec} carries {@code cypher} verbatim - see {@link GraphSpec}'s Javadoc for why the
     * compiled plan itself is not attached to the wire request. Throws {@link
     * stroom.query.planner.cypher.CypherCompileException} or a grammar {@code SyntaxException} for a query
     * outside the locked v1 subset, so a bad query fails fast here rather than reaching {@link
     * GraphSearchProvider} silently.
     * <b>Null status:</b> no parameter or the return value is nullable.
     *
     * @param cypher            never null; the Cypher source text.
     * @param in                never null; only {@code Query.dataSource} is read from it - everything else
     *                          (params, time range) is carried through unchanged.
     * @param expressionContext never null; accepted for parity with the StroomQL compiler seam - unused because
     *                          the v1 Cypher subset has no expression-context-dependent terms (e.g. dashboard
     *                          {@code ${param}} substitution) of its own.
     * @return never null.
     */
    public SearchRequest create(final String cypher,
                                 final SearchRequest in,
                                 final ExpressionContext expressionContext) {
        Objects.requireNonNull(cypher, "cypher");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(expressionContext, "expressionContext");
        final Query inQuery = Objects.requireNonNull(in.getQuery(), "in.getQuery()");

        // Fail fast: parse + compile now so a query outside the v1 subset is rejected at compile time, not
        // silently deferred to GraphSearchProvider re-parsing it at execution time.
        new CypherToLogicalPlan().compile(CypherQueryParser.parse(cypher));

        final Query query = inQuery.copy()
                .graphSpec(GraphSpec.builder().cypher(cypher).build())
                .build();
        return in.copy().query(query).build();
    }
}
