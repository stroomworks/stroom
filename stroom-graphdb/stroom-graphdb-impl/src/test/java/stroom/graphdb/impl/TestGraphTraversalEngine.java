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

import stroom.graphdb.shared.GraphDbDoc;
import stroom.lmdb.serde.UnsignedBytesInstances;
import stroom.planb.impl.dao.UidLookupDb;
import stroom.query.api.DateTimeSettings;
import stroom.query.common.v2.ExpressionPredicateFactory;
import stroom.query.grammar.parse.CypherQueryParser;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValLong;
import stroom.query.language.functions.ValString;
import stroom.query.planner.cypher.CompiledCypherPlan;
import stroom.query.planner.cypher.CypherToLogicalPlan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task PoC.5's Done-when: a single-hop {@code MATCH...RETURN} query, parsed and compiled by the real
 * {@link CypherQueryParser}/{@link CypherToLogicalPlan}, executes against real {@link GraphStores} fixtures
 * (Task PoC.4) and yields the expected {@code Val[]} rows - both for "latest" and for an {@code AS OF} instant.
 */
class TestGraphTraversalEngine {

    private static final GraphDbDoc DOC = GraphDbDoc.builder().uuid("graph-uuid").name("TestGraph").build();
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00.000Z");
    private static final Instant T2 = Instant.parse("2026-06-01T00:00:00.000Z");

    @Test
    void singleHopMatchReturn_yieldsTheExpectedRows_bothLatestAndAsOf(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN a.id");

            // "Latest" (no temporal clause): both accounts are reachable.
            final List<Val[]> latestRows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(latestRows).extracting(row -> row[0].toString())
                    .containsExactlyInAnyOrder("account-a", "account-b");

            // AS OF a time before account-b's edge existed: only account-a is reachable.
            final CompiledCypherPlan asOfEarlier = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) "
                    + "AS OF datetime('2026-02-01T00:00:00Z') RETURN a.id");
            final List<Val[]> earlyRows = stores.read(readTxn ->
                    engine.execute(readTxn, asOfEarlier.plan(), asOfEarlier.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(earlyRows).extracting(row -> row[0].toString()).containsExactly("account-a");
        }
    }

    @Test
    void whereClause_filtersOutRowsThatDoNotMatch(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph2"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) "
                    + "WHERE a.balance > 100 RETURN a.id");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(rows).extracting(row -> row[0].toString()).containsExactly("account-b");
        }
    }

    @Test
    void bareAnchorWithNoHop_returnsJustTheAnchorRow(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph3"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile("MATCH (d:Device {id: 'd-42'}) RETURN d.id");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()[0].toString()).isEqualTo("d-42");
        }
    }

    @Test
    void aroundClause_throwsNotYetSupported(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph4"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) "
                    + "AROUND datetime('2026-07-01T09:00:00Z') +/- duration('PT1H') RETURN a.id");

            assertThatThrownBy(() -> stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build())))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("P4");
        }
    }

    @Test
    void reverseDirectionMatchReturn_yieldsTheExpectedRows(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph5"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            // account-a <- d-42: the same logical edge as the forward test, approached from the other end.
            final CompiledCypherPlan compiled = compile(
                    "MATCH (a:Account {id: 'account-a'})<-[:CONNECTED_TO]-(d:Device) RETURN d.id");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(rows).extracting(row -> row[0].toString()).containsExactly("d-42");
        }
    }

    @Test
    void hopTargetLabelConstraint_filtersOutNeighboursMissingThatLabel(@TempDir final Path root) {
        // Task P3.1: before this, a hop target's own label constraint was silently unenforced - a query like
        // this one would previously have returned both accounts, not just the one carrying both labels.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph7"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account:Premium) RETURN a.id");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(rows).extracting(row -> row[0].toString()).containsExactly("account-b");
        }
    }

    @Test
    void hopTargetLabelConstraint_unknownLabelYieldsNoRows(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph8"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:NoSuchLabel) RETURN a.id");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(rows).isEmpty();
        }
    }

    @Test
    void hopTargetPropertyConstraint_filtersOutNeighboursNotMatchingIt(@TempDir final Path root) {
        // Task P3.1: before this, a hop target's own inline property map was silently unenforced.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph9"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account {balance: 200}) RETURN a.id");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(rows).extracting(row -> row[0].toString()).containsExactly("account-b");
        }
    }

    @Test
    void twoHopChain_yieldsExpectedRowsAcrossBothHops(@TempDir final Path root) {
        // Task P3.2: before this, CypherToLogicalPlan rejected any pattern with more than one hop outright.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph10"), DOC)) {
            seedDeviceAccountOwnerChain(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account)-[:OWNED_BY]->(o:Owner) "
                    + "RETURN o.id");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(rows).extracting(row -> row[0].toString())
                    .containsExactlyInAnyOrder("owner-x", "owner-y");
        }
    }

    @Test
    void twoHopChain_middleHopTargetConstraintPrunesAPath(@TempDir final Path root) {
        // A middle hop's own target constraint (P3.1) must be enforced too, not only the pattern's last hop -
        // only account-b carries the Premium label, so the path through account-a must not survive.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph11"), DOC)) {
            seedDeviceAccountOwnerChain(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account:Premium)-[:OWNED_BY]->(o:Owner) "
                    + "RETURN o.id");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(rows).extracting(row -> row[0].toString()).containsExactly("owner-y");
        }
    }

    @Test
    void threeHopChain_yieldsExpectedRow(@TempDir final Path root) {
        // Both owner-x and owner-y are employed by the same company, via two distinct 3-hop paths (through
        // account-a and account-b respectively) - Cypher MATCH (with no DISTINCT) yields one row per path, so
        // the same company legitimately appears twice here, not once.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph12"), DOC)) {
            seedDeviceAccountOwnerChain(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account)-[:OWNED_BY]->(o:Owner)"
                    + "-[:EMPLOYED_BY]->(c:Company) RETURN c.id");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(rows).hasSize(2);
            assertThat(rows).allSatisfy(row -> assertThat(row[0].toString()).isEqualTo("company-1"));
        }
    }

    @Test
    void undirectedMatchReturn_yieldsTheUnionOfBothDirections(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph6"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            // d-42 has an outgoing edge to each account and an incoming edge from gw-1 - an undirected pattern
            // must union both, not just the outgoing edges a Direction.OUT-only engine would previously return.
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]-(x) RETURN x.id");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(rows).extracting(row -> row[0].toString())
                    .containsExactlyInAnyOrder("account-a", "account-b", "gw-1");
        }
    }

    private static CompiledCypherPlan compile(final String cypher) {
        return new CypherToLogicalPlan().compile(CypherQueryParser.parse(cypher));
    }

    private static void seedDeviceConnectedToAccounts(final GraphStores stores) {
        final long deviceLabel = intern(stores, stores.getLabelUids(), "Device");
        final long accountLabel = intern(stores, stores.getLabelUids(), "Account");
        final long premiumLabel = intern(stores, stores.getLabelUids(), "Premium");
        final long idKey = intern(stores, stores.getPropertyKeyUids(), "id");
        final long connectedTo = intern(stores, stores.getEdgeTypeUids(), "CONNECTED_TO");

        final long deviceUid = intern(stores, stores.getNodeUids(), "d-42");
        final long accountAUid = intern(stores, stores.getNodeUids(), "account-a");
        final long accountBUid = intern(stores, stores.getNodeUids(), "account-b");
        final long gatewayUid = intern(stores, stores.getNodeUids(), "gw-1");

        stores.write(writer -> {
            stores.getNodes().insert(
                    writer, deviceUid, T1, List.of(deviceLabel), Map.of("id", ValString.create("d-42")));
            stores.getNodes().insert(writer, accountAUid, T1, List.of(accountLabel),
                    Map.of("id", ValString.create("account-a"), "balance", ValLong.create(50)));
            stores.getNodes().insert(writer, accountBUid, T1, List.of(accountLabel, premiumLabel),
                    Map.of("id", ValString.create("account-b"), "balance", ValLong.create(200)));
            stores.getNodes().insert(
                    writer, gatewayUid, T1, List.of(deviceLabel), Map.of("id", ValString.create("gw-1")));

            stores.getPropertyIndex().insert(
                    writer, deviceLabel, idKey, "d-42".getBytes(StandardCharsets.UTF_8), deviceUid);
            stores.getPropertyIndex().insert(
                    writer, accountLabel, idKey, "account-a".getBytes(StandardCharsets.UTF_8), accountAUid);

            // d-42 -> account-a/account-b: written to both directions (P1.1's dual-write contract - callers
            // writing a logical edge must write both GraphAdjacencyDb and GraphInEdgeDb).
            stores.getOutEdges().insert(writer, deviceUid, connectedTo, accountAUid, T1, Map.of());
            stores.getInEdges().insert(writer, deviceUid, connectedTo, accountAUid, T1, Map.of());
            stores.getOutEdges().insert(writer, deviceUid, connectedTo, accountBUid, T2, Map.of());
            stores.getInEdges().insert(writer, deviceUid, connectedTo, accountBUid, T2, Map.of());

            // gw-1 -> d-42: a separate edge INTO the device, so an undirected (BOTH) query from d-42 has both an
            // outgoing (to the accounts) and an incoming (from the gateway) edge to union.
            stores.getOutEdges().insert(writer, gatewayUid, connectedTo, deviceUid, T1, Map.of());
            stores.getInEdges().insert(writer, gatewayUid, connectedTo, deviceUid, T1, Map.of());
            return null;
        });
    }

    /**
     * A 3-hop-deep chain fixture, purpose-built for Task P3.2's chain tests: {@code d-42 -CONNECTED_TO->
     * {account-a, account-b} -OWNED_BY-> {owner-x, owner-y} -EMPLOYED_BY-> company-1} - only account-b carries
     * the {@code Premium} label, so a middle-hop label constraint prunes exactly the account-a path.
     */
    private static void seedDeviceAccountOwnerChain(final GraphStores stores) {
        final long deviceLabel = intern(stores, stores.getLabelUids(), "Device");
        final long accountLabel = intern(stores, stores.getLabelUids(), "Account");
        final long premiumLabel = intern(stores, stores.getLabelUids(), "Premium");
        final long ownerLabel = intern(stores, stores.getLabelUids(), "Owner");
        final long companyLabel = intern(stores, stores.getLabelUids(), "Company");
        final long idKey = intern(stores, stores.getPropertyKeyUids(), "id");
        final long connectedTo = intern(stores, stores.getEdgeTypeUids(), "CONNECTED_TO");
        final long ownedBy = intern(stores, stores.getEdgeTypeUids(), "OWNED_BY");
        final long employedBy = intern(stores, stores.getEdgeTypeUids(), "EMPLOYED_BY");

        final long deviceUid = intern(stores, stores.getNodeUids(), "chain-d-42");
        final long accountAUid = intern(stores, stores.getNodeUids(), "chain-account-a");
        final long accountBUid = intern(stores, stores.getNodeUids(), "chain-account-b");
        final long ownerXUid = intern(stores, stores.getNodeUids(), "owner-x");
        final long ownerYUid = intern(stores, stores.getNodeUids(), "owner-y");
        final long companyUid = intern(stores, stores.getNodeUids(), "company-1");

        stores.write(writer -> {
            stores.getNodes().insert(
                    writer, deviceUid, T1, List.of(deviceLabel), Map.of("id", ValString.create("d-42")));
            stores.getNodes().insert(writer, accountAUid, T1, List.of(accountLabel),
                    Map.of("id", ValString.create("account-a")));
            stores.getNodes().insert(writer, accountBUid, T1, List.of(accountLabel, premiumLabel),
                    Map.of("id", ValString.create("account-b")));
            stores.getNodes().insert(
                    writer, ownerXUid, T1, List.of(ownerLabel), Map.of("id", ValString.create("owner-x")));
            stores.getNodes().insert(
                    writer, ownerYUid, T1, List.of(ownerLabel), Map.of("id", ValString.create("owner-y")));
            stores.getNodes().insert(
                    writer, companyUid, T1, List.of(companyLabel), Map.of("id", ValString.create("company-1")));

            stores.getPropertyIndex().insert(
                    writer, deviceLabel, idKey, "d-42".getBytes(StandardCharsets.UTF_8), deviceUid);

            // Every edge is written to both GraphAdjacencyDb and GraphInEdgeDb (P1.1's dual-write contract).
            stores.getOutEdges().insert(writer, deviceUid, connectedTo, accountAUid, T1, Map.of());
            stores.getInEdges().insert(writer, deviceUid, connectedTo, accountAUid, T1, Map.of());
            stores.getOutEdges().insert(writer, deviceUid, connectedTo, accountBUid, T1, Map.of());
            stores.getInEdges().insert(writer, deviceUid, connectedTo, accountBUid, T1, Map.of());

            stores.getOutEdges().insert(writer, accountAUid, ownedBy, ownerXUid, T1, Map.of());
            stores.getInEdges().insert(writer, accountAUid, ownedBy, ownerXUid, T1, Map.of());
            stores.getOutEdges().insert(writer, accountBUid, ownedBy, ownerYUid, T1, Map.of());
            stores.getInEdges().insert(writer, accountBUid, ownedBy, ownerYUid, T1, Map.of());

            stores.getOutEdges().insert(writer, ownerXUid, employedBy, companyUid, T1, Map.of());
            stores.getInEdges().insert(writer, ownerXUid, employedBy, companyUid, T1, Map.of());
            stores.getOutEdges().insert(writer, ownerYUid, employedBy, companyUid, T1, Map.of());
            stores.getInEdges().insert(writer, ownerYUid, employedBy, companyUid, T1, Map.of());
            return null;
        });
    }

    private static long intern(final GraphStores stores, final UidLookupDb db,
                               final String key) {
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
