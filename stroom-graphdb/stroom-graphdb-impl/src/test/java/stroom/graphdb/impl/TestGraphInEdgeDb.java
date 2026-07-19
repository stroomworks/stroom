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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task P1.1: a round-trip test proving {@link GraphInEdgeDb} reads back the expected as-of sources - the
 * reverse-direction analogue of {@code TestGraphPhysicalStores}'s out-edge coverage, over the same physical
 * key/value contract (append-only versions, tombstone-not-delete).
 */
class TestGraphInEdgeDb {

    private static final GraphDbDoc DOC = GraphDbDoc.builder().uuid("graph-uuid").name("TestGraph").build();
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00.000Z");
    private static final Instant T2 = Instant.parse("2026-06-01T00:00:00.000Z");

    @Test
    void expandIn_returnsTheExpectedAsOfSources(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root, DOC)) {
            final long deviceUid = intern(stores, stores.getNodeUids(), "d-42");
            final long accountAUid = intern(stores, stores.getNodeUids(), "account-a");
            final long accountBUid = intern(stores, stores.getNodeUids(), "account-b");
            final long connectedTo = intern(stores, stores.getEdgeTypeUids(), "CONNECTED_TO");

            stores.write(writer -> {
                // Two sources (accountA, accountB) both link to the same destination (device) - accountB's edge
                // starts later, mirroring TestGraphPhysicalStores' out-edge as-of coverage but in reverse.
                stores.getInEdges().insert(writer, accountAUid, connectedTo, deviceUid, T1, Map.of());
                stores.getInEdges().insert(writer, accountBUid, connectedTo, deviceUid, T2, Map.of());
                return null;
            });

            final List<Long> sourcesBeforeT2 = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getInEdges().expandIn(readTxn, deviceUid, connectedTo, T1.plusSeconds(1),
                        neighbour -> sourcesBeforeT2.add(neighbour.srcUid()));
                return null;
            });
            assertThat(sourcesBeforeT2).containsExactly(accountAUid);

            final List<Long> sourcesAtT2 = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getInEdges().expandIn(readTxn, deviceUid, connectedTo, T2,
                        neighbour -> sourcesAtT2.add(neighbour.srcUid()));
                return null;
            });
            assertThat(sourcesAtT2).containsExactlyInAnyOrder(accountAUid, accountBUid);
        }
    }

    @Test
    void expandIn_stopsEmittingASourceOnceItsEdgeIsTombstoned(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root, DOC)) {
            final long src = intern(stores, stores.getNodeUids(), "src");
            final long dst = intern(stores, stores.getNodeUids(), "dst");
            final long edgeType = intern(stores, stores.getEdgeTypeUids(), "LINKS_TO");

            stores.write(writer -> {
                stores.getInEdges().insert(writer, src, edgeType, dst, T1, Map.of());
                stores.getInEdges().delete(writer, src, edgeType, dst, T2);
                return null;
            });

            final List<Long> beforeDelete = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getInEdges().expandIn(
                        readTxn, dst, edgeType, T1.plusSeconds(1), n -> beforeDelete.add(n.srcUid()));
                return null;
            });
            assertThat(beforeDelete).containsExactly(src);

            final List<Long> afterDelete = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getInEdges().expandIn(readTxn, dst, edgeType, T2, n -> afterDelete.add(n.srcUid()));
                return null;
            });
            assertThat(afterDelete).isEmpty();
        }
    }

    @Test
    void expandInWindow_returnsTheLatestIntersectingVersionPerSource(@TempDir final Path root) {
        // Task P4.1: the in-edge mirror of TestGraphPhysicalStores' expandOutWindow coverage.
        try (GraphStores stores = GraphStores.provision(root, DOC)) {
            final long dst = intern(stores, stores.getNodeUids(), "dst");
            final long srcA = intern(stores, stores.getNodeUids(), "srcA");
            final long edgeType = intern(stores, stores.getEdgeTypeUids(), "LINKS_TO");
            final Instant t1 = Instant.parse("2020-01-01T00:00:00Z");
            final Instant t2 = Instant.parse("2020-06-01T00:00:00Z");

            stores.write(writer -> {
                stores.getInEdges().insert(writer, srcA, edgeType, dst, t1, Map.of());
                stores.getInEdges().insert(writer, srcA, edgeType, dst, t2, Map.of());
                return null;
            });

            final List<Long> sources = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getInEdges().expandInWindow(
                        readTxn, dst, edgeType, t2.minus(Duration.ofDays(1)), t2, n -> sources.add(n.srcUid()));
                return null;
            });
            assertThat(sources).containsExactly(srcA);

            final List<Long> none = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getInEdges().expandInWindow(
                        readTxn, dst, edgeType, t1.minus(Duration.ofDays(50)), t1.minus(Duration.ofDays(40)),
                        n -> none.add(n.srcUid()));
                return null;
            });
            assertThat(none).isEmpty();
        }
    }

    @Test
    void expandInWindow_aTombstoneAsTheLatestIntersectingVersionExcludesTheSource(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root, DOC)) {
            final long dst = intern(stores, stores.getNodeUids(), "dst");
            final long src = intern(stores, stores.getNodeUids(), "src");
            final long edgeType = intern(stores, stores.getEdgeTypeUids(), "LINKS_TO");
            final Instant t1 = Instant.parse("2020-01-01T00:00:00Z");
            final Instant t2 = Instant.parse("2020-06-01T00:00:00Z");

            stores.write(writer -> {
                stores.getInEdges().insert(writer, src, edgeType, dst, t1, Map.of());
                stores.getInEdges().delete(writer, src, edgeType, dst, t2);
                return null;
            });

            final List<Long> sources = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getInEdges().expandInWindow(
                        readTxn, dst, edgeType, t1.plus(Duration.ofDays(5)), t2.plus(Duration.ofDays(20)),
                        n -> sources.add(n.srcUid()));
                return null;
            });
            assertThat(sources).isEmpty();
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
