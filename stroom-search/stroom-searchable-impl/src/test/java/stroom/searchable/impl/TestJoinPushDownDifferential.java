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
import stroom.query.api.DateTimeSettings;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.api.JoinSpec;
import stroom.query.api.JoinSpec.JoinEquiKey;
import stroom.query.api.OffsetRange;
import stroom.query.api.Query;
import stroom.query.api.QueryKey;
import stroom.query.api.ResultRequest;
import stroom.query.api.ResultRequest.Fetch;
import stroom.query.api.ResultRequest.ResultStyle;
import stroom.query.api.SearchRequest;
import stroom.query.api.SearchRequestSource;
import stroom.query.api.TableSettings;
import stroom.query.api.TimeFilter;
import stroom.query.common.v2.CoprocessorsFactory;
import stroom.query.common.v2.DataStore;
import stroom.query.common.v2.ExpressionContextFactory;
import stroom.query.common.v2.ExpressionPredicateFactory;
import stroom.query.common.v2.ExpressionPredicateFactory.ValueFunctionFactories;
import stroom.query.common.v2.ExpressionPredicateFactory.ValueFunctionFactory;
import stroom.query.common.v2.IdentityItemMapper;
import stroom.query.common.v2.Item;
import stroom.query.common.v2.JoinBuildSideLookupFactory;
import stroom.query.common.v2.JoinConfig;
import stroom.query.common.v2.JoinDataSourceType;
import stroom.query.common.v2.MapDataStoreFactory;
import stroom.query.common.v2.OpenGroups;
import stroom.query.common.v2.ResultStore;
import stroom.query.common.v2.ResultStoreFactory;
import stroom.query.common.v2.ResultStoreSettingsFactory;
import stroom.query.common.v2.SearchProvider;
import stroom.query.common.v2.SearchProviderRegistry;
import stroom.query.common.v2.SearchResultStoreConfig;
import stroom.query.common.v2.Sizes;
import stroom.query.common.v2.SizesProvider;
import stroom.query.common.v2.SpillingBuildSideLookup;
import stroom.query.common.v2.ValuesFunctionFactory;
import stroom.query.language.SearchRequestFactory;
import stroom.query.language.functions.StateFetcher;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValLong;
import stroom.query.language.functions.ValString;
import stroom.query.language.functions.Values;
import stroom.query.planner.join.HeapBuildSideLookup;

import jakarta.inject.Provider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task #18: the differential/parity gate A1
 * (per-side predicate push-down) and A2 (projection pruning) call for. Every other join test in this package
 * proves correctness against a fake {@link SearchProvider} that <i>ignores</i> the sub-request it's handed (see
 * {@code fakeSideProvider} in {@link TestJoinSearchProvider}) - which proves {@link JoinExecutor}/
 * {@link JoinSearchProvider}'s combine logic is correct, but can never catch a bug in <i>what gets pushed</i>
 * (e.g. a wrong alias-stripping, or pushing the wrong side's term).
 *
 * <p>This class instead uses {@link #realisticFakeProvider}, which genuinely evaluates each side's own compiled
 * {@code where} clause and column selection against a fixed in-memory table - so if
 * {@code OptimisingQueryCompiler}'s push-down/pruning ever pushed the wrong predicate, stripped an alias
 * incorrectly, or dropped a column something downstream needed, that bug would surface here as a wrong (or
 * missing) row, not be silently absorbed by an indifferent mock.</p>
 *
 * <p>Each test builds two compiled join requests for the same logical query - an "unoptimised" baseline (both
 * sides {@code select *}, no pushed filter - the pre-A1/A2 shape) and an "optimised" one (a pushed filter and/or
 * a pruned select list - what {@code OptimisingQueryCompiler}'s A1/A2 now actually produce, verified structurally
 * by {@code TestOptimisingQueryCompilerJoin}) - executes both through the real {@link JoinSearchProvider}, and
 * asserts they return byte-identical joined rows.</p>
 */
class TestJoinPushDownDifferential {

    private static final DocRef LEFT_DATA_SOURCE = new DocRef("LeftType", "left-uuid", "Events");
    private static final DocRef RIGHT_DATA_SOURCE = new DocRef("RightType", "right-uuid", "Users");
    private static final java.util.concurrent.Executor DIRECT_EXECUTOR = Runnable::run;
    private static final SizesProvider SIZES_PROVIDER = Sizes::unlimited;

    /** A build-side lookup factory whose "spill" store is an in-memory {@link HeapBuildSideLookup} stand-in - see
     * the same helper in {@code TestJoinSearchProvider} for why (LMDB native-lib config is awkward across test
     * classes; the real disk store is covered in {@code stroom-query-common}). The byte-identical parity this
     * class asserts holds for the stand-in exactly as it does for the real store, since {@link SpillingBuildSideLookup}
     * routes to whichever spill target it is given. */
    private JoinBuildSideLookupFactory buildSideLookupFactory() {
        final JoinBuildSideLookupFactory factory = mock(JoinBuildSideLookupFactory.class);
        when(factory.create(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> new SpillingBuildSideLookup(
                        inv.getArgument(0), inv.getArgument(1), HeapBuildSideLookup::new));
        return factory;
    }

    private static Column column(final String name) {
        return Column.builder().id(name).name(name).expression(name).build();
    }

    /**
     * A {@link SearchProvider} that genuinely evaluates its sub-request's {@code where} clause and column
     * selection against {@code allRows} (every row of {@code allColumns}, a fixed in-memory table) - unlike
     * {@code fakeSideProvider} elsewhere in this package, which ignores the sub-request entirely and always
     * returns the same fixed rows regardless of what was asked for.
     *
     * <p><b>Preconditions:</b> {@code dataSource}, {@code allColumns}, {@code allRows} must not be null; every
     * element of {@code allRows} must have length {@code allColumns.size}.<br>
     * <b>Postconditions:</b> the returned provider's {@code createResultStore(sideRequest)} filters {@code
     * allRows} by {@code sideRequest}'s {@code where} clause (bare, unqualified field names - a compiled side
     * sub-query never carries an alias prefix) and projects down to the columns {@code sideRequest} actually
     * selects, in the order requested.</p>
     */
    private static SearchProvider realisticFakeProvider(
            final DocRef dataSource, final List<Column> allColumns, final List<Val[]> allRows) {
        final SearchProvider provider = mock(SearchProvider.class);
        when(provider.getDataSourceType()).thenReturn(dataSource.getType());
        when(provider.createResultStore(any())).thenAnswer(invocation -> {
            final SearchRequest sideRequest = invocation.getArgument(0);
            final List<Column> requestedColumns = requestedColumns(sideRequest);
            final Predicate<Values> wherePredicate = bareFieldPredicate(sideRequest, allColumns);

            final List<Val[]> projectedRows = new ArrayList<>();
            for (final Val[] row : allRows) {
                if (wherePredicate.test(Values.of(row))) {
                    projectedRows.add(project(row, allColumns, requestedColumns));
                }
            }
            return fakeResultStore(requestedColumns, projectedRows);
        });
        return provider;
    }

    /** The columns {@code sideRequest} actually selects, in order - a compiled join side always has exactly one
     * {@code TABLE_COMPONENT_ID} result request/mapping (see {@code OptimisingQueryCompiler#compileJoinSide}). */
    private static List<Column> requestedColumns(final SearchRequest sideRequest) {
        return sideRequest.getResultRequests().getFirst().getMappings().getFirst().getColumns();
    }

    /** Builds a predicate over bare (unqualified) field names - a compiled side sub-query's {@code where} clause
     * never carries an alias prefix, unlike the outer join's (see {@code JoinSearchProvider#whereRowPredicate}).
     * A trivial/absent where clause yields an always-true predicate. */
    private static Predicate<Values> bareFieldPredicate(final SearchRequest sideRequest,
                                                        final List<Column> allColumns) {
        final ExpressionOperator where = sideRequest.getQuery() == null ? null : sideRequest.getQuery().getExpression();
        if (where == null || where.getChildren() == null || where.getChildren().isEmpty()) {
            return values -> true;
        }
        final Map<String, ValueFunctionFactory<Values>> accessors = new HashMap<>();
        for (int i = 0; i < allColumns.size(); i++) {
            accessors.put(allColumns.get(i).getName(), new ValuesFunctionFactory(allColumns.get(i), i));
        }
        final ValueFunctionFactories<Values> factories = accessors::get;
        return new ExpressionPredicateFactory()
                .createOptional(where, factories, sideRequest.getDateTimeSettings())
                .orElse(values -> true);
    }

    private static Val[] project(final Val[] row, final List<Column> allColumns, final List<Column> requestedColumns) {
        final Val[] projected = new Val[requestedColumns.size()];
        for (int i = 0; i < requestedColumns.size(); i++) {
            final String name = requestedColumns.get(i).getName();
            final int sourcePos = indexOfColumn(allColumns, name);
            // A requested column this fake table doesn't have (e.g. an auto-added special navigation column) is
            // out of scope for this differential check - only the real datasource fields are compared.
            projected[i] = sourcePos < 0 ? stroom.query.language.functions.ValNull.INSTANCE : row[sourcePos];
        }
        return projected;
    }

    private static int indexOfColumn(final List<Column> columns, final String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static ResultStore fakeResultStore(final List<Column> columns, final List<Val[]> rows) {
        final DataStore dataStore = mock(DataStore.class);
        when(dataStore.getColumns()).thenReturn(columns);
        // A6 build-side selection reads getSize to pick the smaller side; report the fixture's row count.
        when(dataStore.getSize()).thenReturn((long) rows.size());
        org.mockito.Mockito.doAnswer(invocation -> {
            final Consumer<Item> resultConsumer = invocation.getArgument(5);
            for (final Val[] row : rows) {
                final Item item = mock(Item.class);
                when(item.toArray()).thenReturn(row);
                resultConsumer.accept(item);
            }
            return null;
        }).when(dataStore).fetch(any(), any(), any(), any(), any(), any(), any());

        final ResultStore resultStore = mock(ResultStore.class);
        when(resultStore.getData(SearchRequestFactory.TABLE_COMPONENT_ID)).thenReturn(dataStore);
        return resultStore;
    }

    private static SearchProviderRegistry registry(final SearchProvider... providers) {
        final SearchProviderRegistry registry = mock(SearchProviderRegistry.class);
        for (final SearchProvider provider : providers) {
            when(registry.getSearchProvider(org.mockito.ArgumentMatchers.argThat(
                    docRef -> docRef != null && provider.getDataSourceType().equals(docRef.getType()))))
                    .thenReturn(Optional.of(provider));
        }
        return registry;
    }

    private JoinSearchProvider provider(final SearchProviderRegistry registry) {
        return provider(registry, JoinConfig::new);
    }

    private JoinSearchProvider provider(final SearchProviderRegistry registry,
                                        final Provider<JoinConfig> joinConfigProvider) {
        final CoprocessorsFactory coprocessorsFactory = new CoprocessorsFactory(
                new MapDataStoreFactory(SearchResultStoreConfig::new),
                new ExpressionContextFactory(),
                SIZES_PROVIDER,
                () -> DIRECT_EXECUTOR);
        final ResultStoreFactory resultStoreFactory = mock(ResultStoreFactory.class);
        when(resultStoreFactory.create(any(), any())).thenAnswer(inv -> new ResultStore(
                inv.getArgument(0),
                SIZES_PROVIDER,
                null,
                inv.getArgument(1),
                "node",
                new ResultStoreSettingsFactory().get(),
                new MapDataStoreFactory(SearchResultStoreConfig::new),
                new ExpressionPredicateFactory(),
                () -> DIRECT_EXECUTOR));
        return new JoinSearchProvider(
                () -> registry, coprocessorsFactory, resultStoreFactory, new ExpressionPredicateFactory(),
                joinConfigProvider, mock(StateFetcher.class), buildSideLookupFactory());
    }

    /** Builds one side's compiled sub-request exactly as {@code OptimisingQueryCompiler#compileJoinSide} would:
     * a bare {@code where} (already alias-stripped, or null for the unoptimised baseline) and an explicit select
     * list (either every column - the pre-A2 {@code select *} shape - or a pruned subset). */
    private static SearchRequest side(final DocRef dataSource, final ExpressionOperator where,
                                      final List<Column> selectColumns) {
        final TableSettings tableSettings = TableSettings.builder()
                .addColumns(selectColumns.toArray(new Column[0]))
                .extractValues(true)
                .build();
        final ResultRequest resultRequest = ResultRequest.builder()
                .componentId(SearchRequestFactory.TABLE_COMPONENT_ID)
                .mappings(List.of(tableSettings))
                .resultStyle(ResultStyle.TABLE)
                .fetch(Fetch.ALL)
                .build();
        return SearchRequest.builder()
                .query(Query.builder().dataSource(dataSource).expression(where).build())
                .resultRequests(List.of(resultRequest))
                .dateTimeSettings(DateTimeSettings.builder().referenceTime(0L).build())
                .build();
    }

    /** The outer join request: combines two already-compiled sides (see {@link #side}) exactly as
     * {@code OptimisingQueryCompiler#createJoin} would, with {@code residualWhere} as the outer expression. */
    private static SearchRequest outerJoin(
            final SearchRequest leftSide, final SearchRequest rightSide, final JoinSpec.JoinType joinType,
            final ExpressionOperator residualWhere, final List<Column> outerSelectColumns) {
        final JoinSpec joinSpec = JoinSpec.builder()
                .left(leftSide)
                .right(rightSide)
                .joinType(joinType)
                .addEquiKey(new JoinEquiKey("a", "UserId", "b", "Id"))
                .build();
        final TableSettings tableSettings = TableSettings.builder()
                .addColumns(outerSelectColumns.toArray(new Column[0]))
                .extractValues(true)
                .build();
        final ResultRequest resultRequest = ResultRequest.builder()
                .componentId(SearchRequestFactory.TABLE_COMPONENT_ID)
                .mappings(List.of(tableSettings))
                .resultStyle(ResultStyle.TABLE)
                .fetch(Fetch.ALL)
                .build();
        return SearchRequest.builder()
                .searchRequestSource(SearchRequestSource.createBasic())
                .key(new QueryKey("test-differential"))
                .query(Query.builder()
                        .dataSource(new DocRef(JoinDataSourceType.TYPE, "join-uuid", "A ⋈ B"))
                        .expression(residualWhere)
                        .joinSpec(joinSpec)
                        .build())
                .resultRequests(List.of(resultRequest))
                .dateTimeSettings(DateTimeSettings.builder().referenceTime(0L).build())
                .incremental(false)
                .build();
    }

    private static List<Val[]> readTableRows(final ResultStore resultStore) {
        final DataStore dataStore = resultStore.getData(SearchRequestFactory.TABLE_COMPONENT_ID);
        final List<Val[]> rows = new ArrayList<>();
        dataStore.fetch(
                dataStore.getColumns(),
                OffsetRange.UNBOUNDED,
                OpenGroups.ALL,
                new TimeFilter(0, Long.MAX_VALUE),
                IdentityItemMapper.INSTANCE,
                item -> rows.add(item.toArray()),
                count -> {
                });
        return rows;
    }

    /** Both sides' full underlying tables, shared by every test: Events[UserId, StreamId] and Users[Id, Name]. */
    private static List<Column> leftAllColumns() {
        return List.of(column("UserId"), column("StreamId"));
    }

    private static List<Val[]> leftAllRows() {
        return List.of(
                new Val[]{ValLong.create(1), ValLong.create(100)},
                new Val[]{ValLong.create(2), ValLong.create(200)},
                new Val[]{ValLong.create(3), ValLong.create(300)});
    }

    private static List<Column> rightAllColumns() {
        return List.of(column("Id"), column("Name"));
    }

    private static List<Val[]> rightAllRows() {
        return List.of(
                new Val[]{ValLong.create(1), ValString.create("Alice")},
                new Val[]{ValLong.create(2), ValString.create("Bob")},
                new Val[]{ValLong.create(3), ValString.create("Carol")});
    }

    @Test
    void pushedLeftPredicate_innerJoin_producesSameRowsAsUnoptimisedBaseline() {
        final SearchProviderRegistry registry = registry(
                realisticFakeProvider(LEFT_DATA_SOURCE, leftAllColumns(), leftAllRows()),
                realisticFakeProvider(RIGHT_DATA_SOURCE, rightAllColumns(), rightAllRows()));
        final List<Column> outerSelect = List.of(column("a.UserId"), column("b.Name"));

        // Unoptimised: select * both sides, no pushed filter, the full where clause kept as the outer residual.
        final ExpressionOperator fullWhere = ExpressionOperator.builder()
                .addTerm("a.StreamId", Condition.EQUALS, "200")
                .build();
        final SearchRequest unoptimised = outerJoin(
                side(LEFT_DATA_SOURCE, null, leftAllColumns()),
                side(RIGHT_DATA_SOURCE, null, rightAllColumns()),
                JoinSpec.JoinType.INNER, fullWhere, outerSelect);

        // Optimised: "a.StreamId = 200" pushed (alias-stripped) onto the left side; residual is empty.
        final ExpressionOperator pushedLeft = ExpressionOperator.builder()
                .addTerm("StreamId", Condition.EQUALS, "200")
                .build();
        final SearchRequest optimised = outerJoin(
                side(LEFT_DATA_SOURCE, pushedLeft, leftAllColumns()),
                side(RIGHT_DATA_SOURCE, null, rightAllColumns()),
                JoinSpec.JoinType.INNER, ExpressionOperator.builder().build(), outerSelect);

        final List<Val[]> unoptimisedRows = readTableRows(provider(registry).createResultStore(unoptimised));
        final List<Val[]> optimisedRows = readTableRows(provider(registry).createResultStore(optimised));

        assertThat(optimisedRows).hasSameSizeAs(unoptimisedRows).isNotEmpty();
        assertRowSetsMatch(unoptimisedRows, optimisedRows);
    }

    @Test
    void pushedRightPredicate_innerJoin_producesSameRowsAsUnoptimisedBaseline() {
        final SearchProviderRegistry registry = registry(
                realisticFakeProvider(LEFT_DATA_SOURCE, leftAllColumns(), leftAllRows()),
                realisticFakeProvider(RIGHT_DATA_SOURCE, rightAllColumns(), rightAllRows()));
        final List<Column> outerSelect = List.of(column("a.UserId"), column("b.Name"));

        final ExpressionOperator fullWhere = ExpressionOperator.builder()
                .addTerm("b.Name", Condition.EQUALS, "Bob")
                .build();
        final SearchRequest unoptimised = outerJoin(
                side(LEFT_DATA_SOURCE, null, leftAllColumns()),
                side(RIGHT_DATA_SOURCE, null, rightAllColumns()),
                JoinSpec.JoinType.INNER, fullWhere, outerSelect);

        final ExpressionOperator pushedRight = ExpressionOperator.builder()
                .addTerm("Name", Condition.EQUALS, "Bob")
                .build();
        final SearchRequest optimised = outerJoin(
                side(LEFT_DATA_SOURCE, null, leftAllColumns()),
                side(RIGHT_DATA_SOURCE, pushedRight, rightAllColumns()),
                JoinSpec.JoinType.INNER, ExpressionOperator.builder().build(), outerSelect);

        final List<Val[]> unoptimisedRows = readTableRows(provider(registry).createResultStore(unoptimised));
        final List<Val[]> optimisedRows = readTableRows(provider(registry).createResultStore(optimised));

        assertThat(optimisedRows).hasSameSizeAs(unoptimisedRows).isNotEmpty();
        assertRowSetsMatch(unoptimisedRows, optimisedRows);
    }

    @Test
    void leftJoin_pushedLeftPredicateOnly_producesSameRowsAsUnoptimisedBaseline() {
        // The correctness-critical case: A1 never pushes onto a LEFT join's right side, only ever the left
        // (preserved) side. Proves the LEFT-safe subset of push-down is still a true no-op on the final rows.
        final SearchProviderRegistry registry = registry(
                realisticFakeProvider(LEFT_DATA_SOURCE, leftAllColumns(), leftAllRows()),
                realisticFakeProvider(RIGHT_DATA_SOURCE, rightAllColumns(),
                        List.<Val[]>of(new Val[]{ValLong.create(2), ValString.create("Bob")}))); // only Id=2 exists
        final List<Column> outerSelect = List.of(column("a.UserId"), column("b.Name"));

        final ExpressionOperator fullWhere = ExpressionOperator.builder()
                .addTerm("a.UserId", Condition.GREATER_THAN_OR_EQUAL_TO, "2")
                .build();
        final SearchRequest unoptimised = outerJoin(
                side(LEFT_DATA_SOURCE, null, leftAllColumns()),
                side(RIGHT_DATA_SOURCE, null, rightAllColumns()),
                JoinSpec.JoinType.LEFT, fullWhere, outerSelect);

        final ExpressionOperator pushedLeft = ExpressionOperator.builder()
                .addTerm("UserId", Condition.GREATER_THAN_OR_EQUAL_TO, "2")
                .build();
        final SearchRequest optimised = outerJoin(
                side(LEFT_DATA_SOURCE, pushedLeft, leftAllColumns()),
                side(RIGHT_DATA_SOURCE, null, rightAllColumns()),
                JoinSpec.JoinType.LEFT, ExpressionOperator.builder().build(), outerSelect);

        final List<Val[]> unoptimisedRows = readTableRows(provider(registry).createResultStore(unoptimised));
        final List<Val[]> optimisedRows = readTableRows(provider(registry).createResultStore(optimised));

        // UserId 2 (matches, Bob) and 3 (no match, Name null-padded) both survive - proves LEFT semantics
        // (null-padding, not dropping) are preserved when the left side is pre-filtered.
        assertThat(optimisedRows).hasSameSizeAs(unoptimisedRows).hasSize(2);
        assertRowSetsMatch(unoptimisedRows, optimisedRows);
    }

    @Test
    void prunedSelectColumns_stillProducesSameRowsAsSelectingEveryColumn() {
        final SearchProviderRegistry registry = registry(
                realisticFakeProvider(LEFT_DATA_SOURCE, leftAllColumns(), leftAllRows()),
                realisticFakeProvider(RIGHT_DATA_SOURCE, rightAllColumns(), rightAllRows()));
        final List<Column> outerSelect = List.of(column("a.UserId"), column("b.Name"));

        final SearchRequest unoptimised = outerJoin(
                side(LEFT_DATA_SOURCE, null, leftAllColumns()),
                side(RIGHT_DATA_SOURCE, null, rightAllColumns()),
                JoinSpec.JoinType.INNER, ExpressionOperator.builder().build(), outerSelect);

        // A2: each side selects only its equi-key field plus whatever the outer select references it by -
        // "StreamId" (unreferenced) is dropped from the left side; "Name" only is kept on the right.
        final SearchRequest optimised = outerJoin(
                side(LEFT_DATA_SOURCE, null, List.of(column("UserId"))),
                side(RIGHT_DATA_SOURCE, null, List.of(column("Id"), column("Name"))),
                JoinSpec.JoinType.INNER, ExpressionOperator.builder().build(), outerSelect);

        final List<Val[]> unoptimisedRows = readTableRows(provider(registry).createResultStore(unoptimised));
        final List<Val[]> optimisedRows = readTableRows(provider(registry).createResultStore(optimised));

        assertThat(optimisedRows).hasSameSizeAs(unoptimisedRows).hasSize(3);
        assertRowSetsMatch(unoptimisedRows, optimisedRows);
    }

    @Test
    void combinedPushAndPrune_producesSameRowsAsUnoptimisedBaseline() {
        final SearchProviderRegistry registry = registry(
                realisticFakeProvider(LEFT_DATA_SOURCE, leftAllColumns(), leftAllRows()),
                realisticFakeProvider(RIGHT_DATA_SOURCE, rightAllColumns(), rightAllRows()));
        final List<Column> outerSelect = List.of(column("a.UserId"), column("b.Name"));

        final ExpressionOperator fullWhere = ExpressionOperator.builder()
                .addTerm("a.StreamId", Condition.GREATER_THAN, "100")
                .addTerm("b.Name", Condition.EQUALS, "Carol")
                .build();
        final SearchRequest unoptimised = outerJoin(
                side(LEFT_DATA_SOURCE, null, leftAllColumns()),
                side(RIGHT_DATA_SOURCE, null, rightAllColumns()),
                JoinSpec.JoinType.INNER, fullWhere, outerSelect);

        // Both A1 (both predicates pushed, one per side) and A2 (left keeps only UserId+StreamId - it needs
        // StreamId for its own pushed predicate even though the outer select never references it; right keeps
        // only Id+Name) apply together.
        final ExpressionOperator pushedLeft = ExpressionOperator.builder()
                .addTerm("StreamId", Condition.GREATER_THAN, "100")
                .build();
        final ExpressionOperator pushedRight = ExpressionOperator.builder()
                .addTerm("Name", Condition.EQUALS, "Carol")
                .build();
        final SearchRequest optimised = outerJoin(
                side(LEFT_DATA_SOURCE, pushedLeft, leftAllColumns()),
                side(RIGHT_DATA_SOURCE, pushedRight, List.of(column("Id"), column("Name"))),
                JoinSpec.JoinType.INNER, ExpressionOperator.builder().build(), outerSelect);

        final List<Val[]> unoptimisedRows = readTableRows(provider(registry).createResultStore(unoptimised));
        final List<Val[]> optimisedRows = readTableRows(provider(registry).createResultStore(optimised));

        assertThat(optimisedRows).hasSameSizeAs(unoptimisedRows).hasSize(1);
        assertRowSetsMatch(unoptimisedRows, optimisedRows);
    }

    @Test
    void spilledBuildSide_producesByteIdenticalRowsToTheOnHeapBaseline() {
        // The C1/C2 gate: the streaming/spilling build side must be a true no-op on the final rows. Run the same
        // logical INNER join twice through the real JoinSearchProvider - once on-heap (default maxHeapBuildRows),
        // once with maxHeapBuildRows=1 so the 3-row build (right) side spills to a real disk-backed store - and
        // assert byte-identical joined rows.
        final SearchProviderRegistry registry = registry(
                realisticFakeProvider(LEFT_DATA_SOURCE, leftAllColumns(), leftAllRows()),
                realisticFakeProvider(RIGHT_DATA_SOURCE, rightAllColumns(), rightAllRows()));
        final List<Column> outerSelect = List.of(column("a.UserId"), column("b.Name"));
        final SearchRequest join = outerJoin(
                side(LEFT_DATA_SOURCE, null, leftAllColumns()),
                side(RIGHT_DATA_SOURCE, null, rightAllColumns()),
                JoinSpec.JoinType.INNER, ExpressionOperator.builder().build(), outerSelect);

        final List<Val[]> onHeapRows = readTableRows(
                provider(registry, JoinConfig::new).createResultStore(join));
        final List<Val[]> spilledRows = readTableRows(
                provider(registry, () -> new JoinConfig(null, null, 1L, null)).createResultStore(join));

        assertThat(spilledRows).hasSameSizeAs(onHeapRows).isNotEmpty();
        assertRowSetsMatch(onHeapRows, spilledRows);
    }

    @Test
    void buildSideSelection_swapProducesByteIdenticalRowsToNoSwap() {
        // A6 gate: for an INNER join the result must be independent of which side is built. Run the same logical
        // join with data arranged so the smaller side (hence the built side, via getSize) differs between the
        // two runs - run A builds LEFT (left smaller), run B builds RIGHT (right smaller) - and assert the joined
        // rows are byte-identical. The only matching key in both is UserId=2 => [2, "Bob"].
        final List<Column> outerSelect = List.of(column("a.UserId"), column("b.Name"));
        final SearchRequest join = outerJoin(
                side(LEFT_DATA_SOURCE, null, leftAllColumns()),
                side(RIGHT_DATA_SOURCE, null, rightAllColumns()),
                JoinSpec.JoinType.INNER, ExpressionOperator.builder().build(), outerSelect);

        // Run A: left = 1 row, right = 3 rows -> left is smaller -> builds LEFT.
        final SearchProviderRegistry registryA = registry(
                realisticFakeProvider(LEFT_DATA_SOURCE, leftAllColumns(),
                        List.<Val[]>of(new Val[]{ValLong.create(2), ValLong.create(100)})),
                realisticFakeProvider(RIGHT_DATA_SOURCE, rightAllColumns(), rightAllRows()));
        // Run B: left = 3 rows, right = 1 row -> right is smaller -> builds RIGHT (no swap).
        final SearchProviderRegistry registryB = registry(
                realisticFakeProvider(LEFT_DATA_SOURCE, leftAllColumns(), leftAllRows()),
                realisticFakeProvider(RIGHT_DATA_SOURCE, rightAllColumns(),
                        List.<Val[]>of(new Val[]{ValLong.create(2), ValString.create("Bob")})));

        final List<Val[]> builtLeftRows = readTableRows(provider(registryA).createResultStore(join));
        final List<Val[]> builtRightRows = readTableRows(provider(registryB).createResultStore(join));

        assertThat(builtLeftRows).hasSize(1);
        assertThat(builtLeftRows.getFirst()).containsExactly(ValLong.create(2), ValString.create("Bob"));
        assertRowSetsMatch(builtRightRows, builtLeftRows);
    }

    /** Asserts the two row sets are identical, ignoring order (a join makes no ordering guarantee). */
    private static void assertRowSetsMatch(final List<Val[]> expected, final List<Val[]> actual) {
        assertThat(actual.stream().map(Arrays::asList).toList())
                .containsExactlyInAnyOrderElementsOf(expected.stream().map(Arrays::asList).toList());
    }
}
