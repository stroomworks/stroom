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
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.UidLookupDb;
import stroom.query.api.DateTimeSettings;
import stroom.query.common.v2.ExpressionPredicateFactory;
import stroom.query.grammar.parse.CypherQueryParser;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValDouble;
import stroom.query.language.functions.ValLong;
import stroom.query.language.functions.ValNull;
import stroom.query.language.functions.ValString;
import stroom.query.planner.cypher.CompiledCypherPlan;
import stroom.query.planner.cypher.CypherCompileException;
import stroom.query.planner.cypher.CypherToLogicalPlan;
import stroom.query.planner.cypher.TemporalContext;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

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
    void whereStringPredicates_filterByStartsContainsEndsAndRegex(@TempDir final Path root) {
        // String predicates (STARTS WITH / CONTAINS / ENDS WITH / =~) compile to the shared ExpressionTerm
        // Condition vocabulary, which the engine's WHERE predicate path already evaluates - so this needs no
        // GraphTraversalEngine change, only that the whole pipeline flows through end-to-end.
        try (GraphStores stores = GraphStores.provision(root.resolve("strpred"), DOC)) {
            seedDeviceConnectedToAccounts(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final String prefix = "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) WHERE ";

            // ENDS WITH: only account-b ends in 'b'.
            assertThat(matchedIds(stores, engine, prefix + "a.id ENDS WITH 'b' RETURN a.id"))
                    .containsExactly("account-b");
            // CONTAINS: 'unt-a' occurs only in account-a.
            assertThat(matchedIds(stores, engine, prefix + "a.id CONTAINS 'unt-a' RETURN a.id"))
                    .containsExactly("account-a");
            // STARTS WITH: both accounts start with 'account'.
            assertThat(matchedIds(stores, engine, prefix + "a.id STARTS WITH 'account' RETURN a.id"))
                    .containsExactlyInAnyOrder("account-a", "account-b");
            // =~ regex: '.*-a' anchors to account-a only.
            assertThat(matchedIds(stores, engine, prefix + "a.id =~ '.*-a' RETURN a.id"))
                    .containsExactly("account-a");
        }
    }

    @Test
    void whereInAndIsNull_flowThroughEndToEnd(@TempDir final Path root) {
        // IN / IS NULL / IS NOT NULL compile to the shared Condition vocabulary, already evaluated by the engine's
        // WHERE path - so, like the string predicates, this needs no engine change, only end-to-end flow.
        try (GraphStores stores = GraphStores.provision(root.resolve("inisnull"), DOC)) {
            seedDeviceConnectedToAccounts(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final String prefix = "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) WHERE ";

            // IN: only account-b listed.
            assertThat(matchedIds(stores, engine, prefix + "a.id IN ['account-b'] RETURN a.id"))
                    .containsExactly("account-b");
            // IN: both listed.
            assertThat(matchedIds(stores, engine, prefix + "a.id IN ['account-a', 'account-b'] RETURN a.id"))
                    .containsExactlyInAnyOrder("account-a", "account-b");
            // IN []: matches nothing.
            assertThat(matchedIds(stores, engine, prefix + "a.id IN [] RETURN a.id")).isEmpty();
            // IS NULL on an absent property: both accounts (neither carries 'closed').
            assertThat(matchedIds(stores, engine, prefix + "a.closed IS NULL RETURN a.id"))
                    .containsExactlyInAnyOrder("account-a", "account-b");
            // IS NOT NULL on a present property: both accounts have 'balance'.
            assertThat(matchedIds(stores, engine, prefix + "a.balance IS NOT NULL RETURN a.id"))
                    .containsExactlyInAnyOrder("account-a", "account-b");
            // IS NOT NULL on an absent property: none.
            assertThat(matchedIds(stores, engine, prefix + "a.closed IS NOT NULL RETURN a.id")).isEmpty();
        }
    }

    @Test
    void countDistinct_dedupesValuesWithinAGroup(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("countdistinct"), DOC)) {
            seedOfficerWithRepeatedCrimeTypes(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());

            // o-1 investigates 3 crimes of types {theft, theft, fraud}: count = 3, count(DISTINCT) = 2.
            final CompiledCypherPlan compiled = compile(
                    "MATCH (o:Officer {id: 'o-1'})-[:INVESTIGATED]->(c:Crime) "
                    + "RETURN o.id, count(c.type) AS total, count(DISTINCT c.type) AS distinctTypes");
            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build(), compiled.distinct(), compiled.aggregation()));

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()[1].toString()).isEqualTo("3");
            assertThat(rows.getFirst()[2].toString()).isEqualTo("2");
        }
    }

    /**
     * {@code collect(...)} never reaches the executor: it is rejected while compiling, because without a list value
     * type the only representation available was a comma-joined string. This test used to assert that string.
     *
     * <p>Asserted at the compile step rather than deleted, so that re-enabling {@code collect} makes this fail and
     * forces the executor behaviour to be re-specified rather than silently inherited.</p>
     */
    @Test
    void collect_isRejectedBeforeReachingTheExecutor() {
        assertThatThrownBy(() -> compile(
                "MATCH (o:Officer {id: 'o-1'})-[:INVESTIGATED]->(c:Crime) "
                + "RETURN o.id, collect(c.type) AS allTypes"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("no list value type");
    }

    @Test
    void fieldVsFieldWhere_comparesTwoMatchedProperties(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("fieldvsfield"), DOC)) {
            seedAccountTransfers(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());

            // a1(100) transfers to a2(50) and a3(200). a.balance > b.balance selects only the a2 transfer.
            assertThat(runFieldComparison(stores, engine,
                    "MATCH (a:Account {id: 'a1'})-[:TRANSFER]->(b:Account) "
                    + "WHERE a.balance > b.balance RETURN b.id"))
                    .containsExactly("a2");
            // a.balance < b.balance selects only the a3 transfer.
            assertThat(runFieldComparison(stores, engine,
                    "MATCH (a:Account {id: 'a1'})-[:TRANSFER]->(b:Account) "
                    + "WHERE a.balance < b.balance RETURN b.id"))
                    .containsExactly("a3");
        }
    }

    private static List<String> runFieldComparison(final GraphStores stores, final GraphTraversalEngine engine,
                                                   final String cypher) {
        final CompiledCypherPlan compiled = compile(cypher);
        final List<Val[]> rows = stores.read(readTxn ->
                engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                        DateTimeSettings.builder().build(), compiled.distinct(), compiled.aggregation(),
                        compiled.fieldComparisons(), compiled.existsPredicates()));
        return rows.stream().map(row -> row[0].toString()).toList();
    }

    private static void seedAccountTransfers(final GraphStores stores) {
        final long accountLabel = intern(stores, stores.getLabelUids(), "Account");
        final long idKey = intern(stores, stores.getPropertyKeyUids(), "id");
        final long transfer = intern(stores, stores.getEdgeTypeUids(), "TRANSFER");
        final long a1 = intern(stores, stores.getNodeUids(), "a1");
        final long a2 = intern(stores, stores.getNodeUids(), "a2");
        final long a3 = intern(stores, stores.getNodeUids(), "a3");

        stores.write(writer -> {
            stores.getNodes().insert(writer, a1, T1, List.of(accountLabel),
                    Map.of("id", ValString.create("a1"), "balance", ValLong.create(100)));
            stores.getNodes().insert(writer, a2, T1, List.of(accountLabel),
                    Map.of("id", ValString.create("a2"), "balance", ValLong.create(50)));
            stores.getNodes().insert(writer, a3, T1, List.of(accountLabel),
                    Map.of("id", ValString.create("a3"), "balance", ValLong.create(200)));
            stores.getPropertyIndex().insert(
                    writer, accountLabel, idKey, anchorBytes("a1"), a1);
            stores.getOutEdges().insert(writer, a1, transfer, a2, T1, Map.of());
            stores.getInEdges().insert(writer, a1, transfer, a2, T1, Map.of());
            stores.getOutEdges().insert(writer, a1, transfer, a3, T1, Map.of());
            stores.getInEdges().insert(writer, a1, transfer, a3, T1, Map.of());
            return null;
        });
    }

    @Test
    void optionalMatch_includesUnmatchedAnchorWithZeroCountAndNullProjection(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("optionalmatch"), DOC)) {
            seedPersonsAndCrimes(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());

            // p1 has two crimes: count(c) = 2.
            final List<Val[]> p1 = runFull(stores, engine,
                    "MATCH (p:Person {id: 'p1'}) OPTIONAL MATCH (p)-[:PARTY_TO]->(c:Crime) "
                    + "RETURN p.id, count(c) AS n");
            assertThat(p1).hasSize(1);
            assertThat(p1.getFirst()[0].toString()).isEqualTo("p1");
            assertThat(p1.getFirst()[1].toString()).isEqualTo("2");

            // p2 has no crimes: still appears, count 0 (left-outer via the bound marker).
            final List<Val[]> p2 = runFull(stores, engine,
                    "MATCH (p:Person {id: 'p2'}) OPTIONAL MATCH (p)-[:PARTY_TO]->(c:Crime) "
                    + "RETURN p.id, count(c) AS n");
            assertThat(p2).hasSize(1);
            assertThat(p2.getFirst()[0].toString()).isEqualTo("p2");
            assertThat(p2.getFirst()[1].toString()).isEqualTo("0");

            // p2's unmatched optional property projects as null (row kept, not an error).
            final List<Val[]> proj = runFull(stores, engine,
                    "MATCH (p:Person {id: 'p2'}) OPTIONAL MATCH (p)-[:PARTY_TO]->(c:Crime) RETURN p.id, c.type");
            assertThat(proj).hasSize(1);
            assertThat(proj.getFirst()[0].toString()).isEqualTo("p2");
            assertThat(proj.getFirst()[1]).isEqualTo(ValNull.INSTANCE);
        }
    }

    @Test
    void scalarFunctions_evaluateOverRows(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("scalarfns"), DOC)) {
            seedDeviceConnectedToAccounts(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());

            // stroom.upperCase over a matched property value.
            final List<Val[]> upper = runFull(stores, engine,
                    "MATCH (a:Account {id: 'account-a'}) RETURN stroom.upperCase(a.id)");
            assertThat(upper).hasSize(1);
            assertThat(upper.getFirst()[0].toString()).isEqualTo("ACCOUNT-A");
        }
    }

    @Test
    void cypherExactFunctions_evaluateWithCypherSemantics_bothFlavoursCoexist(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("cypherfns"), DOC)) {
            seedDeviceConnectedToAccounts(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final String anchor = "MATCH (a:Account {id: 'account-a'}) RETURN ";

            // Cypher substring(s, start, length): 'ell' (3 chars from index 1) - NOT Stroom's 'el' (end index 3).
            assertThat(one(stores, engine, anchor + "substring('hello', 1, 3)")).isEqualTo("ell");
            // Stroom's own substring (end-index) stays reachable as stroom.substring - both flavours available.
            assertThat(one(stores, engine, anchor + "stroom.substring('hello', 1, 3)")).isEqualTo("el");

            assertThat(one(stores, engine, anchor + "left('hello', 2)")).isEqualTo("he");
            assertThat(one(stores, engine, anchor + "right('hello', 2)")).isEqualTo("lo");
            assertThat(one(stores, engine, anchor + "size('hello')")).isEqualTo("5");
            // Bare Cypher toUpper and namespaced Stroom stroom.upperCase both work.
            assertThat(one(stores, engine, anchor + "toUpper('hi')")).isEqualTo("HI");
            assertThat(one(stores, engine, anchor + "stroom.upperCase('hi')")).isEqualTo("HI");
        }
    }

    @Test
    void generalMathsFunctions_evaluateOverRows(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("mathsfns"), DOC)) {
            seedDeviceConnectedToAccounts(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            // account-a's balance is 50; operands are property-based so the arithmetic feeds real numeric values in.
            final String anchor = "MATCH (a:Account {id: 'account-a'}) RETURN ";

            assertThat(one(stores, engine, anchor + "abs(a.balance - 60)")).isEqualTo("10");
            assertThat(one(stores, engine, anchor + "sqrt(a.balance - 34)")).isEqualTo("4");
            assertThat(one(stores, engine, anchor + "sign(a.balance - 60)")).isEqualTo("-1");
            // exp/log are natural-base; exact at these inputs (exp(0)=1, log(1)=0).
            assertThat(one(stores, engine, anchor + "exp(a.balance - 50)")).isEqualTo("1");
            assertThat(one(stores, engine, anchor + "log(a.balance - 49)")).isEqualTo("0");
            // Reachable under the stroom.* namespace too.
            assertThat(one(stores, engine, anchor + "stroom.abs(a.balance - 60)")).isEqualTo("10");
        }
    }

    @Test
    void cypherCoalesce_returnsFirstNonNull_overOptionalMatchNull(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("coalescefn"), DOC)) {
            seedPersonsAndCrimes(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            // p2 has no crime, so c.type is null; coalesce falls through to 'none'.
            assertThat(one(stores, engine,
                    "MATCH (p:Person {id: 'p2'}) OPTIONAL MATCH (p)-[:PARTY_TO]->(c:Crime) "
                    + "RETURN coalesce(c.type, 'none')")).isEqualTo("none");
        }
    }

    private static String one(final GraphStores stores, final GraphTraversalEngine engine, final String cypher) {
        final List<Val[]> rows = runFull(stores, engine, cypher);
        assertThat(rows).hasSize(1);
        return rows.getFirst()[0].toString();
    }

    private static double oneDouble(final GraphStores stores, final GraphTraversalEngine engine,
                                    final String cypher) {
        final List<Val[]> rows = runFull(stores, engine, cypher);
        assertThat(rows).hasSize(1);
        return rows.getFirst()[0].toDouble();
    }

    @Test
    void existsSubquery_filtersByCorrelatedAdjacency(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("exists"), DOC)) {
            seedPersonsAndCrimes(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            // p1 has PARTY_TO edges to crimes; p2 has none.
            assertThat(runFull(stores, engine,
                    "MATCH (p:Person) WHERE EXISTS { (p)-[:PARTY_TO]->(:Crime) } RETURN p.id"))
                    .extracting(row -> row[0].toString()).containsExactly("p1");
            // NOT EXISTS selects the complement.
            assertThat(runFull(stores, engine,
                    "MATCH (p:Person) WHERE NOT EXISTS { (p)-[:PARTY_TO]->(:Crime) } RETURN p.id"))
                    .extracting(row -> row[0].toString()).containsExactly("p2");
            // Inner target property constraint: p1 has a theft, no assault.
            assertThat(runFull(stores, engine,
                    "MATCH (p:Person) WHERE EXISTS { (p)-[:PARTY_TO]->(c:Crime {type: 'theft'}) } RETURN p.id"))
                    .extracting(row -> row[0].toString()).containsExactly("p1");
            assertThat(runFull(stores, engine,
                    "MATCH (p:Person) WHERE EXISTS { (p)-[:PARTY_TO]->(c:Crime {type: 'assault'}) } RETURN p.id"))
                    .isEmpty();
            // Combined with a literal predicate via AND, and works over a specific anchored node too.
            assertThat(runFull(stores, engine,
                    "MATCH (p:Person) WHERE p.id = 'p1' AND EXISTS { (p)-[:PARTY_TO]->(:Crime) } RETURN p.id"))
                    .extracting(row -> row[0].toString()).containsExactly("p1");
            assertThat(runFull(stores, engine,
                    "MATCH (p:Person {id: 'p2'}) WHERE NOT EXISTS { (p)-[:PARTY_TO]->(:Crime) } RETURN p.id"))
                    .extracting(row -> row[0].toString()).containsExactly("p2");
        }
    }

    @Test
    void labelOnlyMatch_scansAllNodesOfTheLabel(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("labelscan"), DOC)) {
            seedDeviceConnectedToAccounts(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());

            // No anchor property, no hop: scan every Account node (account-a, account-b).
            assertThat(matchedIds(stores, engine, "MATCH (a:Account) RETURN a.id"))
                    .containsExactlyInAnyOrder("account-a", "account-b");
            // A WHERE still filters the scanned rows (account-b's balance is 200).
            assertThat(matchedIds(stores, engine, "MATCH (a:Account) WHERE a.balance > 100 RETURN a.id"))
                    .containsExactly("account-b");
            // A secondary label (account-b also carries Premium) is a valid scan target.
            assertThat(matchedIds(stores, engine, "MATCH (a:Premium) RETURN a.id"))
                    .containsExactly("account-b");
            // Both Device nodes are scanned (d-42 and gw-1).
            assertThat(matchedIds(stores, engine, "MATCH (d:Device) RETURN d.id"))
                    .containsExactlyInAnyOrder("d-42", "gw-1");
            // A label-only scan is a valid traversal anchor too: scan every Device, expand out over CONNECTED_TO.
            // d-42 -> account-a/account-b; gw-1 -> d-42 (a Device, filtered out by the :Account target label).
            assertThat(matchedIds(stores, engine,
                    "MATCH (d:Device)-[:CONNECTED_TO]->(a:Account) RETURN a.id"))
                    .containsExactlyInAnyOrder("account-a", "account-b");
            // An unknown label matches nothing (fails closed, not loud).
            assertThat(matchedIds(stores, engine, "MATCH (a:Ghost) RETURN a.id")).isEmpty();
        }
    }

    @Test
    void caseExpression_evaluatesOverRows(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("casefns"), DOC)) {
            seedDeviceConnectedToAccounts(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            // account-a's balance is 50.
            final String anchor = "MATCH (a:Account {id: 'account-a'}) RETURN ";

            // Searched CASE: 50 is not > 100 but is > 10 -> 'ok'.
            assertThat(one(stores, engine, anchor
                    + "CASE WHEN a.balance > 100 THEN 'rich' WHEN a.balance > 10 THEN 'ok' ELSE 'poor' END"))
                    .isEqualTo("ok");
            // Simple CASE: input matches the second-arm value.
            assertThat(one(stores, engine, anchor
                    + "CASE a.id WHEN 'nobody' THEN 'x' WHEN 'account-a' THEN 'me' ELSE 'other' END"))
                    .isEqualTo("me");
            // Boolean-combined condition + arithmetic result.
            assertThat(oneDouble(stores, engine, anchor
                    + "CASE WHEN a.balance > 10 AND a.balance < 100 THEN a.balance * 2 ELSE 0 END"))
                    .isEqualTo(100.0);
        }
    }

    @Test
    void graphIdentityFunctions_returnNodeIdAndEdgeType(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graphidentity"), DOC)) {
            seedDeviceConnectedToAccounts(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());

            final List<Val[]> rows = runFull(stores, engine,
                    "MATCH (d:Device {id: 'd-42'})-[r:CONNECTED_TO]->(a:Account) RETURN id(d), type(r), id(a)");

            assertThat(rows).hasSize(2);
            assertThat(rows).allSatisfy(row -> {
                assertThat(row[0].toString()).isEqualTo("d-42");            // id(d): the anchor's external id
                assertThat(row[1].toString()).isEqualTo("CONNECTED_TO");    // type(r): the traversed edge's type
            });
            assertThat(rows).extracting(row -> row[2].toString())          // id(a): each reached node's id
                    .containsExactlyInAnyOrder("account-a", "account-b");
        }
    }

    @Test
    void dateTimeFunctions_evaluate(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("datetimefns"), DOC)) {
            seedDeviceConnectedToAccounts(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final String anchor = "MATCH (a:Account {id: 'account-a'}) RETURN ";

            // stroom.formatDate(millis): epoch 0 formats to a 1970 timestamp (deterministic).
            assertThat(one(stores, engine, anchor + "stroom.formatDate(0)")).contains("1970");
            // stroom.now wires through and yields a non-blank value.
            assertThat(one(stores, engine, anchor + "stroom.now()")).isNotBlank();
        }
    }

    @Test
    void arithmetic_evaluatesWithPrecedence(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("arithmetic"), DOC)) {
            seedDeviceConnectedToAccounts(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            // account-a's balance is 50.
            final String anchor = "MATCH (a:Account {id: 'account-a'}) RETURN ";

            assertThat(oneDouble(stores, engine, anchor + "a.balance * 2")).isEqualTo(100.0);
            assertThat(oneDouble(stores, engine, anchor + "a.balance - 10")).isEqualTo(40.0);
            assertThat(oneDouble(stores, engine, anchor + "a.balance / 5")).isEqualTo(10.0);
            // Modulo: 50 % 30 = 20.
            assertThat(oneDouble(stores, engine, anchor + "a.balance % 30")).isEqualTo(20.0);
            // Precedence: * before +.
            assertThat(oneDouble(stores, engine, anchor + "2 + 3 * 4")).isEqualTo(14.0);
            // Parentheses override.
            assertThat(oneDouble(stores, engine, anchor + "(2 + 3) * 4")).isEqualTo(20.0);
        }
    }

    @Test
    void scalarFunction_coalescesAnOptionalMatchNull(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("coalesce"), DOC)) {
            seedPersonsAndCrimes(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());

            // p2 has no crime, so c.type is null; stroom.if(stroom.isNull(...), ...) mirrors coalesce, via the
            // Stroom namespace.
            final List<Val[]> rows = runFull(stores, engine,
                    "MATCH (p:Person {id: 'p2'}) OPTIONAL MATCH (p)-[:PARTY_TO]->(c:Crime) "
                    + "RETURN stroom.if(stroom.isNull(c.type), 'none', c.type)");
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()[0].toString()).isEqualTo("none");
        }
    }

    @Test
    void withHaving_filtersOnAnAggregate(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("withhaving"), DOC)) {
            seedPersonsAndCrimes(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final String pipe = "MATCH (p:Person {id: 'p1'})-[:PARTY_TO]->(c:Crime) "
                    + "WITH p.id AS pid, count(c) AS n ";

            // p1 has 2 crimes. HAVING n > 1 keeps the group; the aggregate value flows to the final RETURN.
            // (Filtering on an aggregate is impossible without WITH - WHERE on a MATCH is pre-aggregation.)
            final List<Val[]> kept = runFull(stores, engine, pipe + "WHERE n > 1 RETURN pid, n");
            assertThat(kept).hasSize(1);
            assertThat(kept.getFirst()[0].toString()).isEqualTo("p1");
            assertThat(kept.getFirst()[1].toString()).isEqualTo("2");

            // HAVING n > 5 drops it.
            assertThat(runFull(stores, engine, pipe + "WHERE n > 5 RETURN pid, n")).isEmpty();
        }
    }

    private static List<Val[]> runFull(final GraphStores stores, final GraphTraversalEngine engine,
                                       final String cypher) {
        final CompiledCypherPlan compiled = compile(cypher);
        return stores.read(readTxn -> engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                DateTimeSettings.builder().build(), compiled.distinct(), compiled.aggregation(),
                compiled.fieldComparisons(), compiled.existsPredicates(), compiled.secondStage()));
    }

    private static void seedPersonsAndCrimes(final GraphStores stores) {
        final long personLabel = intern(stores, stores.getLabelUids(), "Person");
        final long crimeLabel = intern(stores, stores.getLabelUids(), "Crime");
        final long idKey = intern(stores, stores.getPropertyKeyUids(), "id");
        final long partyTo = intern(stores, stores.getEdgeTypeUids(), "PARTY_TO");
        final long p1 = intern(stores, stores.getNodeUids(), "p1");
        final long p2 = intern(stores, stores.getNodeUids(), "p2");
        final long cr1 = intern(stores, stores.getNodeUids(), "cr1");
        final long cr2 = intern(stores, stores.getNodeUids(), "cr2");

        stores.write(writer -> {
            stores.getNodes().insert(writer, p1, T1, List.of(personLabel), Map.of("id", ValString.create("p1")));
            stores.getNodes().insert(writer, p2, T1, List.of(personLabel), Map.of("id", ValString.create("p2")));
            stores.getNodes().insert(writer, cr1, T1, List.of(crimeLabel),
                    Map.of("id", ValString.create("cr1"), "type", ValString.create("theft")));
            stores.getNodes().insert(writer, cr2, T1, List.of(crimeLabel),
                    Map.of("id", ValString.create("cr2"), "type", ValString.create("fraud")));
            stores.getPropertyIndex().insert(
                    writer, personLabel, idKey, anchorBytes("p1"), p1);
            stores.getPropertyIndex().insert(
                    writer, personLabel, idKey, anchorBytes("p2"), p2);
            for (final long crime : List.of(cr1, cr2)) {
                stores.getOutEdges().insert(writer, p1, partyTo, crime, T1, Map.of());
                stores.getInEdges().insert(writer, p1, partyTo, crime, T1, Map.of());
            }
            return null;
        });
    }

    private static void seedOfficerWithRepeatedCrimeTypes(final GraphStores stores) {
        final long officerLabel = intern(stores, stores.getLabelUids(), "Officer");
        final long crimeLabel = intern(stores, stores.getLabelUids(), "Crime");
        final long idKey = intern(stores, stores.getPropertyKeyUids(), "id");
        final long investigated = intern(stores, stores.getEdgeTypeUids(), "INVESTIGATED");

        final long officerUid = intern(stores, stores.getNodeUids(), "o-1");
        final long c1 = intern(stores, stores.getNodeUids(), "c-1");
        final long c2 = intern(stores, stores.getNodeUids(), "c-2");
        final long c3 = intern(stores, stores.getNodeUids(), "c-3");

        stores.write(writer -> {
            stores.getNodes().insert(writer, officerUid, T1, List.of(officerLabel),
                    Map.of("id", ValString.create("o-1")));
            stores.getNodes().insert(writer, c1, T1, List.of(crimeLabel),
                    Map.of("id", ValString.create("c-1"), "type", ValString.create("theft")));
            stores.getNodes().insert(writer, c2, T1, List.of(crimeLabel),
                    Map.of("id", ValString.create("c-2"), "type", ValString.create("theft")));
            stores.getNodes().insert(writer, c3, T1, List.of(crimeLabel),
                    Map.of("id", ValString.create("c-3"), "type", ValString.create("fraud")));
            stores.getPropertyIndex().insert(
                    writer, officerLabel, idKey, anchorBytes("o-1"), officerUid);
            for (final long crime : List.of(c1, c2, c3)) {
                stores.getOutEdges().insert(writer, officerUid, investigated, crime, T1, Map.of());
                stores.getInEdges().insert(writer, officerUid, investigated, crime, T1, Map.of());
            }
            return null;
        });
    }

    private static List<String> matchedIds(final GraphStores stores,
                                           final GraphTraversalEngine engine,
                                           final String cypher) {
        final CompiledCypherPlan compiled = compile(cypher);
        final List<Val[]> rows = stores.read(readTxn ->
                engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                        DateTimeSettings.builder().build()));
        return rows.stream().map(row -> row[0].toString()).toList();
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
        // Code-review fix: rowFor only ever populates "variable.property" keys, never a bare "variable" key,
        // so RETURN of a bare pattern variable (no property access) previously fell through evaluate's
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
    void propertyReturnForAnAbsentProperty_yieldsNullRatherThanThrowing(@TempDir final Path root) {
        // Code-review fix: a graph is schemaless, so a well-formed property reference to a property this node
        // happens to lack (device d-42 is seeded with only 'id', no 'balance') is Cypher's null - it must not
        // crash the whole query with the misleading "bare pattern variable" error the previous containsKey-only
        // check produced. Only a truly bare pattern-variable RETURN (no '.') still throws (see the test above).
        try (GraphStores stores = GraphStores.provision(root.resolve("graph3c"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile("MATCH (d:Device {id: 'd-42'}) RETURN d.balance");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()[0]).isEqualTo(ValNull.INSTANCE);
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
    void edgeVariable_bindsTheTraversedEdgesProperties_soAnEdgePropertyResolves(@TempDir final Path root) {
        // Before edge-variable binding the engine discarded edge data mid-traversal, so c.startTime resolved to
        // ValNull even though the edge carried it. Binding the edge to `c` makes its properties projectable.
        try (GraphStores stores = GraphStores.provision(root.resolve("edgeprops"), DOC)) {
            final long deviceLabel = intern(stores, stores.getLabelUids(), "Device");
            final long accountLabel = intern(stores, stores.getLabelUids(), "Account");
            final long idKey = intern(stores, stores.getPropertyKeyUids(), "id");
            final long connectedTo = intern(stores, stores.getEdgeTypeUids(), "CONNECTED_TO");
            final long deviceUid = intern(stores, stores.getNodeUids(), "d-42");
            final long accountUid = intern(stores, stores.getNodeUids(), "account-a");

            stores.write(writer -> {
                stores.getNodes().insert(writer, deviceUid, T1, List.of(deviceLabel),
                        Map.of("id", ValString.create("d-42")));
                stores.getNodes().insert(writer, accountUid, T1, List.of(accountLabel),
                        Map.of("id", ValString.create("account-a")));
                stores.getPropertyIndex().insert(
                        writer, deviceLabel, idKey, anchorBytes("d-42"), deviceUid);
                stores.getOutEdges().insert(writer, deviceUid, connectedTo, accountUid, T1,
                        Map.of("startTime", ValString.create("2026-07-05T14:02:11Z")));
                stores.getInEdges().insert(writer, deviceUid, connectedTo, accountUid, T1,
                        Map.of("startTime", ValString.create("2026-07-05T14:02:11Z")));
                return null;
            });

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[c:CONNECTED_TO]->(a:Account) RETURN a.id, c.startTime");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()[0].toString()).isEqualTo("account-a");
            assertThat(rows.getFirst()[1].toString()).isEqualTo("2026-07-05T14:02:11Z");
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
    void orderBy_sortsRowsByTheKeyAscending(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph-orderby-asc"), DOC)) {
            seedDeviceConnectedToAccounts(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN a.id ORDER BY a.balance");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build(), compiled.distinct()));
            // account-a (balance 50) sorts before account-b (balance 200), regardless of traversal order.
            assertThat(rows).extracting(row -> row[0].toString()).containsExactly("account-a", "account-b");
        }
    }

    @Test
    void orderByDesc_reversesTheOrder(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph-orderby-desc"), DOC)) {
            seedDeviceConnectedToAccounts(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) "
                    + "RETURN a.id ORDER BY a.balance DESC");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build(), compiled.distinct()));
            assertThat(rows).extracting(row -> row[0].toString()).containsExactly("account-b", "account-a");
        }
    }

    @Test
    void orderByWithLimit_appliesLimitAfterSortingNotToTheRawTraversal(@TempDir final Path root) {
        // Regression: ORDER BY + LIMIT together previously threw (the Sort-before-Limit plan-unwrap bug). It must
        // now return the single highest-balance account - proving LIMIT is applied AFTER the sort, not to an
        // arbitrary first-N of the raw traversal.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph-orderby-limit"), DOC)) {
            seedDeviceConnectedToAccounts(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) "
                    + "RETURN a.id ORDER BY a.balance DESC LIMIT 1");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build(), compiled.distinct()));
            assertThat(rows).extracting(row -> row[0].toString()).containsExactly("account-b");
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // review finding F3: accumulation ceiling / bounded top-N (Batch 4)
    // ------------------------------------------------------------------------------------------------------

    @Test
    void accumulationCeiling_exceededWithNoOrderByOrLimit_throwsAndDoesNotSilentlyTruncate(
            @TempDir final Path root) {
        // Review finding F3: before this fix, a query with no LIMIT had rowCap = Long.MAX_VALUE and nothing else
        // bounded row accumulation - a broad MATCH could grow the in-memory row list without limit until the
        // node OOM'd. Uses the (package-private, test-only) 5-arg constructor to make a tiny ceiling (3 rows)
        // reachable over a handful of seeded leaves (5) rather than needing a million-plus real rows. The
        // traversal must fail loud (throw), not return a silently-truncated 3-row result.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph-ceiling-nolimit"), DOC)) {
            seedHubWithRankedLeaves(stores, 5);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory(), Long.MAX_VALUE, Duration.ofSeconds(30), 3);
            final CompiledCypherPlan compiled = compile(
                    "MATCH (h:Hub {id: 'hub'})-[:LINKS]->(l:Leaf) RETURN l.id");

            assertThatThrownBy(() -> execute(stores, engine, compiled))
                    .isInstanceOf(GraphTraversalLimitExceededException.class)
                    .hasMessageContaining("3")
                    .hasMessageContaining("LIMIT");
        }
    }

    @Test
    void accumulationCeiling_exceededWithOrderByButNoLimit_stillThrows(@TempDir final Path root) {
        // Review finding F3: ORDER BY alone (no LIMIT) also disables rowCap (postProcess - every row must be seen
        // to sort correctly), so this is a second, independent shape that must still be protected by the same
        // ceiling as the plain no-LIMIT case above.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph-ceiling-orderby-nolimit"), DOC)) {
            seedHubWithRankedLeaves(stores, 5);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory(), Long.MAX_VALUE, Duration.ofSeconds(30), 3);
            final CompiledCypherPlan compiled = compile(
                    "MATCH (h:Hub {id: 'hub'})-[:LINKS]->(l:Leaf) RETURN l.id ORDER BY l.rank");

            assertThatThrownBy(() -> execute(stores, engine, compiled))
                    .isInstanceOf(GraphTraversalLimitExceededException.class)
                    .hasMessageContaining("3");
        }
    }

    @Test
    void orderByWithLimit_boundedTopNHeap_matchesUnboundedResultAndStaysWithinATinyCeiling(
            @TempDir final Path root) {
        // Review finding F3 part (b): ORDER BY ... LIMIT n now keeps at most n rows in memory via a bounded
        // top-N heap, instead of materialising every matching row before sorting and truncating. Seeds far more
        // leaves (20) than the LIMIT (5) and sets the accumulation ceiling (via the 5-arg test seam) equal to
        // the LIMIT - an unbounded accumulation of all 20 leaves would blow straight through that ceiling and
        // throw (see the two tests above), so a clean, correct result here proves the bounded heap never
        // actually materialised more than `limit` rows at once. Comparing against a default (effectively
        // unbounded) engine over the same fixture/query proves the bounded path returns byte-for-byte the same
        // rows in the same order as the pre-existing materialise-then-sort-then-truncate path.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph-topn-bounded"), DOC)) {
            seedHubWithRankedLeaves(stores, 20);

            final GraphTraversalEngine unboundedEngine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final GraphTraversalEngine tinyCeilingEngine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory(), Long.MAX_VALUE, Duration.ofSeconds(30), 5);
            final CompiledCypherPlan compiled = compile(
                    "MATCH (h:Hub {id: 'hub'})-[:LINKS]->(l:Leaf) RETURN l.id ORDER BY l.rank LIMIT 5");

            final List<Val[]> baseline = execute(stores, unboundedEngine, compiled);
            final List<Val[]> bounded = execute(stores, tinyCeilingEngine, compiled);

            final List<String> expectedTop5ByRank = List.of(
                    "leaf-00", "leaf-01", "leaf-02", "leaf-03", "leaf-04");
            assertThat(baseline).extracting(row -> row[0].toString()).containsExactlyElementsOf(expectedTop5ByRank);
            assertThat(bounded).extracting(row -> row[0].toString()).containsExactlyElementsOf(expectedTop5ByRank);
        }
    }

    @Test
    void orderByDescWithLimit_boundedTopNHeap_matchesUnboundedResult(@TempDir final Path root) {
        // As above, but DESC - proves the bounded heap's ordering (via the same rowComparator, just reversed)
        // matches the unbounded path for a descending sort too, not only ascending.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph-topn-bounded-desc"), DOC)) {
            seedHubWithRankedLeaves(stores, 20);

            final GraphTraversalEngine unboundedEngine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final GraphTraversalEngine tinyCeilingEngine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory(), Long.MAX_VALUE, Duration.ofSeconds(30), 5);
            final CompiledCypherPlan compiled = compile(
                    "MATCH (h:Hub {id: 'hub'})-[:LINKS]->(l:Leaf) RETURN l.id ORDER BY l.rank DESC LIMIT 5");

            final List<Val[]> baseline = execute(stores, unboundedEngine, compiled);
            final List<Val[]> bounded = execute(stores, tinyCeilingEngine, compiled);

            final List<String> expectedTop5ByRankDesc = List.of(
                    "leaf-19", "leaf-18", "leaf-17", "leaf-16", "leaf-15");
            assertThat(baseline).extracting(row -> row[0].toString())
                    .containsExactlyElementsOf(expectedTop5ByRankDesc);
            assertThat(bounded).extracting(row -> row[0].toString())
                    .containsExactlyElementsOf(expectedTop5ByRankDesc);
        }
    }

    @Test
    void returnDistinct_deduplicatesProjectedRows(@TempDir final Path root) {
        // seedConvergingPaths reaches x via two paths (a->x and a->y->x), so a non-DISTINCT RETURN yields x twice
        // (see variableLengthPath_sameNodeReachedAtTwoDepths_yieldsTwoRows). DISTINCT collapses that to one x.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph-distinct"), DOC)) {
            seedConvergingPaths(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (a:Node {id: 'a'})-[:T*1..3]->(b:Node) RETURN DISTINCT b.id");
            assertThat(compiled.distinct()).isTrue();

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build(), compiled.distinct()));
            assertThat(rows).extracting(row -> row[0].toString()).containsExactlyInAnyOrder("x", "y");
        }
    }

    @Test
    void returnDistinctWithLimit_capsTheDistinctRows(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph-distinct-limit"), DOC)) {
            seedConvergingPaths(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (a:Node {id: 'a'})-[:T*1..3]->(b:Node) RETURN DISTINCT b.id LIMIT 1");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build(), compiled.distinct()));
            assertThat(rows).hasSize(1);
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
    void undirectedMatchOverASelfLoop_nonDistinct_yieldsEachNeighbourExactlyOnce(@TempDir final Path root) {
        // F11: collectNeighbours' BOTH branch previously called expandOut then expandIn unconditionally, so a
        // self-loop edge (src == dst) was discovered by both passes - a non-DISTINCT undirected -[:T]- query
        // returned it twice. The fix skips the self-loop on the expandIn pass only; a normal bidirectional
        // neighbour ("other") must still be returned (once), unaffected.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph-selfloop"), DOC)) {
            seedSelfLoopNode(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (a:Node {id: 'self-loop'})-[:T]-(x) RETURN x.id");

            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));

            assertThat(rows).extracting(row -> row[0].toString())
                    .containsExactlyInAnyOrder("self-loop", "other");
        }
    }

    @Test
    void limitClause_stopsAccumulatingRowsOnceSatisfied(@TempDir final Path root) {
        // Task P7.2: before this, unwrap walked past the compiled Limit node without ever reading its value -
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

    // ------------------------------------------------------------------------------------------------------
    // aggregation (Task 1.5 of)
    // ------------------------------------------------------------------------------------------------------

    @Test
    void countStarWithGroupKey_countsRowsPerGroup(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("agg-count-grouped"), DOC)) {
            seedOfficerInvestigatingCrimes(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (o:Officer {id: 'o-larive'})<-[:INVESTIGATED_BY]-(c:Crime) "
                    + "RETURN c.type, count(*) AS n");

            final List<Val[]> rows = execute(stores, engine, compiled);
            assertThat(rows).extracting(row -> row[0].toString(), row -> row[1].toLong())
                    .containsExactlyInAnyOrder(
                            Tuple.tuple("Drugs", 2L),
                            Tuple.tuple("Burglary", 1L),
                            Tuple.tuple("Fraud", 1L));
        }
    }

    @Test
    void countStarWithNoGroupKey_countsAllMatchedRowsAsASingleRow(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("agg-count-global"), DOC)) {
            seedOfficerInvestigatingCrimes(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (o:Officer {id: 'o-larive'})<-[:INVESTIGATED_BY]-(c:Crime) RETURN count(*) AS n");

            final List<Val[]> rows = execute(stores, engine, compiled);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()[0].toLong()).isEqualTo(4L);
        }
    }

    @Test
    void countOfProperty_countsOnlyRowsWherePropertyIsPresent(@TempDir final Path root) {
        // c4 ("Fraud") has no "severity" property (see seedOfficerInvestigatingCrimes) - count(*) still counts
        // it, count(c.severity) must not.
        try (GraphStores stores = GraphStores.provision(root.resolve("agg-count-property"), DOC)) {
            seedOfficerInvestigatingCrimes(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (o:Officer {id: 'o-larive'})<-[:INVESTIGATED_BY]-(c:Crime) "
                    + "RETURN count(*) AS total, count(c.severity) AS withSeverity");

            final List<Val[]> rows = execute(stores, engine, compiled);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()[0].toLong()).isEqualTo(4L);
            assertThat(rows.getFirst()[1].toLong()).isEqualTo(3L);
        }
    }

    @Test
    void sumOfProperty_sumsAcrossTheWholeMatch(@TempDir final Path root) {
        // c1=3, c2=5, c3=2, c4 has no severity (skipped, not treated as 0) - sum is 3+5+2=10.
        try (GraphStores stores = GraphStores.provision(root.resolve("agg-sum-global"), DOC)) {
            seedOfficerInvestigatingCrimes(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (o:Officer {id: 'o-larive'})<-[:INVESTIGATED_BY]-(c:Crime) "
                    + "RETURN sum(c.severity) AS total");

            final List<Val[]> rows = execute(stores, engine, compiled);
            assertThat(rows.getFirst()[0].toDouble()).isEqualTo(10.0);
        }
    }

    @Test
    void sumOfProperty_groupedByType_splitsCorrectlyAndIsZeroForAGroupWithNoNumericValues(
            @TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("agg-sum-grouped"), DOC)) {
            seedOfficerInvestigatingCrimes(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (o:Officer {id: 'o-larive'})<-[:INVESTIGATED_BY]-(c:Crime) "
                    + "RETURN c.type, sum(c.severity) AS total");

            final List<Val[]> rows = execute(stores, engine, compiled);
            assertThat(rows).extracting(row -> row[0].toString(), row -> row[1].toDouble())
                    .containsExactlyInAnyOrder(
                            Tuple.tuple("Drugs", 8.0),
                            Tuple.tuple("Burglary", 2.0),
                            Tuple.tuple("Fraud", 0.0));
        }
    }

    @Test
    void avgOfProperty_averagesTheNonNullValues(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("agg-avg-global"), DOC)) {
            seedOfficerInvestigatingCrimes(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (o:Officer {id: 'o-larive'})<-[:INVESTIGATED_BY]-(c:Crime) "
                    + "RETURN avg(c.severity) AS average");

            final List<Val[]> rows = execute(stores, engine, compiled);
            assertThat(rows.getFirst()[0].toDouble()).isEqualTo((3.0 + 5.0 + 2.0) / 3.0);
        }
    }

    @Test
    void avgOverAGroupWithNoNumericValues_isNullNotZero(@TempDir final Path root) {
        // Unlike sum's 0, Cypher's avg of an empty/non-numeric set is null - the "Fraud" group has no crime
        // with a severity property at all.
        try (GraphStores stores = GraphStores.provision(root.resolve("agg-avg-grouped"), DOC)) {
            seedOfficerInvestigatingCrimes(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (o:Officer {id: 'o-larive'})<-[:INVESTIGATED_BY]-(c:Crime) "
                    + "RETURN c.type, avg(c.severity) AS average");

            final List<Val[]> rows = execute(stores, engine, compiled);
            final Val[] fraudRow = rows.stream().filter(row -> "Fraud".equals(row[0].toString())).findFirst()
                    .orElseThrow();
            assertThat(fraudRow[1]).isEqualTo(ValNull.INSTANCE);
        }
    }

    @Test
    void minAndMaxOfProperty_preserveTheOriginalValType(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("agg-minmax-global"), DOC)) {
            seedOfficerInvestigatingCrimes(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (o:Officer {id: 'o-larive'})<-[:INVESTIGATED_BY]-(c:Crime) "
                    + "RETURN min(c.severity) AS lowest, max(c.severity) AS highest");

            final List<Val[]> rows = execute(stores, engine, compiled);
            assertThat(rows.getFirst()[0]).isInstanceOf(ValLong.class);
            assertThat(rows.getFirst()[0].toLong()).isEqualTo(2L);
            assertThat(rows.getFirst()[1]).isInstanceOf(ValLong.class);
            assertThat(rows.getFirst()[1].toLong()).isEqualTo(5L);
        }
    }

    @Test
    void minOrMaxOverAGroupWithNoNumericValues_isNull(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("agg-minmax-grouped"), DOC)) {
            seedOfficerInvestigatingCrimes(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (o:Officer {id: 'o-larive'})<-[:INVESTIGATED_BY]-(c:Crime) "
                    + "RETURN c.type, min(c.severity) AS lowest");

            final List<Val[]> rows = execute(stores, engine, compiled);
            final Val[] fraudRow = rows.stream().filter(row -> "Fraud".equals(row[0].toString())).findFirst()
                    .orElseThrow();
            assertThat(fraudRow[1]).isEqualTo(ValNull.INSTANCE);
        }
    }

    @Test
    void emptyMatch_withNoGroupKey_yieldsOneRowWithZeroCount(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("agg-empty-nogroup"), DOC)) {
            seedOfficerInvestigatingCrimes(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (o:Officer {id: 'no-such-officer'})<-[:INVESTIGATED_BY]-(c:Crime) "
                    + "RETURN count(*) AS n");

            final List<Val[]> rows = execute(stores, engine, compiled);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()[0].toLong()).isEqualTo(0L);
        }
    }

    @Test
    void emptyMatch_withAGroupKey_yieldsZeroRows(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("agg-empty-group"), DOC)) {
            seedOfficerInvestigatingCrimes(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (o:Officer {id: 'no-such-officer'})<-[:INVESTIGATED_BY]-(c:Crime) "
                    + "RETURN c.type, count(*) AS n");

            final List<Val[]> rows = execute(stores, engine, compiled);
            assertThat(rows).isEmpty();
        }
    }

    @Test
    void orderByAggregateAliasDescWithLimit_ordersAndCapsTheAggregatedOutput(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("agg-orderby-limit"), DOC)) {
            seedOfficerInvestigatingCrimes(stores);
            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (o:Officer {id: 'o-larive'})<-[:INVESTIGATED_BY]-(c:Crime) "
                    + "RETURN c.type, count(*) AS n ORDER BY n DESC LIMIT 1");

            final List<Val[]> rows = execute(stores, engine, compiled);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()[0].toString()).isEqualTo("Drugs");
            assertThat(rows.getFirst()[1].toLong()).isEqualTo(2L);
        }
    }

    /** Runs {@code compiled} with its own {@code distinct}/{@code aggregation} against {@code stores}. */
    @Test
    void executeDiffBindings_classifiesAddedRemovedModifiedUnchanged_acrossTwoInstants(
            @TempDir final Path root) {
        // A device connected to four accounts, evolving between T1 (baseline) and T2 (comparison):
        //   account-a: edge present at both, but a's balance changes 50 -> 999  => MODIFIED
        //   account-b: edge added at T2                                          => ADDED
        //   account-c: edge present at T1, tombstoned before T2                  => REMOVED
        //   account-d: edge present at both, nothing changes                     => UNCHANGED
        // Run the fixed-length pattern's bindings at each instant, then classify with the pure DiffOperator.
        final Instant tMid = Instant.parse("2026-03-01T00:00:00.000Z");
        try (GraphStores stores = GraphStores.provision(root.resolve("diff"), DOC)) {
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
                        writer, deviceLabel, idKey, anchorBytes("d-42"), deviceUid);

                // account-a: balance changes between the two instants (MODIFIED via full property-set inequality).
                stores.getNodes().insert(writer, aUid, T1, List.of(accountLabel),
                        Map.of("id", ValString.create("account-a"), "balance", ValLong.create(50)));
                stores.getNodes().insert(writer, aUid, T2, List.of(accountLabel),
                        Map.of("id", ValString.create("account-a"), "balance", ValLong.create(999)));
                stores.getNodes().insert(writer, bUid, T1, List.of(accountLabel),
                        Map.of("id", ValString.create("account-b")));
                stores.getNodes().insert(writer, cUid, T1, List.of(accountLabel),
                        Map.of("id", ValString.create("account-c")));
                stores.getNodes().insert(writer, dUid, T1, List.of(accountLabel),
                        Map.of("id", ValString.create("account-d")));

                insertEdge(stores, writer, deviceUid, connectedTo, aUid, T1);   // a: both instants
                insertEdge(stores, writer, deviceUid, connectedTo, bUid, T2);   // b: added at T2
                insertEdge(stores, writer, deviceUid, connectedTo, dUid, T1);   // d: both instants, unchanged
                // c: present at T1, tombstoned before T2.
                insertEdge(stores, writer, deviceUid, connectedTo, cUid, T1);
                stores.getOutEdges().delete(writer, deviceUid, connectedTo, cUid, tMid);
                stores.getInEdges().delete(writer, deviceUid, connectedTo, cUid, tMid);
                return null;
            });

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[c:CONNECTED_TO]->(a:Account) RETURN a.id");

            final List<ClassifiedMatch> classified = stores.read(readTxn -> {
                final List<DiffMatch> baseline = engine.executeDiffBindings(
                        readTxn, compiled.plan(), T1, DateTimeSettings.builder().build());
                final List<DiffMatch> comparison = engine.executeDiffBindings(
                        readTxn, compiled.plan(), T2, DateTimeSettings.builder().build());
                return DiffOperator.classify(baseline, comparison);
            });

            // One classified path per account, keyed by the projected a.id (present in whichever snapshot it exists).
            assertThat(classified)
                    .extracting(m -> m.presentRow().get("a.id").toString(), ClassifiedMatch::changeKind)
                    .containsExactlyInAnyOrder(
                            Tuple.tuple("account-a", ChangeKind.MODIFIED),
                            Tuple.tuple("account-b", ChangeKind.ADDED),
                            Tuple.tuple("account-c", ChangeKind.REMOVED),
                            Tuple.tuple("account-d", ChangeKind.UNCHANGED));
        }
    }

    @Test
    void diffExecutor_projectsChangeKindAndBeforeAfter_suppressingUnchanged(@TempDir final Path root) {
        // Same four-account evolution as the bindings test, but exercised end-to-end through DiffExecutor:
        // the projected delta table carries changeKind + before/after values, and UNCHANGED is suppressed.
        final Instant tMid = Instant.parse("2026-03-01T00:00:00.000Z");
        try (GraphStores stores = GraphStores.provision(root.resolve("diffexec"), DOC)) {
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
                        writer, deviceLabel, idKey, anchorBytes("d-42"), deviceUid);
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

                insertEdge(stores, writer, deviceUid, connectedTo, aUid, T1);
                insertEdge(stores, writer, deviceUid, connectedTo, bUid, T2);
                insertEdge(stores, writer, deviceUid, connectedTo, dUid, T1);
                insertEdge(stores, writer, deviceUid, connectedTo, cUid, T1);
                stores.getOutEdges().delete(writer, deviceUid, connectedTo, cUid, tMid);
                stores.getInEdges().delete(writer, deviceUid, connectedTo, cUid, tMid);
                return null;
            });

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[c:CONNECTED_TO]->(a:Account) "
                    + "DIFF FROM datetime('2026-01-01T00:00:00Z') TO datetime('2026-06-01T00:00:00Z') "
                    + "RETURN changeKind, a.id, before(a.balance), after(a.balance)");

            final List<Val[]> rows = stores.read(readTxn ->
                    DiffExecutor.execute(readTxn, engine, compiled.plan(), compiled.diffContext(),
                            DateTimeSettings.builder().build(), compiled.distinct()));

            // account-d (UNCHANGED) is suppressed; the other three appear with their before/after balances.
            // (col 0 changeKind, 1 a.id, 2 before(a.balance), 3 after(a.balance); a null side => ValNull.)
            assertThat(rows)
                    .extracting(
                            r -> r[0].toString(),
                            r -> r[1].toString(),
                            r -> text(r[2]),
                            r -> text(r[3]))
                    .containsExactlyInAnyOrder(
                            Tuple.tuple("MODIFIED", "account-a", "50", "999"),
                            Tuple.tuple("ADDED", "account-b", null, "10"),
                            Tuple.tuple("REMOVED", "account-c", "20", null));
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // RETURN GRAPH (Workstream D): the element-row output mode (.4,
    // ).
    // ------------------------------------------------------------------------------------------------------

    @Test
    void returnGraph_yieldsOneRowPerDistinctNodeAndEdge_convergingPathsCollapseToOneNodeRow(
            @TempDir final Path root) {
        // seedDeviceAccountOwnerChain: chain-d-42 -CONNECTED_TO-> {account-a, account-b} -OWNED_BY->
        // {owner-x, owner-y} -EMPLOYED_BY-> company-1 (BOTH owners employed by the SAME company). Two distinct
        // 3-hop paths both terminate at company-1 - the acceptance this test proves (N-4): company-1 is emitted
        // as exactly ONE node row (deduplicated by ElementId, not once per path), while its two distinct EMPLOYED_BY
        // edges (from owner-x and from owner-y) are NOT deduplicated - they are genuinely distinct edges.
        try (GraphStores stores = GraphStores.provision(root.resolve("returngraph"), DOC)) {
            seedDeviceAccountOwnerChain(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account)-[:OWNED_BY]->(o:Owner)"
                    + "-[:EMPLOYED_BY]->(c:Company) RETURN GRAPH");
            assertThat(compiled.returnGraph()).isTrue();

            final List<Val[]> rows = stores.read(readTxn ->
                    GraphElementExecutor.execute(readTxn, engine, stores, compiled.plan(),
                            compiled.temporalContext(), compiled.diffContext(), DateTimeSettings.builder().build()));

            assertThat(rows).hasSize(12); // 6 distinct nodes + 6 distinct edges.
            assertThat(rows).extracting(r -> text(r[0])).filteredOn("NODE"::equals).hasSize(6);
            assertThat(rows).extracting(r -> text(r[0])).filteredOn("EDGE"::equals).hasSize(6);

            assertThat(rows)
                    .extracting(
                            r -> text(r[0]), r -> text(r[1]), r -> text(r[2]), r -> text(r[3]), r -> text(r[4]),
                            r -> text(r[5]))
                    .containsExactlyInAnyOrder(
                            Tuple.tuple("NODE", "chain-d-42", "Device", null, null, "{\"id\":\"d-42\"}"),
                            Tuple.tuple("NODE", "chain-account-a", "Account", null, null,
                                    "{\"id\":\"account-a\"}"),
                            Tuple.tuple("NODE", "chain-account-b", "Account,Premium", null, null,
                                    "{\"id\":\"account-b\"}"),
                            Tuple.tuple("NODE", "owner-x", "Owner", null, null, "{\"id\":\"owner-x\"}"),
                            Tuple.tuple("NODE", "owner-y", "Owner", null, null, "{\"id\":\"owner-y\"}"),
                            // Deduplicated: reached via BOTH owner-x and owner-y, still exactly one row.
                            Tuple.tuple("NODE", "company-1", "Company", null, null, "{\"id\":\"company-1\"}"),
                            Tuple.tuple("EDGE", "chain-d-42|CONNECTED_TO|chain-account-a", "CONNECTED_TO",
                                    "chain-d-42", "chain-account-a", "{}"),
                            Tuple.tuple("EDGE", "chain-d-42|CONNECTED_TO|chain-account-b", "CONNECTED_TO",
                                    "chain-d-42", "chain-account-b", "{}"),
                            Tuple.tuple("EDGE", "chain-account-a|OWNED_BY|owner-x", "OWNED_BY",
                                    "chain-account-a", "owner-x", "{}"),
                            Tuple.tuple("EDGE", "chain-account-b|OWNED_BY|owner-y", "OWNED_BY",
                                    "chain-account-b", "owner-y", "{}"),
                            Tuple.tuple("EDGE", "owner-x|EMPLOYED_BY|company-1", "EMPLOYED_BY",
                                    "owner-x", "company-1", "{}"),
                            Tuple.tuple("EDGE", "owner-y|EMPLOYED_BY|company-1", "EMPLOYED_BY",
                                    "owner-y", "company-1", "{}"));
        }
    }

    @Test
    void wholeGraphDump_unanchoredReturnGraph_returnsEveryNodeAndEdge(@TempDir final Path root) {
        // An unanchored MATCH (n) RETURN GRAPH (the Data tab's default "show me the graph") has no label/property
        // anchor to seek, so it rides the engine's whole-graph dump path: every node, plus every edge between two
        // included nodes, at the latest instant. seedDeviceConnectedToAccounts stores 4 nodes and 3 edges.
        try (GraphStores stores = GraphStores.provision(root.resolve("wholegraphdump"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile("MATCH (n) RETURN GRAPH");
            assertThat(compiled.returnGraph()).isTrue();

            final List<Val[]> rows = stores.read(readTxn ->
                    GraphElementExecutor.execute(readTxn, engine, stores, compiled.plan(),
                            compiled.temporalContext(), compiled.diffContext(), DateTimeSettings.builder().build()));

            assertThat(rows).extracting(r -> text(r[0])).filteredOn("NODE"::equals).hasSize(4);
            assertThat(rows).extracting(r -> text(r[0])).filteredOn("EDGE"::equals).hasSize(3);
            assertThat(rows)
                    .extracting(r -> text(r[0]), r -> text(r[1]), r -> text(r[2]), r -> text(r[3]), r -> text(r[4]))
                    .containsExactlyInAnyOrder(
                            Tuple.tuple("NODE", "d-42", "Device", null, null),
                            Tuple.tuple("NODE", "account-a", "Account", null, null),
                            Tuple.tuple("NODE", "account-b", "Account,Premium", null, null),
                            Tuple.tuple("NODE", "gw-1", "Device", null, null),
                            Tuple.tuple("EDGE", "d-42|CONNECTED_TO|account-a", "CONNECTED_TO", "d-42", "account-a"),
                            Tuple.tuple("EDGE", "d-42|CONNECTED_TO|account-b", "CONNECTED_TO", "d-42", "account-b"),
                            Tuple.tuple("EDGE", "gw-1|CONNECTED_TO|d-42", "CONNECTED_TO", "gw-1", "d-42"));
        }
    }

    @Test
    void wholeGraphDump_respectsNodeCap_andNeverEmitsDanglingEdges(@TempDir final Path root) {
        // The node cap bounds the preview scan (RETURN GRAPH takes no LIMIT clause, so the cap is engine-side); an
        // edge is emitted only when BOTH its endpoints are among the included nodes, so a capped-out neighbour
        // never leaves a dangling edge. Uses the test-only cap of 2 rather than seeding 100+ nodes.
        try (GraphStores stores = GraphStores.provision(root.resolve("wholegraphdumplimit"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory(), 200_000L, Duration.ofSeconds(30), 1_000_000L, 2);
            final CompiledCypherPlan compiled = compile("MATCH (n) RETURN GRAPH");

            final List<Val[]> rows = stores.read(readTxn ->
                    GraphElementExecutor.execute(readTxn, engine, stores, compiled.plan(),
                            compiled.temporalContext(), compiled.diffContext(), DateTimeSettings.builder().build()));

            final List<String> nodeIds = rows.stream()
                    .filter(r -> "NODE".equals(text(r[0])))
                    .map(r -> text(r[1]))
                    .toList();
            assertThat(nodeIds).hasSize(2);
            assertThat(rows)
                    .filteredOn(r -> "EDGE".equals(text(r[0])))
                    .allSatisfy(r -> {
                        assertThat(nodeIds).contains(text(r[3]));
                        assertThat(nodeIds).contains(text(r[4]));
                    });
        }
    }

    @Test
    void wholeGraphDump_scanCutShortByTheCap_reportsAWarning(@TempDir final Path root) {
        // The cap is the one guardrail that truncates instead of failing, because failing it would break the
        // default query both graph tabs open with. It must therefore say so: every other limit reports itself.
        // 4 nodes seeded, cap of 2 - the scan stops with nodes left unlooked-at.
        try (GraphStores stores = GraphStores.provision(root.resolve("capwarns"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory(), 200_000L, Duration.ofSeconds(30), 1_000_000L, 2);
            runWholeGraphPreview(stores, engine, "MATCH (n) RETURN GRAPH");

            assertThat(engine.warnings().messages())
                    .singleElement(as(STRING))
                    .contains("stopped at the first 2 nodes")
                    // Names the setting, so an administrator can find it, and the way out, so an analyst can act.
                    .contains("graphdb.wholeGraphNodeCap")
                    .contains("Add a LIMIT");
        }
    }

    @Test
    void wholeGraphDump_asManyNodesAsTheCap_reportsNothing(@TempDir final Path root) {
        // The discriminating case. A graph holding exactly the cap is complete, and reporting it as truncated
        // would be a false alarm on every small graph - which is how a warning teaches people to ignore it.
        // 4 nodes seeded, cap of 4: the scan ends because it ran out of nodes, not because it was stopped.
        try (GraphStores stores = GraphStores.provision(root.resolve("capexact"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory(), 200_000L, Duration.ofSeconds(30), 1_000_000L, 4);
            final List<Val[]> rows = runWholeGraphPreview(stores, engine, "MATCH (n) RETURN GRAPH");

            assertThat(rows).filteredOn(r -> "NODE".equals(text(r[0]))).hasSize(4);
            assertThat(engine.warnings().messages()).isEmpty();
        }
    }

    @Test
    void wholeGraphDump_fewerNodesThanTheCap_reportsNothing(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("capunder"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory(), 200_000L, Duration.ofSeconds(30), 1_000_000L, 50);
            runWholeGraphPreview(stores, engine, "MATCH (n) RETURN GRAPH");

            assertThat(engine.warnings().messages()).isEmpty();
        }
    }

    @Test
    void wholeGraphPreview_withItsOwnLimit_reportsNothing(@TempDir final Path root) {
        // A query that asked for 2 nodes got 2 nodes. Telling the author their own LIMIT truncated the result is
        // noise, and it would fire on the tabs' own default query (MATCH (n) RETURN GRAPH LIMIT 100) forever.
        try (GraphStores stores = GraphStores.provision(root.resolve("ownlimit"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final List<Val[]> rows = runWholeGraphPreview(stores, engine, "MATCH (n) RETURN GRAPH LIMIT 2");

            assertThat(rows).filteredOn(r -> "NODE".equals(text(r[0]))).hasSize(2);
            assertThat(engine.warnings().messages()).isEmpty();
        }
    }

    @Test
    void anchoredReturnGraph_reportsNothing(@TempDir final Path root) {
        // The cap belongs to the unanchored preview scan alone. An anchored pattern walks the index and is bounded
        // by what matches, so it has nothing to report even with a cap smaller than the result.
        try (GraphStores stores = GraphStores.provision(root.resolve("anchorednowarn"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, new ExpressionPredicateFactory(), 200_000L, Duration.ofSeconds(30), 1_000_000L, 1);
            runWholeGraphPreview(stores, engine,
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN GRAPH");

            assertThat(engine.warnings().messages()).isEmpty();
        }
    }

    /** Compiles and runs a {@code RETURN GRAPH} query through the element executor, as the search provider does. */
    private static List<Val[]> runWholeGraphPreview(final GraphStores stores, final GraphTraversalEngine engine,
                                                    final String cypher) {
        final CompiledCypherPlan compiled = compile(cypher);
        return stores.read(readTxn ->
                GraphElementExecutor.execute(readTxn, engine, stores, compiled.plan(),
                        compiled.temporalContext(), compiled.diffContext(), DateTimeSettings.builder().build()));
    }

    @Test
    void returnGraphLimit_wholeGraph_capsNodesAndTheirEdges(@TempDir final Path root) {
        // RETURN GRAPH LIMIT n (valid syntax) bounds the whole-graph preview to n nodes plus the edges between them
        // - the analyst-controlled counterpart of the engine's default cap.
        try (GraphStores stores = GraphStores.provision(root.resolve("wholegraphlimit"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile("MATCH (n) RETURN GRAPH LIMIT 2");
            assertThat(compiled.returnGraph()).isTrue();

            final List<Val[]> rows = stores.read(readTxn ->
                    GraphElementExecutor.execute(readTxn, engine, stores, compiled.plan(),
                            compiled.temporalContext(), compiled.diffContext(), DateTimeSettings.builder().build()));

            final List<String> nodeIds = rows.stream()
                    .filter(r -> "NODE".equals(text(r[0])))
                    .map(r -> text(r[1]))
                    .toList();
            assertThat(nodeIds).hasSize(2);
            assertThat(rows)
                    .filteredOn(r -> "EDGE".equals(text(r[0])))
                    .allSatisfy(r -> {
                        assertThat(nodeIds).contains(text(r[3]));
                        assertThat(nodeIds).contains(text(r[4]));
                    });
        }
    }

    @Test
    void returnGraphLimit_anchoredPattern_capsTheMatchedUnionToNodesAndTheirEdges(@TempDir final Path root) {
        // An anchored RETURN GRAPH LIMIT n collects the full matched union, then caps it to n nodes plus the edges
        // between them (capToNodeLimit) - never leaving a dangling edge to a dropped node.
        try (GraphStores stores = GraphStores.provision(root.resolve("anchoredlimit"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            // Without LIMIT: d-42 + account-a + account-b + 2 edges. LIMIT 2 keeps 2 nodes and only their edges.
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN GRAPH LIMIT 2");

            final List<Val[]> rows = stores.read(readTxn ->
                    GraphElementExecutor.execute(readTxn, engine, stores, compiled.plan(),
                            compiled.temporalContext(), compiled.diffContext(), DateTimeSettings.builder().build()));

            final List<String> nodeIds = rows.stream()
                    .filter(r -> "NODE".equals(text(r[0])))
                    .map(r -> text(r[1]))
                    .toList();
            assertThat(nodeIds).hasSize(2);
            assertThat(rows)
                    .filteredOn(r -> "EDGE".equals(text(r[0])))
                    .allSatisfy(r -> {
                        assertThat(nodeIds).contains(text(r[3]));
                        assertThat(nodeIds).contains(text(r[4]));
                    });
        }
    }

    @Test
    void returnGraph_labelScoped_returnsOnlyThatLabelsNodes(@TempDir final Path root) {
        // MATCH (n:Account) RETURN GRAPH browses one label's nodes - a label-only anchor with no property index to
        // seek, served by the preview scan + a label filter. seedDeviceConnectedToAccounts has 2 Account nodes
        // (account-a, account-b) and 2 Device nodes; every edge is Device->Account or Device->Device, so no edge
        // connects two Accounts and the label-scoped result has 0 edges.
        try (GraphStores stores = GraphStores.provision(root.resolve("labelscoped"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile("MATCH (n:Account) RETURN GRAPH");

            final List<Val[]> rows = stores.read(readTxn ->
                    GraphElementExecutor.execute(readTxn, engine, stores, compiled.plan(),
                            compiled.temporalContext(), compiled.diffContext(), DateTimeSettings.builder().build()));

            assertThat(rows)
                    .extracting(r -> text(r[0]), r -> text(r[1]))
                    .containsExactlyInAnyOrder(
                            Tuple.tuple("NODE", "account-a"),
                            Tuple.tuple("NODE", "account-b"));
        }
    }

    @Test
    void returnGraph_labelScoped_unknownLabel_returnsEmpty(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("labelscopedunknown"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile("MATCH (n:NoSuchLabel) RETURN GRAPH");

            final List<Val[]> rows = stores.read(readTxn ->
                    GraphElementExecutor.execute(readTxn, engine, stores, compiled.plan(),
                            compiled.temporalContext(), compiled.diffContext(), DateTimeSettings.builder().build()));

            assertThat(rows).isEmpty();
        }
    }

    @Test
    void expandNode_returnsTheNodePlusAllNeighboursBothDirections(@TempDir final Path root) {
        // "Expand neighbours" from d-42: it has outgoing CONNECTED_TO edges to account-a and account-b and an
        // incoming CONNECTED_TO edge from gw-1 - so the expand yields d-42 + those 3 neighbours + 3 edges, found by
        // node identity (external id) across all edge types and both directions, with no anchor property needed.
        try (GraphStores stores = GraphStores.provision(root.resolve("expand"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final List<Val[]> rows = stores.read(readTxn ->
                    GraphElementExecutor.executeExpand(readTxn, engine, stores, "d-42", 50, null));

            assertThat(rows).extracting(r -> text(r[0])).filteredOn("NODE"::equals).hasSize(4);
            assertThat(rows).extracting(r -> text(r[0])).filteredOn("EDGE"::equals).hasSize(3);
            assertThat(rows)
                    .extracting(r -> text(r[0]), r -> text(r[1]), r -> text(r[3]), r -> text(r[4]))
                    .containsExactlyInAnyOrder(
                            Tuple.tuple("NODE", "d-42", null, null),
                            Tuple.tuple("NODE", "account-a", null, null),
                            Tuple.tuple("NODE", "account-b", null, null),
                            Tuple.tuple("NODE", "gw-1", null, null),
                            Tuple.tuple("EDGE", "d-42|CONNECTED_TO|account-a", "d-42", "account-a"),
                            Tuple.tuple("EDGE", "d-42|CONNECTED_TO|account-b", "d-42", "account-b"),
                            Tuple.tuple("EDGE", "gw-1|CONNECTED_TO|d-42", "gw-1", "d-42"));
        }
    }

    @Test
    void expandNode_honoursTemporalInstant(@TempDir final Path root) {
        // The d-42 -> account-b edge is written at T2; d-42 -> account-a and gw-1 -> d-42 at T1. Expanding d-42
        // AS OF T1 must therefore see only account-a and gw-1 as neighbours - account-b's edge does not yet exist,
        // so the expansion matches the historical snapshot rather than leaking the present-day edge.
        try (GraphStores stores = GraphStores.provision(root.resolve("expandtemporal"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final TemporalContext asOfT1 = new TemporalContext(TemporalContext.Mode.AS_OF, T1, null, null);
            final List<Val[]> rows = stores.read(readTxn ->
                    GraphElementExecutor.executeExpand(readTxn, engine, stores, "d-42", 50, asOfT1));

            assertThat(rows)
                    .filteredOn(r -> "NODE".equals(text(r[0])))
                    .extracting(r -> text(r[1]))
                    .containsExactlyInAnyOrder("d-42", "account-a", "gw-1");
            assertThat(rows)
                    .filteredOn(r -> "EDGE".equals(text(r[0])))
                    .extracting(r -> text(r[1]))
                    .containsExactlyInAnyOrder("d-42|CONNECTED_TO|account-a", "gw-1|CONNECTED_TO|d-42");
        }
    }

    @Test
    void expandNode_unknownId_returnsEmpty(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("expandunknown"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final List<Val[]> rows = stores.read(readTxn ->
                    GraphElementExecutor.executeExpand(readTxn, engine, stores, "no-such-node", 50, null));

            assertThat(rows).isEmpty();
        }
    }

    @Test
    void returnGraph_whereClause_onlyIncludesElementsFromFullyMatchingPaths(@TempDir final Path root) {
        // Connectivity guarantee: an element only appears if it is on a path that fully matches the
        // pattern AND its WHERE - account-a's path fails "balance > 100", so neither account-a nor its edge
        // appear, but the device (shared by both paths) still appears via the surviving account-b path. Also
        // proves the "properties" column's JSON rendering: numeric values unquoted, keys sorted.
        try (GraphStores stores = GraphStores.provision(root.resolve("returngraphwhere"), DOC)) {
            seedDeviceConnectedToAccounts(stores);

            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) "
                    + "WHERE a.balance > 100 RETURN GRAPH");

            final List<Val[]> rows = stores.read(readTxn ->
                    GraphElementExecutor.execute(readTxn, engine, stores, compiled.plan(),
                            compiled.temporalContext(), compiled.diffContext(), DateTimeSettings.builder().build()));

            assertThat(rows).hasSize(3); // device + account-b nodes, plus their connecting edge.
            assertThat(rows)
                    .extracting(r -> text(r[0]), r -> text(r[1]))
                    .containsExactlyInAnyOrder(
                            Tuple.tuple("NODE", "d-42"),
                            Tuple.tuple("NODE", "account-b"),
                            Tuple.tuple("EDGE", "d-42|CONNECTED_TO|account-b"));
            assertThat(rows).extracting(r -> text(r[1])).doesNotContain("account-a");

            final Val[] accountBRow = rows.stream()
                    .filter(r -> "account-b".equals(text(r[1])))
                    .findFirst().orElseThrow();
            assertThat(text(accountBRow[5])).isEqualTo("{\"balance\":200,\"id\":\"account-b\"}");
        }
    }

    @Test
    void diffReturnGraph_classifiesEachElementIndependently_includingUnchangedContext(
            @TempDir final Path root) {
        // Same four-account evolution as executeDiffBindings_classifies... above, but through the annotated-
        // subgraph mode: per-element (not per-path) classification, and UNCHANGED is INCLUDED (unlike the
        // delta table) as the connectivity context §5.6 requires. Notably account-a's NODE is MODIFIED while
        // its connecting EDGE is UNCHANGED (the edge itself never changed - only the node's balance did) -
        // proving classification is genuinely per-element, not rolled up to the path.
        final Instant tMid = Instant.parse("2026-03-01T00:00:00.000Z");
        try (GraphStores stores = GraphStores.provision(root.resolve("diffreturngraph"), DOC)) {
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
                        writer, deviceLabel, idKey, anchorBytes("d-42"), deviceUid);

                stores.getNodes().insert(writer, aUid, T1, List.of(accountLabel),
                        Map.of("id", ValString.create("account-a"), "balance", ValLong.create(50)));
                stores.getNodes().insert(writer, aUid, T2, List.of(accountLabel),
                        Map.of("id", ValString.create("account-a"), "balance", ValLong.create(999)));
                stores.getNodes().insert(writer, bUid, T1, List.of(accountLabel),
                        Map.of("id", ValString.create("account-b")));
                stores.getNodes().insert(writer, cUid, T1, List.of(accountLabel),
                        Map.of("id", ValString.create("account-c")));
                stores.getNodes().insert(writer, dUid, T1, List.of(accountLabel),
                        Map.of("id", ValString.create("account-d")));

                insertEdge(stores, writer, deviceUid, connectedTo, aUid, T1);   // a: both instants, MODIFIED node
                insertEdge(stores, writer, deviceUid, connectedTo, bUid, T2);   // b: added at T2
                insertEdge(stores, writer, deviceUid, connectedTo, dUid, T1);   // d: both instants, unchanged
                insertEdge(stores, writer, deviceUid, connectedTo, cUid, T1);   // c: present at T1, gone by T2
                stores.getOutEdges().delete(writer, deviceUid, connectedTo, cUid, tMid);
                stores.getInEdges().delete(writer, deviceUid, connectedTo, cUid, tMid);
                return null;
            });

            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'd-42'})-[c:CONNECTED_TO]->(a:Account) "
                    + "DIFF FROM datetime('2026-01-01T00:00:00Z') TO datetime('2026-06-01T00:00:00Z') "
                    + "RETURN GRAPH");
            assertThat(compiled.returnGraph()).isTrue();
            assertThat(compiled.diffContext()).isNotNull();

            final List<Val[]> rows = stores.read(readTxn ->
                    GraphElementExecutor.execute(readTxn, engine, stores, compiled.plan(),
                            compiled.temporalContext(), compiled.diffContext(), DateTimeSettings.builder().build()));

            // 5 nodes (device, a, b, c, d) + 4 edges (device->{a,b,c,d}) = 9 rows; UNCHANGED is kept, not
            // suppressed (unlike the delta table). The 7th column (changeKind) is present on every row.
            assertThat(rows).hasSize(9);
            assertThat(rows)
                    .extracting(r -> text(r[0]), r -> text(r[1]), r -> text(r[6]))
                    .containsExactlyInAnyOrder(
                            Tuple.tuple("NODE", "d-42", "UNCHANGED"),
                            Tuple.tuple("NODE", "account-a", "MODIFIED"),
                            Tuple.tuple("NODE", "account-b", "ADDED"),
                            Tuple.tuple("NODE", "account-c", "REMOVED"),
                            Tuple.tuple("NODE", "account-d", "UNCHANGED"),
                            Tuple.tuple("EDGE", "d-42|CONNECTED_TO|account-a", "UNCHANGED"),
                            Tuple.tuple("EDGE", "d-42|CONNECTED_TO|account-b", "ADDED"),
                            Tuple.tuple("EDGE", "d-42|CONNECTED_TO|account-c", "REMOVED"),
                            Tuple.tuple("EDGE", "d-42|CONNECTED_TO|account-d", "UNCHANGED"));
        }
    }

    /** A null-safe rendering of a projected value: {@code ValNull} (an absent before/after side) renders as a
     * Java {@code null} rather than {@link ValNull#toString}'s own {@code null} String. */
    private static String text(final Val value) {
        return value == null || value == ValNull.INSTANCE ? null : value.toString();
    }

    private static void insertEdge(final GraphStores stores, final LmdbWriter writer, final long src,
                                   final long type, final long dst, final Instant validFrom) {
        // Dual-write contract (P1.1): a logical edge is written to both adjacency directions.
        stores.getOutEdges().insert(writer, src, type, dst, validFrom, Map.of());
        stores.getInEdges().insert(writer, src, type, dst, validFrom, Map.of());
    }

    private static List<Val[]> execute(final GraphStores stores, final GraphTraversalEngine engine,
                                       final CompiledCypherPlan compiled) {
        return stores.read(readTxn ->
                engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                        DateTimeSettings.builder().build(), compiled.distinct(), compiled.aggregation()));
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
                    writer, deviceLabel, idKey, anchorBytes("d-42"), deviceUid);
            stores.getPropertyIndex().insert(
                    writer, accountLabel, idKey, anchorBytes("account-a"), accountAUid);

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
                    writer, deviceLabel, idKey, anchorBytes("d-42"), deviceUid);

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
                    writer, nodeLabel, idKey, anchorBytes("n1"), n1Uid);

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

            stores.getPropertyIndex().insert(writer, nodeLabel, idKey, anchorBytes("a"), aUid);

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
     * F11's self-loop fixture: {@code self-loop} has a self-loop {@code T} edge (src == dst) plus a normal
     * bidirectional edge to {@code other}, so an undirected {@code -[:T]-} query from {@code self-loop} exercises
     * both the self-loop de-dup and an unaffected normal neighbour in the same traversal.
     */
    private static void seedSelfLoopNode(final GraphStores stores) {
        final long nodeLabel = intern(stores, stores.getLabelUids(), "Node");
        final long idKey = intern(stores, stores.getPropertyKeyUids(), "id");
        final long edgeType = intern(stores, stores.getEdgeTypeUids(), "T");

        final long selfUid = intern(stores, stores.getNodeUids(), "self-loop");
        final long otherUid = intern(stores, stores.getNodeUids(), "other");

        stores.write(writer -> {
            stores.getNodes().insert(
                    writer, selfUid, T1, List.of(nodeLabel), Map.of("id", ValString.create("self-loop")));
            stores.getNodes().insert(
                    writer, otherUid, T1, List.of(nodeLabel), Map.of("id", ValString.create("other")));

            stores.getPropertyIndex().insert(
                    writer, nodeLabel, idKey, anchorBytes("self-loop"), selfUid);

            // The self-loop edge: src == dst == selfUid.
            stores.getOutEdges().insert(writer, selfUid, edgeType, selfUid, T1, Map.of());
            stores.getInEdges().insert(writer, selfUid, edgeType, selfUid, T1, Map.of());

            // A normal bidirectional pair, which the self-loop fix must not affect.
            stores.getOutEdges().insert(writer, selfUid, edgeType, otherUid, T1, Map.of());
            stores.getInEdges().insert(writer, selfUid, edgeType, otherUid, T1, Map.of());
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
                    writer, deviceLabel, idKey, anchorBytes("d-42"), deviceUid);

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
                    writer, deviceLabel, idKey, anchorBytes("d-42"), deviceUid);

            stores.getOutEdges().insert(writer, deviceUid, connectedTo, accountUid, oldIdentityStart, Map.of());
            stores.getInEdges().insert(writer, deviceUid, connectedTo, accountUid, oldIdentityStart, Map.of());
            return null;
        });
    }

    /**
     * Task 1.5: a single officer investigating four
     * crimes - two "Drugs" (severity 3, 5), one "Burglary" (severity 2), and one "Fraud" with NO {@code severity}
     * property at all, so {@code count(c.severity)} can differ from {@code count(*)}, and {@code sum}/
     * {@code avg}/{@code min}/{@code max} can be tested against a group with no numeric values. The anchor is
     * the (indexed) officer, reached backward from each crime, so grouping/aggregating over multiple crimes needs
     * no anchor-property-index workaround (an anchor MATCH still requires a label + property predicate - see
     * {@code GraphTraversalEngine.resolveAnchors} - but the group-by/aggregate columns here are all on the hop
     * target {@code c}, not the anchor).
     */
    private static void seedOfficerInvestigatingCrimes(final GraphStores stores) {
        final long officerLabel = intern(stores, stores.getLabelUids(), "Officer");
        final long crimeLabel = intern(stores, stores.getLabelUids(), "Crime");
        final long idKey = intern(stores, stores.getPropertyKeyUids(), "id");
        final long investigatedBy = intern(stores, stores.getEdgeTypeUids(), "INVESTIGATED_BY");

        final long officerUid = intern(stores, stores.getNodeUids(), "o-larive");
        final long c1Uid = intern(stores, stores.getNodeUids(), "c1");
        final long c2Uid = intern(stores, stores.getNodeUids(), "c2");
        final long c3Uid = intern(stores, stores.getNodeUids(), "c3");
        final long c4Uid = intern(stores, stores.getNodeUids(), "c4");

        stores.write(writer -> {
            stores.getNodes().insert(
                    writer, officerUid, T1, List.of(officerLabel), Map.of("id", ValString.create("o-larive")));
            stores.getNodes().insert(writer, c1Uid, T1, List.of(crimeLabel),
                    Map.of("id", ValString.create("c1"), "type", ValString.create("Drugs"),
                            "severity", ValLong.create(3)));
            stores.getNodes().insert(writer, c2Uid, T1, List.of(crimeLabel),
                    Map.of("id", ValString.create("c2"), "type", ValString.create("Drugs"),
                            "severity", ValLong.create(5)));
            stores.getNodes().insert(writer, c3Uid, T1, List.of(crimeLabel),
                    Map.of("id", ValString.create("c3"), "type", ValString.create("Burglary"),
                            "severity", ValLong.create(2)));
            stores.getNodes().insert(writer, c4Uid, T1, List.of(crimeLabel),
                    Map.of("id", ValString.create("c4"), "type", ValString.create("Fraud")));

            stores.getPropertyIndex().insert(
                    writer, officerLabel, idKey, anchorBytes("o-larive"), officerUid);

            // Every edge is written to both GraphAdjacencyDb and GraphInEdgeDb (P1.1's dual-write contract).
            // Written crime -> officer (matching the POLE report's own (c:Crime)-[:INVESTIGATED_BY]->(o:Officer)
            // direction); tests anchor on the officer and traverse backward ("<-") to reach every crime.
            for (final long crimeUid : List.of(c1Uid, c2Uid, c3Uid, c4Uid)) {
                stores.getOutEdges().insert(writer, crimeUid, investigatedBy, officerUid, T1, Map.of());
                stores.getInEdges().insert(writer, crimeUid, investigatedBy, officerUid, T1, Map.of());
            }
            return null;
        });
    }

    /**
     * A hub node with {@code count} outgoing {@code LINKS} edges to distinct leaf nodes, each carrying a distinct
     * numeric {@code rank} property ({@code 0..count-1}) - built for the F3 accumulation-ceiling/bounded-top-N
     * regression tests below, which need "many more matching rows than a LIMIT" without a genuinely million-row
     * fixture.
     */
    private static void seedHubWithRankedLeaves(final GraphStores stores, final int count) {
        final long hubLabel = intern(stores, stores.getLabelUids(), "Hub");
        final long leafLabel = intern(stores, stores.getLabelUids(), "Leaf");
        final long idKey = intern(stores, stores.getPropertyKeyUids(), "id");
        final long links = intern(stores, stores.getEdgeTypeUids(), "LINKS");
        final long hubUid = intern(stores, stores.getNodeUids(), "hub");

        final long[] leafUids = new long[count];
        for (int i = 0; i < count; i++) {
            leafUids[i] = intern(stores, stores.getNodeUids(), "leaf-" + String.format("%02d", i));
        }

        stores.write(writer -> {
            stores.getNodes().insert(
                    writer, hubUid, T1, List.of(hubLabel), Map.of("id", ValString.create("hub")));
            stores.getPropertyIndex().insert(
                    writer, hubLabel, idKey, anchorBytes("hub"), hubUid);

            for (int i = 0; i < count; i++) {
                stores.getNodes().insert(writer, leafUids[i], T1, List.of(leafLabel),
                        Map.of("id", ValString.create("leaf-" + String.format("%02d", i)),
                                "rank", ValLong.create(i)));
                insertEdge(stores, writer, hubUid, links, leafUids[i], T1);
            }
            return null;
        });
    }

    private static long intern(final GraphStores stores, final UidLookupDb db,
                               final String key) {
        return stores.write(writer -> db.put(writer.getWriteTxn(), directBuffer(key), uidBuffer ->
                UnsignedBytesInstances.ofLength(uidBuffer.remaining())
                        .get(uidBuffer.duplicate())));
    }

    /**
     * The bytes a string property's anchor is keyed on. Goes through the encoder rather than taking the raw
     * UTF-8, because that is what ingest does - and since numbers are keyed by value rather than by text, the
     * two are no longer the same thing even for a string.
     */
    private static byte[] anchorBytes(final String value) {
        return GraphAnchorEncoding.anchorValueBytes(ValString.create(value));
    }

    private static ByteBuffer directBuffer(final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }
}
