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
import stroom.query.common.v2.QueryOptimiserConfig;
import stroom.query.language.functions.ExpressionContext;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jspecify.annotations.NullMarked;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A {@link QueryCompiler} that selects {@link LegacyQueryCompiler} or {@link OptimisingQueryCompiler} on every
 * call based on the live value of {@code stroom.query.optimiser.enabled} (see
 * {@code docs/query-optimiser-implementation-plan.md}, Task 1.5). The flag is re-read per call (not cached at
 * construction) so a config change takes effect immediately, without requiring a restart.
 */
@NullMarked
public class DispatchingQueryCompiler implements QueryCompiler {

    private final LegacyQueryCompiler legacyQueryCompiler;
    private final OptimisingQueryCompiler optimisingQueryCompiler;
    private final Provider<QueryOptimiserConfig> queryOptimiserConfigProvider;

    /**
     * @param legacyQueryCompiler          must not be null; used while the flag is off (the default).
     * @param optimisingQueryCompiler      must not be null; used while the flag is on.
     * @param queryOptimiserConfigProvider must not be null; consulted on every {@link #create}/
     *                                     {@link #extractDataSourceOnly} call, not cached.
     */
    @Inject
    public DispatchingQueryCompiler(final LegacyQueryCompiler legacyQueryCompiler,
                                    final OptimisingQueryCompiler optimisingQueryCompiler,
                                    final Provider<QueryOptimiserConfig> queryOptimiserConfigProvider) {
        this.legacyQueryCompiler = Objects.requireNonNull(legacyQueryCompiler, "legacyQueryCompiler");
        this.optimisingQueryCompiler = Objects.requireNonNull(optimisingQueryCompiler, "optimisingQueryCompiler");
        this.queryOptimiserConfigProvider =
                Objects.requireNonNull(queryOptimiserConfigProvider, "queryOptimiserConfigProvider");
    }

    @Override
    public SearchRequest create(final String query, final SearchRequest in, final ExpressionContext expressionContext) {
        return delegate().create(query, in, expressionContext);
    }

    @Override
    public void extractDataSourceOnly(final String query, final Consumer<DocRef> consumer) {
        delegate().extractDataSourceOnly(query, consumer);
    }

    @Override
    public ExplainPlan explain(final String query, final ExpressionContext expressionContext) {
        return delegate().explain(query, expressionContext);
    }

    private QueryCompiler delegate() {
        return queryOptimiserConfigProvider.get().isEnabled() ? optimisingQueryCompiler : legacyQueryCompiler;
    }
}
