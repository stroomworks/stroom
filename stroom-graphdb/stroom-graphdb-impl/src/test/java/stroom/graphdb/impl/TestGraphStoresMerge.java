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
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValString;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests {@link GraphStores#merge} - folding an independently-written fragment into an authoritative store.
 *
 * <p>Merge cannot copy bytes: every graph key embeds interned UIDs allocated per-environment, so the same UID
 * means different things in two stores. These tests exist because the failure mode of getting that wrong is a
 * store that queries cleanly and answers incorrectly, rather than one that throws.</p>
 */
class TestGraphStoresMerge {

    private static final GraphDbDoc DOC = GraphDbDoc.builder().uuid("graph-uuid").name("TestGraph").build();
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00.000Z");
    private static final Instant T2 = Instant.parse("2026-06-01T00:00:00.000Z");

    @Test
    void traversalSpanningTwoFragments_resolvesAfterMerge(@TempDir final Path root) {
        // The headline case. Edge a->b lives in one fragment and b->c in another, so neither fragment can answer
        // the two-hop pattern and no amount of merging independent query results would reconstruct it. Only a
        // merged store can.
        final Path targetDir = root.resolve("target");
        final Path fragment1 = root.resolve("fragment1");
        final Path fragment2 = root.resolve("fragment2");

        try (GraphStores stores = GraphStores.provision(fragment1, DOC)) {
            writeNode(stores, "a", "Thing");
            writeNode(stores, "b", "Thing");
            writeEdge(stores, "a", "LINKS", "b");
        }
        try (GraphStores stores = GraphStores.provision(fragment2, DOC)) {
            writeNode(stores, "b", "Thing");
            writeNode(stores, "c", "Thing");
            writeEdge(stores, "b", "LINKS", "c");
        }

        try (GraphStores target = GraphStores.provision(targetDir, DOC)) {
            target.merge(fragment1);
            target.merge(fragment2);

            // Walk a -> b -> c entirely within the merged store.
            final long a = uidOf(target, "a");
            final long linksType = typeUidOf(target, "LINKS");
            final List<Long> fromA = neighbours(target, a, linksType);
            assertThat(fromA).hasSize(1);

            final List<Long> fromB = neighbours(target, fromA.getFirst(), linksType);
            assertThat(fromB).hasSize(1);
            assertThat(externalIdOf(target, fromB.getFirst())).isEqualTo("c");
        }
    }

    @Test
    void fragmentsThatAssignTheSameUidToDifferentNames_mergeCorrectly(@TempDir final Path root) {
        // Each fragment interns from its own counter, so fragment1's uid 1 and fragment2's uid 1 are different
        // entities. Interning in opposite orders guarantees the UID spaces collide, which is exactly what the
        // translation maps exist to survive. A byte-level copy would silently conflate the two.
        final Path targetDir = root.resolve("target");
        final Path fragment1 = root.resolve("fragment1");
        final Path fragment2 = root.resolve("fragment2");

        try (GraphStores stores = GraphStores.provision(fragment1, DOC)) {
            writeNode(stores, "first", "Thing");
            writeNode(stores, "second", "Thing");
        }
        try (GraphStores stores = GraphStores.provision(fragment2, DOC)) {
            writeNode(stores, "second", "Thing");
            writeNode(stores, "first", "Thing");
        }

        try (GraphStores target = GraphStores.provision(targetDir, DOC)) {
            target.merge(fragment1);
            target.merge(fragment2);

            final long first = uidOf(target, "first");
            final long second = uidOf(target, "second");
            assertThat(first).isNotEqualTo(second);
            assertThat(externalIdOf(target, first)).isEqualTo("first");
            assertThat(externalIdOf(target, second)).isEqualTo("second");
        }
    }

    @Test
    void mergingTheSameFragmentTwice_changesNothing(@TempDir final Path root) {
        // Idempotency is what makes a partially-delivered fragment safe to resend. If anything in the merge path
        // ever stamps a time or aggregates a value, this fails.
        final Path targetDir = root.resolve("target");
        final Path fragment = root.resolve("fragment");

        try (GraphStores stores = GraphStores.provision(fragment, DOC)) {
            writeNode(stores, "a", "Thing");
            writeNode(stores, "b", "Thing");
            writeEdge(stores, "a", "LINKS", "b");
        }

        try (GraphStores target = GraphStores.provision(targetDir, DOC)) {
            target.merge(fragment);
            final Counts afterFirst = countsOf(target);

            target.merge(fragment);
            assertThat(countsOf(target)).isEqualTo(afterFirst);
        }
    }

    @Test
    void mergePreservesEveryVersion_notJustTheLatest(@TempDir final Path root) {
        // Merge reproduces a fragment's whole history, so a point-in-time query against the merged store must see
        // what it would have seen against the fragment.
        final Path targetDir = root.resolve("target");
        final Path fragment = root.resolve("fragment");

        try (GraphStores stores = GraphStores.provision(fragment, DOC)) {
            writeNode(stores, "a", "Thing", T1, Map.of("state", ValString.create("old")));
            writeNode(stores, "a", "Thing", T2, Map.of("state", ValString.create("new")));
        }

        try (GraphStores target = GraphStores.provision(targetDir, DOC)) {
            target.merge(fragment);

            final long a = uidOf(target, "a");
            assertThat(propertyAsOf(target, a, T1)).isEqualTo("old");
            assertThat(propertyAsOf(target, a, T2)).isEqualTo("new");
        }
    }

    @Test
    void mergedAnchors_matchThoseWrittenByDirectIngest(@TempDir final Path root) {
        // Anchors are rebuilt during merge rather than copied, so they must come out byte-identical to a direct
        // ingest of the same data - otherwise a merged graph answers property-anchored queries differently from a
        // single-node one. Values either side of the index's 32-byte inline tier boundary are the interesting ones.
        final String shortValue = "x".repeat(10);
        final String tierBoundary = "y".repeat(32);
        final String longValue = "z".repeat(33);

        final Path direct = root.resolve("direct");
        final Path fragment = root.resolve("fragment");
        final Path merged = root.resolve("merged");

        for (final Path dir : List.of(direct, fragment)) {
            try (GraphStores stores = GraphStores.provision(dir, DOC)) {
                writeNode(stores, "a", "Thing", T1, Map.of(
                        "short", ValString.create(shortValue),
                        "boundary", ValString.create(tierBoundary),
                        "long", ValString.create(longValue)));
            }
        }

        try (GraphStores target = GraphStores.provision(merged, DOC)) {
            target.merge(fragment);
            try (GraphStores directStores = GraphStores.open(direct, DOC, true)) {
                assertThat(countsOf(target).anchors()).isEqualTo(countsOf(directStores).anchors());
            }
        }
    }

    @Test
    void mergingAFragmentWithAMismatchedStamp_isRefused(@TempDir final Path root) {
        final Path targetDir = root.resolve("target");
        final Path fragment = root.resolve("fragment");

        try (GraphStores stores = GraphStores.provision(fragment, DOC)) {
            writeNode(stores, "a", "Thing");
        }
        GraphSchemaDbTestSupport.overwriteSchemaVersion(
                fragment, DOC, GraphSchemaDb.CURRENT_SCHEMA_VERSION + 1);

        try (GraphStores target = GraphStores.provision(targetDir, DOC)) {
            assertThatThrownBy(() -> target.merge(fragment))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Schema version mismatch");

            // Nothing was written - a refused fragment must not leave a partial merge behind.
            assertThat(countsOf(target).nodes()).isZero();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private record Counts(long nodes, long outEdges, long inEdges, long anchors) {

    }

    private static Counts countsOf(final GraphStores stores) {
        return stores.read(txn -> new Counts(
                stores.getNodes().count(txn),
                stores.getOutEdges().count(txn),
                stores.getInEdges().count(txn),
                stores.getPropertyIndex().count(txn)));
    }

    private static void writeNode(final GraphStores stores, final String id, final String label) {
        writeNode(stores, id, label, T1, Map.of());
    }

    private static void writeNode(final GraphStores stores,
                                  final String id,
                                  final String label,
                                  final Instant validFrom,
                                  final Map<String, Val> properties) {
        // Everything happens inside ONE writer: GraphStores.write takes the environment's single write lock, so a
        // nested write() would deadlock against its own outer transaction.
        stores.write(writer -> {
            final long nodeUid = intern(writer, stores.getNodeUids(), id, GraphStores.NODE_UID_WIDTH);
            final long labelUid = intern(writer, stores.getLabelUids(), label, GraphStores.TYPE_UID_WIDTH);
            stores.getNodes().insert(writer, nodeUid, validFrom, List.of(labelUid), properties);
            for (final Map.Entry<String, Val> property : properties.entrySet()) {
                final long propKeyUid = intern(
                        writer, stores.getPropertyKeyUids(), property.getKey(), GraphStores.TYPE_UID_WIDTH);
                stores.getPropertyIndex().insert(
                        writer,
                        labelUid,
                        propKeyUid,
                        GraphAnchorEncoding.anchorValueBytes(property.getValue()),
                        nodeUid);
            }
            return null;
        });
    }

    private static void writeEdge(final GraphStores stores,
                                  final String src,
                                  final String type,
                                  final String dst) {
        stores.write(writer -> {
            final long srcUid = intern(writer, stores.getNodeUids(), src, GraphStores.NODE_UID_WIDTH);
            final long dstUid = intern(writer, stores.getNodeUids(), dst, GraphStores.NODE_UID_WIDTH);
            final long typeUid = intern(writer, stores.getEdgeTypeUids(), type, GraphStores.TYPE_UID_WIDTH);
            stores.getOutEdges().insert(writer, srcUid, typeUid, dstUid, T1, Map.of());
            stores.getInEdges().insert(writer, srcUid, typeUid, dstUid, T1, Map.of());
            return null;
        });
    }

    /** Interns within an existing writer. Never opens its own transaction - see writeNode. */
    private static long intern(final LmdbWriter writer,
                               final UidLookupDb db,
                               final String name,
                               final int width) {
        return db.put(
                writer.getWriteTxn(),
                directBuffer(name),
                uidBuffer -> UnsignedBytesInstances.ofLength(width).get(uidBuffer.duplicate()));
    }

    private static long uidOf(final GraphStores stores, final String externalId) {
        return stores.write(writer ->
                intern(writer, stores.getNodeUids(), externalId, GraphStores.NODE_UID_WIDTH));
    }

    private static long typeUidOf(final GraphStores stores, final String type) {
        return stores.write(writer ->
                intern(writer, stores.getEdgeTypeUids(), type, GraphStores.TYPE_UID_WIDTH));
    }

    private static String externalIdOf(final GraphStores stores, final long nodeUid) {
        return stores.read(txn -> {
            final ByteBuffer value = stores.getNodeUids().getValue(txn, nodeUid);
            final byte[] bytes = new byte[value.remaining()];
            value.duplicate().get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        });
    }

    private static List<Long> neighbours(final GraphStores stores, final long fromUid, final long edgeTypeUid) {
        return stores.read(txn -> {
            final List<Long> result = new ArrayList<>();
            stores.getOutEdges().expandOut(txn, fromUid, edgeTypeUid, T2, neighbour ->
                    result.add(neighbour.dstUid()));
            return result;
        });
    }

    private static String propertyAsOf(final GraphStores stores, final long nodeUid, final Instant asOf) {
        return stores.read(txn -> {
            final Optional<GraphNodeDb.NodeVersion> version = stores.getNodes().getNode(txn, nodeUid, asOf);
            return version
                    .map(v -> v.properties().get("state"))
                    .map(Object::toString)
                    .orElse(null);
        });
    }

    private static ByteBuffer directBuffer(final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }
}
