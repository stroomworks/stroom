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

package stroom.graphdb.impl;

import stroom.docref.DocRef;
import stroom.docstore.api.DocFinder;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.lmdb.serde.UnsignedBytesInstances;
import stroom.planb.impl.dao.UidLookupDb;
import stroom.query.api.Column;
import stroom.query.api.DateTimeSettings;
import stroom.query.api.GraphSpec;
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
import stroom.query.common.v2.FieldInfoResultPageFactory;
import stroom.query.common.v2.IdentityItemMapper;
import stroom.query.common.v2.MapDataStoreFactory;
import stroom.query.common.v2.OpenGroups;
import stroom.query.common.v2.ResultStore;
import stroom.query.common.v2.ResultStoreFactory;
import stroom.query.common.v2.ResultStoreSettingsFactory;
import stroom.query.common.v2.SearchResultStoreConfig;
import stroom.query.common.v2.Sizes;
import stroom.query.common.v2.SizesProvider;
import stroom.query.language.SearchRequestFactory;
import stroom.query.language.functions.ExpressionContext;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValLong;
import stroom.query.language.functions.ValString;
import stroom.security.api.SecurityContext;
import stroom.util.shared.PermissionException;
import stroom.util.shared.UserRef;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task PoC.6's Done-when: a Cypher single-hop query, submitted as a real {@link SearchRequest} carrying a
 * {@link GraphSpec}, returns real rows through real coprocessors/{@link ResultStore} - the graph analogue of
 * {@code stroom.searchable.impl.TestJoinSearchProvider}'s
 * {@code innerJoin_returnsRealJoinedRows_throughRealCoprocessors}. Only {@link GraphDbDocCache}/
 * {@link GraphStoreManager} are faked (to avoid standing up the real doc store/cache stack); everything from
 * {@link GraphSearchProvider#createResultStore} inward - compiling the Cypher text, opening real PoC.4 LMDB
 * fixtures, running the real {@link GraphTraversalEngine}, and feeding real {@code Coprocessors} - is genuine.
 */
class TestGraphSearchProvider {

    private static final GraphDbDoc DOC = GraphDbDoc.builder().uuid("graph-uuid").name("TestGraph").build();
    private static final DocRef DOC_REF = DOC.asDocRef();
    private static final Executor DIRECT_EXECUTOR = Runnable::run;
    private static final SizesProvider SIZES_PROVIDER = Sizes::unlimited;
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00.000Z");
    private static final Instant T2 = Instant.parse("2026-06-01T00:00:00.000Z");

    @Test
    void singleHopCypherQuery_returnsRealRows_throughRealCoprocessors(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root, DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphSearchProvider provider = provider(stores);
            final SearchRequest request = requestFor(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN a.id");

            final ResultStore resultStore = provider.createResultStore(request);

            final List<Val[]> rows = readTableRows(resultStore);
            assertThat(rows).extracting(row -> row[0].toString())
                    .containsExactlyInAnyOrder("account-a", "account-b");
        }
    }

    @Test
    void singleHopCypherQuery_withCompilerDerivedResultRequests_returnsRealRows(@TempDir final Path root) {
        // Proves CypherCompiler's own resultRequests (rather than this test class's hand-built one, see
        // requestFor()) carry a real Cypher RETURN clause's columns all the way through to real rows - i.e. that
        // a Cypher query submitted the way QueryServiceImpl.mapRequest() actually seeds one (with no
        // resultRequests of its own) does not come back silently empty.
        try (GraphStores stores = GraphStores.provision(root, DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphSearchProvider provider = provider(stores);
            final SearchRequest seed = SearchRequest.builder()
                    .searchRequestSource(SearchRequestSource.createBasic())
                    .key(new QueryKey("test-graph"))
                    .query(Query.builder().dataSource(DOC_REF).build())
                    .dateTimeSettings(DateTimeSettings.builder().referenceTime(0L).build())
                    .incremental(false)
                    .build();
            final SearchRequest request = new GraphCypherQueryCompiler(mock(DocFinder.class)).create(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN a.id",
                    seed,
                    new ExpressionContext());

            final ResultStore resultStore = provider.createResultStore(request);

            final List<Val[]> rows = readTableRows(resultStore);
            assertThat(rows).extracting(row -> row[0].toString())
                    .containsExactlyInAnyOrder("account-a", "account-b");
        }
    }

    @Test
    void asOfCypherQuery_returnsPointInTimeCorrectRows(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root, DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphSearchProvider provider = provider(stores);
            final SearchRequest request = requestFor(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) "
                    + "AS OF datetime('2026-02-01T00:00:00Z') RETURN a.id");

            final ResultStore resultStore = provider.createResultStore(request);

            final List<Val[]> rows = readTableRows(resultStore);
            assertThat(rows).extracting(row -> row[0].toString()).containsExactly("account-a");
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // aggregation (Task 1.4 of docs/graphdb-analytic-functions-implementation-plan.md)
    // ------------------------------------------------------------------------------------------------------

    @Test
    void unaliasedCountStarAggregate_resolvesThroughTheRealColumnPipeline(@TempDir final Path root) {
        // The guard task 1.4 exists to run: CypherCompiler.buildResultRequests builds every column's expression
        // as "${" + field.name() + "}" - for an unaliased aggregate that name is now the "${}"-free "count(*)"
        // (see CypherToLogicalPlan.defaultAggregateName). Uses the real GraphCypherQueryCompiler (like
        // singleHopCypherQuery_withCompilerDerivedResultRequests_returnsRealRows above), so this proves
        // "${count(*)}" resolves end-to-end through the real column pipeline, not just at compile time.
        try (GraphStores stores = GraphStores.provision(root, DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphSearchProvider provider = provider(stores);
            final SearchRequest request = compilerDerivedRequest(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN count(*)");

            final ResultStore resultStore = provider.createResultStore(request);

            final List<Val[]> rows = readTableRows(resultStore);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()[0].toLong()).isEqualTo(2L);
        }
    }

    @Test
    void aliasedGroupedAggregate_returnsRealRows_throughRealCoprocessors(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root, DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphSearchProvider provider = provider(stores);
            final SearchRequest request = compilerDerivedRequest(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) "
                    + "RETURN a.id, sum(a.balance) AS total");

            final ResultStore resultStore = provider.createResultStore(request);

            final List<Val[]> rows = readTableRows(resultStore);
            assertThat(rows).extracting(row -> row[0].toString(), row -> row[1].toDouble())
                    .containsExactlyInAnyOrder(
                            Tuple.tuple("account-a", 50.0),
                            Tuple.tuple("account-b", 200.0));
        }
    }

    @Test
    void withHavingPipe_returnsRealRows_throughRealCoprocessors(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root, DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphSearchProvider provider = provider(stores);
            // Aggregate to a total per device, then filter on the aggregate (HAVING) - impossible without WITH.
            // d-42's two accounts total 250, which passes total > 100.
            final SearchRequest request = compilerDerivedRequest(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) "
                    + "WITH d.id AS device, sum(a.balance) AS total WHERE total > 100 "
                    + "RETURN device, total");

            final ResultStore resultStore = provider.createResultStore(request);

            final List<Val[]> rows = readTableRows(resultStore);
            assertThat(rows).extracting(row -> row[0].toString(), row -> row[1].toDouble())
                    .containsExactly(Tuple.tuple("d-42", 250.0));
        }
    }

    @Test
    void missingGraphSpec_rejectedClearly() {
        final GraphSearchProvider provider = provider(null);
        final SearchRequest requestWithNoGraphSpec = SearchRequest.builder()
                .query(Query.builder().dataSource(DOC_REF).build())
                .build();

        assertThatThrownBy(() -> provider.createResultStore(requestWithNoGraphSpec))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void permissionException_fromDocResolution_propagatesOutOfCreateResultStore() {
        // Task P7.1: GraphDbDocCacheImpl.get() already throws PermissionException when the caller lacks USE
        // permission (TestGraphDbDocCacheImpl.get_throwsWhenCallerLacksUsePermission proves that directly). This
        // proves what happens to it one layer up: doc resolution (getGraphDbDoc, called before the ResultStore
        // is even constructed) is OUTSIDE createResultStore's own try/catch, so the exception propagates rather
        // than being downgraded to a soft resultStore.addError() entry - exactly like
        // JoinSearchProvider.createResultStore, whose own per-side doc/datasource resolution (realiseSide) is
        // likewise called before its try block. There is no ResultStore yet for a pre-resolution failure to be
        // attached to, so propagating here (for a caller further up the stack to turn into a hard search
        // failure) is the consistent, existing contract - not a gap this task needs to close.
        final GraphSearchProvider provider = providerWithThrowingDocCache(
                new PermissionException(UserRef.builder().uuid("u1").subjectId("user").build(),
                        "You are not authorised to read " + DOC_REF));
        final SearchRequest request = requestFor("MATCH (d:Device {id: 'd-42'}) RETURN d.id");

        assertThatThrownBy(() -> provider.createResultStore(request))
                .isInstanceOf(PermissionException.class)
                .hasMessageContaining("not authorised");
    }

    @Test
    void malformedCypherAtExecutionTime_surfacesAsAResultStoreError_notAnUncaughtThrow() {
        // Code-review fix: the execution-time compile (re-parsing/re-compiling GraphSpec.getCypher(), needed
        // since the compiled plan itself is never carried on the wire - see this class's own Javadoc) used to
        // run before the try block existed at all, so a malformed Cypher string reaching this method directly
        // (bypassing QueryServiceImpl's own upfront compile, e.g. a stored/replayed SearchRequest) propagated
        // raw out of createResultStore instead of being caught like every other execution failure. It's now
        // inside the try, alongside coprocessors/resultStore construction moved ahead of it.
        final GraphSearchProvider provider = provider(null);
        final SearchRequest request = requestFor("not cypher at all");

        final ResultStore resultStore = provider.createResultStore(request);

        assertThat(resultStore.getErrors()).isNotEmpty();
    }

    // ------------------------------------------------------------------------------------------------------
    // DIFF (docs/temporal-cypher-diff-operator.md)
    // ------------------------------------------------------------------------------------------------------

    @Test
    void diffCypherQuery_returnsDeltaTable_throughRealCoprocessors(@TempDir final Path root) {
        // End-to-end oracle: a DIFF query's changeKind + before/after columns resolve through the real
        // compiler-derived column pipeline and coprocessor/result-store path, with UNCHANGED suppressed.
        final Instant tMid = Instant.parse("2026-03-01T00:00:00.000Z");
        try (GraphStores stores = GraphStores.provision(root, DOC)) {
            final long deviceLabel = intern(stores, stores.getLabelUids(), "Device");
            final long accountLabel = intern(stores, stores.getLabelUids(), "Account");
            final long idKey = intern(stores, stores.getPropertyKeyUids(), "id");
            final long connectedTo = intern(stores, stores.getEdgeTypeUids(), "CONNECTED_TO");
            final long deviceUid = intern(stores, stores.getNodeUids(), "d-42");
            final long aUid = intern(stores, stores.getNodeUids(), "account-a");
            final long bUid = intern(stores, stores.getNodeUids(), "account-b");
            final long cUid = intern(stores, stores.getNodeUids(), "account-c");
            final long dUid = intern(stores, stores.getNodeUids(), "account-d");

            stores.write(writer -> {
                stores.getNodes().insert(writer, deviceUid, T1, List.of(deviceLabel),
                        Map.of("id", ValString.create("d-42")));
                stores.getPropertyIndex().insert(
                        writer, deviceLabel, idKey, "d-42".getBytes(StandardCharsets.UTF_8), deviceUid);
                stores.getNodes().insert(writer, aUid, T1, List.of(accountLabel),
                        Map.of("id", ValString.create("account-a"), "balance", ValLong.create(50)));
                stores.getNodes().insert(writer, aUid, T2, List.of(accountLabel),
                        Map.of("id", ValString.create("account-a"), "balance", ValLong.create(999)));
                stores.getNodes().insert(writer, bUid, T1, List.of(accountLabel),
                        Map.of("id", ValString.create("account-b"), "balance", ValLong.create(10)));
                stores.getNodes().insert(writer, cUid, T1, List.of(accountLabel),
                        Map.of("id", ValString.create("account-c"), "balance", ValLong.create(20)));
                stores.getNodes().insert(writer, dUid, T1, List.of(accountLabel),
                        Map.of("id", ValString.create("account-d"), "balance", ValLong.create(30)));

                stores.getOutEdges().insert(writer, deviceUid, connectedTo, aUid, T1, Map.of());
                stores.getOutEdges().insert(writer, deviceUid, connectedTo, bUid, T2, Map.of());
                stores.getOutEdges().insert(writer, deviceUid, connectedTo, dUid, T1, Map.of());
                stores.getOutEdges().insert(writer, deviceUid, connectedTo, cUid, T1, Map.of());
                stores.getOutEdges().delete(writer, deviceUid, connectedTo, cUid, tMid);
                return null;
            });

            final GraphSearchProvider provider = provider(stores);
            final SearchRequest request = compilerDerivedRequest(
                    "MATCH (d:Device {id: 'd-42'})-[c:CONNECTED_TO]->(a:Account) "
                    + "DIFF FROM datetime('2026-01-01T00:00:00Z') TO datetime('2026-06-01T00:00:00Z') "
                    + "RETURN changeKind, a.id, before(a.balance), after(a.balance)");

            final ResultStore resultStore = provider.createResultStore(request);

            final List<Val[]> rows = readTableRows(resultStore);
            assertThat(rows)
                    .extracting(r -> r[0].toString(), r -> r[1].toString(), r -> diffText(r[2]), r -> diffText(r[3]))
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple("MODIFIED", "account-a", "50", "999"),
                            org.assertj.core.groups.Tuple.tuple("ADDED", "account-b", null, "10"),
                            org.assertj.core.groups.Tuple.tuple("REMOVED", "account-c", "20", null));
        }
    }

    /** Null-safe rendering of an absent before/after value (a null String rather than a "null" String). */
    private static String diffText(final Val value) {
        return value == null ? null : value.toString();
    }

    // ------------------------------------------------------------------------------------------------------
    // RETURN GRAPH (Workstream D): D3's acceptance - CypherCompiler.buildResultRequests advertises the frozen
    // element-row columns, verified end-to-end through the real compiled columns and coprocessor/result-store
    // path (mirrors diffCypherQuery_returnsDeltaTable_throughRealCoprocessors above).
    // ------------------------------------------------------------------------------------------------------

    @Test
    void returnGraphCypherQuery_advertisesTheFrozenElementRowColumns_andReturnsRealRows(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root, DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphSearchProvider provider = provider(stores);
            final SearchRequest request = compilerDerivedRequest(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN GRAPH");

            final ResultStore resultStore = provider.createResultStore(request);

            final DataStore dataStore = resultStore.getData(SearchRequestFactory.TABLE_COMPONENT_ID);
            assertThat(dataStore.getColumns()).extracting(Column::getName)
                    .containsExactly("kind", "id", "labels", "source", "target", "properties");

            final List<Val[]> rows = readTableRows(resultStore);
            // d-42 + account-a + account-b nodes, plus their 2 connecting edges = 5 rows.
            assertThat(rows).hasSize(5);
            assertThat(rows).extracting(r -> r[1].toString())
                    .contains("d-42", "account-a", "account-b",
                            "d-42|CONNECTED_TO|account-a", "d-42|CONNECTED_TO|account-b");
        }
    }

    @Test
    void diffReturnGraphCypherQuery_advertisesChangeKindAsA7thColumn_andReturnsRealRows(
            @TempDir final Path root) {
        final Instant tMid = Instant.parse("2026-03-01T00:00:00.000Z");
        try (GraphStores stores = GraphStores.provision(root, DOC)) {
            final long deviceLabel = intern(stores, stores.getLabelUids(), "Device");
            final long accountLabel = intern(stores, stores.getLabelUids(), "Account");
            final long idKey = intern(stores, stores.getPropertyKeyUids(), "id");
            final long connectedTo = intern(stores, stores.getEdgeTypeUids(), "CONNECTED_TO");
            final long deviceUid = intern(stores, stores.getNodeUids(), "d-42");
            final long aUid = intern(stores, stores.getNodeUids(), "account-a");
            final long bUid = intern(stores, stores.getNodeUids(), "account-b");

            stores.write(writer -> {
                stores.getNodes().insert(writer, deviceUid, T1, List.of(deviceLabel),
                        Map.of("id", ValString.create("d-42")));
                stores.getPropertyIndex().insert(
                        writer, deviceLabel, idKey, "d-42".getBytes(StandardCharsets.UTF_8), deviceUid);
                stores.getNodes().insert(writer, aUid, T1, List.of(accountLabel),
                        Map.of("id", ValString.create("account-a")));
                stores.getNodes().insert(writer, bUid, T1, List.of(accountLabel),
                        Map.of("id", ValString.create("account-b")));

                stores.getOutEdges().insert(writer, deviceUid, connectedTo, aUid, T1, Map.of());
                stores.getOutEdges().insert(writer, deviceUid, connectedTo, bUid, T2, Map.of());
                return null;
            });

            final GraphSearchProvider provider = provider(stores);
            final SearchRequest request = compilerDerivedRequest(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) "
                    + "DIFF FROM datetime('2026-01-01T00:00:00Z') TO datetime('2026-06-01T00:00:00Z') "
                    + "RETURN GRAPH");

            final ResultStore resultStore = provider.createResultStore(request);

            final DataStore dataStore = resultStore.getData(SearchRequestFactory.TABLE_COMPONENT_ID);
            assertThat(dataStore.getColumns()).extracting(Column::getName)
                    .containsExactly("kind", "id", "labels", "source", "target", "properties", "changeKind");

            final List<Val[]> rows = readTableRows(resultStore);
            // device + account-a (UNCHANGED) + account-b (ADDED) nodes, + their 2 edges (UNCHANGED, ADDED) = 5.
            assertThat(rows).hasSize(5);
            assertThat(rows)
                    .extracting(r -> r[1].toString(), r -> r[6].toString())
                    .contains(
                            Tuple.tuple("account-b", "ADDED"),
                            Tuple.tuple("d-42|CONNECTED_TO|account-b", "ADDED"),
                            Tuple.tuple("account-a", "UNCHANGED"));
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------------------

    /**
     * A real {@link CoprocessorsFactory} (backed by the lightweight {@link MapDataStoreFactory}, no LMDB/temp-dir
     * of its own) plus a {@link ResultStoreFactory} mock that wraps whatever coprocessors it is handed in a
     * genuine {@link ResultStore} - mirrors {@code TestJoinSearchProvider.provider}.
     */
    private static GraphSearchProvider provider(final GraphStores stores) {
        final CoprocessorsFactory coprocessorsFactory = new CoprocessorsFactory(
                new MapDataStoreFactory(SearchResultStoreConfig::new),
                new ExpressionContextFactory(),
                SIZES_PROVIDER,
                () -> DIRECT_EXECUTOR);

        final ResultStoreFactory resultStoreFactory = mock(ResultStoreFactory.class);
        when(resultStoreFactory.create(any(), any()))
                .thenAnswer(inv -> new ResultStore(
                        inv.getArgument(0),
                        SIZES_PROVIDER,
                        null,
                        inv.getArgument(1),
                        "node",
                        new ResultStoreSettingsFactory().get(),
                        new MapDataStoreFactory(SearchResultStoreConfig::new),
                        new ExpressionPredicateFactory(),
                        () -> DIRECT_EXECUTOR));

        final GraphDbDocCache graphDbDocCache = mock(GraphDbDocCache.class);
        when(graphDbDocCache.get(DOC.getName())).thenReturn(DOC);

        final GraphStoreManager graphStoreManager = mock(GraphStoreManager.class);
        if (stores != null) {
            when(graphStoreManager.getOrOpen(DOC)).thenReturn(stores);
        }

        final SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.useAsReadResult(any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());

        return new GraphSearchProvider(
                graphDbDocCache,
                mock(GraphDbDocStore.class),
                graphStoreManager,
                coprocessorsFactory,
                resultStoreFactory,
                new ExpressionPredicateFactory(),
                securityContext,
                mock(FieldInfoResultPageFactory.class),
                mock(DocFinder.class));
    }

    /**
     * Task P7.1: same shape as {@link #provider}, except doc resolution (the point
     * {@code GraphDbDocCacheImpl.get()} does its real, explicit {@code DocumentPermission.USE} check) throws
     * {@code docResolutionError} instead of returning {@link #DOC} - proving what happens to that exception once
     * it reaches {@code createResultStore}.
     */
    private static GraphSearchProvider providerWithThrowingDocCache(final RuntimeException docResolutionError) {
        final CoprocessorsFactory coprocessorsFactory = new CoprocessorsFactory(
                new MapDataStoreFactory(SearchResultStoreConfig::new),
                new ExpressionContextFactory(),
                SIZES_PROVIDER,
                () -> DIRECT_EXECUTOR);

        final ResultStoreFactory resultStoreFactory = mock(ResultStoreFactory.class);
        when(resultStoreFactory.create(any(), any()))
                .thenAnswer(inv -> new ResultStore(
                        inv.getArgument(0),
                        SIZES_PROVIDER,
                        null,
                        inv.getArgument(1),
                        "node",
                        new ResultStoreSettingsFactory().get(),
                        new MapDataStoreFactory(SearchResultStoreConfig::new),
                        new ExpressionPredicateFactory(),
                        () -> DIRECT_EXECUTOR));

        final GraphDbDocCache graphDbDocCache = mock(GraphDbDocCache.class);
        when(graphDbDocCache.get(DOC.getName())).thenThrow(docResolutionError);

        final SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.useAsReadResult(any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());

        return new GraphSearchProvider(
                graphDbDocCache,
                mock(GraphDbDocStore.class),
                mock(GraphStoreManager.class),
                coprocessorsFactory,
                resultStoreFactory,
                new ExpressionPredicateFactory(),
                securityContext,
                mock(FieldInfoResultPageFactory.class),
                mock(DocFinder.class));
    }

    private static Column column(final String name) {
        return Column.builder().id(name).name(name).expression(name).build();
    }

    private static SearchRequest requestFor(final String cypher) {
        final TableSettings tableSettings = TableSettings.builder()
                .addColumns(column("a.id"))
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
                .key(new QueryKey("test-graph"))
                .query(Query.builder()
                        .dataSource(DOC_REF)
                        .graphSpec(GraphSpec.builder().cypher(cypher).build())
                        .build())
                .resultRequests(List.of(tableResultRequest))
                .dateTimeSettings(DateTimeSettings.builder().referenceTime(0L).build())
                .incremental(false)
                .build();
    }

    /**
     * Builds a {@link SearchRequest} the way {@code QueryServiceImpl.mapRequest} actually seeds one for a
     * submitted Cypher query - via the real {@link GraphCypherQueryCompiler}, which derives {@code resultRequests}
     * from the compiled {@code RETURN} clause's columns (see {@code CypherCompiler.buildResultRequests}) - rather
     * than {@link #requestFor}'s single hand-built {@code "a.id"} column, so a multi-column or aggregate
     * {@code RETURN} gets real, compiler-derived columns.
     */
    private static SearchRequest compilerDerivedRequest(final String cypher) {
        final SearchRequest seed = SearchRequest.builder()
                .searchRequestSource(SearchRequestSource.createBasic())
                .key(new QueryKey("test-graph"))
                .query(Query.builder().dataSource(DOC_REF).build())
                .dateTimeSettings(DateTimeSettings.builder().referenceTime(0L).build())
                .incremental(false)
                .build();
        return new GraphCypherQueryCompiler(mock(DocFinder.class)).create(cypher, seed, new ExpressionContext());
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

    private static void seedDeviceConnectedToAccounts(final GraphStores stores) {
        final long deviceLabel = intern(stores, stores.getLabelUids(), "Device");
        final long accountLabel = intern(stores, stores.getLabelUids(), "Account");
        final long idKey = intern(stores, stores.getPropertyKeyUids(), "id");
        final long connectedTo = intern(stores, stores.getEdgeTypeUids(), "CONNECTED_TO");

        final long deviceUid = intern(stores, stores.getNodeUids(), "d-42");
        final long accountAUid = intern(stores, stores.getNodeUids(), "account-a");
        final long accountBUid = intern(stores, stores.getNodeUids(), "account-b");

        stores.write(writer -> {
            stores.getNodes().insert(
                    writer, deviceUid, T1, List.of(deviceLabel), Map.of("id", ValString.create("d-42")));
            stores.getNodes().insert(writer, accountAUid, T1, List.of(accountLabel),
                    Map.of("id", ValString.create("account-a"), "balance", ValLong.create(50)));
            stores.getNodes().insert(writer, accountBUid, T1, List.of(accountLabel),
                    Map.of("id", ValString.create("account-b"), "balance", ValLong.create(200)));

            stores.getPropertyIndex().insert(
                    writer, deviceLabel, idKey, "d-42".getBytes(StandardCharsets.UTF_8), deviceUid);

            stores.getOutEdges().insert(writer, deviceUid, connectedTo, accountAUid, T1, Map.of());
            stores.getOutEdges().insert(writer, deviceUid, connectedTo, accountBUid, T2, Map.of());
            return null;
        });
    }

    private static long intern(final GraphStores stores, final UidLookupDb db, final String key) {
        return stores.write(writer -> db.put(writer.getWriteTxn(), directBuffer(key), uidBuffer ->
                UnsignedBytesInstances.ofLength(uidBuffer.remaining())
                        .get(uidBuffer.duplicate())));
    }

    private static ByteBuffer directBuffer(final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }
}
