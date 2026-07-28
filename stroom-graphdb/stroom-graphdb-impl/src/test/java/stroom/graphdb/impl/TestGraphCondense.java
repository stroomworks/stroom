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
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValString;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers condensing: collapsing runs of consecutive identical versions.
 *
 * <p>This is the piece retention cannot do. Reloading a graph on a schedule re-asserts every node and edge whether
 * or not anything changed, so a week of unchanged data costs seven versions of everything - and each is a
 * legitimate version inside the retention window, so nothing ages it out.</p>
 *
 * <p>The property these tests exist to protect is that <b>no query result changes at any instant</b>. That is what
 * lets condensing run unconditionally, with no cut-off and no setting, so it is asserted directly rather than
 * inferred from row counts: every instant across the whole history is queried before and after and the answers
 * compared.</p>
 */
class TestGraphCondense {

    private static final GraphDbDoc DOC = GraphDbDoc.builder().uuid("condense").name("Condense").build();

    private static final Instant DAY_1 = Instant.parse("2026-01-01T00:00:00.000Z");
    private static final Instant DAY_2 = Instant.parse("2026-01-02T00:00:00.000Z");
    private static final Instant DAY_3 = Instant.parse("2026-01-03T00:00:00.000Z");
    private static final Instant DAY_4 = Instant.parse("2026-01-04T00:00:00.000Z");

    /**
     * The headline case: a node re-asserted unchanged on four days should keep one version, not four.
     */
    @Test
    void nodeReassertedUnchanged_collapsesToOneVersion(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("c1"), DOC)) {
            final long nodeUid = interned(stores, "n1");
            for (final Instant day : List.of(DAY_1, DAY_2, DAY_3, DAY_4)) {
                writeNode(stores, nodeUid, day, "active");
            }

            assertThat(stores.condense()).isEqualTo(3);
            assertThat(versionsOf(stores, nodeUid)).isEqualTo(1);
        }
    }

    /**
     * The load-bearing property: the answer at <b>every</b> instant is unchanged. A condense that kept the latest
     * of each run rather than the earliest would pass a row-count test and fail this one.
     */
    @Test
    void condensing_changesNoAnswerAtAnyInstant(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("c2"), DOC)) {
            final long nodeUid = interned(stores, "n1");
            // A run, then a change, then another run - so there is something to collapse either side of a real
            // transition.
            writeNode(stores, nodeUid, DAY_1, "active");
            writeNode(stores, nodeUid, DAY_2, "active");
            writeNode(stores, nodeUid, DAY_3, "suspended");
            writeNode(stores, nodeUid, DAY_4, "suspended");

            final List<String> before = statusAtEveryInstant(stores, nodeUid);

            assertThat(stores.condense()).isEqualTo(2);

            assertThat(statusAtEveryInstant(stores, nodeUid)).isEqualTo(before);
            // And the transition itself is intact, which is the whole point of a temporal store.
            assertThat(before).containsExactly(null, "active", "active", "suspended", "suspended");
        }
    }

    /**
     * A value that changes and then changes back must keep all three versions. Comparing against only the last
     * surviving value - rather than the immediately preceding one - would wrongly collapse the third into the
     * first and lose the middle state entirely.
     */
    @Test
    void valueThatChangesBack_keepsEveryVersion(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("c3"), DOC)) {
            final long nodeUid = interned(stores, "n1");
            writeNode(stores, nodeUid, DAY_1, "active");
            writeNode(stores, nodeUid, DAY_2, "suspended");
            writeNode(stores, nodeUid, DAY_3, "active");

            assertThat(stores.condense()).isZero();
            assertThat(versionsOf(stores, nodeUid)).isEqualTo(3);
            assertThat(statusAt(stores, nodeUid, DAY_2)).contains("suspended");
        }
    }

    /**
     * Runs must not be detected across two different nodes that happen to share a value. Key order puts a node's
     * versions together, but the very next entry belongs to another node.
     */
    @Test
    void twoNodesSharingAValue_areNotCollapsedIntoEachOther(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("c4"), DOC)) {
            final long first = interned(stores, "n1");
            final long second = interned(stores, "n2");
            writeNode(stores, first, DAY_1, "same");
            writeNode(stores, second, DAY_1, "same");

            assertThat(stores.condense()).isZero();
            assertThat(versionsOf(stores, first)).isEqualTo(1);
            assertThat(versionsOf(stores, second)).isEqualTo(1);
        }
    }

    /**
     * Edges condense too, and both directions must stay mirrors of each other - a one-sided edge is the invariant
     * this store is most able to break.
     */
    @Test
    void edgesCondense_andBothDirectionsStayMirrors(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("c5"), DOC)) {
            final long src = interned(stores, "n1");
            final long dst = interned(stores, "n2");
            final long edgeType = internedType(stores, "KNOWS");

            for (final Instant day : List.of(DAY_1, DAY_2, DAY_3)) {
                stores.write(writer -> {
                    stores.getOutEdges().insert(writer, src, edgeType, dst, day, Map.of());
                    stores.getInEdges().insert(writer, src, edgeType, dst, day, Map.of());
                    return null;
                });
            }

            // Two out-edge versions plus two in-edge versions removed.
            assertThat(stores.condense()).isEqualTo(4);

            final long outCount = stores.read(txn -> stores.getOutEdges().count(txn));
            final long inCount = stores.read(txn -> stores.getInEdges().count(txn));
            assertThat(outCount).as("out-edge versions").isEqualTo(1);
            assertThat(inCount).as("in-edge versions").isEqualTo(1);
        }
    }

    /**
     * Condensing an already-condensed store must do nothing, so the scheduled job is cheap when there is nothing
     * to do and cannot creep.
     */
    @Test
    void condensingTwice_removesNothingTheSecondTime(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("c6"), DOC)) {
            final long nodeUid = interned(stores, "n1");
            writeNode(stores, nodeUid, DAY_1, "active");
            writeNode(stores, nodeUid, DAY_2, "active");

            assertThat(stores.condense()).isEqualTo(1);
            assertThat(stores.condense()).isZero();
        }
    }

    // ------------------------------------------------------------------------------------------------------

    /** The node's status at each of the four days plus one instant before any of them. */
    private static List<String> statusAtEveryInstant(final GraphStores stores, final long nodeUid) {
        final List<String> statuses = new ArrayList<>();
        statuses.add(statusAt(stores, nodeUid, DAY_1.minusSeconds(1)).orElse(null));
        for (final Instant day : List.of(DAY_1, DAY_2, DAY_3, DAY_4)) {
            statuses.add(statusAt(stores, nodeUid, day).orElse(null));
        }
        return statuses;
    }

    private static Optional<String> statusAt(final GraphStores stores, final long nodeUid, final Instant asOf) {
        return stores.read(txn -> stores.getNodes().getNode(txn, nodeUid, asOf)
                .map(version -> version.properties().get("status"))
                .map(Val::toString));
    }

    private static long versionsOf(final GraphStores stores, final long nodeUid) {
        final Long count = stores.read(txn -> {
            final long[] seen = {0};
            stores.getNodes().forEachVersion(txn, (uid, validFrom, version) -> {
                if (uid == nodeUid) {
                    seen[0]++;
                }
            });
            return seen[0];
        });
        return count;
    }

    private static void writeNode(final GraphStores stores,
                                  final long nodeUid,
                                  final Instant validFrom,
                                  final String status) {
        final Map<String, Val> properties = new LinkedHashMap<>();
        properties.put("status", ValString.create(status));
        stores.write(writer -> {
            stores.getNodes().insert(writer, nodeUid, validFrom, List.of(), properties);
            return null;
        });
    }

    private static long interned(final GraphStores stores, final String externalId) {
        return uidOf(stores, stores.getNodeUids(), externalId);
    }

    private static long internedType(final GraphStores stores, final String type) {
        return uidOf(stores, stores.getEdgeTypeUids(), type);
    }

    private static long uidOf(final GraphStores stores, final UidLookupDb db, final String name) {
        return stores.write(writer -> db.put(
                writer.getWriteTxn(),
                directBuffer(name),
                b -> UnsignedBytesInstances.ofLength(b.remaining()).get(b.duplicate())));
    }

    private static ByteBuffer directBuffer(final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }
}
