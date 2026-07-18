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
import stroom.query.api.datasource.QueryFieldProvider;
import stroom.query.language.functions.ExpressionContext;
import stroom.security.api.SecurityContext;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jspecify.annotations.NullMarked;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A {@link QueryCompiler} that compiles StroomQL via the ANTLR grammar (see {@code stroom-query-grammar}) rather
 * than the legacy hand-coded {@link SearchRequestFactory}. Aims for exact output parity with the legacy compiler
 * for every construct the parity corpus exercises (see {@code docs/query-optimiser-implementation-plan.md},
 * Task 1.4) - actual compilation work is delegated to a fresh {@link AstToSearchRequestMapper} per call, since
 * that class holds per-compile mutable state and is not reusable.
 */
@NullMarked
public class OptimisingQueryCompiler implements QueryCompiler {

    private final VisualisationTokenConsumer visualisationTokenConsumer;
    private final DataSourceResolver dataSourceResolver;
    private final Provider<QueryFieldProvider> queryFieldProviderProvider;
    private final SecurityContext securityContext;

    /**
     * @param visualisationTokenConsumer must not be null.
     * @param dataSourceResolver         must not be null.
     * @param queryFieldProviderProvider must not be null.
     * @param securityContext            must not be null.
     */
    @Inject
    public OptimisingQueryCompiler(final VisualisationTokenConsumer visualisationTokenConsumer,
                                   final DataSourceResolver dataSourceResolver,
                                   final Provider<QueryFieldProvider> queryFieldProviderProvider,
                                   final SecurityContext securityContext) {
        this.visualisationTokenConsumer =
                Objects.requireNonNull(visualisationTokenConsumer, "visualisationTokenConsumer");
        this.dataSourceResolver = Objects.requireNonNull(dataSourceResolver, "dataSourceResolver");
        this.queryFieldProviderProvider =
                Objects.requireNonNull(queryFieldProviderProvider, "queryFieldProviderProvider");
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext");
    }

    @Override
    public SearchRequest create(final String query, final SearchRequest in, final ExpressionContext expressionContext) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(expressionContext, "expressionContext");
        return newMapper().create(query, in, expressionContext);
    }

    @Override
    public void extractDataSourceOnly(final String query, final Consumer<DocRef> consumer) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(consumer, "consumer");
        newMapper().extractDataSourceOnly(query, consumer);
    }

    private AstToSearchRequestMapper newMapper() {
        return new AstToSearchRequestMapper(
                visualisationTokenConsumer, dataSourceResolver, queryFieldProviderProvider, securityContext);
    }
}
