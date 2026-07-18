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

package stroom.query.language;

import stroom.docref.DocRef;
import stroom.query.api.ExplainPlan;
import stroom.query.api.SearchRequest;
import stroom.query.language.functions.ExpressionContext;

import org.jspecify.annotations.NullMarked;

import java.util.function.Consumer;

/**
 * Compiles StroomQL text into a {@link SearchRequest}.
 *
 * <p>This is the single seam through which all StroomQL is turned into a {@link SearchRequest}, both for
 * validation and for execution (see {@code docs/query-optimiser-implementation-plan.md}, Task 0.2). Two
 * implementations exist: {@link LegacyQueryCompiler}, which delegates to the hand-coded
 * {@link SearchRequestFactory}, and (from Phase 1 onward) an ANTLR-grammar-driven implementation. Callers must
 * not depend on which implementation is bound - both are required to produce equivalent results for any given
 * query (see the design doc's dual-run parity section).</p>
 */
@NullMarked
public interface QueryCompiler {

    /**
     * Compiles {@code query} into a full {@link SearchRequest}, using {@code in} as the seed request to
     * copy/merge fields (such as result requests) from.
     *
     * @param query the StroomQL text to compile. Must not be blank in a way the implementation rejects (e.g. an
     *              empty or all-whitespace query is rejected); malformed queries are reported by throwing a
     *              {@link RuntimeException} describing the problem, not by returning null.
     * @param in    the seed {@link SearchRequest} whose non-query fields (e.g. result requests, date/time
     *              settings) are carried into the returned request.
     * @param expressionContext context used to resolve {@code ${param}} references and current-time functions
     *              during compilation.
     * @return a new, fully-populated {@link SearchRequest}. Never null on normal return; compilation failures are
     *         reported via a thrown exception.
     */
    SearchRequest create(String query, SearchRequest in, ExpressionContext expressionContext);

    /**
     * Extracts only the data source {@link DocRef} referenced by a query's {@code from} clause, without
     * compiling the rest of the query. Used where the caller needs to know which datasource a query targets
     * (e.g. to resolve fields) before a full compile is warranted.
     *
     * @param query    the StroomQL text to inspect. Same acceptance rules as {@link #create}.
     * @param consumer callback invoked at most once, with the resolved {@link DocRef}, if and only if the query's
     *                 {@code from} clause resolves to one. Never invoked with a null argument.
     */
    void extractDataSourceOnly(String query, Consumer<DocRef> consumer);

    /**
     * Explains how {@code query} would be compiled/executed, with a cost estimate where one is available - see
     * {@code docs/query-optimiser-implementation-plan.md}, Task 4.1. A genuinely new operation (not a changed
     * {@link #create}): never used to drive execution, only to inform a user before they run a query.
     *
     * @param query              the StroomQL text to explain. Same acceptance rules as {@link #create}.
     * @param expressionContext context used the same way as in {@link #create}.
     * @return never null; a tree with one node per logical operator. Cost figures
     *         ({@code estimatedRows}/{@code estimatedDurationMs}/{@code confidence}) are present only where a
     *         cost model actually produced one - implementations with no cost model (e.g. the legacy engine)
     *         return a plan with descriptive text only, never a fabricated number. Malformed queries are
     *         reported by throwing, exactly as {@link #create} does.
     */
    ExplainPlan explain(String query, ExpressionContext expressionContext);
}
