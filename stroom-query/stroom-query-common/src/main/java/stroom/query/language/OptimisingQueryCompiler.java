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
import stroom.graphdb.shared.GraphDbDoc;
import stroom.query.api.Column;
import stroom.query.api.ExplainPlan;
import stroom.query.api.ExpressionItem;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.GraphSpec;
import stroom.query.api.GroupSelection;
import stroom.query.api.JoinSpec;
import stroom.query.api.Query;
import stroom.query.api.QueryKey;
import stroom.query.api.ResultRequest;
import stroom.query.api.SearchRequest;
import stroom.query.api.TableSettings;
import stroom.query.api.TimeRange;
import stroom.query.api.datasource.QueryFieldProvider;
import stroom.query.api.token.TokenException;
import stroom.query.common.v2.JoinDataSourceType;
import stroom.query.grammar.ast.AstFilterClause;
import stroom.query.grammar.ast.AstQuery;
import stroom.query.grammar.ast.cypher.AstCypherQuery;
import stroom.query.grammar.parse.CypherQueryParser;
import stroom.query.grammar.parse.StroomQlParser;
import stroom.query.language.functions.ExpressionContext;
import stroom.query.planner.bind.Binder;
import stroom.query.planner.cost.CostModel;
import stroom.query.planner.cypher.CypherJoinSchema;
import stroom.query.planner.cypher.CypherToLogicalPlan;
import stroom.query.planner.logical.Aggregate;
import stroom.query.planner.logical.EquiKey;
import stroom.query.planner.logical.Expand;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.GraphJoinSource;
import stroom.query.planner.logical.Having;
import stroom.query.planner.logical.Join;
import stroom.query.planner.logical.JoinType;
import stroom.query.planner.logical.Limit;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.NodeScan;
import stroom.query.planner.logical.Project;
import stroom.query.planner.logical.ProjectField;
import stroom.query.planner.logical.Scan;
import stroom.query.planner.logical.Sort;
import stroom.query.planner.logical.VarLengthExpand;
import stroom.query.planner.logical.Window;
import stroom.query.planner.port.FieldInfoSource;
import stroom.query.planner.port.IndexShardStats;
import stroom.query.planner.port.MetaStats;
import stroom.query.planner.port.StateStoreStats;
import stroom.query.planner.rewrite.AutoWhereFilterSplitRule;
import stroom.query.planner.rewrite.RewritePipeline;
import stroom.security.api.SecurityContext;
import stroom.util.date.DateUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A {@link QueryCompiler} that compiles StroomQL via the ANTLR grammar (see {@code stroom-query-grammar}) rather
 * than the legacy hand-coded {@link SearchRequestFactory}. Aims for exact output parity with the legacy compiler
 * for every construct the parity corpus exercises (
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
     * The join binder/{@code fieldInfoSource} "data source name" a side never really has - used purely to make
     * {@link JoinPredicateSplitter#split} treat every predicate on a graph join side's alias as non-push-eligible
     * (decision D2's existing "unknown field -&gt; never eligible" default already does this; this sentinel just
     * ensures {@link FieldInfoSource#getFields} is asked about a name that provably resolves to nothing real, per
     * that port's own contract). Pushing a StroomQL predicate into a Cypher body is out of scope for v1 - see
     * {@link GraphJoinSource}'s Javadoc.
     */
    private static final String GRAPH_SIDE_PUSH_DOWN_SENTINEL = "\u0000graph-join-side-sentinel";

    /**
     * Task 6.1x (Phase 6): the outer {@link
     * SearchRequest} for a join query. Scoped, like Task 6.1, to the common shape: exactly one {@code join}
     * (two sources), each side either a bare {@code Scan}/{@code Filter} (see {@link #findScanAndFilter} -
     * {@code PushFiltersBelowJoinsRule} can push a where-clause term down into exactly this shape when it
     * references only one side's alias, so a bare-{@code Scan}-only check would wrongly reject a query this
     * project's own rewrite pipeline already knows how to optimise) or (Workstream C) a
     * {@link GraphJoinSource} - a Cypher sub-query join side. An N-way chain
     * or a nested/nested-source join rejects cleanly (see {@link #findJoin}) rather than silently mis-binding.
     * Unlike {@link #applyPlanEnhancements}, there's no established "prior behaviour" to protect here - every
     * join query used to just throw - so this method is <b>not</b> fail-open; a genuine failure (an unsupported
     * shape, a domain-type-incompatible equi-key, an invalid graph sub-query, ...) propagates normally.
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
        final JoinSideBinding leftSide = join == null ? null : classifyJoinSide(join.left());
        final JoinSideBinding rightSide = join == null ? null : classifyJoinSide(join.right());
        if (leftSide == null || rightSide == null) {
            throw new TokenException(
                    null, "This join shape is not yet supported - both sides must be plain datasource scans "
                          + "(optionally filtered) or a Cypher graph sub-query.");
        }
        final JoinSpec.JoinType wireJoinType = join.joinType() == JoinType.LEFT
                ? JoinSpec.JoinType.LEFT
                : JoinSpec.JoinType.INNER;

        // The outer request is compiled first (from the raw query text, exactly as before A1) purely to obtain
        // its where clause:, decision D3 (Phase 1, item A1) splits
        // that clause into the part(s) safe to pre-filter each side with (via JoinPredicateSplitter) and a
        // residual that - as before - is still evaluated across the joined rows by JoinSearchProvider (see the
        // plan doc's Phase 6 "where across joins" note). A LEFT join never pre-filters its right (null-supplying)
        // side - see JoinPredicateSplitter.split's Javadoc for why that would silently change results. A graph
        // side is never pushed to either (see GRAPH_SIDE_PUSH_DOWN_SENTINEL) - a predicate on its alias always
        // ends up in the residual.
        final SearchRequest outer = newMapper().create(query, in, expressionContext, true);
        final ExpressionOperator outerWhere = outer.getQuery().getExpression();
        final @Nullable ExpressionOperator leftPush;
        final @Nullable ExpressionOperator rightPush;
        final @Nullable ExpressionOperator residualWhere;
        if (outerWhere == null) {
            leftPush = null;
            rightPush = null;
            residualWhere = null;
        } else {
            final JoinPredicateSplitter.Split split = new JoinPredicateSplitter(fieldInfoSource).split(
                    outerWhere,
                    leftSide.alias(), pushDownDataSourceName(leftSide),
                    rightSide.alias(), pushDownDataSourceName(rightSide),
                    wireJoinType);
            leftPush = split.leftPush();
            rightPush = split.rightPush();
            residualWhere = split.residual();
        }

        // Task A2 (decision D4): each side selects only its own equi-key field(s) plus whatever the outer query
        // actually references it by, instead of select * - see JoinProjectionAnalyzer.fieldsNeededFor. Not
        // attempted for a graph side - see compileSide's Javadoc on why its RETURN list is used as-is.
        final List<String> leftEquiKeyFields = join.equiKeys().stream().map(equiKey -> equiKey.left().field()).toList();
        final List<String> rightEquiKeyFields =
                join.equiKeys().stream().map(equiKey -> equiKey.right().field()).toList();

        final SearchRequest leftRequest = compileSide(
                leftSide, leftPush, leftEquiKeyFields, outer, residualWhere, expressionContext);
        final SearchRequest rightRequest = compileSide(
                rightSide, rightPush, rightEquiKeyFields, outer, residualWhere, expressionContext);

        final List<JoinSpec.JoinEquiKey> equiKeys = join.equiKeys().stream()
                .map(OptimisingQueryCompiler::toWireEquiKey)
                .toList();
        final JoinSpec joinSpec = JoinSpec.builder()
                .left(leftRequest)
                .right(rightRequest)
                .joinType(wireJoinType)
                .equiKeys(equiKeys)
                .build();

        final DocRef sentinelDataSource = new DocRef(
                JoinDataSourceType.TYPE, UUID.randomUUID().toString(),
                describeSideForSentinelName(leftSide) + " ⋈ " + describeSideForSentinelName(rightSide));
        return outer.copy()
                .query(outer.getQuery().copy()
                        .dataSource(sentinelDataSource)
                        .joinSpec(joinSpec)
                        .expression(residualWhere)
                        .build())
                .build();
    }

    /**
     * @return {@code side}'s "data source name" for {@link JoinPredicateSplitter#split} - the real name for a
     *         plain scan side, or {@link #GRAPH_SIDE_PUSH_DOWN_SENTINEL} for a graph side (see that constant's
     *         Javadoc).
     */
    private static String pushDownDataSourceName(final JoinSideBinding side) {
        return side.isGraph() ? GRAPH_SIDE_PUSH_DOWN_SENTINEL : side.scanAndFilter().scan().dataSourceName();
    }

    private static String describeSideForSentinelName(final JoinSideBinding side) {
        return side.isGraph() ? side.graphSource().alias() + " (Cypher)" : side.scanAndFilter().scan().dataSourceName();
    }

    /**
     * Compiles one join side into its own single-source {@link SearchRequest} - a plain scan side via the
     * existing {@link #compileJoinSide} (Task 6.1b), or (Workstream C, Phase P3) a graph side via
     * {@link #compileGraphJoinSide}. A graph side's {@code push}/{@code equiKeyFields} are accepted only for a
     * uniform call shape with the scan-side path - {@code push} is always null by construction (see
     * {@link #GRAPH_SIDE_PUSH_DOWN_SENTINEL}), and its projection is never narrowed to just the equi-key/outer-
     * referenced fields the way a scan side's is (decision D4): the graph side's own {@code RETURN} list already
     * declares exactly the columns it projects, and there is no StroomQL-side mechanism to rewrite a Cypher
     * {@code RETURN} clause down to a subset - narrowing it is squarely the analyst's own job, in the Cypher text
     * itself (see the design doc's non-goals on predicate/projection push-down into the traversal).
     */
    private SearchRequest compileSide(
            final JoinSideBinding side, final @Nullable ExpressionOperator push, final List<String> equiKeyFields,
            final SearchRequest outer, final @Nullable ExpressionOperator residualWhere,
            final ExpressionContext expressionContext) {
        if (side.isGraph()) {
            return compileGraphJoinSide(side.graphSource());
        }
        final Set<String> selectFields = JoinProjectionAnalyzer.fieldsNeededFor(
                outer, residualWhere, side.alias(), equiKeyFields);
        final Scan scan = side.scanAndFilter().scan();
        final @Nullable Filter filter = toPushedFilter(scan, push);
        final SearchRequest base = compileJoinSide(scan, filter, selectFields, expressionContext);
        // Task A3: a pushed time-bound predicate must
        // prune shards on that side exactly as it would for an ordinary single-source query (Task 5.2's
        // applyTimeRange), not just filter rows after they're read - NodeSearchTaskCreator.getPartitionTimeRange
        // only ever reads Query.timeRange, never derives bounds from Query.expression directly, so without this
        // a pushed time predicate would filter results correctly but scan every shard doing it. Only attempted
        // when something was actually pushed (applyTimeRange/ScanTimeRangeExtractor require a non-null Filter).
        return filter == null
                ? base
                : applyTimeRange(base, new ScanAndFilter(scan, filter), expressionContext);
    }

    /**
     * Task C3: compiles a graph sub-query join side
     * into its own single-source {@link SearchRequest} carrying a {@link GraphSpec} - instead of synthesising a
     * {@code from "<name>" select *} the way {@link #compileJoinSide} does for a plain scan side, this builds the
     * side's {@code Query} directly with {@code graphSource}'s raw Cypher text on a {@link GraphSpec} and its
     * resolved target {@code GraphDb} doc as {@code Query.dataSource} - exactly what {@code GraphSearchProvider}
     * (a registered {@code SearchProvider} for type {@code GraphDb}) already expects, so dispatch through
     * {@code JoinSearchProvider#openSide} works unchanged (it resolves purely by {@code DocRef.getType}).
     *
     * <p>This is the analogue of {@code stroom.graphdb.impl.CypherCompiler#create} for a standalone Cypher
     * query, reimplemented here rather than called directly - this module cannot depend on
     * {@code stroom-graphdb-impl} (that dependency already runs the other way: graphdb-impl depends on this
     * module). The one piece that really would be pure duplication - deriving the {@code RETURN} column list -
     * is not duplicated: both this method and {@code CypherCompiler.buildResultRequests} ultimately go through
     * {@link CypherJoinSchema}/{@link CypherToLogicalPlan} in {@code stroom-query-planner}, a module both already
     * depend on.</p>
     *
     * @throws TokenException if {@code graphSource}'s Cypher text fails to parse or compile, violates
     *                        {@link CypherJoinSchema}'s C0 contract, has no target datasource (no leading
     *                        {@code from "X"} clause), or that datasource does not resolve to a {@code GraphDb}.
     */
    private SearchRequest compileGraphJoinSide(final GraphJoinSource graphSource) {
        final AstCypherQuery ast;
        final LogicalPlan plan;
        try {
            ast = CypherQueryParser.parse(graphSource.cypherText());
            plan = new CypherToLogicalPlan().compile(ast).plan();
        } catch (final RuntimeException e) {
            throw new TokenException(
                    null, "Join side '" + graphSource.alias() + "' is not a valid Cypher sub-query: "
                          + e.getMessage());
        }
        final DocRef dataSource = resolveGraphDataSource(graphSource, ast);
        final List<ProjectField> fields;
        try {
            fields = CypherJoinSchema.deriveJoinColumns(plan);
        } catch (final RuntimeException e) {
            throw new TokenException(null, "Join side '" + graphSource.alias() + "': " + e.getMessage());
        }

        final TableSettings.Builder tableSettingsBuilder = TableSettings.builder().extractValues(false);
        for (final ProjectField field : fields) {
            tableSettingsBuilder.addColumns(Column.builder()
                    .id(field.name())
                    .name(field.name())
                    .expression("${" + field.name() + "}")
                    .visible(true)
                    .build());
        }
        final ResultRequest tableResultRequest = ResultRequest.builder()
                .componentId(SearchRequestFactory.TABLE_COMPONENT_ID)
                .mappings(Collections.singletonList(tableSettingsBuilder.build()))
                .resultStyle(ResultRequest.ResultStyle.TABLE)
                .fetch(ResultRequest.Fetch.ALL)
                .groupSelection(new GroupSelection())
                .build();

        final Query sideQuery = Query.builder()
                .dataSource(dataSource)
                .graphSpec(GraphSpec.builder().cypher(graphSource.cypherText()).build())
                .build();
        return new SearchRequest(
                null, new QueryKey(UUID.randomUUID().toString()), sideQuery,
                Collections.singletonList(tableResultRequest), null, false, null);
    }

    /**
     * Resolves a graph join side's target {@code GraphDb} doc from its own leading {@code from "X"} clause (a
     * join side has no {@code SearchRequestSource.ownerDocRef} of its own to fall back on - unlike a standalone
     * Cypher query, see {@code stroom.graphdb.impl.CypherCompiler#resolveDataSource}, the join side is always
     * named explicitly inside the brackets).
     *
     * @throws TokenException if {@code ast} has no leading {@code from "X"} clause, or {@code name} does not
     *                         resolve to a {@code GraphDb}-typed doc.
     */
    private DocRef resolveGraphDataSource(final GraphJoinSource graphSource, final AstCypherQuery ast) {
        final String name = ast.dataSourceName();
        if (name == null) {
            throw new TokenException(
                    null, "Join side '" + graphSource.alias() + "' has no target Graph DB - add a leading "
                          + "from \"...\" clause inside the brackets");
        }
        final DocRef dataSource = dataSourceResolver.resolveDataSourceRef(name);
        if (!GraphDbDoc.TYPE.equals(dataSource.getType())) {
            throw new TokenException(
                    null, "Join side '" + graphSource.alias() + "' must be a Graph DB - \"" + name + "\" is a "
                          + dataSource.getType());
        }
        return dataSource;
    }

    /**
     * One join side's bound shape - either a plain scan (optionally filtered, see {@link #findScanAndFilter}) or
     * a Cypher graph sub-query (see {@link #findGraphJoinSource}). Exactly one of the two fields is non-null -
     * see {@link #classifyJoinSide}, the only place this is constructed.
     */
    private record JoinSideBinding(@Nullable ScanAndFilter scanAndFilter, @Nullable GraphJoinSource graphSource) {

        private boolean isGraph() {
            return graphSource != null;
        }

        private String alias() {
            return isGraph() ? graphSource.alias() : scanAndFilter.scan().alias();
        }
    }

    /**
     * @return null if {@code sidePlan} is neither a plain scan shape ({@link #findScanAndFilter}) nor a bare
     *         {@link GraphJoinSource} ({@link #findGraphJoinSource}) - an unsupported join operand shape.
     */
    private static @Nullable JoinSideBinding classifyJoinSide(final LogicalPlan sidePlan) {
        final ScanAndFilter scanAndFilter = findScanAndFilter(sidePlan);
        if (scanAndFilter != null) {
            return new JoinSideBinding(scanAndFilter, null);
        }
        final GraphJoinSource graphSource = findGraphJoinSource(sidePlan);
        if (graphSource != null) {
            return new JoinSideBinding(null, graphSource);
        }
        return null;
    }

    /**
     * @return {@code sidePlan} itself if it is a bare {@link GraphJoinSource}, else null. Unlike
     *         {@link #findScanAndFilter}, no wrapper-descent is attempted: {@code Binder} always embeds a graph
     *         join side directly as a {@link Join} operand (never wrapped in a {@link Filter}) - a graph side's
     *         alias is excluded from {@code PlanRewriteUtil.collectScans} entirely (see {@link GraphJoinSource}'s
     *         Javadoc), so the rewrite pipeline's own filter-pushing rules never wrap one in a {@code Filter}
     *         either.
     */
    private static @Nullable GraphJoinSource findGraphJoinSource(final LogicalPlan sidePlan) {
        return sidePlan instanceof final GraphJoinSource graphJoinSource ? graphJoinSource : null;
    }

    /**
     * Wraps a pushed predicate as the {@code Filter} shape {@link #compileJoinSide} expects, using {@code scan}
     * itself as the (unused - {@link #compileJoinSide} only reads the predicate slots) placeholder {@code input}.
     *
     * @param scan      must not be null.
     * @param wherePush nullable; when null, this method returns null (no filter to apply for this side).
     * @return null iff {@code wherePush} is null.
     */
    private static @Nullable Filter toPushedFilter(final Scan scan, final @Nullable ExpressionOperator wherePush) {
        return wherePush == null ? null : new Filter(scan, wherePush, null, scan.position());
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
            // A graph join side (Workstream C, Phase P1/P2) is itself always a leaf operand of a Join, never a
            // Join of its own - see GraphJoinSource's Javadoc.
            case final GraphJoinSource g -> null;
        };
    }

    /**
     * Parameterises {@code searchRequest} with insights from the Phase 2/3 bind/rewrite pipeline - a derived time
     * range (Task 5.2) and the auto where/filter split (Task 5.3) - see
     * Phase 5. Fail-open: {@code Binder} enforces stricter
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
            result = applyWhereFilterSplit(result, ast, rewrittenScanAndFilter.scan());
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
     * Task 5.3, reworked by Task 8.1: routes the index-ineligible remainder of a bare {@code where} clause to
     * extraction-time filtering instead of leaving it in the scan-time expression, where an unsupported
     * field/condition today silently zeroes the whole result set (an ANDed {@code MatchNoDocsQuery} - see the
     * finding in the plan doc's Phase 5 section).
     *
     * <p><b>The executed predicate is partitioned from the legacy-compiled expression itself</b> (Task 8.1):
     * every term this method places in {@code Query.expression} or a {@code valueFilter} is the exact
     * {@link stroom.query.api.ExpressionTerm} the {@link AstToSearchRequestMapper} built - same
     * unescaped/validated value, same resolved dictionary {@code DocRef} - never a {@code Binder}-built
     * rendering of it. The Binder's term values are raw source text (quotes included) and are for
     * EXPLAIN/classification only; adopting them here is exactly the executed-values defect Task 8.1 removed,
     * and partitioning the legacy tree removes the whole class - there is no second representation left that
     * has to agree with the executed one. Classification is delegated to {@link AutoWhereFilterSplitRule}
     * itself, by wrapping the legacy expression in a synthetic {@link Filter} over {@code scan}, so the
     * executed split can never disagree with the planner rule's notion of index-eligibility.</p>
     *
     * <p>Only splits when {@code ast} has no explicit {@code filter} clause (the rule's own documented no-op
     * invariant) and the expression is enabled. Nested <b>enabled</b> {@code AND}s are flattened first (a
     * semantic identity for conjunction) so a three-plus-conjunct {@code where} - which both compilers fold
     * into nested pairwise {@code AND}s - is classified per term, the same granularity the rewrite pipeline
     * achieves on the bound plan; see {@link #flattenEnabledAnds}. When nothing is ineligible the request is
     * returned completely unmodified, original expression tree intact.</p>
     */
    private SearchRequest applyWhereFilterSplit(
            final SearchRequest searchRequest, final AstQuery ast, final Scan scan) {
        final boolean hasExplicitFilterClause = ast.clauses().stream()
                .anyMatch(clause -> clause instanceof AstFilterClause);
        final ExpressionOperator legacyWhere = searchRequest.getQuery() == null
                ? null
                : searchRequest.getQuery().getExpression();
        if (hasExplicitFilterClause || legacyWhere == null || !legacyWhere.enabled()) {
            return searchRequest;
        }
        // AutoWhereFilterSplitRule.apply on a Filter always yields a Filter, so the cast cannot fail.
        final Filter split = (Filter) new AutoWhereFilterSplitRule(fieldInfoSource)
                .apply(new Filter(scan, flattenEnabledAnds(legacyWhere), null, scan.position()));
        if (split.filterPredicate() == null) {
            return searchRequest;
        }
        final ExpressionOperator newExpression = split.wherePredicate() == null
                ? ExpressionOperator.builder().build()
                : split.wherePredicate();
        final ExpressionOperator newValueFilter = split.filterPredicate();

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
     * Flattens {@code where}'s nested <b>enabled</b> {@code AND}s into one flat conjunct list - a semantic
     * identity for conjunction ({@code AND(AND(a,b),c) = AND(a,b,c)}) that lets {@link AutoWhereFilterSplitRule}
     * classify each term individually instead of treating a nested pairwise {@code AND} (the shape both
     * compilers' left-associative folds produce for three-plus conjuncts) as one opaque, never-eligible unit.
     * A disabled operator is never flattened through - hoisting a disabled sub-tree's children into an enabled
     * parent would silently re-enable a predicate the caller switched off (the Task 8.4 principle) - so it is
     * passed to the rule whole, as a single conjunct.
     *
     * @param where never null; must itself be enabled (the caller checks).
     * @return never null; {@code where} unchanged when its top-level operator is not {@code AND} (the rule
     *         declines to split a non-{@code AND} predicate anyway) or it has no children.
     */
    private static ExpressionOperator flattenEnabledAnds(final ExpressionOperator where) {
        if (effectiveOp(where) != Op.AND || where.getChildren() == null) {
            return where;
        }
        final List<ExpressionItem> conjuncts = new ArrayList<>();
        collectConjuncts(where, conjuncts);
        return ExpressionOperator.builder().op(Op.AND).children(conjuncts).build();
    }

    /**
     * Depth-first helper for {@link #flattenEnabledAnds}: appends {@code andOperator}'s conjuncts to {@code out},
     * recursing only through children that are themselves enabled {@code AND}s with children.
     */
    private static void collectConjuncts(final ExpressionOperator andOperator, final List<ExpressionItem> out) {
        for (final ExpressionItem child : andOperator.getChildren()) {
            if (child instanceof final ExpressionOperator childOperator
                && childOperator.enabled()
                && effectiveOp(childOperator) == Op.AND
                && childOperator.getChildren() != null) {
                collectConjuncts(childOperator, out);
            } else {
                out.add(child);
            }
        }
    }

    /**
     * @return {@code operator}'s op, defaulting null to {@link Op#AND} - the same reading
     *         {@link AutoWhereFilterSplitRule} applies to an op-less operator.
     */
    private static Op effectiveOp(final ExpressionOperator operator) {
        return operator.getOp() == null ? Op.AND : operator.getOp();
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
            // A graph join side (Workstream C, Phase P1/P2) is not a plain relational Scan either - handled
            // separately by findGraphJoinSource/classifyJoinSide.
            case final GraphJoinSource g -> null;
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
     * Task 4.1.
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
     * Task 6.1b (Phase 6): compiles one join side's
     * {@code Scan} leaf (optionally with a {@code Filter} directly over it - see {@link #createJoin}'s Javadoc
     * on why a side isn't always a bare {@code Scan}) into its own ordinary, single-source {@link SearchRequest},
     * by synthesising a {@code select <fields>} sub-query and reusing {@link AstToSearchRequestMapper} rather
     * than hand-building wire types for the field-selection part; {@code filter}'s predicate(s), when present,
     * are applied directly onto the result as {@code ExpressionOperator}s (the same "already a wire type, just
     * assign it" pattern Task 5.2/5.3 use) rather than re-derived through StroomQL text. Called from
     * {@link #createJoin} for each side of a join.
     *
     * <p>{@code selectFields} restricts the sub-query to exactly the fields this side needs (its own equi-key
     * plus every field the outer query actually references it by - see
     * {@link JoinProjectionAnalyzer#fieldsNeededFor}, decision D4) rather than {@code select *} - fewer columns
     * means less work for the datasource's scan and a smaller {@code FieldIndex} downstream.</p>
     *
     * @param selectFields must not be null or empty (a join side always needs at least its own equi-key field).
     */
    SearchRequest compileJoinSide(
            final Scan scan, final @Nullable Filter filter, final Collection<String> selectFields,
            final ExpressionContext expressionContext) {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(selectFields, "selectFields");
        Objects.requireNonNull(expressionContext, "expressionContext");
        if (selectFields.isEmpty()) {
            throw new IllegalArgumentException(
                    "selectFields must not be empty - a join side always needs at least its own equi-key field");
        }
        final String syntheticQuery = "from \"" + escapeForDoubleQuotedString(scan.dataSourceName()) + "\" select "
                                       + String.join(", ", selectFields);
        final SearchRequest seed = new SearchRequest(
                null, new QueryKey(UUID.randomUUID().toString()), null, null, null, false, null);
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
