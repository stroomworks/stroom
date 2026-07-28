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
import stroom.query.common.v2.JoinBuildSideLookupFactory;
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
import stroom.query.planner.join.BuildSideLookup;
import stroom.query.planner.join.JoinExecutor;
import stroom.query.planner.join.JoinLimitExceededException;
import stroom.query.planner.logical.JoinType;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.ResultPage;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Routes execution for a query compiled with a {@code join} clause - see
 * Task 6.1. Registered under the sentinel
 * {@link JoinDataSourceType#TYPE}, the same way {@link SearchableSearchProvider} registers once per
 * {@code Searchable} - {@link SearchProviderRegistryImpl} resolves either purely by {@code DocRef.getType}, no
 * special-casing needed there for this to work.
 *
 * <p>Depends on {@link SearchProviderRegistry} via a lazy {@link Provider} rather than directly: {@code
 * SearchProviderRegistryImpl} is itself constructed from {@code Set<SearchProvider>}, which this class is a
 * member of - injecting the registry directly would be a construction-time cycle; {@code Provider.get} defers
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
 * <p><b>Memory guardrails (decision D1)</b>: because
 * both sides and the joined output are fully materialised in memory (the simplification above), each side is
 * capped at {@link JoinConfig#getMaxSideRows} rows and the joined output at
 * {@link JoinConfig#getMaxOutputRows} rows. A breach throws {@code JoinLimitExceededException}, which
 * {@link #createResultStore} captures via {@link ResultStore#addError(Throwable)} - the same in-band error
 * reporting {@link SearchableSearchProvider#buildStore} uses - so an oversized join fails with a clear message
 * rather than exhausting heap or surfacing as an opaque 500.</p>
 */
class JoinSearchProvider implements SearchProvider {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(JoinSearchProvider.class);

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

    /**
     * The Graph DB datasource type name - mirrors {@code stroom.graphdb.shared.GraphDbDoc.TYPE}. Duplicated as a
     * literal, rather than depending on {@code stroom-graphdb-impl} from this module, purely to apply the graph-
     * side row-count guardrail (Task C4) - the same
     * "detect a side's type structurally, without a new module dependency" convention {@link
     * #PLAN_B_DATA_SOURCE_TYPE} already uses above.
     */
    private static final String GRAPH_DATA_SOURCE_TYPE = "GraphDb";

    /** The lookup side's synthetic output column names (decision D5) - see {@link #lookupSideSyntheticColumns}. */
    private static final String LOOKUP_KEY_COLUMN = "Key";
    private static final String LOOKUP_VALUE_COLUMN = "Value";

    private final Provider<SearchProviderRegistry> searchProviderRegistryProvider;
    private final CoprocessorsFactory coprocessorsFactory;
    private final ResultStoreFactory resultStoreFactory;
    private final ExpressionPredicateFactory expressionPredicateFactory;
    private final Provider<JoinConfig> joinConfigProvider;
    private final StateFetcher stateFetcher;
    private final JoinBuildSideLookupFactory buildSideLookupFactory;

    /**
     * @param searchProviderRegistryProvider must not be null.
     * @param coprocessorsFactory            must not be null.
     * @param resultStoreFactory             must not be null.
     * @param expressionPredicateFactory      must not be null.
     * @param joinConfigProvider              must not be null; supplies the current {@code stroom.query.join}
     *                                        memory guardrails (see
     * decision D1)
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
     * @param buildSideLookupFactory          must not be null; creates the {@code BuildSideLookup} the hash-join
     *                                        path realises its build (right) side into - on-heap while small,
     *                                        spilling to disk past {@link JoinConfig#getMaxHeapBuildRows} (see
     * items C1/C2).
     *                                        It owns the LMDB wiring so this module needs no LMDB dependency.
     */
    @Inject
    JoinSearchProvider(final Provider<SearchProviderRegistry> searchProviderRegistryProvider,
                       final CoprocessorsFactory coprocessorsFactory,
                       final ResultStoreFactory resultStoreFactory,
                       final ExpressionPredicateFactory expressionPredicateFactory,
                       final Provider<JoinConfig> joinConfigProvider,
                       final StateFetcher stateFetcher,
                       final JoinBuildSideLookupFactory buildSideLookupFactory) {
        this.searchProviderRegistryProvider =
                Objects.requireNonNull(searchProviderRegistryProvider, "searchProviderRegistryProvider");
        this.coprocessorsFactory = Objects.requireNonNull(coprocessorsFactory, "coprocessorsFactory");
        this.resultStoreFactory = Objects.requireNonNull(resultStoreFactory, "resultStoreFactory");
        this.expressionPredicateFactory =
                Objects.requireNonNull(expressionPredicateFactory, "expressionPredicateFactory");
        this.joinConfigProvider = Objects.requireNonNull(joinConfigProvider, "joinConfigProvider");
        this.stateFetcher = Objects.requireNonNull(stateFetcher, "stateFetcher");
        this.buildSideLookupFactory = Objects.requireNonNull(buildSideLookupFactory, "buildSideLookupFactory");
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
     * decision D1), which surfaces as a
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
     * @return {@code searchRequest.getQuery.getJoinSpec}, never null.
     * @throws IllegalArgumentException if {@code searchRequest.getQuery} or its {@code JoinSpec} is null.
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
     *     <li>{@link #joinAndFeedViaStreamingHashJoin} otherwise - realise the build (right) side into a
     *     {@code BuildSideLookup} (spilling to disk past a threshold) and stream the probe (left) side against
     *     it, feeding joined rows out as they are produced.</li>
     * </ul>
     *
     * <p><b>Preconditions:</b> all four parameters must be non-null.<br>
     * <b>Postconditions:</b> on normal return, every joined row surviving the outer {@code where} clause has been
     * passed to {@code coprocessors.accept(...)}; returns nothing.</p>
     *
     * @throws JoinLimitExceededException if the build side exceeds {@code joinConfig.getMaxSideRows} or the
     *                                    joined output exceeds {@code joinConfig.getMaxOutputRows}.
     */
    private void joinAndFeed(
            final SearchRequest searchRequest, final JoinSpec joinSpec, final JoinConfig joinConfig,
            final CoprocessorsImpl coprocessors) {
        final @Nullable LookupSide lookupSide = detectPlanBLookupSide(joinSpec);
        if (lookupSide != null) {
            joinAndFeedViaBroadcastLookup(searchRequest, joinSpec, joinConfig, coprocessors, lookupSide);
        } else {
            joinAndFeedViaStreamingHashJoin(searchRequest, joinSpec, joinConfig, coprocessors);
        }
    }

    /**
     * The streaming/spilling hash-join strategy (items
     * C1/C2). Rather than realising both sides fully in memory, it:
     * <ol>
     *     <li>realises the <b>build (right)</b> side into a {@link BuildSideLookup} from
     *     {@link #buildSideLookupFactory} - an on-heap hash map while small, transparently spilling to a
     *     disk-backed store once it grows past {@link JoinConfig#getMaxHeapBuildRows}, so a large build side no
     *     longer exhausts heap;</li>
     *     <li>streams the <b>probe (left)</b> side out of its {@code DataStore} one row at a time, joining each
     *     against the build side and feeding survivors of the outer {@code where} clause straight to
     *     {@code coprocessors} - the probe side is never collected into a list.</li>
     * </ol>
     *
     * <p><b>Build-side selection (A6).</b> For an {@code INNER} join the result is independent of which side is
     * built, so the <b>smaller</b> side (by {@link DataStore#getSize}) is chosen as the build side and the
     * larger is streamed - cheaper, and never a larger build side than the old always-build-right behaviour. A
     * {@code LEFT} join <b>always</b> keeps build = right / probe = left: its preserved (left) side must be the
     * probe side so unmatched left rows are emitted inline (null-padded) by {@link JoinExecutor#streamingProbe}
     * with no outer-join bookkeeping. Ties keep the default (build = right).</p>
     *
     * <p>The output-row cap ({@link JoinConfig#getMaxOutputRows}) is enforced by
     * {@link JoinExecutor#streamingProbe} as rows are produced; the build-side cap
     * ({@link JoinConfig#getMaxSideRows}) is enforced as the build side is realised. The streaming probe side is
     * deliberately not row-capped - it never accumulates.</p>
     *
     * <p><b>Preconditions:</b> all four parameters must be non-null.<br>
     * <b>Postconditions:</b> on normal return, every joined row surviving the outer {@code where} clause has been
     * passed to {@code coprocessors.accept(...)}; returns nothing. The {@link BuildSideLookup} (deleting any spill
     * directory) and both sides' intermediate {@link ResultStore}s are always released before this method returns
     * or throws.</p>
     *
     * @throws JoinLimitExceededException if the build side exceeds {@code joinConfig.getMaxSideRows} while being
     *                                    realised, or the joined output would exceed
     *                                    {@code joinConfig.getMaxOutputRows}.
     */
    private void joinAndFeedViaStreamingHashJoin(
            final SearchRequest searchRequest, final JoinSpec joinSpec, final JoinConfig joinConfig,
            final CoprocessorsImpl coprocessors) {
        final JoinEquiKey firstEquiKey = joinSpec.getEquiKeys().getFirst();
        final JoinType joinType = joinSpec.getJoinType() == JoinSpec.JoinType.LEFT
                ? JoinType.LEFT
                : JoinType.INNER;

        // Open the left side first; if opening the right side then fails, the left side's already-open
        // ResultStore must still be destroyed (the try/finally below only covers both once both exist). Neither
        // call reads any rows yet.
        final OpenedSide left = openSide(joinSpec.getLeft(), joinConfig);
        try {
            final OpenedSide right = openSide(joinSpec.getRight(), joinConfig);
            // A6 build-side selection: for an INNER join build the smaller side; a LEFT join must keep probe=left
            // (its unmatched rows emit inline) so it never swaps. Both sides' sub-searches have completed, so
            // DataStore.getSize is a final, O(1) size signal. "build" is realised into the lookup; "probe" is
            // streamed. The combined row emitted downstream is always [probe columns..., build columns...].
            final boolean buildIsLeft = joinType == JoinType.INNER
                                        && left.dataStore.getSize() < right.dataStore.getSize();
            final OpenedSide build = buildIsLeft ? left : right;
            final OpenedSide probe = buildIsLeft ? right : left;
            final String buildAlias = buildIsLeft ? firstEquiKey.getLeftAlias() : firstEquiKey.getRightAlias();
            final String probeAlias = buildIsLeft ? firstEquiKey.getRightAlias() : firstEquiKey.getLeftAlias();
            LOGGER.debug(() -> "Join build-side selection: building " + (buildIsLeft ? "LEFT" : "RIGHT")
                               + " side (leftSize=" + left.dataStore.getSize()
                               + ", rightSize=" + right.dataStore.getSize() + ", joinType=" + joinType + ")");

            // The build lookup owns a spill store that must be closed even if realising/probing fails.
            try (final BuildSideLookup buildSide = buildSideLookupFactory.create(
                    joinConfig.getMaxHeapBuildRows(), joinConfig.getMaxHeapBuildBytes())) {
                // Build phase: stream every build-side row into the lookup (null-keyed rows can never match, so
                // they are not stored as probe targets), capped by maxSideRows.
                final int[] buildKeyPositions = keyPositions(build.columns, joinSpec.getEquiKeys(), buildIsLeft);
                realiseIntoBuildSide(build.dataStore, build.columns, buildKeyPositions, buildSide,
                        joinConfig.getMaxSideRows());

                // whereRowPredicate/buildFieldMapping are positional (probe slice first, build slice second) - the
                // alias args map the outer query's a./b. fields onto whichever physical slice holds them, so this
                // is correct in either orientation (the broadcast-lookup path relies on the same property).
                final Predicate<Values> whereRowPredicate = whereRowPredicate(
                        searchRequest, probe.columns, build.columns, probeAlias, buildAlias);
                final int[] mapping = buildFieldMapping(
                        coprocessors.getFieldIndex(), probe.columns, build.columns, probeAlias, buildAlias);

                // Probe phase: stream the probe side, join each row against the build side, feed survivors out.
                final Consumer<Val[]> probeConsumer = JoinExecutor.streamingProbe(
                        keyPositions(probe.columns, joinSpec.getEquiKeys(), !buildIsLeft),
                        buildSide,
                        build.columns.size(),
                        joinType,
                        joinConfig.getMaxOutputRows(),
                        combinedRow -> {
                            if (whereRowPredicate.test(Values.of(combinedRow))) {
                                coprocessors.accept(assembleRow(combinedRow, mapping));
                            }
                        });
                fetchRows(probe.dataStore, probe.columns, probeConsumer);
            } finally {
                right.resultStore.destroy();
            }
        } finally {
            left.resultStore.destroy();
        }
    }

    /**
     * Streams every row of an already-realised build side's {@code dataStore} into {@code buildSide}, deriving
     * each row's equi-key with {@link JoinExecutor#keyOf} (the identical derivation {@link JoinExecutor#streamingProbe}
     * uses for the probe side) and skipping SQL-null-keyed rows - they can never match, so they are not probe
     * targets, exactly as the original in-memory hash join did.
     *
     * <p><b>Preconditions:</b> all parameters non-null; {@code maxSideRows >= 0}.<br>
     * <b>Postconditions:</b> every non-null-keyed row has been {@code put} into {@code buildSide}.</p>
     *
     * @throws JoinLimitExceededException if more than {@code maxSideRows} rows are read from the build side.
     */
    private void realiseIntoBuildSide(final DataStore dataStore, final List<Column> columns,
                                      final int[] keyPositions, final BuildSideLookup buildSide,
                                      final long maxSideRows) {
        final long[] realised = {0L};
        fetchRows(dataStore, columns, row -> {
            if (realised[0] >= maxSideRows) {
                throw JoinLimitExceededException.forRowCount(
                        "join build side row count", maxSideRows, realised[0] + 1);
            }
            realised[0]++;
            final List<String> key = JoinExecutor.keyOf(row, keyPositions);
            if (key != null) {
                buildSide.put(key, row);
            }
        });
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
     * {@link ResultStore} (opened by {@link #openSide}) is always destroyed before this method returns or
     * throws.</p>
     *
     * <p>The probe side is <b>streamed</b> out of its {@code DataStore} one row at a time (like the hash-join
     * path) rather than realised into a list, so an enrichment join over an arbitrarily large event stream runs
     * in bounded memory; it is therefore not row-capped by {@code maxSideRows}. Only the joined output is bounded
     * (by {@code maxOutputRows}).</p>
     *
     * @throws JoinLimitExceededException if the joined output would exceed {@code joinConfig.getMaxOutputRows}.
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

        final OpenedSide probe = openSide(probeRequest, joinConfig);
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

            // Stream the probe side one row at a time through a per-row lookup consumer (never realising the probe
            // side into a list), each looked up against the Plan B/State store; the lookup side is never scanned.
            final Consumer<Val[]> probeConsumer = JoinExecutor.broadcastLookupProbe(
                    probeKeyPosition, probe.columns.size(),
                    stateFetcher, mapName, effectiveTimeMs(), joinType, joinConfig.getMaxOutputRows(),
                    combinedRow -> {
                        if (whereRowPredicate.test(Values.of(combinedRow))) {
                            coprocessors.accept(assembleRow(combinedRow, mapping));
                        }
                    });
            fetchRows(probe.dataStore, probe.columns, probeConsumer);
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
     * Phase 6 "where across joins". The where clause
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
     * OptimisingQueryCompiler#compileJoinSide}) to completion via its own {@link SearchProvider} and returns its
     * completed {@link DataStore} and columns, <b>without</b> reading any rows - the caller streams rows via
     * {@link #fetchRows} on demand (the build side into a {@link BuildSideLookup}, the probe side straight through
     * the join). Splitting "open the side" from "read the side" is what lets the probe side stream rather than be
     * materialised into a list.
     *
     * <p><b>Preconditions:</b> {@code sideRequest} must be non-null and carry a non-null {@code Query.dataSource}
     * naming a datasource type with a registered {@link SearchProvider}; {@code joinConfig} must be non-null.<br>
     * <b>Postconditions:</b> never null; the returned side's {@code resultStore} is left <b>open</b> - the caller
     * owns destroying it. On any failure opening or awaiting the store, it is destroyed before the exception
     * propagates, so no store leaks.</p>
     *
     * @throws stroom.query.planner.join.JoinLimitExceededException if {@code sideRequest} is a graph (Task C4,
     *                                                                docs/graphdb-stroomql-join-implementation-
     *                                                                plan.md, Phase P4) side whose realised row
     *                                                                count exceeds {@link
     *                                                                JoinConfig#getMaxSideRows} - see
     *                                                                {@link #checkGraphSideRowCap}.
     */
    private OpenedSide openSide(final SearchRequest sideRequest, final JoinConfig joinConfig) {
        final DocRef dataSourceRef = sideRequest.getQuery().getDataSource();
        final SearchProvider searchProvider = searchProviderRegistryProvider.get()
                .getSearchProvider(dataSourceRef)
                .orElseThrow(() -> new IllegalStateException(
                        "No SearchProvider registered for join side datasource type '"
                        + dataSourceRef.getType() + "'"));

        final ResultStore resultStore = searchProvider.createResultStore(sideRequest);
        try {
            resultStore.awaitCompletion();
            final DataStore dataStore = resultStore.getData(SearchRequestFactory.TABLE_COMPONENT_ID);
            checkGraphSideRowCap(dataSourceRef, dataStore, joinConfig);
            return new OpenedSide(resultStore, dataStore, dataStore.getColumns());
        } catch (final InterruptedException e) {
            resultStore.destroy();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while opening a join side", e);
        } catch (final RuntimeException e) {
            resultStore.destroy();
            throw e;
        }
    }

    /**
     * Task C4: surfaces a clear, in-band error - via
     * the same {@link JoinLimitExceededException} the build-side/output guardrails already throw, captured by
     * {@link #createResultStore}'s {@code ResultStore.addError} handling - when a graph join side's realised row
     * count exceeds {@link JoinConfig#getMaxSideRows}.
     *
     * <p>Reuses that existing cap rather than introducing a new configuration property (per the design doc's
     * risk-mitigation note: "reuse the existing join guardrails"), but applies it differently: {@code
     * maxSideRows} otherwise only ever bounds whichever side {@link #joinAndFeedViaStreamingHashJoin} chooses as
     * the <i>build</i> side - an ordinary Scan-typed <i>probe</i> side is deliberately left uncapped (an
     * arbitrarily large event stream must keep streaming). A graph side gets no such exemption regardless of
     * which role it ends up playing: {@code GraphSearchProvider.createResultStore} already realised its entire
     * traversal result in memory - as a plain {@code List<Val[]>}, with no row cap of its own, unlike an Index/
     * Searchable side whose own result-store settings already bound its ingest - by the time this method runs, so
     * the memory cost has already been paid regardless of whether the row is ever streamed onward.</p>
     *
     * @param dataSourceRef the side's resolved datasource - only its {@code type} is inspected.
     * @param dataStore     the side's just-realised {@link DataStore}; {@link DataStore#getSize} is an O(1)
     *                      signal at this point (the sub-search has already completed).
     * @param joinConfig    supplies the current {@code stroom.query.join.maxSideRows} guardrail.
     */
    private static void checkGraphSideRowCap(
            final DocRef dataSourceRef, final DataStore dataStore, final JoinConfig joinConfig) {
        if (!GRAPH_DATA_SOURCE_TYPE.equals(dataSourceRef.getType())) {
            return;
        }
        final long size = dataStore.getSize();
        final long maxSideRows = joinConfig.getMaxSideRows();
        if (size > maxSideRows) {
            throw JoinLimitExceededException.forRowCount("graph join side row count", maxSideRows, size);
        }
    }

    /**
     * Reads every row of {@code dataStore} (all groups, unbounded, no time filter), handing each row's
     * {@code Val[]} to {@code rowConsumer} as it is read - the single "stream a completed side's rows" primitive
     * both {@link #realiseIntoBuildSide} and the probe loops share. Because it never
     * accumulates internally, a caller that also does not accumulate (the probe side) streams in bounded memory.
     *
     * <p><b>Preconditions:</b> all parameters non-null.<br>
     * <b>Postconditions:</b> {@code rowConsumer} has been called once per row in {@code dataStore}.</p>
     */
    private static void fetchRows(final DataStore dataStore, final List<Column> columns,
                                  final Consumer<Val[]> rowConsumer) {
        dataStore.fetch(
                columns,
                OffsetRange.UNBOUNDED,
                OpenGroups.ALL,
                null,
                IdentityItemMapper.INSTANCE,
                item -> rowConsumer.accept(item.toArray()),
                count -> {
                });
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
     * @return an array of length {@code fieldIndex.size}; entry {@code i} is the combined-row position feeding
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

    /** A join side whose sub-search has completed - its {@link DataStore} and columns, with rows not yet read
     * (streamed on demand via {@link #fetchRows}). The {@code resultStore} is owned by the caller of
     * {@link #openSide} and must be destroyed by it. */
    private record OpenedSide(ResultStore resultStore, DataStore dataStore, List<Column> columns) {
    }
}
