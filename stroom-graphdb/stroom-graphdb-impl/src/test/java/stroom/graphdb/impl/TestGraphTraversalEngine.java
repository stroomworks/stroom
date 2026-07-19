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
import java.time.Duration;
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
    void bareVariableReturn_throwsRatherThanSilentlyReturningTheSameStringForEveryRow(@TempDir final Path root) {
        // Code-review fix: rowFor() only ever populates "variable.property" keys, never a bare "variable" key,
        // so RETURN of a bare pattern variable (no property access) previously fell through evaluate()'s
        // row.containsKey check and silently returned the literal string "d" for every single row - not a
        // per-row identifier, the same fixed value regardless of which node actually matched. Now throws
        // instead, matching this class's own stated "throw rather than a wrong result" philosophy.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph3b"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile("MATCH (d:Device {id: 'd-42'}) RETURN d");

            assertThatThrownBy(() -> stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build())))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("bare pattern variable");
        }
    }

    @Test
    void aroundClause_returnsNeighboursWhoseEdgeIntersectsTheWindow(@TempDir final Path root) {
        // Task P4.2: before this, any AROUND/BETWEEN clause threw UnsupportedOperationException.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph4"), DOC)) {
            seedDeviceWindowedAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            // Window [2025-06-01, 2026-06-01]: account-before's edge ended (2025-02-01) before the window
            // starts; account-inside's edge (started 2026-01-01, never tombstoned) intersects it;
            // account-after's edge doesn't start until 2027, after the window ends.
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) "
                    + "BETWEEN datetime('2025-06-01T00:00:00Z') AND datetime('2026-06-01T00:00:00Z') "
                    + "RETURN a.id");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(rows).extracting(row -> row[0].toString()).containsExactly("account-inside");
        }
    }

    @Test
    void aroundClause_windowUpperBoundLandingExactlyOnAVersionsValidFrom_includesIt(@TempDir final Path root) {
        // Task P4.2: an engine-level spot-check of the P0.3 boundary rule already exhaustively unit-tested at
        // the DAO level in Task P4.1 (validFrom == to includes).
        try (GraphStores stores = GraphStores.provision(root.resolve("graph4b"), DOC)) {
            seedDeviceWindowedAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) "
                    + "BETWEEN datetime('2025-06-01T00:00:00Z') AND datetime('2026-01-01T00:00:00Z') "
                    + "RETURN a.id");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(rows).extracting(row -> row[0].toString()).containsExactly("account-inside");
        }
    }

    @Test
    void windowClause_reResolvesTheAnchorAgainstTheWindow_notLatest(@TempDir final Path root) {
        // Task P4.2: resolveAnchors must switch to the window-based node lookup too, not just hop expansion.
        // The device's id changes from 'd-42' to 'd-42-renamed' at 2022-01-01, long after the test window - if
        // anchor re-validation incorrectly fell back to a "latest" floor lookup instead of the window lookup, it
        // would see 'd-42-renamed' (not matching this MATCH's anchor property) and find no anchor at all.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph4c"), DOC)) {
            seedDeviceWithChangingIdentity(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) "
                    + "BETWEEN datetime('2020-01-01T00:00:00Z') AND datetime('2020-06-01T00:00:00Z') "
                    + "RETURN a.id");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(rows).extracting(row -> row[0].toString()).containsExactly("only-account");
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
    void variableLengthPath_overACyclicGraphTerminatesWithTheCorrectReachableSet(@TempDir final Path root) {
        // Task P3.3: a genuine cycle (n1 -> n2 -> n3 -> n1). The maxHops bound alone guarantees termination,
        // but the per-path visited-set cycle guard is what keeps the RESULT correct - without it, this would
        // keep re-walking the cycle and emit spurious rows all the way out to depth 5.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph13"), DOC)) {
            seedCyclicChain(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (a:Node {id: 'n1'})-[:NEXT*1..5]->(b:Node) RETURN b.id");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(rows).extracting(row -> row[0].toString()).containsExactlyInAnyOrder("n2", "n3");
        }
    }

    @Test
    void variableLengthPath_sameNodeReachedAtTwoDepths_yieldsTwoRows(@TempDir final Path root) {
        // Task P3.3: a -> x (1 hop) and a -> y -> x (2 hops) both reach x - Cypher path semantics (not
        // deduplicated by node identity) mean x must appear twice, once per depth.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph14"), DOC)) {
            seedConvergingPaths(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (a:Node {id: 'a'})-[:T*1..3]->(b:Node) RETURN b.id");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(rows).extracting(row -> row[0].toString()).containsExactlyInAnyOrder("x", "y", "x");
        }
    }

    @Test
    void variableLengthPath_minHopsGreaterThanOne_excludesCloserNeighbours(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph15"), DOC)) {
            seedConvergingPaths(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (a:Node {id: 'a'})-[:T*2..3]->(b:Node) RETURN b.id");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(rows).extracting(row -> row[0].toString()).containsExactly("x");
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

    @Test
    void limitClause_stopsAccumulatingRowsOnceSatisfied(@TempDir final Path root) {
        // Task P7.2: before this, unwrap() walked past the compiled Limit node without ever reading its value -
        // the traversal engine computed every matching row regardless of a query's own LIMIT.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph16"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN a.id LIMIT 1");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            // Two accounts match without the LIMIT (see singleHopMatchReturn_yieldsTheExpectedRows_bothLatestAndAsOf).
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()[0].toString()).isIn("account-a", "account-b");
        }
    }

    @Test
    void variableLengthPath_hopRangeAboveTheCeiling_throwsImmediately(@TempDir final Path root) {
        // Task P7.2: Cypher.g4 forbids the unbounded -[:T*]-> form, but placed no ceiling on an explicit range -
        // -[:NEXT*1..51]-> (above MAX_VAR_LENGTH_HOPS = 50) was previously accepted and attempted verbatim.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph17"), DOC)) {
            seedCyclicChain(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (a:Node {id: 'n1'})-[:NEXT*1..51]->(b:Node) RETURN b.id");

            assertThatThrownBy(() -> stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build())))
                    .isInstanceOf(GraphTraversalLimitExceededException.class);
        }
    }

    @Test
    void variableLengthPath_exceedingThePathStateBudget_throwsClearly(@TempDir final Path root) {
        // Task P7.2: a modest hop range against a high-fan-out node can still explore an exponential number of
        // paths - the total-path-state ceiling guards this independently of the hop-range ceiling above. Uses
        // the (package-private, test-only) 3-arg constructor to make a tiny budget reachable over
        // seedConvergingPaths' small fixture (node "a" has two outgoing edges) rather than needing hundreds of
        // thousands of edges to reach the real production default.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph18"), DOC)) {
            seedConvergingPaths(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory(), 2);
            final CompiledCypherPlan compiled = compile(
                    "MATCH (a:Node {id: 'a'})-[:T*1..3]->(b:Node) RETURN b.id");

            assertThatThrownBy(() -> stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build())))
                    .isInstanceOf(GraphTraversalLimitExceededException.class);
        }
    }

    @Test
    void wallClockDeadline_boundsASingleHopWithNoLimit_throwsClearly(@TempDir final Path root) {
        // Code-review fix: the wall-clock deadline used to be checked only once per hop, so a single hop with a
        // wide fan-out and no LIMIT - the exact scenario MAX_TRAVERSAL_DURATION's own Javadoc names as its reason
        // for existing - was never actually bounded, since the whole neighbour scan for that one hop ran between
        // deadline checks. Uses the (package-private, test-only) 4-arg constructor with a zero-duration budget so
        // the deadline is already passed by the time the first neighbour is visited, deterministically, over
        // seedDeviceConnectedToAccounts' small fixture rather than needing a genuinely slow query.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph19"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory(), Long.MAX_VALUE, Duration.ZERO);
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN a.id");

            assertThatThrownBy(() -> stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build())))
                    .isInstanceOf(GraphTraversalLimitExceededException.class);
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

    /** Task P3.3's cyclic fixture: {@code n1 -NEXT-> n2 -NEXT-> n3 -NEXT-> n1}. */
    private static void seedCyclicChain(final GraphStores stores) {
        final long nodeLabel = intern(stores, stores.getLabelUids(), "Node");
        final long idKey = intern(stores, stores.getPropertyKeyUids(), "id");
        final long next = intern(stores, stores.getEdgeTypeUids(), "NEXT");

        final long n1Uid = intern(stores, stores.getNodeUids(), "cycle-n1");
        final long n2Uid = intern(stores, stores.getNodeUids(), "cycle-n2");
        final long n3Uid = intern(stores, stores.getNodeUids(), "cycle-n3");

        stores.write(writer -> {
            stores.getNodes().insert(writer, n1Uid, T1, List.of(nodeLabel), Map.of("id", ValString.create("n1")));
            stores.getNodes().insert(writer, n2Uid, T1, List.of(nodeLabel), Map.of("id", ValString.create("n2")));
            stores.getNodes().insert(writer, n3Uid, T1, List.of(nodeLabel), Map.of("id", ValString.create("n3")));

            stores.getPropertyIndex().insert(
                    writer, nodeLabel, idKey, "n1".getBytes(StandardCharsets.UTF_8), n1Uid);

            stores.getOutEdges().insert(writer, n1Uid, next, n2Uid, T1, Map.of());
            stores.getInEdges().insert(writer, n1Uid, next, n2Uid, T1, Map.of());
            stores.getOutEdges().insert(writer, n2Uid, next, n3Uid, T1, Map.of());
            stores.getInEdges().insert(writer, n2Uid, next, n3Uid, T1, Map.of());
            stores.getOutEdges().insert(writer, n3Uid, next, n1Uid, T1, Map.of());
            stores.getInEdges().insert(writer, n3Uid, next, n1Uid, T1, Map.of());
            return null;
        });
    }

    /**
     * Task P3.3's converging-paths fixture: {@code a -T-> x} (1 hop) and {@code a -T-> y -T-> x} (2 hops) both
     * reach {@code x}, at two different depths, via two distinct paths.
     */
    private static void seedConvergingPaths(final GraphStores stores) {
        final long nodeLabel = intern(stores, stores.getLabelUids(), "Node");
        final long idKey = intern(stores, stores.getPropertyKeyUids(), "id");
        final long edgeType = intern(stores, stores.getEdgeTypeUids(), "T");

        final long aUid = intern(stores, stores.getNodeUids(), "conv-a");
        final long xUid = intern(stores, stores.getNodeUids(), "conv-x");
        final long yUid = intern(stores, stores.getNodeUids(), "conv-y");

        stores.write(writer -> {
            stores.getNodes().insert(writer, aUid, T1, List.of(nodeLabel), Map.of("id", ValString.create("a")));
            stores.getNodes().insert(writer, xUid, T1, List.of(nodeLabel), Map.of("id", ValString.create("x")));
            stores.getNodes().insert(writer, yUid, T1, List.of(nodeLabel), Map.of("id", ValString.create("y")));

            stores.getPropertyIndex().insert(writer, nodeLabel, idKey, "a".getBytes(StandardCharsets.UTF_8), aUid);

            stores.getOutEdges().insert(writer, aUid, edgeType, xUid, T1, Map.of());
            stores.getInEdges().insert(writer, aUid, edgeType, xUid, T1, Map.of());
            stores.getOutEdges().insert(writer, aUid, edgeType, yUid, T1, Map.of());
            stores.getInEdges().insert(writer, aUid, edgeType, yUid, T1, Map.of());
            stores.getOutEdges().insert(writer, yUid, edgeType, xUid, T1, Map.of());
            stores.getInEdges().insert(writer, yUid, edgeType, xUid, T1, Map.of());
            return null;
        });
    }

    /**
     * Task P4.2's window fixture: {@code d-42}'s edges to three accounts, each with a distinct relationship to
     * the test windows - {@code account-before}'s edge ended (2025-02-01) before any test window starts,
     * {@code account-inside}'s edge (started 2026-01-01, never tombstoned) intersects every test window,
     * {@code account-after}'s edge doesn't start until 2027, after every test window ends. The device itself has
     * a single, unchanging identity throughout - {@link #seedDeviceWithChangingIdentity} covers the anchor's own
     * window re-resolution separately, so this fixture isn't burdened with that concern too.
     */
    private static void seedDeviceWindowedAccounts(final GraphStores stores) {
        final long deviceLabel = intern(stores, stores.getLabelUids(), "Device");
        final long accountLabel = intern(stores, stores.getLabelUids(), "Account");
        final long idKey = intern(stores, stores.getPropertyKeyUids(), "id");
        final long connectedTo = intern(stores, stores.getEdgeTypeUids(), "CONNECTED_TO");

        final long deviceUid = intern(stores, stores.getNodeUids(), "window-d-42");
        final long beforeUid = intern(stores, stores.getNodeUids(), "account-before");
        final long insideUid = intern(stores, stores.getNodeUids(), "account-inside");
        final long afterUid = intern(stores, stores.getNodeUids(), "account-after");

        final Instant deviceStart = Instant.parse("2019-01-01T00:00:00Z");
        final Instant beforeStart = Instant.parse("2025-01-01T00:00:00Z");
        final Instant beforeEnd = Instant.parse("2025-02-01T00:00:00Z");
        final Instant insideStart = Instant.parse("2026-01-01T00:00:00Z");
        final Instant afterStart = Instant.parse("2027-01-01T00:00:00Z");

        stores.write(writer -> {
            stores.getNodes().insert(
                    writer, deviceUid, deviceStart, List.of(deviceLabel), Map.of("id", ValString.create("d-42")));
            stores.getNodes().insert(writer, beforeUid, deviceStart, List.of(accountLabel),
                    Map.of("id", ValString.create("account-before")));
            stores.getNodes().insert(writer, insideUid, deviceStart, List.of(accountLabel),
                    Map.of("id", ValString.create("account-inside")));
            stores.getNodes().insert(writer, afterUid, deviceStart, List.of(accountLabel),
                    Map.of("id", ValString.create("account-after")));

            stores.getPropertyIndex().insert(
                    writer, deviceLabel, idKey, "d-42".getBytes(StandardCharsets.UTF_8), deviceUid);

            stores.getOutEdges().insert(writer, deviceUid, connectedTo, beforeUid, beforeStart, Map.of());
            stores.getInEdges().insert(writer, deviceUid, connectedTo, beforeUid, beforeStart, Map.of());
            stores.getOutEdges().delete(writer, deviceUid, connectedTo, beforeUid, beforeEnd);
            stores.getInEdges().delete(writer, deviceUid, connectedTo, beforeUid, beforeEnd);

            stores.getOutEdges().insert(writer, deviceUid, connectedTo, insideUid, insideStart, Map.of());
            stores.getInEdges().insert(writer, deviceUid, connectedTo, insideUid, insideStart, Map.of());

            stores.getOutEdges().insert(writer, deviceUid, connectedTo, afterUid, afterStart, Map.of());
            stores.getInEdges().insert(writer, deviceUid, connectedTo, afterUid, afterStart, Map.of());
            return null;
        });
    }

    /**
     * Task P4.2's anchor-re-resolution fixture: {@code d-42} carries {@code id: 'd-42'} from 2020-01-01, then
     * {@code id: 'd-42-renamed'} from 2022-01-01 onward (its LATEST identity) - a query anchored on the OLD id,
     * windowed entirely within the earlier period, must still resolve the anchor by consulting that period's
     * version, not the node's current one.
     */
    private static void seedDeviceWithChangingIdentity(final GraphStores stores) {
        final long deviceLabel = intern(stores, stores.getLabelUids(), "Device");
        final long accountLabel = intern(stores, stores.getLabelUids(), "Account");
        final long idKey = intern(stores, stores.getPropertyKeyUids(), "id");
        final long connectedTo = intern(stores, stores.getEdgeTypeUids(), "CONNECTED_TO");

        final long deviceUid = intern(stores, stores.getNodeUids(), "renaming-d-42");
        final long accountUid = intern(stores, stores.getNodeUids(), "only-account");

        final Instant oldIdentityStart = Instant.parse("2020-01-01T00:00:00Z");
        final Instant newIdentityStart = Instant.parse("2022-01-01T00:00:00Z");

        stores.write(writer -> {
            stores.getNodes().insert(
                    writer, deviceUid, oldIdentityStart, List.of(deviceLabel), Map.of("id", ValString.create("d-42")));
            stores.getNodes().insert(writer, deviceUid, newIdentityStart, List.of(deviceLabel),
                    Map.of("id", ValString.create("d-42-renamed")));
            stores.getNodes().insert(
                    writer, accountUid, oldIdentityStart, List.of(accountLabel),
                    Map.of("id", ValString.create("only-account")));

            // The property index has no time dimension (Task PoC.4) - it tracks every value the device's id
            // property has ever held, so this seek still finds deviceUid even though 'd-42' isn't its current
            // identity; the interesting behaviour under test is entirely in getNodeWindow's re-validation.
            stores.getPropertyIndex().insert(
                    writer, deviceLabel, idKey, "d-42".getBytes(StandardCharsets.UTF_8), deviceUid);

            stores.getOutEdges().insert(writer, deviceUid, connectedTo, accountUid, oldIdentityStart, Map.of());
            stores.getInEdges().insert(writer, deviceUid, connectedTo, accountUid, oldIdentityStart, Map.of());
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
