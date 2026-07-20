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

package stroom.searchable.impl;

import stroom.docref.DocRef;
import stroom.query.api.Column;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.JoinSpec;
import stroom.query.api.JoinSpec.JoinEquiKey;
import stroom.query.api.OffsetRange;
import stroom.query.api.SearchRequest;
import stroom.query.api.datasource.FindFieldCriteria;
import stroom.query.api.datasource.QueryField;
import stroom.query.common.v2.CoprocessorsFactory;
import stroom.query.common.v2.CoprocessorsImpl;
import stroom.query.common.v2.DataStore;
import stroom.query.common.v2.DataStoreSettings;
import stroom.query.common.v2.ExpressionPredicateFactory;
import stroom.query.common.v2.ExpressionPredicateFactory.ValueFunctionFactories;
import stroom.query.common.v2.ExpressionPredicateFactory.ValueFunctionFactory;
import stroom.query.common.v2.IdentityItemMapper;
import stroom.query.common.v2.JoinConfig;
import stroom.query.common.v2.JoinDataSourceType;
import stroom.query.common.v2.OpenGroups;
import stroom.query.common.v2.ResultStore;
import stroom.query.common.v2.ResultStoreFactory;
import stroom.query.common.v2.SearchProvider;
import stroom.query.common.v2.SearchProviderRegistry;
import stroom.query.common.v2.ValuesFunctionFactory;
import stroom.query.language.SearchRequestFactory;
import stroom.query.language.functions.FieldIndex;
import stroom.query.language.functions.StateFetcher;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValNull;
import stroom.query.language.functions.Values;
import stroom.query.planner.cost.JoinAlgorithm;
import stroom.query.planner.join.JoinExecutor;
import stroom.query.planner.join.JoinExecutor.Side;
import stroom.query.planner.join.JoinLimitExceededException;
import stroom.query.planner.logical.JoinType;
import stroom.util.shared.ResultPage;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Routes execution for a query compiled with a {@code join} clause - see
 * {@code docs/query-optimiser-implementation-plan.md}, Task 6.1. Registered under the sentinel
 * {@link JoinDataSourceType#TYPE}, the same way {@link SearchableSearchProvider} registers once per
 * {@code Searchable} - {@link SearchProviderRegistryImpl} resolves either purely by {@code DocRef.getType()}, no
 * special-casing needed there for this to work.
 *
 * <p>Depends on {@link SearchProviderRegistry} via a lazy {@link Provider} rather than directly: {@code
 * SearchProviderRegistryImpl} is itself constructed from {@code Set<SearchProvider>}, which this class is a
 * member of - injecting the registry directly would be a construction-time cycle; {@code Provider.get()} defers
 * the lookup until {@link #createResultStore} actually runs, by which point both are fully constructed.</p>
 *
 * <p><b>Scope (Task 6.1d / 6.1x / where-across-joins - see the plan doc's Phase 6 section)</b>: a two-source
 * {@code INNER}/{@code LEFT} join executes end-to-end and returns real rows, including one with a {@code where}
 * clause. Each side is run as its own {@code select *} sub-query, the rows are combined by {@link JoinExecutor},
 * the outer {@code where} clause is applied across each combined row (see {@link #whereRowPredicate}), and each
 * surviving row is placed at the outer coprocessor's {@link FieldIndex} positions (which key on the outer
 * {@code select} columns' alias-qualified expression text, e.g. {@code "a.field"} - see
 * {@link #buildFieldMapping}) and fed via {@code accept(Val[])}. From there the existing coprocessor/
 * {@link ResultStore} machinery applies {@code select}/{@code group}/{@code having}/{@code sort}/{@code limit}
 * exactly as for any other query.</p>
 *
 * <p><b>Simplifications (later optimisations, not correctness concerns)</b>: {@link #createResultStore} realises
 * sides and feeds all rows <i>synchronously</i> before returning an already-complete {@link ResultStore} - unlike
 * {@link SearchableSearchProvider}, which runs its feed asynchronously on an {@code Executor}. Per-side predicate
 * push-down (pre-filtering each side's own sub-query before it ever reaches this class) happens at compile time
 * in {@code OptimisingQueryCompiler} (Task A1), not here.</p>
 *
 * <p><b>Two execution strategies (see {@link #joinAndFeed}, decision D8, item B1)</b>: by default, both sides are
 * realised in full and combined with an in-memory hash join ({@link #joinAndFeedViaHashJoin}). When one side is
 * detected as a keyed Plan B/State lookup ({@link #detectPlanBLookupSide}), that side is never realised at all -
 * the other (probe) side is streamed against it via {@link JoinExecutor#broadcastLookupJoin} instead
 * ({@link #joinAndFeedViaBroadcastLookup}), the enrichment-join fast path.</p>
 *
 * <p><b>Memory guardrails (see {@code docs/join-scalability-implementation-plan.md}, decision D1)</b>: because
 * both sides and the joined output are fully materialised in memory (the simplification above), each side is
 * capped at {@link JoinConfig#getMaxSideRows()} rows and the joined output at
 * {@link JoinConfig#getMaxOutputRows()} rows. A breach throws {@code JoinLimitExceededException}, which
 * {@link #createResultStore} captures via {@link ResultStore#addError(Throwable)} - the same in-band error
 * reporting {@link SearchableSearchProvider#buildStore} uses - so an oversized join fails with a clear message
 * rather than exhausting heap or surfacing as an opaque 500.</p>
 */
class JoinSearchProvider implements SearchProvider {

    /**
     * The Plan B/State datasource type name - mirrors {@code stroom.planb.shared.PlanBDoc.TYPE}. Duplicated as a
     * literal, rather than depending on {@code stroom-planb-impl} from this module, purely to detect a
     * {@link JoinAlgorithm#BROADCAST_LOOKUP}-eligible side (decision D8) - see this class's Javadoc.
     */
    private static final String PLAN_B_DATA_SOURCE_TYPE = "PlanB";

    /**
     * The Plan B key column's field name - mirrors {@code stroom.planb.impl.dao.state.StateFields.KEY}. Only an
     * equi-key on exactly this field is point-lookup-addressable (decision D8).
     */
    private static final String PLAN_B_KEY_FIELD = "Key";

    /** The lookup side's synthetic output column names (decision D5) - see {@link #lookupSideSyntheticColumns()}. */
    private static final String LOOKUP_KEY_COLUMN = "Key";
    private static final String LOOKUP_VALUE_COLUMN = "Value";

    private final Provider<SearchProviderRegistry> searchProviderRegistryProvider;
    private final CoprocessorsFactory coprocessorsFactory;
    private final ResultStoreFactory resultStoreFactory;
    private final ExpressionPredicateFactory expressionPredicateFactory;
    private final Provider<JoinConfig> joinConfigProvider;
    private final StateFetcher stateFetcher;

    /**
     * @param searchProviderRegistryProvider must not be null.
     * @param coprocessorsFactory            must not be null.
     * @param resultStoreFactory             must not be null.
     * @param expressionPredicateFactory      must not be null.
     * @param joinConfigProvider              must not be null; supplies the current {@code stroom.query.join}
     *                                        memory guardrails (see
     *                                        {@code docs/join-scalability-implementation-plan.md}, decision D1)
     *                                        - a live {@link Provider}, not a captured value, so a runtime config
     *                                        change is honoured by the next search rather than requiring a
     *                                        restart (the same convention {@code DispatchingQueryCompiler} uses
     *                                        for {@code QueryOptimiserConfig}).
     * @param stateFetcher                    must not be null; performs the keyed point lookup for a
     *                                        {@link JoinAlgorithm#BROADCAST_LOOKUP} join (decisions D5/D7/D8,
     *                                        item B1) - its binding lives in {@code stroom-planb-impl}'s Guice
     *                                        module, resolved by Guice without this module needing a compile-time
     *                                        dependency on that one (the {@link StateFetcher} interface itself is
     *                                        already visible via {@code stroom-query-language}).
     */
    @Inject
    JoinSearchProvider(final Provider<SearchProviderRegistry> searchProviderRegistryProvider,
                       final CoprocessorsFactory coprocessorsFactory,
                       final ResultStoreFactory resultStoreFactory,
                       final ExpressionPredicateFactory expressionPredicateFactory,
                       final Provider<JoinConfig> joinConfigProvider,
                       final StateFetcher stateFetcher) {
        this.searchProviderRegistryProvider =
                Objects.requireNonNull(searchProviderRegistryProvider, "searchProviderRegistryProvider");
        this.coprocessorsFactory = Objects.requireNonNull(coprocessorsFactory, "coprocessorsFactory");
        this.resultStoreFactory = Objects.requireNonNull(resultStoreFactory, "resultStoreFactory");
        this.expressionPredicateFactory =
                Objects.requireNonNull(expressionPredicateFactory, "expressionPredicateFactory");
        this.joinConfigProvider = Objects.requireNonNull(joinConfigProvider, "joinConfigProvider");
        this.stateFetcher = Objects.requireNonNull(stateFetcher, "stateFetcher");
    }

    /** Which side of a join is the {@link JoinAlgorithm#BROADCAST_LOOKUP}-eligible one - see
     * {@link #detectPlanBLookupSide}. */
    private enum LookupSide {
        LEFT,
        RIGHT
    }

    @Override
    public String getDataSourceType() {
        return JoinDataSourceType.TYPE;
    }

    @Override
    public List<DocRef> getDataSourceDocRefs() {
        // A join has no discoverable datasource docs of its own - its two sides are ordinary datasources,
        // each already discoverable via their own SearchProvider.
        return List.of();
    }

    @Override
    public ResultPage<QueryField> getFieldInfo(final FindFieldCriteria criteria) {
        // Field info for a join's outer query comes from its sides' own datasources via AstToSearchRequestMapper
        // (Task 6.1b), not from this sentinel type - nothing to report here.
        return ResultPage.empty();
    }

    @Override
    public int getFieldCount(final DocRef docRef) {
        return 0;
    }

    /**
     * Executes a {@code join}-clause search: realises both sides, combines them, applies the outer {@code where}
     * clause, and feeds every surviving row to a coprocessor - see this class's Javadoc for the full shape.
     *
     * <p>Following {@code SearchableSearchProvider.buildStore}'s established pattern, the outer {@link ResultStore}
     * is created <i>before</i> any of that work runs (it only depends on {@code searchRequest}, not on either side
     * having been realised), and every step after that is wrapped in a single try/catch that reports a failure via
     * {@link ResultStore#addError(Throwable)} rather than letting it propagate out of this method - including a
     * breach of either {@code stroom.query.join} memory guardrail (see
     * {@code docs/join-scalability-implementation-plan.md}, decision D1), which surfaces as a
     * {@link JoinLimitExceededException} in-band, not an {@code OutOfMemoryError} or an opaque 500.</p>
     *
     * <p><b>Preconditions:</b> {@code searchRequest} must not be null and must carry a non-null
     * {@code Query.joinSpec} (checked below).<br>
     * <b>Postconditions:</b> always returns a completed (or completing) {@link ResultStore}, never null; never
     * throws - any failure is captured on the returned store instead.</p>
     *
     * @throws IllegalArgumentException if {@code searchRequest} has no {@code JoinSpec} - this is a caller
     *                                   programming error (the wrong request routed here), not a data-dependent
     *                                   failure, so it is thrown immediately rather than captured on a store.
     */
    @Override
    public ResultStore createResultStore(final SearchRequest searchRequest) {
        Objects.requireNonNull(searchRequest, "searchRequest");
        final JoinSpec joinSpec = requireJoinSpec(searchRequest);
        final JoinConfig joinConfig = joinConfigProvider.get();

        final CoprocessorsImpl coprocessors = coprocessorsFactory.create(
                searchRequest, DataStoreSettings.createBasicSearchResultStoreSettings());
        final ResultStore resultStore = resultStoreFactory.create(
                searchRequest.getSearchRequestSource(), coprocessors);
        try {
            joinAndFeed(searchRequest, joinSpec, joinConfig, coprocessors);
        } catch (final RuntimeException e) {
            resultStore.addError(e);
        } finally {
            resultStore.signalComplete();
        }
        return resultStore;
    }

    /**
     * @param searchRequest must not be null.
     * @return {@code searchRequest.getQuery().getJoinSpec()}, never null.
     * @throws IllegalArgumentException if {@code searchRequest.getQuery()} or its {@code JoinSpec} is null.
     */
    private static JoinSpec requireJoinSpec(final SearchRequest searchRequest) {
        final JoinSpec joinSpec = searchRequest.getQuery() == null ? null : searchRequest.getQuery().getJoinSpec();
        if (joinSpec == null) {
            throw new IllegalArgumentException(
                    "SearchRequest routed to " + JoinDataSourceType.TYPE + " must carry a JoinSpec");
        }
        return joinSpec;
    }

    /**
     * Executes the join and feeds every surviving row to {@code coprocessors} - the real join logic, factored out
     * of {@link #createResultStore} so that method's try/catch/finally around it (the
     * {@link ResultStore#addError(Throwable)} handling) stays a thin, readable wrapper. Dispatches to one of two
     * strategies (decision D8, item B1):
     * <ul>
     *     <li>{@link #joinAndFeedViaBroadcastLookup} when {@link #detectPlanBLookupSide} finds one side is a
     *     keyed Plan B/State lookup - streams the other (probe) side against it, never realising the lookup
     *     side at all.</li>
     *     <li>{@link #joinAndFeedViaHashJoin} otherwise - the original strategy: realise both sides in full,
     *     combine with an in-memory hash join.</li>
     * </ul>
     *
     * <p><b>Preconditions:</b> all four parameters must be non-null.<br>
     * <b>Postconditions:</b> on normal return, every joined row surviving the outer {@code where} clause has been
     * passed to {@code coprocessors.accept(...)}; returns nothing.</p>
     *
     * @throws JoinLimitExceededException if a side (or, for the hash-join strategy, the joined output) would
     *                                    exceed its {@code joinConfig} cap.
     */
    private void joinAndFeed(
            final SearchRequest searchRequest, final JoinSpec joinSpec, final JoinConfig joinConfig,
            final CoprocessorsImpl coprocessors) {
        final @Nullable LookupSide lookupSide = detectPlanBLookupSide(joinSpec);
        if (lookupSide != null) {
            joinAndFeedViaBroadcastLookup(searchRequest, joinSpec, joinConfig, coprocessors, lookupSide);
        } else {
            joinAndFeedViaHashJoin(searchRequest, joinSpec, joinConfig, coprocessors);
        }
    }

    /**
     * The original join strategy (Task 6.1d, Phase 0's guardrails): realises both sides in full, combines them
     * with an in-memory hash join, applies the outer {@code where} clause, and feeds every surviving row to
     * {@code coprocessors}.
     *
     * <p><b>Preconditions:</b> all four parameters must be non-null.<br>
     * <b>Postconditions:</b> on normal return, every joined row surviving the outer {@code where} clause has been
     * passed to {@code coprocessors.accept(...)}; returns nothing. Both sides' intermediate {@link ResultStore}s
     * (opened by {@link #realiseSide}) are always destroyed before this method returns or throws.</p>
     *
     * @throws JoinLimitExceededException if either side exceeds {@code joinConfig.getMaxSideRows()} while being
     *                                    realised, or the joined output would exceed
     *                                    {@code joinConfig.getMaxOutputRows()}.
     */
    private void joinAndFeedViaHashJoin(
            final SearchRequest searchRequest, final JoinSpec joinSpec, final JoinConfig joinConfig,
            final CoprocessorsImpl coprocessors) {
        // Realise the left side first; if realising the right side then fails, the left side's already-open
        // ResultStore must still be destroyed (the try/finally below only covers both sides once both exist).
        final RealisedSide left = realiseSide(joinSpec.getLeft(), joinConfig.getMaxSideRows());
        final RealisedSide right;
        try {
            right = realiseSide(joinSpec.getRight(), joinConfig.getMaxSideRows());
        } catch (final RuntimeException e) {
            left.resultStore.destroy();
            throw e;
        }
        final List<Val[]> joinedRows;
        try {
            final Side leftSide = new Side(left.rows, keyPositions(left.columns, joinSpec.getEquiKeys(), true),
                    left.columns.size());
            final Side rightSide = new Side(right.rows, keyPositions(right.columns, joinSpec.getEquiKeys(), false),
                    right.columns.size());
            final JoinType joinType = joinSpec.getJoinType() == JoinSpec.JoinType.LEFT
                    ? JoinType.LEFT
                    : JoinType.INNER;
            joinedRows = JoinExecutor.join(
                    leftSide, rightSide, joinType, JoinAlgorithm.HASH_JOIN, joinConfig.getMaxOutputRows());
        } finally {
            left.resultStore.destroy();
            right.resultStore.destroy();
        }

        // Apply the outer where clause across the joined rows (see whereRowPredicate) - the join's "where" can't
        // be applied by either single-source side (it references both aliases), so it's evaluated here on the
        // combined row, then only matching rows are fed to the coprocessor.
        final Predicate<Values> whereRowPredicate = whereRowPredicate(searchRequest, left.columns, right.columns,
                joinSpec.getEquiKeys().getFirst().getLeftAlias(),
                joinSpec.getEquiKeys().getFirst().getRightAlias());

        // Feed each surviving joined row at the FieldIndex positions its alias-qualified select-column
        // expressions claimed.
        final int[] mapping = buildFieldMapping(
                coprocessors.getFieldIndex(),
                left.columns,
                right.columns,
                joinSpec.getEquiKeys().getFirst().getLeftAlias(),
                joinSpec.getEquiKeys().getFirst().getRightAlias());

        for (final Val[] joinedRow : joinedRows) {
            if (whereRowPredicate.test(Values.of(joinedRow))) {
                coprocessors.accept(assembleRow(joinedRow, mapping));
            }
        }
    }

    /**
     * The enrichment-join fast path (decisions D5/D7/D8, item B1): realises only the probe side (the side that
     * is <i>not</i> the Plan B lookup), then streams it through {@link JoinExecutor#broadcastLookupJoin} against
     * {@link #stateFetcher} - the lookup side's own store is never realised or scanned.
     *
     * <p>{@code JoinExecutor.broadcastLookupJoin} always produces combined rows shaped
     * {@code [probe columns..., Key, Value]} (probe-first). {@link #whereRowPredicate}/{@link #buildFieldMapping}
     * are purely positional despite their "left"/"right" parameter names (see their own Javadoc) - so this method
     * passes the probe side and lookup side as whichever slice is physically first/second in that combined row,
     * not necessarily in {@code JoinSpec}'s left/right order (that depends on which side {@code lookupSide}
     * names).</p>
     *
     * <p><b>Preconditions:</b> all five parameters must be non-null.<br>
     * <b>Postconditions:</b> on normal return, every joined row surviving the outer {@code where} clause has been
     * passed to {@code coprocessors.accept(...)}; returns nothing. The probe side's intermediate
     * {@link ResultStore} (opened by {@link #realiseSide}) is always destroyed before this method returns or
     * throws.</p>
     *
     * @throws JoinLimitExceededException if the probe side exceeds {@code joinConfig.getMaxSideRows()} while
     *                                    being realised, or the joined output would exceed
     *                                    {@code joinConfig.getMaxOutputRows()}.
     */
    private void joinAndFeedViaBroadcastLookup(
            final SearchRequest searchRequest, final JoinSpec joinSpec, final JoinConfig joinConfig,
            final CoprocessorsImpl coprocessors, final LookupSide lookupSide) {
        final boolean lookupIsLeft = lookupSide == LookupSide.LEFT;
        final SearchRequest probeRequest = lookupIsLeft ? joinSpec.getRight() : joinSpec.getLeft();
        final SearchRequest lookupRequest = lookupIsLeft ? joinSpec.getLeft() : joinSpec.getRight();
        final JoinEquiKey equiKey = joinSpec.getEquiKeys().getFirst();
        final String probeKeyField = lookupIsLeft ? equiKey.getRightField() : equiKey.getLeftField();
        final String probeAlias = lookupIsLeft ? equiKey.getRightAlias() : equiKey.getLeftAlias();
        final String lookupAlias = lookupIsLeft ? equiKey.getLeftAlias() : equiKey.getRightAlias();
        final String mapName = lookupRequest.getQuery().getDataSource().getName();

        final RealisedSide probe = realiseSide(probeRequest, joinConfig.getMaxSideRows());
        try {
            final int probeKeyPosition = positionOf(probe.columns, probeKeyField);
            final List<Column> lookupColumns = lookupSideSyntheticColumns();
            final JoinType joinType = joinSpec.getJoinType() == JoinSpec.JoinType.LEFT
                    ? JoinType.LEFT
                    : JoinType.INNER;

            // Combined row order is always [probe..., Key, Value] - see this method's Javadoc on why "probe"/
            // "lookup" here play the role "left"/"right" normally would, regardless of which JoinSpec side the
            // lookup actually is.
            final Predicate<Values> whereRowPredicate = whereRowPredicate(
                    searchRequest, probe.columns, lookupColumns, probeAlias, lookupAlias);
            final int[] mapping = buildFieldMapping(
                    coprocessors.getFieldIndex(), probe.columns, lookupColumns, probeAlias, lookupAlias);

            JoinExecutor.broadcastLookupJoin(
                    probe.rows.iterator(), probeKeyPosition, probe.columns.size(),
                    stateFetcher, mapName, effectiveTimeMs(), joinType, joinConfig.getMaxOutputRows(),
                    combinedRow -> {
                        if (whereRowPredicate.test(Values.of(combinedRow))) {
                            coprocessors.accept(assembleRow(combinedRow, mapping));
                        }
                    });
        } finally {
            probe.resultStore.destroy();
        }
    }

    /**
     * Decides whether either side of {@code joinSpec} is safely usable as a {@link JoinAlgorithm#BROADCAST_LOOKUP}
     * lookup side (decision D8) - structurally, without resolving the actual {@code PlanBDoc} (which would need a
     * new dependency on {@code stroom-planb-impl}; see this class's Javadoc and the {@code stateFetcher}
     * constructor parameter's Javadoc for why that dependency isn't needed for the lookup itself).
     *
     * <p>A side qualifies when: (a) there is exactly one equi-key (a composite key isn't representable via
     * {@link StateFetcher#getState}'s single-string key - decision D8), (b) that side's datasource type is
     * {@link #PLAN_B_DATA_SOURCE_TYPE}, and (c) the equi-key field on that side is exactly
     * {@link #PLAN_B_KEY_FIELD} (the only point-lookup-addressable column). If both sides somehow qualify (e.g. a
     * Plan B store joined to another Plan B store on their key columns), the left side wins, deterministically -
     * an edge case, not expected in practice.</p>
     *
     * <p><b>Known v1 limitation</b> (decision D6): this does not check the underlying store's actual
     * {@code stateType}. A {@code RANGED_STATE}/{@code TEMPORAL_RANGED_STATE}/{@code SESSION} store (which use a
     * different key shape internally) would still be detected as lookup-eligible here, and would then fail with
     * whatever exception {@link StateFetcher#getState} itself throws for a mismatched key (e.g. a
     * {@code NumberFormatException} for a range store) - always safely captured by {@link #createResultStore}'s
     * error handling, never silently wrong or unhandled, just a less specific message than a purpose-built
     * rejection would give. A future enhancement could inject a doc cache to check {@code stateType} up front.</p>
     *
     * @param joinSpec must not be null.
     * @return null if neither side qualifies.
     */
    private static @Nullable LookupSide detectPlanBLookupSide(final JoinSpec joinSpec) {
        if (joinSpec.getEquiKeys().size() != 1) {
            return null;
        }
        final JoinEquiKey equiKey = joinSpec.getEquiKeys().getFirst();
        if (isPlanBKeyedLookupSide(joinSpec.getLeft(), equiKey.getLeftField())) {
            return LookupSide.LEFT;
        }
        if (isPlanBKeyedLookupSide(joinSpec.getRight(), equiKey.getRightField())) {
            return LookupSide.RIGHT;
        }
        return null;
    }

    private static boolean isPlanBKeyedLookupSide(final SearchRequest sideRequest, final String equiKeyField) {
        final DocRef dataSource = sideRequest.getQuery() == null ? null : sideRequest.getQuery().getDataSource();
        return dataSource != null
               && PLAN_B_DATA_SOURCE_TYPE.equals(dataSource.getType())
               && PLAN_B_KEY_FIELD.equals(equiKeyField);
    }

    /** The lookup side's two synthetic output columns (decision D5) - {@code Key} (the probe row's own join-key
     * value, echoed back) then {@code Value} (the looked-up {@link Val}, {@link ValNull#INSTANCE} on a miss). */
    private static List<Column> lookupSideSyntheticColumns() {
        return List.of(
                Column.builder().id(LOOKUP_KEY_COLUMN).name(LOOKUP_KEY_COLUMN).expression(LOOKUP_KEY_COLUMN).build(),
                Column.builder().id(LOOKUP_VALUE_COLUMN).name(LOOKUP_VALUE_COLUMN)
                        .expression(LOOKUP_VALUE_COLUMN).build());
    }

    /**
     * The instant to evaluate a {@link JoinAlgorithm#BROADCAST_LOOKUP} lookup as of (decision D7). <b>v1
     * simplification</b>: always "now" - only meaningful for a temporal store in the first place, and a more
     * precise "the query's own effective time" is a documented follow-up, not implemented here.
     */
    private static long effectiveTimeMs() {
        return System.currentTimeMillis();
    }

    /**
     * Builds a predicate over the <i>combined</i> joined row (left columns then right columns) from the outer
     * query's {@code where} clause ({@code Query.expression}) - see
     * {@code docs/query-optimiser-implementation-plan.md}, Phase 6 "where across joins". The where clause
     * references alias-qualified fields ({@code a.field}/{@code b.field}); each is resolved to its combined-row
     * position (left columns at their own index, right columns offset by the left width) and extracted via the
     * same {@link ValuesFunctionFactory} the coprocessor's own {@code valueFilter} uses, so numeric/date/text
     * comparison semantics match. A trivial/absent where clause yields an always-true predicate.
     *
     * <p>Evaluating the where clause here, on the combined row, is the correct physical position for a join: a
     * single side can't evaluate a predicate that references both aliases, and the combined row is where every
     * referenced field resolves. What's already resolved before the join runs - per-side predicate push-down at
     * compile time ({@code OptimisingQueryCompiler}'s Task A1) - is simply absent from {@code where} by the time
     * it gets here; this method only ever sees the residual.</p>
     */
    private Predicate<Values> whereRowPredicate(
            final SearchRequest searchRequest,
            final List<Column> leftColumns,
            final List<Column> rightColumns,
            final String leftAlias,
            final String rightAlias) {
        final ExpressionOperator where = searchRequest.getQuery() == null
                ? null
                : searchRequest.getQuery().getExpression();
        if (where == null || where.getChildren() == null || where.getChildren().isEmpty()) {
            return values -> true;
        }

        final Map<String, ValueFunctionFactory<Values>> accessors = new HashMap<>();
        for (int i = 0; i < leftColumns.size(); i++) {
            accessors.put(leftAlias + "." + leftColumns.get(i).getName(),
                    new ValuesFunctionFactory(leftColumns.get(i), i));
        }
        final int leftWidth = leftColumns.size();
        for (int j = 0; j < rightColumns.size(); j++) {
            accessors.put(rightAlias + "." + rightColumns.get(j).getName(),
                    new ValuesFunctionFactory(rightColumns.get(j), leftWidth + j));
        }
        final ValueFunctionFactories<Values> factories = accessors::get;

        return expressionPredicateFactory
                .createOptional(where, factories, searchRequest.getDateTimeSettings())
                .orElse(values -> true);
    }

    /**
     * Runs {@code sideRequest} (an ordinary, already-compiled single-source request - see {@code
     * OptimisingQueryCompiler#compileJoinSide}, Task 6.1b) to completion via its own {@link SearchProvider}, and
     * reads back every row - the same "run a sub-query, then read every row of the completed store" shape {@code
     * QueryServiceImpl#getColumnValues} already uses, not new machinery - aborting once more than
     * {@code maxSideRows} rows have been read, per {@code docs/join-scalability-implementation-plan.md}, decision
     * D1 (Phase 0, item C4).
     *
     * <p><b>Preconditions:</b> {@code sideRequest} must not be null and must carry a non-null
     * {@code Query.dataSource} naming a datasource type with a registered {@link SearchProvider}; {@code
     * maxSideRows} must be {@code >= 0} (a value of {@code 0} means "no rows allowed at all" - the first row read
     * breaches it).<br>
     * <b>Postconditions:</b> on normal return, {@code result.rows()} has {@code <= maxSideRows} rows; the returned
     * {@link RealisedSide} is never null and its {@code resultStore} is left open (the caller,
     * {@link #joinAndFeed}, owns destroying it). On any failure - including a breach of {@code maxSideRows} - the
     * side's {@link ResultStore} opened by this method is destroyed before the exception propagates, so no store
     * leaks regardless of where realisation fails.</p>
     *
     * @param maxSideRows the maximum number of rows this side may contribute; see {@link JoinConfig#getMaxSideRows()}.
     * @throws JoinLimitExceededException if more than {@code maxSideRows} rows are read from this side.
     */
    private RealisedSide realiseSide(final SearchRequest sideRequest, final long maxSideRows) {
        final DocRef dataSourceRef = sideRequest.getQuery().getDataSource();
        final SearchProvider searchProvider = searchProviderRegistryProvider.get()
                .getSearchProvider(dataSourceRef)
                .orElseThrow(() -> new IllegalStateException(
                        "No SearchProvider registered for join side datasource type '"
                        + dataSourceRef.getType() + "'"));

        final ResultStore resultStore = searchProvider.createResultStore(sideRequest);
        // Once the store is open, any failure completing it or reading it back must destroy it here - the store
        // is only handed to the caller (and thus only becomes destroyable by the caller's finally) on the fully
        // successful return, so a failure between here and that return would otherwise leak this side's store.
        try {
            resultStore.awaitCompletion();

            final DataStore dataStore = resultStore.getData(SearchRequestFactory.TABLE_COMPONENT_ID);
            final List<Column> columns = dataStore.getColumns();
            final List<Val[]> rows = new ArrayList<>();
            dataStore.fetch(
                    columns,
                    OffsetRange.UNBOUNDED,
                    OpenGroups.ALL,
                    null,
                    IdentityItemMapper.INSTANCE,
                    item -> {
                        if (rows.size() >= maxSideRows) {
                            throw JoinLimitExceededException.forRowCount(
                                    "join side row count", maxSideRows, rows.size() + 1);
                        }
                        rows.add(item.toArray());
                    },
                    count -> {
                    });

            return new RealisedSide(resultStore, columns, rows);
        } catch (final InterruptedException e) {
            resultStore.destroy();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while realising a join side", e);
        } catch (final RuntimeException e) {
            resultStore.destroy();
            throw e;
        }
    }

    /** One equi-key's field name, resolved to its position among {@code columns} (composite keys supported). */
    private static int[] keyPositions(
            final List<Column> columns, final List<JoinEquiKey> equiKeys, final boolean forLeftSide) {
        final int[] positions = new int[equiKeys.size()];
        for (int i = 0; i < equiKeys.size(); i++) {
            final String fieldName = forLeftSide ? equiKeys.get(i).getLeftField() : equiKeys.get(i).getRightField();
            positions[i] = positionOf(columns, fieldName);
        }
        return positions;
    }

    private static int positionOf(final List<Column> columns, final String fieldName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equals(fieldName)) {
                return i;
            }
        }
        throw new IllegalStateException("Equi-key field '" + fieldName + "' not found among compiled columns "
                                         + columns.stream().map(Column::getName).toList());
    }

    /**
     * Maps each position in the outer coprocessor's {@code fieldIndex} to a position in the <i>combined</i> joined
     * row (left side's columns then right side's columns). The {@code fieldIndex} keys on the outer {@code select}
     * columns' alias-qualified expression text ({@code "a.field"}); this splits that into alias + field, decides
     * which side the alias names, and finds that field's position within that side's own columns. A
     * {@code fieldIndex} entry that isn't an {@code alias.field} naming one of the two join sides (e.g. an
     * auto-added special {@code StreamId}/{@code EventId} navigation column) maps to {@code -1} - {@link
     * #assembleRow} fills those with {@link ValNull} rather than a wrong value.
     *
     * @return an array of length {@code fieldIndex.size()}; entry {@code i} is the combined-row position feeding
     *         outer field position {@code i}, or {@code -1} for "no source".
     */
    static int[] buildFieldMapping(
            final FieldIndex fieldIndex,
            final List<Column> leftColumns,
            final List<Column> rightColumns,
            final String leftAlias,
            final String rightAlias) {
        final int leftWidth = leftColumns.size();
        final int[] mapping = new int[fieldIndex.size()];
        for (int outerPos = 0; outerPos < mapping.length; outerPos++) {
            final String name = fieldIndex.getField(outerPos);
            mapping[outerPos] = -1;
            final int dot = name == null ? -1 : name.indexOf('.');
            if (dot <= 0) {
                continue;
            }
            final String alias = name.substring(0, dot);
            final String field = name.substring(dot + 1);
            if (alias.equals(leftAlias)) {
                final int col = indexOfColumn(leftColumns, field);
                if (col >= 0) {
                    mapping[outerPos] = col;
                }
            } else if (alias.equals(rightAlias)) {
                final int col = indexOfColumn(rightColumns, field);
                if (col >= 0) {
                    mapping[outerPos] = leftWidth + col;
                }
            }
        }
        return mapping;
    }

    private static int indexOfColumn(final List<Column> columns, final String fieldName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equals(fieldName)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Reorders one combined joined row into the {@code Val[]} the outer coprocessor expects, per the {@code
     * mapping} from {@link #buildFieldMapping}. Unmapped outer positions ({@code -1}) become {@link ValNull}.
     */
    static Val[] assembleRow(final Val[] combinedRow, final int[] mapping) {
        final Val[] out = new Val[mapping.length];
        for (int i = 0; i < mapping.length; i++) {
            out[i] = mapping[i] < 0 ? ValNull.INSTANCE : combinedRow[mapping[i]];
        }
        return out;
    }

    private record RealisedSide(ResultStore resultStore, List<Column> columns, List<Val[]> rows) {
    }
}
