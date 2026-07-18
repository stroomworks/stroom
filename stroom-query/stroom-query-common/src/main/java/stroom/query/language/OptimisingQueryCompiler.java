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
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ResultRequest;
import stroom.query.api.SearchRequest;
import stroom.query.api.TableSettings;
import stroom.query.api.TimeRange;
import stroom.query.api.datasource.QueryFieldProvider;
import stroom.query.grammar.ast.AstQuery;
import stroom.query.grammar.parse.StroomQlParser;
import stroom.query.language.functions.ExpressionContext;
import stroom.query.planner.bind.Binder;
import stroom.query.planner.cost.CostModel;
import stroom.query.planner.logical.Aggregate;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.Having;
import stroom.query.planner.logical.Join;
import stroom.query.planner.logical.Limit;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.Project;
import stroom.query.planner.logical.Scan;
import stroom.query.planner.logical.Sort;
import stroom.query.planner.logical.Window;
import stroom.query.planner.port.FieldInfoSource;
import stroom.query.planner.port.IndexShardStats;
import stroom.query.planner.port.MetaStats;
import stroom.query.planner.port.StateStoreStats;
import stroom.query.planner.rewrite.RewritePipeline;
import stroom.security.api.SecurityContext;
import stroom.util.date.DateUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;
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

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(OptimisingQueryCompiler.class);

    private final VisualisationTokenConsumer visualisationTokenConsumer;
    private final DataSourceResolver dataSourceResolver;
    private final Provider<QueryFieldProvider> queryFieldProviderProvider;
    private final SecurityContext securityContext;
    private final FieldInfoSource fieldInfoSource;
    private final MetaStats metaStats;
    private final IndexShardStats indexShardStats;
    private final StateStoreStats stateStoreStats;

    /**
     * @param visualisationTokenConsumer must not be null.
     * @param dataSourceResolver         must not be null.
     * @param queryFieldProviderProvider must not be null.
     * @param securityContext            must not be null.
     * @param fieldInfoSource            must not be null; used by {@link #explain} to bind/rewrite the query
     *                                   (Phases 2-3's pipeline) - not used by {@link #create}.
     * @param metaStats                  must not be null; see {@link #explain}.
     * @param indexShardStats            must not be null; see {@link #explain}.
     * @param stateStoreStats            must not be null; see {@link #explain}.
     */
    @Inject
    public OptimisingQueryCompiler(final VisualisationTokenConsumer visualisationTokenConsumer,
                                   final DataSourceResolver dataSourceResolver,
                                   final Provider<QueryFieldProvider> queryFieldProviderProvider,
                                   final SecurityContext securityContext,
                                   final FieldInfoSource fieldInfoSource,
                                   final MetaStats metaStats,
                                   final IndexShardStats indexShardStats,
                                   final StateStoreStats stateStoreStats) {
        this.visualisationTokenConsumer =
                Objects.requireNonNull(visualisationTokenConsumer, "visualisationTokenConsumer");
        this.dataSourceResolver = Objects.requireNonNull(dataSourceResolver, "dataSourceResolver");
        this.queryFieldProviderProvider =
                Objects.requireNonNull(queryFieldProviderProvider, "queryFieldProviderProvider");
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext");
        this.fieldInfoSource = Objects.requireNonNull(fieldInfoSource, "fieldInfoSource");
        this.metaStats = Objects.requireNonNull(metaStats, "metaStats");
        this.indexShardStats = Objects.requireNonNull(indexShardStats, "indexShardStats");
        this.stateStoreStats = Objects.requireNonNull(stateStoreStats, "stateStoreStats");
    }

    @Override
    public SearchRequest create(final String query, final SearchRequest in, final ExpressionContext expressionContext) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(expressionContext, "expressionContext");
        final SearchRequest searchRequest = newMapper().create(query, in, expressionContext);
        return applyPlanEnhancements(query, searchRequest, expressionContext);
    }

    /**
     * Parameterises {@code searchRequest} with insights from the Phase 2/3 bind/rewrite pipeline - a derived time
     * range (Task 5.2) and the auto where/filter split (Task 5.3) - see
     * {@code docs/query-optimiser-implementation-plan.md}, Phase 5. Fail-open: {@code Binder} enforces stricter
     * validation than {@link AstToSearchRequestMapper} does (Task 2.2), so any failure here (or any other
     * exception) falls back to {@code searchRequest} completely unmodified - this must never make {@link #create}
     * behave worse than it did before this method existed.
     */
    private SearchRequest applyPlanEnhancements(
            final String query, final SearchRequest searchRequest, final ExpressionContext expressionContext) {
        try {
            final AstQuery ast = StroomQlParser.parse(query);
            final LogicalPlan bound = new Binder(fieldInfoSource).bind(ast);
            final LogicalPlan rewritten = RewritePipeline.standard(fieldInfoSource).run(bound);
            final ScanAndFilter boundScanAndFilter = findScanAndFilter(bound);
            final ScanAndFilter rewrittenScanAndFilter = findScanAndFilter(rewritten);
            if (boundScanAndFilter == null || rewrittenScanAndFilter == null
                || rewrittenScanAndFilter.filter() == null) {
                // Either a Join is present somewhere on the path (Phase 6 territory) or there's no where/filter
                // predicate to derive anything from - nothing to enhance either way.
                return searchRequest;
            }
            SearchRequest result = applyTimeRange(searchRequest, rewrittenScanAndFilter, expressionContext);
            result = applyWhereFilterSplit(result, boundScanAndFilter.filter(), rewrittenScanAndFilter.filter());
            return result;
        } catch (final RuntimeException e) {
            LOGGER.debug(() -> "Unable to enhance compiled SearchRequest for query [" + query + "]: "
                                + e.getMessage(), e);
            return searchRequest;
        }
    }

    /**
     * Task 5.2: a WHERE-clause time bound should narrow which shards get searched exactly the way an explicit
     * UI/API time-range picker value already does - see the "Query.timeRange is the one wire field that already
     * does something real" finding in the plan doc's Phase 5 section. Never overrides an explicit, already-set
     * time range - that always wins.
     */
    private SearchRequest applyTimeRange(
            final SearchRequest searchRequest,
            final ScanAndFilter scanAndFilter,
            final ExpressionContext expressionContext) {
        if (searchRequest.getQuery() == null || searchRequest.getQuery().getTimeRange() != null) {
            return searchRequest;
        }
        final ScanTimeBounds bounds = ScanTimeRangeExtractor.extract(
                scanAndFilter.scan(), scanAndFilter.filter(), fieldInfoSource, expressionContext);
        if (bounds.fromTimeMs() == null && bounds.toTimeMs() == null) {
            return searchRequest;
        }
        final TimeRange timeRange = new TimeRange(
                null,
                DateUtil.createNormalDateTimeString(bounds.fromTimeMs()),
                DateUtil.createNormalDateTimeString(bounds.toTimeMs()));
        return searchRequest.copy()
                .query(searchRequest.getQuery().copy().timeRange(timeRange).build())
                .build();
    }

    /**
     * Task 5.3: routes the ineligible remainder of a bare {@code where} clause to extraction-time filtering
     * instead of leaving it in the scan-time expression, where an unsupported field/condition today silently
     * zeroes the whole result set (an ANDed {@code MatchNoDocsQuery} - see the finding in the plan doc's Phase 5
     * section, and {@code query-optimiser-known-differences.md}). Only triggers when {@code
     * AutoWhereFilterSplitRule} actually moved something: {@code boundFilter} (pre-rewrite) had no explicit
     * {@code filter} clause of its own (that case is always a no-op - see the rule's own Javadoc invariant), and
     * {@code rewrittenFilter} (post-rewrite) now has a non-null {@code filterPredicate} that wasn't there before.
     */
    private SearchRequest applyWhereFilterSplit(
            final SearchRequest searchRequest, final Filter boundFilter, final Filter rewrittenFilter) {
        if (boundFilter.filterPredicate() != null || rewrittenFilter.filterPredicate() == null) {
            return searchRequest;
        }
        final ExpressionOperator newExpression = rewrittenFilter.wherePredicate() == null
                ? ExpressionOperator.builder().build()
                : rewrittenFilter.wherePredicate();
        final ExpressionOperator newValueFilter = rewrittenFilter.filterPredicate();

        final List<ResultRequest> resultRequests = searchRequest.getResultRequests();
        final List<ResultRequest> updatedResultRequests = resultRequests == null
                ? null
                : resultRequests.stream()
                        .map(resultRequest -> applyValueFilter(resultRequest, newValueFilter))
                        .toList();

        return searchRequest.copy()
                .query(searchRequest.getQuery().copy().expression(newExpression).build())
                .resultRequests(updatedResultRequests)
                .build();
    }

    /**
     * Sets {@code valueFilter} on every {@link TableSettings} in {@code resultRequest} that doesn't already have
     * one - safe because {@link #applyWhereFilterSplit} only calls this when the original query had no explicit
     * {@code filter} clause at all (so every {@code TableSettings} here currently has a null {@code valueFilter});
     * a {@code TableSettings} that unexpectedly already has one (e.g. a future clause this method doesn't know
     * about) is left untouched rather than clobbered.
     */
    private static ResultRequest applyValueFilter(
            final ResultRequest resultRequest, final ExpressionOperator valueFilter) {
        if (resultRequest.getMappings() == null) {
            return resultRequest;
        }
        final List<TableSettings> updated = resultRequest.getMappings().stream()
                .map(tableSettings -> tableSettings.getValueFilter() == null
                        ? tableSettings.copy().valueFilter(valueFilter).build()
                        : tableSettings)
                .toList();
        return resultRequest.copy().mappings(updated).build();
    }

    /**
     * Descends through single-input wrapper nodes (Project/Aggregate/Having/Window/Sort/Limit) to the plan's
     * bottom {@code Filter}/{@code Scan} pair - the "single source, no Join anywhere on the path" shape Task
     * 5.2/5.3 support (a {@code Join} anywhere defers to Phase 6). Returns null when a {@code Join} is found
     * instead, or when a {@code Filter} wraps anything other than a bare {@code Scan}.
     */
    private static @Nullable ScanAndFilter findScanAndFilter(final LogicalPlan plan) {
        return switch (plan) {
            case final Scan scan -> new ScanAndFilter(scan, null);
            case final Filter f -> f.input() instanceof final Scan scan ? new ScanAndFilter(scan, f) : null;
            case final Project p -> findScanAndFilter(p.input());
            case final Aggregate a -> findScanAndFilter(a.input());
            case final Having h -> findScanAndFilter(h.input());
            case final Window w -> findScanAndFilter(w.input());
            case final Sort s -> findScanAndFilter(s.input());
            case final Limit l -> findScanAndFilter(l.input());
            case final Join j -> null;
        };
    }

    private record ScanAndFilter(Scan scan, @Nullable Filter filter) {
    }

    @Override
    public void extractDataSourceOnly(final String query, final Consumer<DocRef> consumer) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(consumer, "consumer");
        newMapper().extractDataSourceOnly(query, consumer);
    }

    /**
     * The first real consumer of the Phase 2/3 pipeline (previously exercised only by its own unit tests): binds
     * and rewrites {@code query}, then costs each {@code Scan} - see
     * {@code docs/query-optimiser-implementation-plan.md}, Task 4.1.
     *
     * @param query same contract as {@link QueryCompiler#explain}.
     * @param expressionContext same contract as {@link QueryCompiler#explain}.
     * @return never null.
     */
    @Override
    public ExplainPlan explain(final String query, final ExpressionContext expressionContext) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(expressionContext, "expressionContext");
        final AstQuery ast = StroomQlParser.parse(query);
        final LogicalPlan bound = new Binder(fieldInfoSource).bind(ast);
        final LogicalPlan rewritten = RewritePipeline.standard(fieldInfoSource).run(bound);
        final CostModel costModel = new CostModel(metaStats, indexShardStats, stateStoreStats);
        return new LogicalPlanExplainer(costModel, fieldInfoSource, expressionContext).explain(rewritten);
    }

    private AstToSearchRequestMapper newMapper() {
        return new AstToSearchRequestMapper(
                visualisationTokenConsumer, dataSourceResolver, queryFieldProviderProvider, securityContext);
    }

    /**
     * Task 6.1b (first slice only - see {@code docs/query-optimiser-implementation-plan.md}, Phase 6): compiles
     * one join side's {@code Scan} leaf into its own ordinary, single-source {@link SearchRequest}, by
     * synthesising a trivial "select every field" sub-query and reusing {@link AstToSearchRequestMapper} rather
     * than hand-building wire types - {@code scan} never has a {@code where}/{@code filter} predicate of its own
     * (verified: {@code Binder.bindFromAndJoins} always attaches join-query predicates above the whole join, not
     * to either side individually - the grammar has no syntax for a per-side clause), so nothing beyond field
     * selection is needed here.
     *
     * <p><b>Not yet wired into {@link #create}</b> - see the class Javadoc/plan doc: {@link
     * AstToSearchRequestMapper} rejects any join-containing query at the very first line of its own {@code
     * create()}, so it can't build the *outer* (post-join) {@code SearchRequest} either, and that request's
     * {@code where}/{@code select}/{@code group}/{@code having} clauses reference alias-qualified fields
     * ({@code a.field}) that nothing in this module can compile to a wire {@link stroom.query.api.ExpressionOperator}
     * yet ({@link stroom.query.planner.bind.Binder} only validates such references, it doesn't lower them to wire
     * types). That's a separate, not-yet-scoped capability - this method is deliberately just the piece that's
     * ready.</p>
     */
    SearchRequest compileJoinSide(final Scan scan, final ExpressionContext expressionContext) {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(expressionContext, "expressionContext");
        final String syntheticQuery = "from \"" + escapeForDoubleQuotedString(scan.dataSourceName()) + "\" select *";
        final SearchRequest seed = new SearchRequest(null, null, null, null, null, false, null);
        return newMapper().create(syntheticQuery, seed, expressionContext);
    }

    private static String escapeForDoubleQuotedString(final String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
