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

import jakarta.inject.Inject;
import org.jspecify.annotations.NullMarked;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A {@link QueryCompiler} that delegates verbatim to the existing hand-coded {@link SearchRequestFactory}.
 *
 * <p>This is the default, always-available implementation: it must remain behaviourally indistinguishable from
 * calling {@link SearchRequestFactory} directly, since it is the legacy engine that every other
 * {@link QueryCompiler} implementation is validated against (see the design doc's dual-run parity section).</p>
 */
@NullMarked
public class LegacyQueryCompiler implements QueryCompiler {

    private final SearchRequestFactory searchRequestFactory;

    /**
     * @param searchRequestFactory the legacy factory to delegate to. Must not be null.
     */
    @Inject
    public LegacyQueryCompiler(final SearchRequestFactory searchRequestFactory) {
        this.searchRequestFactory = Objects.requireNonNull(searchRequestFactory, "searchRequestFactory");
    }

    /**
     * Delegates directly to {@link SearchRequestFactory#create(String, SearchRequest, ExpressionContext)}.
     *
     * @param query same contract as {@link QueryCompiler#create}.
     * @param in same contract as {@link QueryCompiler#create}.
     * @param expressionContext same contract as {@link QueryCompiler#create}.
     * @return the result of the legacy factory call, unmodified.
     */
    @Override
    public SearchRequest create(final String query,
                                final SearchRequest in,
                                final ExpressionContext expressionContext) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(expressionContext, "expressionContext");
        return searchRequestFactory.create(query, in, expressionContext);
    }

    /**
     * Delegates directly to {@link SearchRequestFactory#extractDataSourceOnly(String, Consumer)}.
     *
     * @param query same contract as {@link QueryCompiler#extractDataSourceOnly}.
     * @param consumer same contract as {@link QueryCompiler#extractDataSourceOnly}.
     */
    @Override
    public void extractDataSourceOnly(final String query, final Consumer<DocRef> consumer) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(consumer, "consumer");
        searchRequestFactory.extractDataSourceOnly(query, consumer);
    }

    /**
     * Legacy has no logical-plan/cost pipeline to reuse, so this degrades gracefully rather than fabricating a
     * cost figure: a single node naming the query's datasource, with no cost estimate at all.
     *
     * @param query same contract as {@link QueryCompiler#explain}.
     * @param expressionContext same contract as {@link QueryCompiler#explain} (unused here - legacy's
     *                          {@link SearchRequestFactory#extractDataSourceOnly} needs no expression context).
     * @return never null; see this method's class-level contract note above.
     */
    @Override
    public ExplainPlan explain(final String query, final ExpressionContext expressionContext) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(expressionContext, "expressionContext");
        final AtomicReference<DocRef> dataSourceRef = new AtomicReference<>();
        extractDataSourceOnly(query, dataSourceRef::set);
        final DocRef docRef = dataSourceRef.get();
        final String name = docRef == null ? "?" : docRef.getName();
        return ExplainPlan.builder()
                .description("Scan " + name + " (legacy engine - no cost estimate available)")
                .build();
    }
}
