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
import stroom.query.api.SearchRequest;
import stroom.query.language.functions.ExpressionContext;

import org.jspecify.annotations.NullMarked;

/**
 * A query-language compiler for a datasource that isn't addressed via StroomQL's own {@code from} clause
 * (implementation plan Task P6.1) - e.g. Cypher, which
 * has no {@code FROM}-equivalent of its own (Decision D4), so its target datasource must instead be known from
 * context (a search's {@code SearchRequestSource.ownerDocRef}) before any compiler is chosen.
 *
 * <p>Deliberately a separate interface from {@link QueryCompiler} (not an alternative implementation of it),
 * even though {@link #create} has the identical shape - {@code QueryCompiler} is injected as a single,
 * unqualified binding everywhere the "one true StroomQL compiler" is needed; keeping this as its own type avoids
 * any risk of an alternative-language implementation accidentally satisfying that injection point instead.</p>
 *
 * <p>Bound as an empty-by-default {@code Set} (mirroring the port/multibinder-discovery pattern already used for
 * {@code SearchProvider}/{@code DataSourceProvider}/the cost ports throughout this codebase - see Task P5.1) so
 * the module binding {@link QueryCompiler}'s consumer needs no compile-time dependency on any specific
 * alternative-language module.</p>
 */
@NullMarked
public interface AlternativeQueryCompiler {

    /**
     * @param dataSourceRef the resolved datasource a search was run against/from - never null when this method is
     *                       called (callers only consult this interface once a non-null ref is known).
     * @return true if this compiler is the one that should handle a query targeting {@code dataSourceRef}.
     */
    boolean supports(DocRef dataSourceRef);

    /**
     * Compiles {@code query} into a full {@link SearchRequest}, exactly as {@link QueryCompiler#create} does -
     * see that method's Javadoc for the general contract. Only ever called after {@link #supports} has already
     * returned true for {@code in.getQuery.getDataSource}.
     *
     * @param query             the source text to compile - in whatever language this compiler understands, not
     *                          necessarily StroomQL.
     * @param in                the seed {@link SearchRequest} whose non-query fields are carried into the
     *                          returned request; its {@code Query.dataSource} is already set to the resolved
     *                          target datasource.
     * @param expressionContext context used the same way {@link QueryCompiler#create} uses it.
     * @return a new, fully-populated {@link SearchRequest}. Never null on normal return; compilation failures are
     *         reported via a thrown exception.
     */
    SearchRequest create(String query, SearchRequest in, ExpressionContext expressionContext);
}
