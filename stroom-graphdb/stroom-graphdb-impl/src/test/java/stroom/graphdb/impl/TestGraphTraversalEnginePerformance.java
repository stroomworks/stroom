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
import stroom.query.language.functions.ValString;
import stroom.query.planner.cypher.CompiledCypherPlan;
import stroom.query.planner.cypher.CypherToLogicalPlan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task P8.2: a synthetic-data performance test for {@link GraphTraversalEngine}, mirroring
 * {@code stroom.planb.impl.dao.TestStateDb}'s own {@code ...Performance()} idiom - the one real, used perf-test
 * precedent in this codebase (a data volume well beyond any correctness fixture, with elapsed time logged for a
 * human to eyeball, not a hard ceiling assertion - CI hardware variance makes a ceiling assertion the wrong idiom
 * here, and nothing else in this repo asserts on elapsed time either). Gives future cursor/buffer-reuse work
 * (e.g. {@code GraphNodeDb.getNode} allocates a fresh direct {@code ByteBuffer} per neighbour dereferenced during
 * a traversal - a real, cited cost, not this task's job to fix) a baseline to measure against. Correctness of the
 * returned rows IS asserted - a perf test that silently stopped returning correct rows would be worse than none.
 */
class TestGraphTraversalEnginePerformance {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestGraphTraversalEnginePerformance.class);

    private static final GraphDbDoc DOC = GraphDbDoc.builder().uuid("graph-uuid").name("PerfGraph").build();
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00.000Z");
    private static final int FAN_OUT = 2_000;
    private static final int CHAIN_LENGTH = 50;

    @Test
    void fixedLengthHop_overAWideFanOutHub(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph-fanout"), DOC)) {
            seedHubAndSpokes(stores, FAN_OUT);

            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (d:Device {id: 'hub'})-[:CONNECTED_TO]->(a:Account) RETURN a.id");

            final long start = System.nanoTime();
            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            LOGGER.info("fixedLengthHop_overAWideFanOutHub: {} rows in {} ms", rows.size(), elapsedMs);

            assertThat(rows).hasSize(FAN_OUT);
        }
    }

    @Test
    void variableLengthPath_overALongChain(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph-chain"), DOC)) {
            seedChain(stores, CHAIN_LENGTH);

            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
            final CompiledCypherPlan compiled = compile(
                    "MATCH (a:Node {id: 'n0'})-[:NEXT*1.." + (CHAIN_LENGTH - 1) + "]->(b:Node) RETURN b.id");

            final long start = System.nanoTime();
            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            DateTimeSettings.builder().build()));
            final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            LOGGER.info("variableLengthPath_overALongChain: {} rows in {} ms", rows.size(), elapsedMs);

            assertThat(rows).hasSize(CHAIN_LENGTH - 1);
        }
    }

    private static CompiledCypherPlan compile(final String cypher) {
        return new CypherToLogicalPlan().compile(CypherQueryParser.parse(cypher));
    }

    /** One {@code Device} hub connected to {@code fanOut} distinct {@code Account} nodes - a wide fixed-length
     * fan-out, exercising {@code GraphTraversalEngine.acceptChainNeighbour}/{@code GraphNodeDb.getNode} at a
     * volume no correctness fixture in this module approaches. */
    private static void seedHubAndSpokes(final GraphStores stores, final int fanOut) {
        final long deviceLabel = intern(stores, stores.getLabelUids(), "Device");
        final long accountLabel = intern(stores, stores.getLabelUids(), "Account");
        final long idKey = intern(stores, stores.getPropertyKeyUids(), "id");
        final long connectedTo = intern(stores, stores.getEdgeTypeUids(), "CONNECTED_TO");
        final long hubUid = intern(stores, stores.getNodeUids(), "hub");

        stores.write(writer -> {
            stores.getNodes().insert(
                    writer, hubUid, T1, List.of(deviceLabel), Map.of("id", ValString.create("hub")));
            stores.getPropertyIndex().insert(
                    writer, deviceLabel, idKey, "hub".getBytes(StandardCharsets.UTF_8), hubUid);
            return null;
        });

        for (int i = 0; i < fanOut; i++) {
            final String accountId = "account-" + i;
            final long accountUid = intern(stores, stores.getNodeUids(), accountId);
            stores.write(writer -> {
                stores.getNodes().insert(writer, accountUid, T1, List.of(accountLabel),
                        Map.of("id", ValString.create(accountId)));
                stores.getOutEdges().insert(writer, hubUid, connectedTo, accountUid, T1, Map.of());
                stores.getInEdges().insert(writer, hubUid, connectedTo, accountUid, T1, Map.of());
                return null;
            });
        }
    }

    /** A linear chain {@code n0 -NEXT-> n1 -NEXT-> ... -NEXT-> n(length-1)} - a variable-length BFS with no
     * fan-out (each state has exactly one neighbour), isolating hop-count cost from fan-out cost. */
    private static void seedChain(final GraphStores stores, final int length) {
        final long nodeLabel = intern(stores, stores.getLabelUids(), "Node");
        final long idKey = intern(stores, stores.getPropertyKeyUids(), "id");
        final long next = intern(stores, stores.getEdgeTypeUids(), "NEXT");

        long previousUid = -1;
        for (int i = 0; i < length; i++) {
            final String nodeId = "n" + i;
            final long uid = intern(stores, stores.getNodeUids(), nodeId);
            final long finalPreviousUid = previousUid;
            stores.write(writer -> {
                stores.getNodes().insert(writer, uid, T1, List.of(nodeLabel), Map.of("id", ValString.create(nodeId)));
                if (finalPreviousUid != -1) {
                    stores.getOutEdges().insert(writer, finalPreviousUid, next, uid, T1, Map.of());
                    stores.getInEdges().insert(writer, finalPreviousUid, next, uid, T1, Map.of());
                }
                return null;
            });
            if (i == 0) {
                stores.write(writer -> {
                    stores.getPropertyIndex().insert(
                            writer, nodeLabel, idKey, "n0".getBytes(StandardCharsets.UTF_8), uid);
                    return null;
                });
            }
            previousUid = uid;
        }
    }

    private static long intern(final GraphStores stores, final UidLookupDb db, final String key) {
        return stores.write(writer -> db.put(writer.getWriteTxn(), directBuffer(key), uidBuffer ->
                UnsignedBytesInstances.ofLength(uidBuffer.remaining()).get(uidBuffer.duplicate())));
    }

    private static ByteBuffer directBuffer(final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }
}
