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
import stroom.query.language.SearchRequestFactory;
import stroom.query.language.functions.FieldIndex;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValLong;
import stroom.query.language.functions.ValNull;
import stroom.query.language.functions.ValString;
import stroom.query.planner.join.HeapBuildSideLookup;

import jakarta.inject.Provider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 6.1d/6.1x: the sentinel-type registration route, the structural rejections, and - the point of "closing
 * the gap" - a real end-to-end test proving a where-less two-source join returns actual joined rows through a
 * real outer {@code Coprocessors}/{@code ResultStore} (only the two join sides are faked). Plus direct unit tests
 * of the {@code buildFieldMapping}/{@code assembleRow} helpers, the novel positional logic.
 */
class TestJoinSearchProvider {

    private static final DocRef LEFT_DATA_SOURCE = new DocRef("LeftType", "left-uuid", "Left");
    private static final DocRef RIGHT_DATA_SOURCE = new DocRef("RightType", "right-uuid", "Right");
    private static final Executor DIRECT_EXECUTOR = Runnable::run;
    private static final SizesProvider SIZES_PROVIDER = Sizes::unlimited;

    /**
     * A build-side lookup factory whose "spill" store is an in-memory {@link HeapBuildSideLookup} stand-in rather
     * than the real disk-backed one. This keeps {@code stroom-searchable-impl} tests free of the LMDB native
     * library (whose one-per-JVM configuration is awkward across test classes) while still exercising the real
     * {@link SpillingBuildSideLookup} threshold/drain/routing that {@link JoinSearchProvider} relies on. The real
     * disk-backed spill store ({@code LmdbJoinBuildStore}) is covered end-to-end in {@code stroom-query-common}'s
     * {@code TestLmdbJoinBuildStore}/{@code TestSpillingBuildSideLookup}.
     */
    private JoinBuildSideLookupFactory buildSideLookupFactory() {
        final JoinBuildSideLookupFactory factory = mock(JoinBuildSideLookupFactory.class);
        when(factory.create(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> new SpillingBuildSideLookup(
                        inv.getArgument(0), inv.getArgument(1), HeapBuildSideLookup::new));
        return factory;
    }

    /**
     * The default guardrails (see {@link JoinConfig}'s own defaults) - large enough not to trip any existing
     * small-fixture test.
     */
    private static final Provider<JoinConfig> DEFAULT_JOIN_CONFIG_PROVIDER = JoinConfig::new;

    private JoinSearchProvider provider(final SearchProviderRegistry registry) {
        return provider(registry, DEFAULT_JOIN_CONFIG_PROVIDER);
    }

    private JoinSearchProvider provider(final SearchProviderRegistry registry,
                                        final Provider<JoinConfig> joinConfigProvider) {
        // None of the fixtures using this overload involve a Plan B side, so detectPlanBLookupSide never fires
        // and this mock is never actually invoked - see provider(registry, joinConfigProvider, stateFetcher) for
        // the B1 (broadcast lookup) tests, which supply a real one.
        return provider(registry, joinConfigProvider, mock(stroom.query.language.functions.StateFetcher.class));
    }

    /**
     * A real {@link CoprocessorsFactory} backed by the lightweight {@link MapDataStoreFactory} (no LMDB/temp-dir),
     * plus a {@link ResultStoreFactory} mock that wraps whatever coprocessors it's handed in a genuine
     * {@link ResultStore} - so {@code createResultStore}'s full path (real FieldIndex, real accept, real readback)
     * is exercised without standing up NodeInfo/SecurityContext/etc.
     */
    private JoinSearchProvider provider(final SearchProviderRegistry registry,
                                        final Provider<JoinConfig> joinConfigProvider,
                                        final stroom.query.language.functions.StateFetcher stateFetcher) {
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
                joinConfigProvider, stateFetcher, buildSideLookupFactory());
    }

    private JoinSearchProvider providerWithMockDeps(final SearchProviderRegistry registry) {
        return new JoinSearchProvider(
                () -> registry, mock(CoprocessorsFactory.class), mock(ResultStoreFactory.class),
                new ExpressionPredicateFactory(), DEFAULT_JOIN_CONFIG_PROVIDER,
                mock(stroom.query.language.functions.StateFetcher.class),
                mock(JoinBuildSideLookupFactory.class));
    }

    private SearchProviderRegistry registry(final SearchProvider... providers) {
        final SearchProviderRegistry registry = mock(SearchProviderRegistry.class);
        for (final SearchProvider provider : providers) {
            when(registry.getSearchProvider(argThatMatchesType(provider.getDataSourceType())))
                    .thenReturn(java.util.Optional.of(provider));
        }
        return registry;
    }

    private static DocRef argThatMatchesType(final String type) {
        return org.mockito.ArgumentMatchers.argThat(docRef -> docRef != null && type.equals(docRef.getType()));
    }

    // ------------------------------------------------------------------------------------------------------
    // Structural / registration
    // ------------------------------------------------------------------------------------------------------

    @Test
    void getDataSourceType_isTheSentinelType() {
        assertThat(providerWithMockDeps(mock(SearchProviderRegistry.class)).getDataSourceType())
                .isEqualTo(JoinDataSourceType.TYPE);
    }

    @Test
    void searchProviderRegistry_resolvesItForTheSentinelType_noRegistryChangeNeeded() {
        final JoinSearchProvider joinSearchProvider = providerWithMockDeps(mock(SearchProviderRegistry.class));
        final SearchProviderRegistryImpl realRegistry = new SearchProviderRegistryImpl(
                mock(Executor.class),
                mock(stroom.task.api.TaskManager.class),
                mock(stroom.task.api.TaskContextFactory.class),
                mock(stroom.ui.config.shared.UiConfig.class),
                mock(CoprocessorsFactory.class),
                mock(ResultStoreFactory.class),
                mock(stroom.security.api.SecurityContext.class),
                java.util.Set.of(joinSearchProvider),
                java.util.Map.of());

        assertThat(realRegistry.getSearchProvider(new DocRef(JoinDataSourceType.TYPE, "u", "A ⋈ B")))
                .contains(joinSearchProvider);
    }

    @Test
    void missingJoinSpec_rejectedClearly() {
        final SearchRequest requestWithNoJoinSpec = SearchRequest.builder()
                .query(Query.builder().dataSource(new DocRef(JoinDataSourceType.TYPE, "u", "n")).build())
                .build();

        assertThatThrownBy(() -> providerWithMockDeps(mock(SearchProviderRegistry.class))
                .createResultStore(requestWithNoJoinSpec))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------------------
    // End-to-end: real outer coprocessors, faked sides, real joined rows back
    // ------------------------------------------------------------------------------------------------------

    @Test
    void innerJoin_returnsRealJoinedRows_throughRealCoprocessors() {
        // Left: [UserId] rows 1,2. Right: [Id, Name] row (2, "Bob"). INNER join on a.UserId = b.Id.
        final SearchProvider leftProvider = fakeSideProvider(
                LEFT_DATA_SOURCE, List.of("UserId"),
                List.of(new Val[]{ValLong.create(1)}, new Val[]{ValLong.create(2)}));
        final SearchProvider rightProvider = fakeSideProvider(
                RIGHT_DATA_SOURCE, List.of("Id", "Name"),
                List.<Val[]>of(new Val[]{ValLong.create(2), ValString.create("Bob")}));

        final ResultStore resultStore = provider(registry(leftProvider, rightProvider))
                .createResultStore(outerRequest(ExpressionOperator.builder().build()));

        final List<Val[]> rows = readTableRows(resultStore);
        assertThat(rows).hasSize(1);
        // Outer select columns are [a.UserId, b.Name] - see outerRequest(). The one matching row is UserId=2.
        assertThat(rows.getFirst()[0]).isEqualTo(ValLong.create(2));
        assertThat(rows.getFirst()[1]).isEqualTo(ValString.create("Bob"));
    }

    @Test
    void whereClauseAcrossAJoin_filtersCombinedRowsByEquality() {
        // Left UserId 1,2,3; right Id->Name for all three. INNER join -> 3 combined rows. where b.Name = 'Bob'.
        final SearchProvider leftProvider = fakeSideProvider(
                LEFT_DATA_SOURCE, List.of("UserId"),
                List.of(new Val[]{ValLong.create(1)}, new Val[]{ValLong.create(2)}, new Val[]{ValLong.create(3)}));
        final SearchProvider rightProvider = fakeSideProvider(
                RIGHT_DATA_SOURCE, List.of("Id", "Name"),
                List.of(new Val[]{ValLong.create(1), ValString.create("Alice")},
                        new Val[]{ValLong.create(2), ValString.create("Bob")},
                        new Val[]{ValLong.create(3), ValString.create("Carol")}));

        final ExpressionOperator where = ExpressionOperator.builder()
                .addTerm("b.Name", Condition.EQUALS, "Bob")
                .build();
        final ResultStore resultStore = provider(registry(leftProvider, rightProvider))
                .createResultStore(outerRequest(where));

        final List<Val[]> rows = readTableRows(resultStore);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()[0]).isEqualTo(ValLong.create(2));
        assertThat(rows.getFirst()[1]).isEqualTo(ValString.create("Bob"));
    }

    @Test
    void whereClauseAcrossAJoin_numericComparisonUsesNumericSemantics() {
        // UserIds 2 and 10. `a.UserId >= 3` must keep 10 (numeric), not drop it (a string compare of "10" vs "3"
        // would exclude it). Proves the join where-predicate compares numerically, not lexicographically.
        final SearchProvider leftProvider = fakeSideProvider(
                LEFT_DATA_SOURCE, List.of("UserId"),
                List.of(new Val[]{ValLong.create(2)}, new Val[]{ValLong.create(10)}));
        final SearchProvider rightProvider = fakeSideProvider(
                RIGHT_DATA_SOURCE, List.of("Id", "Name"),
                List.of(new Val[]{ValLong.create(2), ValString.create("Bob")},
                        new Val[]{ValLong.create(10), ValString.create("Zoe")}));

        final ExpressionOperator where = ExpressionOperator.builder()
                .addTerm("a.UserId", Condition.GREATER_THAN_OR_EQUAL_TO, "3")
                .build();
        final ResultStore resultStore = provider(registry(leftProvider, rightProvider))
                .createResultStore(outerRequest(where));

        final List<Val[]> rows = readTableRows(resultStore);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()[0]).isEqualTo(ValLong.create(10));
        assertThat(rows.getFirst()[1]).isEqualTo(ValString.create("Zoe"));
    }

    @Test
    void leftJoin_padsUnmatchedLeftRows_endToEnd() {
        // Left UserId 1,2; right only has Id=2. A LEFT join must keep UserId=1 with a null b.Name, not drop it.
        final SearchProvider leftProvider = fakeSideProvider(
                LEFT_DATA_SOURCE, List.of("UserId"),
                List.of(new Val[]{ValLong.create(1)}, new Val[]{ValLong.create(2)}));
        final SearchProvider rightProvider = fakeSideProvider(
                RIGHT_DATA_SOURCE, List.of("Id", "Name"),
                List.<Val[]>of(new Val[]{ValLong.create(2), ValString.create("Bob")}));

        final ResultStore resultStore = provider(registry(leftProvider, rightProvider))
                .createResultStore(outerRequest(ExpressionOperator.builder().build(), JoinSpec.JoinType.LEFT));

        final List<Val[]> rows = readTableRows(resultStore);
        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(row -> {
            assertThat(row[0]).isEqualTo(ValLong.create(1));
            assertThat(row[1]).isEqualTo(ValNull.INSTANCE);
        });
        assertThat(rows).anySatisfy(row -> {
            assertThat(row[0]).isEqualTo(ValLong.create(2));
            assertThat(row[1]).isEqualTo(ValString.create("Bob"));
        });
    }

    // ------------------------------------------------------------------------------------------------------
    // Memory guardrails (see docs/join-scalability-implementation-plan.md, decision D1)
    // ------------------------------------------------------------------------------------------------------

    @Test
    void maxSideRows_underTheCap_isUnaffected() {
        final SearchProvider leftProvider = fakeSideProvider(
                LEFT_DATA_SOURCE, List.of("UserId"),
                List.of(new Val[]{ValLong.create(1)}, new Val[]{ValLong.create(2)}));
        final SearchProvider rightProvider = fakeSideProvider(
                RIGHT_DATA_SOURCE, List.of("Id", "Name"),
                List.<Val[]>of(new Val[]{ValLong.create(2), ValString.create("Bob")}));

        final ResultStore resultStore =
                provider(registry(leftProvider, rightProvider), () -> new JoinConfig(2L, null, null, null))
                        .createResultStore(outerRequest(ExpressionOperator.builder().build()));

        assertThat(resultStore.getErrors()).isEmpty();
        assertThat(readTableRows(resultStore)).hasSize(1);
    }

    @Test
    void maxSideRows_exceeded_reportsAClearErrorOnTheResultStore_andRealisesNoOutput() {
        // maxSideRows caps the build (right) side. The right side has 2 rows but the configured cap only allows 1
        // - realising it must abort, and the whole search must report the error rather than silently truncating
        // the join's input. (The streaming probe/left side is deliberately not row-capped.)
        final SearchProvider leftProvider = fakeSideProvider(
                LEFT_DATA_SOURCE, List.of("UserId"),
                List.<Val[]>of(new Val[]{ValLong.create(2)}));
        final SearchProvider rightProvider = fakeSideProvider(
                RIGHT_DATA_SOURCE, List.of("Id", "Name"),
                List.of(new Val[]{ValLong.create(1), ValString.create("Alice")},
                        new Val[]{ValLong.create(2), ValString.create("Bob")}));

        final ResultStore resultStore =
                provider(registry(leftProvider, rightProvider), () -> new JoinConfig(1L, null, null, null))
                        .createResultStore(outerRequest(ExpressionOperator.builder().build()));

        assertThat(resultStore.getErrors())
                .anySatisfy(error -> assertThat(error.getMessage()).contains("join build side row count"));
        assertThat(readTableRows(resultStore)).isEmpty();
    }

    @Test
    void maxOutputRows_exceeded_reportsAClearErrorOnTheResultStore() {
        // Both sides are small enough individually, but the join's output (2 matching rows) exceeds a cap of 1.
        final SearchProvider leftProvider = fakeSideProvider(
                LEFT_DATA_SOURCE, List.of("UserId"),
                List.of(new Val[]{ValLong.create(1)}, new Val[]{ValLong.create(2)}));
        final SearchProvider rightProvider = fakeSideProvider(
                RIGHT_DATA_SOURCE, List.of("Id", "Name"),
                List.of(new Val[]{ValLong.create(1), ValString.create("Alice")},
                        new Val[]{ValLong.create(2), ValString.create("Bob")}));

        final ResultStore resultStore =
                provider(registry(leftProvider, rightProvider), () -> new JoinConfig(null, 1L, null, null))
                        .createResultStore(outerRequest(ExpressionOperator.builder().build()));

        assertThat(resultStore.getErrors())
                .anySatisfy(error -> assertThat(error.getMessage()).contains("join output row count"));
    }

    @Test
    void buildSideCrossingMaxHeapBuildRows_spillsToDisk_andStillReturnsCorrectRows() {
        // maxHeapBuildRows = 1 forces the 3-row build (right) side to spill to a real disk-backed store after the
        // first row - proving the streaming/spilling path (C1/C2) produces the same joined output as the on-heap
        // path. The left (probe) side streams against the spilled build side.
        final SearchProvider leftProvider = fakeSideProvider(
                LEFT_DATA_SOURCE, List.of("UserId"),
                List.of(new Val[]{ValLong.create(1)},
                        new Val[]{ValLong.create(2)},
                        new Val[]{ValLong.create(3)}));
        final SearchProvider rightProvider = fakeSideProvider(
                RIGHT_DATA_SOURCE, List.of("Id", "Name"),
                List.of(new Val[]{ValLong.create(1), ValString.create("Alice")},
                        new Val[]{ValLong.create(2), ValString.create("Bob")},
                        new Val[]{ValLong.create(3), ValString.create("Carol")}));

        // maxSideRows/maxOutputRows left at defaults (large); only maxHeapBuildRows is lowered to force a spill.
        final ResultStore resultStore =
                provider(registry(leftProvider, rightProvider), () -> new JoinConfig(null, null, 1L, null))
                        .createResultStore(outerRequest(ExpressionOperator.builder().build()));

        assertThat(resultStore.getErrors()).isEmpty();
        final List<Val[]> rows = readTableRows(resultStore);
        // Outer select is [a.UserId, b.Name]; all three users match, so all three enriched rows come back.
        assertThat(rows).hasSize(3);
        assertThat(rows).anySatisfy(row -> assertThat(row).containsExactly(
                ValLong.create(1), ValString.create("Alice")));
        assertThat(rows).anySatisfy(row -> assertThat(row).containsExactly(
                ValLong.create(3), ValString.create("Carol")));
    }

    // ------------------------------------------------------------------------------------------------------
    // Error / resource-safety paths
    // ------------------------------------------------------------------------------------------------------

    @Test
    void unregisteredSideDatasource_reportsAClearErrorOnTheResultStore() {
        // Left registered, right NOT -> realising the right side finds no SearchProvider. createResultStore
        // captures this on the returned ResultStore (see its Javadoc) rather than throwing, so the search fails
        // with a clear in-band message instead of an opaque exception - the same convention
        // SearchableSearchProvider uses for a failure mid-search.
        final SearchProvider leftProvider = fakeSideProvider(
                LEFT_DATA_SOURCE, List.of("UserId"), List.<Val[]>of(new Val[]{ValLong.create(1)}));

        final ResultStore resultStore = provider(registry(leftProvider))
                .createResultStore(outerRequest(ExpressionOperator.builder().build()));

        assertThat(resultStore.getErrors())
                .anySatisfy(error -> assertThat(error.getMessage()).contains("No SearchProvider registered"));
    }

    @Test
    void equiKeyFieldNotFoundAmongCompiledColumns_reportsAClearErrorOnTheResultStore() {
        // The left side's columns don't contain the equi-key field ("UserId"), so positionOf fails clearly -
        // captured on the ResultStore, same as the unregistered-datasource case above.
        final SearchProvider leftProvider = fakeSideProvider(
                LEFT_DATA_SOURCE, List.of("SomethingElse"), List.<Val[]>of(new Val[]{ValLong.create(1)}));
        final SearchProvider rightProvider = fakeSideProvider(
                RIGHT_DATA_SOURCE, List.of("Id", "Name"),
                List.<Val[]>of(new Val[]{ValLong.create(2), ValString.create("Bob")}));

        final ResultStore resultStore = provider(registry(leftProvider, rightProvider))
                .createResultStore(outerRequest(ExpressionOperator.builder().build()));

        assertThat(resultStore.getErrors())
                .anySatisfy(error -> assertThat(error.getMessage()).contains("Equi-key field 'UserId' not found"));
    }

    @Test
    void leftSideResultStore_isDestroyed_whenRightSideRealisationFails() {
        // The left side realises fine (an open ResultStore); the right side's sub-search then throws. The left
        // side's store must still be destroyed rather than leaked - it was created before the try/finally below.
        // The failure itself is captured on the outer ResultStore (see createResultStore's Javadoc), not thrown.
        final DataStore leftData = mock(DataStore.class);
        when(leftData.getColumns()).thenReturn(List.of(column("UserId")));
        final ResultStore leftStore = mock(ResultStore.class);
        when(leftStore.getData(any())).thenReturn(leftData);
        final SearchProvider leftProvider = mock(SearchProvider.class);
        when(leftProvider.getDataSourceType()).thenReturn(LEFT_DATA_SOURCE.getType());
        when(leftProvider.createResultStore(any())).thenReturn(leftStore);

        final SearchProvider rightProvider = mock(SearchProvider.class);
        when(rightProvider.getDataSourceType()).thenReturn(RIGHT_DATA_SOURCE.getType());
        when(rightProvider.createResultStore(any())).thenThrow(new RuntimeException("right side boom"));

        final ResultStore resultStore = provider(registry(leftProvider, rightProvider))
                .createResultStore(outerRequest(ExpressionOperator.builder().build()));

        assertThat(resultStore.getErrors())
                .anySatisfy(error -> assertThat(error.getMessage()).contains("right side boom"));
        verify(leftStore).destroy();
    }

    // ------------------------------------------------------------------------------------------------------
    // Pure positional helpers
    // ------------------------------------------------------------------------------------------------------

    @Test
    void buildFieldMapping_mapsAliasQualifiedFieldsToTheirCombinedRowPositions() {
        // Populate a FieldIndex exactly the way a real coprocessor does - by the select columns' expression text.
        final FieldIndex fieldIndex = new FieldIndex();
        fieldIndex.create("a.UserId");
        fieldIndex.create("b.Name");

        final int[] mapping = JoinSearchProvider.buildFieldMapping(
                fieldIndex,
                List.of(column("UserId")),
                List.of(column("Id"), column("Name")),
                "a",
                "b");

        // a.UserId -> left column 0 (combined pos 0); b.Name -> right column 1 (combined pos leftWidth(1)+1 = 2).
        assertThat(mapping).containsExactly(0, 2);
    }

    @Test
    void buildFieldMapping_unrecognisedOrUnqualifiedNames_mapToMinusOne() {
        final FieldIndex fieldIndex = new FieldIndex();
        fieldIndex.create("StreamId");        // unqualified (e.g. an auto-added special column) -> -1
        fieldIndex.create("c.Whatever");      // alias matches neither side -> -1
        fieldIndex.create("a.Missing");       // left alias but no such column -> -1

        final int[] mapping = JoinSearchProvider.buildFieldMapping(
                fieldIndex, List.of(column("UserId")), List.of(column("Id")), "a", "b");

        assertThat(mapping).containsExactly(-1, -1, -1);
    }

    @Test
    void assembleRow_placesValuesPerMappingAndNullsTheRest() {
        final Val[] combined = {ValLong.create(2), ValLong.create(99), ValString.create("Bob")};
        final Val[] out = JoinSearchProvider.assembleRow(combined, new int[]{0, 2, -1});

        assertThat(out).containsExactly(ValLong.create(2), ValString.create("Bob"), ValNull.INSTANCE);
    }

    // ------------------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------------------

    private static Column column(final String name) {
        return Column.builder().id(name).name(name).expression(name).build();
    }

    // ------------------------------------------------------------------------------------------------------
    // Task B1 (see docs/join-scalability-implementation-plan.md, decisions D5/D7/D8): the enrichment-join fast
    // path against a keyed Plan B/State store. PLAN_B_DATA_SOURCE mirrors what detectPlanBLookupSide looks for
    // (DocRef.getType() == "PlanB"); an equi-key field of "Key" makes a side lookup-eligible.
    // ------------------------------------------------------------------------------------------------------

    private static final DocRef PLAN_B_DATA_SOURCE = new DocRef("PlanB", "planb-uuid", "Users");

    /** A SearchProvider that fails the test if it is ever asked to realise the Plan B side - proving
     * broadcastLookupJoin genuinely never scans the lookup store, the whole point of B1. */
    private static SearchProvider planBProviderThatMustNeverBeRealised() {
        final SearchProvider provider = mock(SearchProvider.class);
        when(provider.getDataSourceType()).thenReturn(PLAN_B_DATA_SOURCE.getType());
        when(provider.createResultStore(any())).thenThrow(new AssertionError(
                "The Plan B lookup side must never be realised via its own SearchProvider - "
                + "broadcastLookupJoin should stream the probe side against StateFetcher instead"));
        return provider;
    }

    /** Outer join request: select a.UserId, b.Value; lookup side's equi-key field is "Key" (lookup-eligible). */
    private static SearchRequest lookupOuterRequest(
            final ExpressionOperator outerExpression, final JoinSpec.JoinType joinType, final boolean lookupIsLeft) {
        final SearchRequest probeSide = SearchRequest.builder()
                .query(Query.builder().dataSource(LEFT_DATA_SOURCE).build())
                .build();
        final SearchRequest lookupSide = SearchRequest.builder()
                .query(Query.builder().dataSource(PLAN_B_DATA_SOURCE).build())
                .build();
        final JoinSpec joinSpec = JoinSpec.builder()
                .left(lookupIsLeft ? lookupSide : probeSide)
                .right(lookupIsLeft ? probeSide : lookupSide)
                .joinType(joinType)
                .addEquiKey(lookupIsLeft
                        ? new JoinEquiKey("b", "Key", "a", "UserId")
                        : new JoinEquiKey("a", "UserId", "b", "Key"))
                .build();

        final TableSettings tableSettings = TableSettings.builder()
                .addColumns(column("a.UserId"), column("b.Value"))
                .extractValues(true)
                .build();
        final ResultRequest tableResultRequest = ResultRequest.builder()
                .componentId(SearchRequestFactory.TABLE_COMPONENT_ID)
                .mappings(List.of(tableSettings))
                .resultStyle(ResultStyle.TABLE)
                .fetch(Fetch.ALL)
                .build();

        return SearchRequest.builder()
                .searchRequestSource(SearchRequestSource.createBasic())
                .key(new QueryKey("test-join-lookup"))
                .query(Query.builder()
                        .dataSource(new DocRef(JoinDataSourceType.TYPE, "join-uuid", "A ⋈ B"))
                        .expression(outerExpression)
                        .joinSpec(joinSpec)
                        .build())
                .resultRequests(List.of(tableResultRequest))
                .dateTimeSettings(DateTimeSettings.builder().referenceTime(0L).build())
                .incremental(false)
                .build();
    }

    @Test
    void broadcastLookup_innerJoin_matchingKey_returnsLookedUpValue() {
        final SearchProvider probeProvider = fakeSideProvider(
                LEFT_DATA_SOURCE, List.of("UserId"),
                List.of(new Val[]{ValLong.create(1)}, new Val[]{ValLong.create(2)}));
        final stroom.query.language.functions.StateFetcher stateFetcher =
                (map, key, effectiveTimeMs) -> "2".equals(key) ? ValString.create("Bob") : ValNull.INSTANCE;

        final ResultStore resultStore = provider(
                registry(probeProvider, planBProviderThatMustNeverBeRealised()),
                DEFAULT_JOIN_CONFIG_PROVIDER, stateFetcher)
                .createResultStore(lookupOuterRequest(
                        ExpressionOperator.builder().build(), JoinSpec.JoinType.INNER, false));

        assertThat(resultStore.getErrors()).isEmpty();
        final List<Val[]> rows = readTableRows(resultStore);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()[0]).isEqualTo(ValLong.create(2));
        assertThat(rows.getFirst()[1]).isEqualTo(ValString.create("Bob"));
    }

    @Test
    void broadcastLookup_innerJoin_missingKey_dropsTheProbeRow() {
        final SearchProvider probeProvider = fakeSideProvider(
                LEFT_DATA_SOURCE, List.of("UserId"), List.<Val[]>of(new Val[]{ValLong.create(1)}));
        final stroom.query.language.functions.StateFetcher stateFetcher =
                (map, key, effectiveTimeMs) -> ValNull.INSTANCE;

        final ResultStore resultStore = provider(
                registry(probeProvider, planBProviderThatMustNeverBeRealised()),
                DEFAULT_JOIN_CONFIG_PROVIDER, stateFetcher)
                .createResultStore(lookupOuterRequest(
                        ExpressionOperator.builder().build(), JoinSpec.JoinType.INNER, false));

        assertThat(resultStore.getErrors()).isEmpty();
        assertThat(readTableRows(resultStore)).isEmpty();
    }

    @Test
    void broadcastLookup_leftJoin_missingKey_keepsTheProbeRow_nullPadded() {
        final SearchProvider probeProvider = fakeSideProvider(
                LEFT_DATA_SOURCE, List.of("UserId"), List.<Val[]>of(new Val[]{ValLong.create(1)}));
        final stroom.query.language.functions.StateFetcher stateFetcher =
                (map, key, effectiveTimeMs) -> ValNull.INSTANCE;

        final ResultStore resultStore = provider(
                registry(probeProvider, planBProviderThatMustNeverBeRealised()),
                DEFAULT_JOIN_CONFIG_PROVIDER, stateFetcher)
                .createResultStore(lookupOuterRequest(
                        ExpressionOperator.builder().build(), JoinSpec.JoinType.LEFT, false));

        assertThat(resultStore.getErrors()).isEmpty();
        final List<Val[]> rows = readTableRows(resultStore);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()[0]).isEqualTo(ValLong.create(1));
        assertThat(rows.getFirst()[1]).isEqualTo(ValNull.INSTANCE);
    }

    @Test
    void broadcastLookup_lookupIsTheJoinSpecLeftSide_stillWorks() {
        // The physical combined-row order inside JoinExecutor.broadcastLookupJoin is always [probe..., Key,
        // Value] regardless of which JoinSpec slot (left/right) the lookup occupies - proves the reordering in
        // joinAndFeedViaBroadcastLookup is correct when the lookup happens to be JoinSpec-left.
        final SearchProvider probeProvider = fakeSideProvider(
                LEFT_DATA_SOURCE, List.of("UserId"), List.<Val[]>of(new Val[]{ValLong.create(2)}));
        final stroom.query.language.functions.StateFetcher stateFetcher =
                (map, key, effectiveTimeMs) -> "2".equals(key) ? ValString.create("Bob") : ValNull.INSTANCE;

        final ResultStore resultStore = provider(
                registry(probeProvider, planBProviderThatMustNeverBeRealised()),
                DEFAULT_JOIN_CONFIG_PROVIDER, stateFetcher)
                .createResultStore(lookupOuterRequest(
                        ExpressionOperator.builder().build(), JoinSpec.JoinType.INNER, true));

        assertThat(resultStore.getErrors()).isEmpty();
        final List<Val[]> rows = readTableRows(resultStore);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()[0]).isEqualTo(ValLong.create(2));
        assertThat(rows.getFirst()[1]).isEqualTo(ValString.create("Bob"));
    }

    @Test
    void broadcastLookup_whereClauseOnLookedUpValue_filtersTheCombinedRow() {
        final SearchProvider probeProvider = fakeSideProvider(
                LEFT_DATA_SOURCE, List.of("UserId"),
                List.of(new Val[]{ValLong.create(1)}, new Val[]{ValLong.create(2)}));
        final java.util.Map<String, Val> lookup =
                java.util.Map.of("1", ValString.create("Alice"), "2", ValString.create("Bob"));
        final stroom.query.language.functions.StateFetcher stateFetcher =
                (map, key, effectiveTimeMs) -> lookup.getOrDefault(key, ValNull.INSTANCE);
        final ExpressionOperator where = ExpressionOperator.builder()
                .addTerm("b.Value", Condition.EQUALS, "Bob")
                .build();

        final ResultStore resultStore = provider(
                registry(probeProvider, planBProviderThatMustNeverBeRealised()),
                DEFAULT_JOIN_CONFIG_PROVIDER, stateFetcher)
                .createResultStore(lookupOuterRequest(where, JoinSpec.JoinType.INNER, false));

        assertThat(resultStore.getErrors()).isEmpty();
        final List<Val[]> rows = readTableRows(resultStore);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()[0]).isEqualTo(ValLong.create(2));
        assertThat(rows.getFirst()[1]).isEqualTo(ValString.create("Bob"));
    }

    @Test
    void broadcastLookup_largeProbeSide_streamsAndSucceeds_notCappedByMaxSideRows() {
        // The B1 probe side is now streamed (never realised into a list), so a probe side far larger than
        // maxSideRows must NOT abort - only the joined output is capped. Here maxSideRows=1 but the probe has 2
        // rows: previously this reported "join side row count"; now it succeeds and returns both enriched rows.
        final SearchProvider probeProvider = fakeSideProvider(
                LEFT_DATA_SOURCE, List.of("UserId"),
                List.of(new Val[]{ValLong.create(1)}, new Val[]{ValLong.create(2)}));
        final stroom.query.language.functions.StateFetcher stateFetcher =
                (map, key, effectiveTimeMs) -> ValString.create("x");

        final ResultStore resultStore = provider(
                registry(probeProvider, planBProviderThatMustNeverBeRealised()),
                () -> new JoinConfig(1L, null, null, null), stateFetcher)
                .createResultStore(lookupOuterRequest(
                        ExpressionOperator.builder().build(), JoinSpec.JoinType.INNER, false));

        assertThat(resultStore.getErrors()).isEmpty();
        assertThat(readTableRows(resultStore)).hasSize(2);
    }

    @Test
    void broadcastLookup_outputExceedsMaxOutputRows_reportsAClearError() {
        final SearchProvider probeProvider = fakeSideProvider(
                LEFT_DATA_SOURCE, List.of("UserId"),
                List.of(new Val[]{ValLong.create(1)}, new Val[]{ValLong.create(2)}));
        final stroom.query.language.functions.StateFetcher stateFetcher =
                (map, key, effectiveTimeMs) -> ValString.create("x");

        final ResultStore resultStore = provider(
                registry(probeProvider, planBProviderThatMustNeverBeRealised()),
                () -> new JoinConfig(null, 1L, null, null), stateFetcher)
                .createResultStore(lookupOuterRequest(
                        ExpressionOperator.builder().build(), JoinSpec.JoinType.INNER, false));

        assertThat(resultStore.getErrors())
                .anySatisfy(error -> assertThat(error.getMessage()).contains("join output row count"));
    }

    @Test
    void nonKeyEquiKeyFieldOnAPlanBTypedSide_isNotLookupEligible_fallsBackToHashJoin() {
        // Same DocRef type ("PlanB") but the equi-key field is "OtherField", not "Key" - not point-lookup
        // addressable, so detectPlanBLookupSide must not treat it as a lookup side. Uses an ordinary
        // fakeSideProvider (realised normally) to prove the fallback hash-join path still works.
        final SearchProvider probeProvider = fakeSideProvider(
                LEFT_DATA_SOURCE, List.of("UserId"), List.<Val[]>of(new Val[]{ValLong.create(1)}));
        final SearchProvider planBTypedButNotKeyed = fakeSideProvider(
                PLAN_B_DATA_SOURCE, List.of("OtherField"), List.<Val[]>of(new Val[]{ValLong.create(1)}));

        final SearchRequest probeSide = SearchRequest.builder()
                .query(Query.builder().dataSource(LEFT_DATA_SOURCE).build()).build();
        final SearchRequest lookupLikeSide = SearchRequest.builder()
                .query(Query.builder().dataSource(PLAN_B_DATA_SOURCE).build()).build();
        final JoinSpec joinSpec = JoinSpec.builder()
                .left(probeSide)
                .right(lookupLikeSide)
                .joinType(JoinSpec.JoinType.INNER)
                .addEquiKey(new JoinEquiKey("a", "UserId", "b", "OtherField"))
                .build();
        final TableSettings tableSettings = TableSettings.builder()
                .addColumns(column("a.UserId"), column("b.OtherField"))
                .extractValues(true)
                .build();
        final SearchRequest request = SearchRequest.builder()
                .searchRequestSource(SearchRequestSource.createBasic())
                .key(new QueryKey("test-fallback"))
                .query(Query.builder()
                        .dataSource(new DocRef(JoinDataSourceType.TYPE, "join-uuid", "A ⋈ B"))
                        .expression(ExpressionOperator.builder().build())
                        .joinSpec(joinSpec)
                        .build())
                .resultRequests(List.of(ResultRequest.builder()
                        .componentId(SearchRequestFactory.TABLE_COMPONENT_ID)
                        .mappings(List.of(tableSettings))
                        .resultStyle(ResultStyle.TABLE)
                        .fetch(Fetch.ALL)
                        .build()))
                .dateTimeSettings(DateTimeSettings.builder().referenceTime(0L).build())
                .incremental(false)
                .build();

        final ResultStore resultStore = provider(registry(probeProvider, planBTypedButNotKeyed))
                .createResultStore(request);

        assertThat(resultStore.getErrors()).isEmpty();
        assertThat(readTableRows(resultStore)).hasSize(1);
    }

    @Test
    void compositeEquiKeyOnAPlanBTypedSide_isNotLookupEligible_fallsBackToHashJoin() {
        // A composite (>1) equi-key isn't representable via StateFetcher's single-string key (decision D8), so
        // detectPlanBLookupSide must decline even though the side's type and first field ("Key") would otherwise
        // qualify - falls back to the ordinary hash-join path, which realises the Plan B side normally.
        final SearchProvider probeProvider = fakeSideProvider(
                LEFT_DATA_SOURCE, List.of("UserId", "Region"),
                List.<Val[]>of(new Val[]{ValLong.create(1), ValString.create("north")}));
        final SearchProvider planBTypedButComposite = fakeSideProvider(
                PLAN_B_DATA_SOURCE, List.of("Key", "Region"),
                List.<Val[]>of(new Val[]{ValLong.create(1), ValString.create("north")}));

        final SearchRequest probeSide = SearchRequest.builder()
                .query(Query.builder().dataSource(LEFT_DATA_SOURCE).build()).build();
        final SearchRequest lookupLikeSide = SearchRequest.builder()
                .query(Query.builder().dataSource(PLAN_B_DATA_SOURCE).build()).build();
        final JoinSpec joinSpec = JoinSpec.builder()
                .left(probeSide)
                .right(lookupLikeSide)
                .joinType(JoinSpec.JoinType.INNER)
                .addEquiKey(new JoinEquiKey("a", "UserId", "b", "Key"))
                .addEquiKey(new JoinEquiKey("a", "Region", "b", "Region"))
                .build();
        final TableSettings tableSettings = TableSettings.builder()
                .addColumns(column("a.UserId"), column("b.Key"))
                .extractValues(true)
                .build();
        final SearchRequest request = SearchRequest.builder()
                .searchRequestSource(SearchRequestSource.createBasic())
                .key(new QueryKey("test-composite-fallback"))
                .query(Query.builder()
                        .dataSource(new DocRef(JoinDataSourceType.TYPE, "join-uuid", "A ⋈ B"))
                        .expression(ExpressionOperator.builder().build())
                        .joinSpec(joinSpec)
                        .build())
                .resultRequests(List.of(ResultRequest.builder()
                        .componentId(SearchRequestFactory.TABLE_COMPONENT_ID)
                        .mappings(List.of(tableSettings))
                        .resultStyle(ResultStyle.TABLE)
                        .fetch(Fetch.ALL)
                        .build()))
                .dateTimeSettings(DateTimeSettings.builder().referenceTime(0L).build())
                .incremental(false)
                .build();

        final ResultStore resultStore = provider(registry(probeProvider, planBTypedButComposite))
                .createResultStore(request);

        assertThat(resultStore.getErrors()).isEmpty();
        assertThat(readTableRows(resultStore)).hasSize(1);
    }

    /** Outer join request: select a.UserId, b.Name; dataSource = sentinel; joinSpec = a.UserId = b.Id INNER. */
    private static SearchRequest outerRequest(final ExpressionOperator outerExpression) {
        return outerRequest(outerExpression, JoinSpec.JoinType.INNER);
    }

    private static SearchRequest outerRequest(final ExpressionOperator outerExpression,
                                              final JoinSpec.JoinType joinType) {
        final SearchRequest leftSide = SearchRequest.builder()
                .query(Query.builder().dataSource(LEFT_DATA_SOURCE).build())
                .build();
        final SearchRequest rightSide = SearchRequest.builder()
                .query(Query.builder().dataSource(RIGHT_DATA_SOURCE).build())
                .build();
        final JoinSpec joinSpec = JoinSpec.builder()
                .left(leftSide)
                .right(rightSide)
                .joinType(joinType)
                .addEquiKey(new JoinEquiKey("a", "UserId", "b", "Id"))
                .build();

        final TableSettings tableSettings = TableSettings.builder()
                .addColumns(column("a.UserId"), column("b.Name"))
                .extractValues(true)
                .build();
        final ResultRequest tableResultRequest = ResultRequest.builder()
                .componentId(SearchRequestFactory.TABLE_COMPONENT_ID)
                .mappings(List.of(tableSettings))
                .resultStyle(ResultStyle.TABLE)
                .fetch(Fetch.ALL)
                .build();

        return SearchRequest.builder()
                .searchRequestSource(SearchRequestSource.createBasic())
                .key(new QueryKey("test-join"))
                .query(Query.builder()
                        .dataSource(new DocRef(JoinDataSourceType.TYPE, "join-uuid", "A ⋈ B"))
                        .expression(outerExpression)
                        .joinSpec(joinSpec)
                        .build())
                .resultRequests(List.of(tableResultRequest))
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

    @SuppressWarnings("unchecked")
    private static SearchProvider fakeSideProvider(
            final DocRef dataSource, final List<String> columnNames, final List<Val[]> rows) {
        final List<Column> columns = columnNames.stream().map(TestJoinSearchProvider::column).toList();

        final DataStore dataStore = mock(DataStore.class);
        when(dataStore.getColumns()).thenReturn(columns);
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

        final SearchProvider provider = mock(SearchProvider.class);
        when(provider.getDataSourceType()).thenReturn(dataSource.getType());
        when(provider.createResultStore(any())).thenReturn(resultStore);
        return provider;
    }
}
