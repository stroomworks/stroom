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
import stroom.query.api.JoinSpec;
import stroom.query.api.ResultRequest;
import stroom.query.api.SearchRequest;
import stroom.query.api.TableSettings;
import stroom.query.api.TimeRange;
import stroom.query.api.datasource.QueryFieldProvider;
import stroom.query.api.token.TokenException;
import stroom.query.common.v2.JoinDataSourceType;
import stroom.query.grammar.ast.AstQuery;
import stroom.query.grammar.parse.StroomQlParser;
import stroom.query.language.functions.ExpressionContext;
import stroom.query.planner.bind.Binder;
import stroom.query.planner.cost.CostModel;
import stroom.query.planner.logical.Aggregate;
import stroom.query.planner.logical.EquiKey;
import stroom.query.planner.logical.Expand;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.Having;
import stroom.query.planner.logical.Join;
import stroom.query.planner.logical.JoinType;
import stroom.query.planner.logical.Limit;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.NodeScan;
import stroom.query.planner.logical.Project;
import stroom.query.planner.logical.Scan;
import stroom.query.planner.logical.Sort;
import stroom.query.planner.logical.VarLengthExpand;
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
import java.util.UUID;
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
     * @param fieldInfoSource            must not be null; used by {@link #explain} and by {@link #create}'s
     *                                   plan-enhancement step (time-range + where/filter-split parameterisation,
     *                                   Phase 5) to bind/rewrite the query via Phases 2-3's pipeline.
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
        final AstQuery ast = StroomQlParser.parse(query);
        if (!ast.from().joins().isEmpty()) {
            return createJoin(query, ast, in, expressionContext);
        }
        final SearchRequest searchRequest = newMapper().create(query, in, expressionContext);
        return applyPlanEnhancements(query, searchRequest, expressionContext);
    }

    /**
     * Task 6.1x (see {@code docs/query-optimiser-implementation-plan.md}, Phase 6): the outer {@link
     * SearchRequest} for a join query. Scoped, like Task 6.1, to the common shape: exactly one {@code join}
     * (two sources), both sides either a bare {@code Scan} or a {@code Filter} directly over one (see {@link
     * #findScanAndFilter} - {@code PushFiltersBelowJoinsRule} can push a where-clause term down into exactly this
     * shape when it references only one side's alias, so a bare-{@code Scan}-only check would wrongly reject a
     * query this project's own rewrite pipeline already knows how to optimise). An N-way chain or a
     * nested/nested-source join rejects cleanly (see {@link #findJoin}) rather than silently mis-binding. Unlike
     * {@link #applyPlanEnhancements}, there's no established "prior behaviour" to protect here - every join query
     * used to just throw - so this method is <b>not</b> fail-open; a genuine failure (an unsupported shape, a
     * domain-type-incompatible equi-key, ...) propagates normally.
     *
     * <p>Reuses {@link AstToSearchRequestMapper#create(String, SearchRequest, ExpressionContext, boolean)} (Task
     * 6.1x's `allowJoins` overload) to build the *outer* request's {@code where}/{@code select}/{@code group}/
     * {@code having}/{@code sort}/{@code limit} - verified safe: a dotted {@code alias.field} reference is one
     * bareword token at the grammar level, so the mapper's existing blind text-passthrough (no field/alias
     * validation at all) already produces byte-identical {@code ExpressionTerm.field}/{@code Column.expression}
     * values to what {@code Binder.bindTerm}/{@code qualifiedName} compute for the same source text - see the
     * plan doc's Phase 6 section for the full finding. The mapper resolves {@code Query.dataSource} to the
     * {@code from} clause's left source (its only concept of "the" datasource) - overridden below with the
     * sentinel {@link JoinDataSourceType#TYPE} plus the {@link JoinSpec} the real datasources/equi-keys live on.</p>
     */
    private SearchRequest createJoin(
            final String query, final AstQuery ast, final SearchRequest in, final ExpressionContext expressionContext) {
        if (ast.from().joins().size() > 1) {
            throw new TokenException(
                    null, "Only a single join is supported for now - N-way join chains are not yet enabled.");
        }

        final LogicalPlan bound = new Binder(fieldInfoSource).bind(ast);
        final LogicalPlan rewritten = RewritePipeline.standard(fieldInfoSource).run(bound);
        final Join join = findJoin(rewritten);
        final ScanAndFilter leftSide = join == null ? null : findScanAndFilter(join.left());
        final ScanAndFilter rightSide = join == null ? null : findScanAndFilter(join.right());
        if (leftSide == null || rightSide == null) {
            throw new TokenException(
                    null, "This join shape is not yet supported - both sides must be plain datasource scans "
                          + "(optionally filtered).");
        }

        // Compile each side as a pure "select *" (no per-side predicate). The full where clause stays in the
        // outer Query.expression and is applied across the joined rows by JoinSearchProvider (see the plan doc's
        // Phase 6 "where across joins" note). Pushing single-side terms down into a side's own sub-query - which
        // would let it pre-filter before the join - is a later efficiency optimisation; it needs the pushed
        // predicate's alias stripped (a single-source side knows the field as "field", not "alias.field"), which
        // is deliberately not done here.
        final SearchRequest leftRequest = compileJoinSide(leftSide.scan(), null, expressionContext);
        final SearchRequest rightRequest = compileJoinSide(rightSide.scan(), null, expressionContext);
        final List<JoinSpec.JoinEquiKey> equiKeys = join.equiKeys().stream()
                .map(OptimisingQueryCompiler::toWireEquiKey)
                .toList();
        final JoinSpec joinSpec = JoinSpec.builder()
                .left(leftRequest)
                .right(rightRequest)
                .joinType(join.joinType() == JoinType.LEFT
                        ? JoinSpec.JoinType.LEFT
                        : JoinSpec.JoinType.INNER)
                .equiKeys(equiKeys)
                .build();

        final SearchRequest outer = newMapper().create(query, in, expressionContext, true);
        final DocRef sentinelDataSource = new DocRef(
                JoinDataSourceType.TYPE, UUID.randomUUID().toString(),
                leftSide.scan().dataSourceName() + " ⋈ " + rightSide.scan().dataSourceName());
        return outer.copy()
                .query(outer.getQuery().copy().dataSource(sentinelDataSource).joinSpec(joinSpec).build())
                .build();
    }

    private static JoinSpec.JoinEquiKey toWireEquiKey(final EquiKey equiKey) {
        return new JoinSpec.JoinEquiKey(
                equiKey.left().alias(), equiKey.left().field(),
                equiKey.right().alias(), equiKey.right().field());
    }

    /**
     * Descends through single-input wrapper nodes (Project/Aggregate/Having/Window/Sort/Limit) to find a {@code
     * Join} node - null if there isn't one (a bare {@code Scan}/{@code Filter} plan) or if it's nested beneath
     * another {@code Join} (an N-way chain - {@link #createJoin} only supports exactly one join).
     */
    private static @Nullable Join findJoin(final LogicalPlan plan) {
        return switch (plan) {
            case final Join j -> j;
            case final Scan s -> null;
            case final Filter f -> findJoin(f.input());
            case final Project p -> findJoin(p.input());
            case final Aggregate a -> findJoin(a.input());
            case final Having h -> findJoin(h.input());
            case final Window w -> findJoin(w.input());
            case final Sort s -> findJoin(s.input());
            case final Limit l -> findJoin(l.input());
            // Graph plans (Task PoC.2) never contain a relational Join - a Cypher hop is an Expand, not this
            // node - so a NodeScan leaf ends the search, and Expand/VarLengthExpand simply recurse through.
            case final NodeScan ns -> null;
            case final Expand e -> findJoin(e.input());
            case final VarLengthExpand vle -> findJoin(vle.input());
        };
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
            // Graph plans (Task PoC.2): a NodeScan is not a relational Scan, so this "single relational
            // source" cost-explain path doesn't apply to it; Expand/VarLengthExpand simply recurse through.
            case final NodeScan ns -> null;
            case final Expand e -> findScanAndFilter(e.input());
            case final VarLengthExpand vle -> findScanAndFilter(vle.input());
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
     * Task 6.1b (see {@code docs/query-optimiser-implementation-plan.md}, Phase 6): compiles one join side's
     * {@code Scan} leaf (optionally with a {@code Filter} directly over it - see {@link #createJoin}'s Javadoc
     * on why a side isn't always a bare {@code Scan}) into its own ordinary, single-source {@link SearchRequest},
     * by synthesising a trivial "select every field" sub-query and reusing {@link AstToSearchRequestMapper}
     * rather than hand-building wire types for the field-selection part; {@code filter}'s predicate(s), when
     * present, are applied directly onto the result as {@code ExpressionOperator}s (the same "already a wire
     * type, just assign it" pattern Task 5.2/5.3 use) rather than re-derived through StroomQL text. Called from
     * {@link #createJoin} for each side of a join.
     */
    SearchRequest compileJoinSide(
            final Scan scan, final @Nullable Filter filter, final ExpressionContext expressionContext) {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(expressionContext, "expressionContext");
        final String syntheticQuery = "from \"" + escapeForDoubleQuotedString(scan.dataSourceName()) + "\" select *";
        final SearchRequest seed = new SearchRequest(null, null, null, null, null, false, null);
        final SearchRequest base = newMapper().create(syntheticQuery, seed, expressionContext);
        if (filter == null) {
            return base;
        }

        SearchRequest result = base;
        if (filter.wherePredicate() != null) {
            result = result.copy()
                    .query(result.getQuery().copy().expression(filter.wherePredicate()).build())
                    .build();
        }
        if (filter.filterPredicate() != null) {
            final List<ResultRequest> resultRequests = result.getResultRequests();
            final List<ResultRequest> updated = resultRequests == null
                    ? null
                    : resultRequests.stream()
                            .map(resultRequest -> applyValueFilter(resultRequest, filter.filterPredicate()))
                            .toList();
            result = result.copy().resultRequests(updated).build();
        }
        return result;
    }

    private static String escapeForDoubleQuotedString(final String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
