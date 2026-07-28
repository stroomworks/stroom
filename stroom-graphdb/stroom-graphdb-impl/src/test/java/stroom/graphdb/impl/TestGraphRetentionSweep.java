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
import stroom.planb.shared.RetentionSettings;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValString;
import stroom.util.shared.time.SimpleDuration;
import stroom.util.shared.time.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers what retention actually reclaims from the property index.
 *
 * <p>Retention used to sweep only the node and edge stores. The property index was left untouched, and it holds an
 * anchor per distinct value each node has ever had - so on a continuously fed graph it grew without bound while the
 * versioned tables stayed flat. That is why storage grew even with retention enabled.</p>
 *
 * <p>Two things make this table awkward, and both shaped the implementation. It has no {@code validFrom}, so it
 * cannot be aged by time. And a stored anchor's value cannot be decoded back out of its key, so it cannot be tested
 * against the surviving versions row by row. It is therefore cleared and re-derived from the versions that
 * survived.</p>
 */
class TestGraphRetentionSweep {

    private static final Instant OLDEST = Instant.parse("2019-01-01T00:00:00.000Z");
    private static final Instant OLD = Instant.parse("2020-01-01T00:00:00.000Z");
    private static final Instant RECENT = Instant.now().minusSeconds(60);

    /**
     * The headline case. A node whose property changed leaves an anchor for every value it ever held; once the
     * superseded version is aged out, that stale anchor must go with it.
     *
     * <p>Three versions, not two, because retention keeps the <b>last</b> version at or before the cut-off as the
     * floor - it is needed to answer "what did this look like just before the retained window". So two versions
     * either side of the cut-off delete nothing at all.</p>
     */
    @Test
    void anAnchorForASupersededValue_isReclaimed(@TempDir final Path root) {
        final GraphDbDoc doc = docWithRetention();
        try (GraphStores stores = GraphStores.provision(root.resolve("sweep1"), doc)) {
            final long nodeUid = writeNode(stores, "n1", OLDEST, "v1");
            writeVersion(stores, nodeUid, OLD, "v2");
            writeVersion(stores, nodeUid, RECENT, "v3");

            assertThat(anchorsFor(stores, "v1")).containsExactly(nodeUid);

            assertThat(stores.deleteOldData(doc)).isPositive();

            assertThat(anchorsFor(stores, "v1")).as("superseded value").isEmpty();
            assertThat(anchorsFor(stores, "v2")).as("retained floor version").containsExactly(nodeUid);
            assertThat(anchorsFor(stores, "v3")).as("current version").containsExactly(nodeUid);
        }
    }

    /**
     * An unchanged node keeps its anchors, because retention keeps its only version. This is the assertion that
     * stops the rebuild being implemented as "clear it", which would satisfy the case above while making every
     * property lookup miss.
     */
    @Test
    void anUnchangedNode_keepsItsAnchors(@TempDir final Path root) {
        final GraphDbDoc doc = docWithRetention();
        try (GraphStores stores = GraphStores.provision(root.resolve("sweep2"), doc)) {
            final long nodeUid = writeNode(stores, "n1", OLD, "only");

            assertThat(stores.deleteOldData(doc)).isZero();

            assertThat(anchorsFor(stores, "only")).containsExactly(nodeUid);
        }
    }

    /**
     * Several nodes sharing a value must all survive the rebuild. A rebuild that dropped duplicates, or that
     * re-interned a property key per node, would lose all but one.
     */
    @Test
    void nodesSharingAValue_allSurviveTheRebuild(@TempDir final Path root) {
        final GraphDbDoc doc = docWithRetention();
        try (GraphStores stores = GraphStores.provision(root.resolve("sweep3"), doc)) {
            final long first = writeNode(stores, "n1", OLDEST, "old");
            writeVersion(stores, first, OLD, "shared");
            writeVersion(stores, first, RECENT, "shared");
            final long second = writeNode(stores, "n2", RECENT, "shared");

            assertThat(stores.deleteOldData(doc)).isPositive();

            assertThat(anchorsFor(stores, "old")).isEmpty();
            assertThat(anchorsFor(stores, "shared")).containsExactlyInAnyOrder(first, second);
        }
    }

    /**
     * Retention disabled must change nothing. Worth pinning because the sweep now clears and rebuilds a whole
     * table, so a mistake in the enabled-check destroys the index rather than merely skipping a cleanup.
     */
    @Test
    void retentionDisabled_removesNothing(@TempDir final Path root) {
        final GraphDbDoc doc = GraphDbDoc.builder().uuid("no-retention").name("NoRetention").build();
        try (GraphStores stores = GraphStores.provision(root.resolve("sweep4"), doc)) {
            final long nodeUid = writeNode(stores, "n1", OLDEST, "v1");
            writeVersion(stores, nodeUid, RECENT, "v2");

            assertThat(stores.deleteOldData(doc)).isZero();

            assertThat(anchorsFor(stores, "v1")).containsExactly(nodeUid);
            assertThat(anchorsFor(stores, "v2")).containsExactly(nodeUid);
        }
    }

    private static List<Long> anchorsFor(final GraphStores stores, final String value) {
        final long labelUid = uidOf(stores, stores.getLabelUids(), "Thing");
        final long propKeyUid = uidOf(stores, stores.getPropertyKeyUids(), "status");
        return stores.read(txn -> stores.getPropertyIndex().findAnchors(
                txn, labelUid, propKeyUid, value.getBytes(StandardCharsets.UTF_8)));
    }

    private static GraphDbDoc docWithRetention() {
        return GraphDbDoc
                .builder()
                .uuid("retention-graph")
                .name("RetentionGraph")
                .retention(new RetentionSettings.Builder()
                        .enabled(true)
                        .duration(SimpleDuration.builder().time(1).timeUnit(TimeUnit.DAYS).build())
                        .build())
                .build();
    }

    private static long writeNode(final GraphStores stores,
                                  final String externalId,
                                  final Instant validFrom,
                                  final String status) {
        final long nodeUid = uidOf(stores, stores.getNodeUids(), externalId);
        writeVersion(stores, nodeUid, validFrom, status);
        return nodeUid;
    }

    private static void writeVersion(final GraphStores stores,
                                     final long nodeUid,
                                     final Instant validFrom,
                                     final String status) {
        final long labelUid = uidOf(stores, stores.getLabelUids(), "Thing");
        final long propKeyUid = uidOf(stores, stores.getPropertyKeyUids(), "status");
        final Map<String, Val> properties = new LinkedHashMap<>();
        properties.put("status", ValString.create(status));

        stores.write(writer -> {
            stores.getNodes().insert(writer, nodeUid, validFrom, List.of(labelUid), properties);
            stores.getPropertyIndex().insert(writer, labelUid, propKeyUid,
                    GraphAnchorEncoding.anchorValueBytes(properties.get("status")), nodeUid);
            return null;
        });
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
