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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
                stores.getNodes().insert(writer, deviceUid, T1, List.of(deviceLabel), new byte[0]);
                stores.getNodes().insert(writer, accountAUid, T1, List.of(accountLabel), new byte[0]);
                stores.getNodes().insert(writer, accountBUid, T1, List.of(accountLabel), new byte[0]);

                // Anchor the device by its external id.
                stores.getPropertyIndex().insert(
                        writer, deviceLabel, idKey, "d-42".getBytes(StandardCharsets.UTF_8), deviceUid);

                // d-42 -> account-a from T1 (still current); d-42 -> account-b only from T2 onward.
                stores.getOutEdges().insert(writer, deviceUid, connectedTo, accountAUid, T1, new byte[0]);
                stores.getOutEdges().insert(writer, deviceUid, connectedTo, accountBUid, T2, new byte[0]);
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
        }
    }

    @Test
    void expandOut_stopsEmittingANeighbourOnceItsEdgeIsTombstoned(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph2"), DOC)) {
            final long src = intern(stores, stores.getNodeUids(), "src");
            final long dst = intern(stores, stores.getNodeUids(), "dst");
            final long edgeType = intern(stores, stores.getEdgeTypeUids(), "LINKS_TO");

            stores.write(writer -> {
                stores.getOutEdges().insert(writer, src, edgeType, dst, T1, new byte[0]);
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
                stores.getNodes().insert(writer, nodeUid, T2, List.of(label), new byte[0]);
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

    private static long intern(final GraphStores stores, final UidLookupDb db,
                               final String key) {
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
