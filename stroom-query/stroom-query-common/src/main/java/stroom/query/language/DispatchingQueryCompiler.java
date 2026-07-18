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
import stroom.query.common.v2.QueryOptimiserMode;
import stroom.query.language.functions.ExpressionContext;
import stroom.util.json.JsonUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A {@link QueryCompiler} that selects {@link LegacyQueryCompiler} or {@link OptimisingQueryCompiler} on every
 * call based on the live value of {@code stroom.query.optimiser.mode} (see
 * {@code docs/query-optimiser-implementation-plan.md}, Tasks 1.5 and 5.4). The flag is re-read per call (not
 * cached at construction) so a config change takes effect immediately, without requiring a restart.
 *
 * <p>{@link QueryOptimiserMode#SHADOW} only changes {@link #create}'s behaviour: legacy still compiles and its
 * result is always what's returned/served (identical to {@link QueryOptimiserMode#OFF} from the caller's point
 * of view), but the optimising compiler also runs, best-effort and fail-open, purely to log any divergence.
 * {@link #extractDataSourceOnly}/{@link #explain} treat {@code SHADOW} the same as {@code OFF} - there's nothing
 * to shadow-diff for a datasource-only extraction or an already-advisory-only {@code explain()} call.</p>
 */
@NullMarked
public class DispatchingQueryCompiler implements QueryCompiler {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(DispatchingQueryCompiler.class);

    private final LegacyQueryCompiler legacyQueryCompiler;
    private final OptimisingQueryCompiler optimisingQueryCompiler;
    private final Provider<QueryOptimiserConfig> queryOptimiserConfigProvider;

    /**
     * @param legacyQueryCompiler          must not be null; used while the mode is {@code OFF} or {@code SHADOW}
     *                                     (the default is {@code OFF}).
     * @param optimisingQueryCompiler      must not be null; used while the mode is {@code ON} (and, best-effort,
     *                                     alongside legacy while the mode is {@code SHADOW}).
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
        final QueryOptimiserMode mode = queryOptimiserConfigProvider.get().getMode();
        if (mode == QueryOptimiserMode.ON) {
            return optimisingQueryCompiler.create(query, in, expressionContext);
        }
        final SearchRequest legacyResult = legacyQueryCompiler.create(query, in, expressionContext);
        if (mode == QueryOptimiserMode.SHADOW) {
            shadowCompileAndLog(query, in, expressionContext, legacyResult);
            logEstimatedDuration(query, expressionContext);
        }
        return legacyResult;
    }

    @Override
    public void extractDataSourceOnly(final String query, final Consumer<DocRef> consumer) {
        servingDelegate().extractDataSourceOnly(query, consumer);
    }

    @Override
    public ExplainPlan explain(final String query, final ExpressionContext expressionContext) {
        return servingDelegate().explain(query, expressionContext);
    }

    private QueryCompiler servingDelegate() {
        return queryOptimiserConfigProvider.get().getMode() == QueryOptimiserMode.ON
                ? optimisingQueryCompiler
                : legacyQueryCompiler;
    }

    /**
     * Best-effort, fail-open: compiles {@code query} with the optimising compiler purely to log any divergence
     * from {@code legacyResult} - the result actually served. Must never affect what's served in any way,
     * including by throwing - see docs/query-optimiser-implementation-plan.md, Task 5.4.
     */
    private void shadowCompileAndLog(
            final String query,
            final SearchRequest in,
            final ExpressionContext expressionContext,
            final SearchRequest legacyResult) {
        try {
            final SearchRequest optimisingResult = optimisingQueryCompiler.create(query, in, expressionContext);
            final String legacyJson = JsonUtil.writeValueAsConsistentString(legacyResult);
            final String optimisingJson = JsonUtil.writeValueAsConsistentString(optimisingResult);
            if (legacyJson.equals(optimisingJson)) {
                LOGGER.debug(() -> "Shadow mode: optimising compiler matched legacy for query [" + query + "]");
            } else {
                LOGGER.info(() -> "Shadow mode: optimising compiler diverged from legacy for query [" + query + "]"
                                   + "\nlegacy: " + legacyJson
                                   + "\noptimising: " + optimisingJson);
            }
        } catch (final RuntimeException e) {
            LOGGER.debug(() -> "Shadow mode: optimising compiler failed for query [" + query + "]: "
                                + e.getMessage(), e);
        }
    }

    /**
     * Task 5.5 (the estimate half only - see docs/query-optimiser-implementation-plan.md, Phase 5, for why the
     * "actual duration" half is deferred: it needs a completion-time hook in the shared, engine-agnostic
     * {@code ResultStoreManager}, and a way to correlate that back to this compile-time estimate given a
     * {@code QueryKey} doesn't exist yet at this point - a bigger, separately-planned change, not a same-day
     * addition here). Logs the optimiser's {@code CostModel}-estimated duration for {@code query}, one step
     * towards the design doc's "actual-vs-estimated" telemetry-calibration goal. Best-effort and fail-open, same
     * as the rest of shadow mode - an estimate failure must never affect what's served.
     */
    private void logEstimatedDuration(final String query, final ExpressionContext expressionContext) {
        try {
            final ExplainPlan plan = optimisingQueryCompiler.explain(query, expressionContext);
            final Long estimatedDurationMs = findEstimatedDurationMs(plan);
            if (estimatedDurationMs != null) {
                LOGGER.info(() -> "Shadow mode: optimiser estimated " + estimatedDurationMs
                                   + "ms for query [" + query + "]");
            }
        } catch (final RuntimeException e) {
            LOGGER.debug(() -> "Shadow mode: unable to estimate duration for query [" + query + "]: "
                                + e.getMessage(), e);
        }
    }

    /** The first (there's at most one before Phase 6 joins land) node in {@code plan} carrying a cost estimate. */
    private static @Nullable Long findEstimatedDurationMs(final ExplainPlan plan) {
        if (plan.getEstimatedDurationMs() != null) {
            return plan.getEstimatedDurationMs();
        }
        if (plan.getChildren() != null) {
            for (final ExplainPlan child : plan.getChildren()) {
                final Long found = findEstimatedDurationMs(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
