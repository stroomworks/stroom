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
import stroom.query.language.functions.ValString;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task PoC.4: a round-trip test proving {@link GraphNodeDb}/{@link GraphAdjacencyDb}/{@link GraphPropertyIndex}
 * read back the expected as-of neighbours and node version against a real temp-dir LMDB env, using
 * {@link GraphStores} exactly as {@code GraphTraversalEngine} (PoC.5) will: intern names via the interning
 * namespaces, write versioned nodes/edges/anchors, then anchor-seek -&gt; 1-hop expand -&gt; floor-filter.
 */
class TestGraphPhysicalStores {

    private static final GraphDbDoc DOC = GraphDbDoc.builder().uuid("graph-uuid").name("TestGraph").build();

    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00.000Z");
    private static final Instant T2 = Instant.parse("2026-06-01T00:00:00.000Z");

    @Test
    void anchorSeek_expand_andFloorFilter_readBackTheExpectedAsOfNeighbours(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph"), DOC)) {
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
                stores.getNodes().insert(writer, accountAUid, T1, List.of(accountLabel), Map.of());
                stores.getNodes().insert(writer, accountBUid, T1, List.of(accountLabel), Map.of());

                // Anchor the device by its external id.
                stores.getPropertyIndex().insert(
                        writer, deviceLabel, idKey, "d-42".getBytes(StandardCharsets.UTF_8), deviceUid);

                // d-42 -> account-a from T1 (still current); d-42 -> account-b only from T2 onward.
                stores.getOutEdges().insert(writer, deviceUid, connectedTo, accountAUid, T1, Map.of());
                stores.getOutEdges().insert(writer, deviceUid, connectedTo, accountBUid, T2, Map.of());
                return null;
            });

            // Anchor seek.
            final List<Long> anchors = stores.read(readTxn -> stores.getPropertyIndex().findAnchors(
                    readTxn, deviceLabel, idKey, "d-42".getBytes(StandardCharsets.UTF_8)));
            assertThat(anchors).containsExactly(deviceUid);

            // 1-hop expand as-of a time before account-b's edge existed.
            final List<Long> neighboursBeforeT2 = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getOutEdges().expandOut(readTxn, deviceUid, connectedTo, T1.plusSeconds(1),
                        neighbour -> neighboursBeforeT2.add(neighbour.dstUid()));
                return null;
            });
            assertThat(neighboursBeforeT2).containsExactly(accountAUid);

            // 1-hop expand as-of a time after both edges existed.
            final List<Long> neighboursAtT2 = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getOutEdges().expandOut(readTxn, deviceUid, connectedTo, T2,
                        neighbour -> neighboursAtT2.add(neighbour.dstUid()));
                return null;
            });
            assertThat(neighboursAtT2).containsExactlyInAnyOrder(accountAUid, accountBUid);

            // Floor-filter: the correct as-of node version for the anchor.
            final Optional<GraphNodeDb.NodeVersion> device = stores.read(
                    readTxn -> stores.getNodes().getNode(readTxn, deviceUid, T2));
            assertThat(device).isPresent();
            assertThat(device.get().labelUids()).containsExactly(deviceLabel);
            assertThat(device.get().properties()).containsEntry("id", ValString.create("d-42"));
        }
    }

    @Test
    void expandOut_stopsEmittingANeighbourOnceItsEdgeIsTombstoned(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph2"), DOC)) {
            final long src = intern(stores, stores.getNodeUids(), "src");
            final long dst = intern(stores, stores.getNodeUids(), "dst");
            final long edgeType = intern(stores, stores.getEdgeTypeUids(), "LINKS_TO");

            stores.write(writer -> {
                stores.getOutEdges().insert(writer, src, edgeType, dst, T1, Map.of());
                stores.getOutEdges().delete(writer, src, edgeType, dst, T2);
                return null;
            });

            final List<Long> beforeDelete = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getOutEdges().expandOut(
                        readTxn, src, edgeType, T1.plusSeconds(1), n -> beforeDelete.add(n.dstUid()));
                return null;
            });
            assertThat(beforeDelete).containsExactly(dst);

            final List<Long> afterDelete = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getOutEdges().expandOut(readTxn, src, edgeType, T2, n -> afterDelete.add(n.dstUid()));
                return null;
            });
            assertThat(afterDelete).isEmpty();
        }
    }

    @Test
    void getNode_returnsEmptyForAnInstantBeforeAnyVersionExisted(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph3"), DOC)) {
            final long nodeUid = intern(stores, stores.getNodeUids(), "n1");
            final long label = intern(stores, stores.getLabelUids(), "Thing");

            stores.write(writer -> {
                stores.getNodes().insert(writer, nodeUid, T2, List.of(label), Map.of());
                return null;
            });

            final Optional<GraphNodeDb.NodeVersion> tooEarly = stores.read(
                    readTxn -> stores.getNodes().getNode(readTxn, nodeUid, T1));
            assertThat(tooEarly).isEmpty();
        }
    }

    @Test
    void propertyIndex_supportsMultipleNodesSharingTheSameValue(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph4"), DOC)) {
            final long label = intern(stores, stores.getLabelUids(), "Account");
            final long statusKey = intern(stores, stores.getPropertyKeyUids(), "status");
            final long accountA = intern(stores, stores.getNodeUids(), "a");
            final long accountB = intern(stores, stores.getNodeUids(), "b");

            stores.write(writer -> {
                stores.getPropertyIndex().insert(
                        writer, label, statusKey, "active".getBytes(StandardCharsets.UTF_8), accountA);
                stores.getPropertyIndex().insert(
                        writer, label, statusKey, "active".getBytes(StandardCharsets.UTF_8), accountB);
                return null;
            });

            final List<Long> anchors = stores.read(readTxn -> stores.getPropertyIndex().findAnchors(
                    readTxn, label, statusKey, "active".getBytes(StandardCharsets.UTF_8)));
            assertThat(anchors).containsExactlyInAnyOrder(accountA, accountB);
        }
    }

    @Test
    void propertyIndex_resolvesAnchorsAtEveryValueTierBoundary(@TempDir final Path root) {
        // Task P1.3: DIRECT (<=32 bytes), UID_LOOKUP (33-511 bytes), HASH_LOOKUP (>511 bytes) - prove findAnchors
        // resolves correctly right at and either side of both tier boundaries, not just for short values. Each
        // length uses a distinct fill character (not just a distinct length) so no value is a byte-for-byte
        // prefix of another - GraphPropertyIndex's DIRECT tier has no length delimiter (see its Javadoc's
        // documented limitation), so same-character values of different lengths would otherwise cross-match.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph5"), DOC)) {
            final long label = intern(stores, stores.getLabelUids(), "Thing");
            final long key = intern(stores, stores.getPropertyKeyUids(), "value");

            final Map<Integer, Long> nodeUidByLength = new HashMap<>();
            final int[] lengths = {31, 32, 33, 510, 511, 512};
            for (int i = 0; i < lengths.length; i++) {
                final int length = lengths[i];
                final long nodeUid = intern(stores, stores.getNodeUids(), "n-" + length);
                nodeUidByLength.put(length, nodeUid);
                final byte[] valueBytes = valueOfLength(length, i);
                stores.write(writer -> {
                    stores.getPropertyIndex().insert(writer, label, key, valueBytes, nodeUid);
                    return null;
                });
            }

            for (int i = 0; i < lengths.length; i++) {
                final int length = lengths[i];
                final byte[] valueBytes = valueOfLength(length, i);
                final List<Long> anchors = stores.read(readTxn -> stores.getPropertyIndex().findAnchors(
                        readTxn, label, key, valueBytes));
                assertThat(anchors)
                        .as("anchors for a %d-byte value", length)
                        .containsExactly(nodeUidByLength.get(length));
            }

            // A value that was never inserted at a lookup-backed tier resolves to no anchors, not an error.
            final List<Long> neverInserted = stores.read(readTxn -> stores.getPropertyIndex().findAnchors(
                    readTxn, label, key, "y".repeat(100).getBytes(StandardCharsets.UTF_8)));
            assertThat(neverInserted).isEmpty();
        }
    }

    @Test
    void nodeDb_deleteOldData_keepsFloorVersionAndDeletesOlderOnes(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph6"), DOC)) {
            final long labelA = intern(stores, stores.getLabelUids(), "A");
            final long labelB = intern(stores, stores.getLabelUids(), "B");
            final long labelC = intern(stores, stores.getLabelUids(), "C");
            final long nodeUid = intern(stores, stores.getNodeUids(), "n1");
            final Instant t1 = Instant.parse("2020-01-01T00:00:00Z");
            final Instant t2 = Instant.parse("2020-06-01T00:00:00Z");
            final Instant t3 = Instant.parse("2021-01-01T00:00:00Z");
            final Instant deleteBefore = t2.plusSeconds(1);

            stores.write(writer -> {
                stores.getNodes().insert(writer, nodeUid, t1, List.of(labelA), Map.of());
                stores.getNodes().insert(writer, nodeUid, t2, List.of(labelB), Map.of());
                stores.getNodes().insert(writer, nodeUid, t3, List.of(labelC), Map.of());
                return null;
            });

            final long deletedCount = stores.write(writer -> stores.getNodes().deleteOldData(
                    writer, deleteBefore, stores.getNodeUidRecorder(), stores.getLabelUidRecorder()));
            assertThat(deletedCount).isEqualTo(1);

            final Optional<GraphNodeDb.NodeVersion> deletedVersion = stores.read(
                    readTxn -> stores.getNodes().getNode(readTxn, nodeUid, t1.plusSeconds(1)));
            assertThat(deletedVersion)
                    .as("the @t1 version was superseded within the retention window and is now gone")
                    .isEmpty();

            final Optional<GraphNodeDb.NodeVersion> floor = stores.read(
                    readTxn -> stores.getNodes().getNode(readTxn, nodeUid, deleteBefore));
            assertThat(floor).isPresent();
            assertThat(floor.get().labelUids()).containsExactly(labelB);

            final Optional<GraphNodeDb.NodeVersion> latest = stores.read(
                    readTxn -> stores.getNodes().getNode(readTxn, nodeUid, t3));
            assertThat(latest).isPresent();
            assertThat(latest.get().labelUids()).containsExactly(labelC);

            // deleteOldData recorded the survivors (B, C) as used but did not itself sweep - a separate,
            // explicit deleteUnused pass (GraphStores.deleteOldData's job in production) is needed to remove A.
            stores.write(writer -> {
                stores.read(readTxn -> {
                    stores.getLabelUidRecorder().deleteUnused(readTxn, writer);
                    return null;
                });
                return null;
            });
            assertThat(lookup(stores, stores.getLabelUids(), "A")).isEmpty();
            assertThat(lookup(stores, stores.getLabelUids(), "B")).contains(labelB);
            assertThat(lookup(stores, stores.getLabelUids(), "C")).contains(labelC);
        }
    }

    @Test
    void adjacencyDbs_deleteOldData_keepsFloorVersionAndDeletesOlderOnes(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph7"), DOC)) {
            final long src = intern(stores, stores.getNodeUids(), "src");
            final long dst = intern(stores, stores.getNodeUids(), "dst");
            final long edgeType = intern(stores, stores.getEdgeTypeUids(), "LINKS_TO");
            final Instant t1 = Instant.parse("2020-01-01T00:00:00Z");
            final Instant t2 = Instant.parse("2020-06-01T00:00:00Z");
            final Instant t3 = Instant.parse("2021-01-01T00:00:00Z");
            final Instant deleteBefore = t2.plusSeconds(1);

            stores.write(writer -> {
                stores.getOutEdges().insert(writer, src, edgeType, dst, t1, Map.of());
                stores.getOutEdges().insert(writer, src, edgeType, dst, t2, Map.of());
                stores.getOutEdges().delete(writer, src, edgeType, dst, t3);
                stores.getInEdges().insert(writer, src, edgeType, dst, t1, Map.of());
                stores.getInEdges().insert(writer, src, edgeType, dst, t2, Map.of());
                stores.getInEdges().delete(writer, src, edgeType, dst, t3);
                return null;
            });

            final long outDeleted = stores.write(writer -> stores.getOutEdges().deleteOldData(
                    writer, deleteBefore, stores.getNodeUidRecorder(), stores.getEdgeTypeUidRecorder()));
            final long inDeleted = stores.write(writer -> stores.getInEdges().deleteOldData(
                    writer, deleteBefore, stores.getNodeUidRecorder(), stores.getEdgeTypeUidRecorder()));
            assertThat(outDeleted).isEqualTo(1);
            assertThat(inDeleted).isEqualTo(1);

            final List<Long> outBeforeFloor = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getOutEdges().expandOut(readTxn, src, edgeType, t1.plusSeconds(1),
                        n -> outBeforeFloor.add(n.dstUid()));
                return null;
            });
            assertThat(outBeforeFloor)
                    .as("the @t1 out-edge version was superseded and is now gone")
                    .isEmpty();

            final List<Long> outAtFloor = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getOutEdges().expandOut(readTxn, src, edgeType, deleteBefore, n -> outAtFloor.add(n.dstUid()));
                return null;
            });
            assertThat(outAtFloor).containsExactly(dst);

            final List<Long> inAtFloor = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getInEdges().expandIn(readTxn, dst, edgeType, deleteBefore, n -> inAtFloor.add(n.srcUid()));
                return null;
            });
            assertThat(inAtFloor).containsExactly(src);

            final List<Long> outAtT3 = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getOutEdges().expandOut(readTxn, src, edgeType, t3, n -> outAtT3.add(n.dstUid()));
                return null;
            });
            assertThat(outAtT3)
                    .as("the @t3 tombstone still applies - the edge is absent from t3 onward")
                    .isEmpty();
        }
    }

    private static byte[] valueOfLength(final int length, final int distinguisher) {
        final char fillChar = (char) ('a' + distinguisher);
        return String.valueOf(fillChar).repeat(length).getBytes(StandardCharsets.UTF_8);
    }

    private static long intern(final GraphStores stores, final UidLookupDb db,
                               final String key) {
        return stores.write(writer -> db.put(writer.getWriteTxn(), directBuffer(key), uidBuffer ->
                UnsignedBytesInstances.ofLength(uidBuffer.remaining()).get(uidBuffer.duplicate())));
    }

    private static Optional<Long> lookup(final GraphStores stores, final UidLookupDb db, final String key) {
        return stores.read(readTxn -> db.get(
                readTxn,
                directBuffer(key),
                maybeUid -> maybeUid.map(uidBuffer ->
                        UnsignedBytesInstances.ofLength(uidBuffer.remaining()).get(uidBuffer.duplicate()))));
    }

    private static ByteBuffer directBuffer(final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }
}
