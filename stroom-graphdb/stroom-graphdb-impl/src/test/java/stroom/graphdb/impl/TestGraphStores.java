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
import stroom.util.shared.time.SimpleDuration;
import stroom.util.shared.time.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestGraphStores {

    private static final GraphDbDoc DOC = GraphDbDoc.builder()
            .uuid("graph-uuid")
            .name("TestGraph")
            .build();

    @Test
    void provision_internsAndPersistsAcrossReopen(@TempDir final Path root) {
        final Path dir = root.resolve("graph1");

        final long nodeAUid;
        try (GraphStores stores = GraphStores.provision(dir, DOC)) {
            nodeAUid = intern(stores, stores.getNodeUids(), "nodeA", GraphStores.NODE_UID_WIDTH);
            final long nodeAUidAgain = intern(stores, stores.getNodeUids(), "nodeA", GraphStores.NODE_UID_WIDTH);
            final long nodeBUid = intern(stores, stores.getNodeUids(), "nodeB", GraphStores.NODE_UID_WIDTH);

            // Get-or-create: interning the same key twice returns the same UID.
            assertThat(nodeAUidAgain).isEqualTo(nodeAUid);
            // Distinct keys get distinct UIDs.
            assertThat(nodeBUid).isNotEqualTo(nodeAUid);
        }

        // Reopen against the same directory: the interned mapping survived the close.
        try (GraphStores reopened = GraphStores.open(dir, DOC, false)) {
            final Optional<Long> resolved = lookup(reopened, reopened.getNodeUids(), "nodeA");
            assertThat(resolved).contains(nodeAUid);
        }
    }

    @Test
    void interning_alwaysReturnsFixedWidthUids_acrossTheWidthBoundary(@TempDir final Path root) {
        // UidLookupDb's default (variable-width) UID would grow from 1 to 2 bytes once the counter passes 255 -
        // silently breaking any composite-key prefix scan built on top of it (the P0.1 spike finding). The graph's
        // namespaces are constructed with a StaticUnsignedBytesFactory specifically to prevent this; prove it here
        // by interning past the 1-byte boundary (256 distinct keys) and asserting every returned UID buffer is
        // exactly TYPE_UID_WIDTH bytes, not growing to accommodate larger values.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph-width-test"), DOC)) {
            for (int i = 0; i < 300; i++) {
                final String key = "label-" + i;
                final int width = stores.write(writer -> stores.getLabelUids().put(
                        writer.getWriteTxn(),
                        directBuffer(key),
                        uidBuffer -> uidBuffer.remaining()));
                assertThat(width).isEqualTo(GraphStores.TYPE_UID_WIDTH);
            }
        }
    }

    @Test
    void rebuild_dropsExistingDataAndReprovisionsEmpty(@TempDir final Path root) {
        final Path dir = root.resolve("graph2");

        // rebuild() closes `stores` itself (documented postcondition) - it must not also be managed by a
        // try-with-resources here, or it would be closed twice.
        final GraphStores stores = GraphStores.provision(dir, DOC);
        intern(stores, stores.getNodeUids(), "nodeA", GraphStores.NODE_UID_WIDTH);

        try (GraphStores rebuilt = stores.rebuild(dir, DOC)) {
            final Optional<Long> resolved = lookup(rebuilt, rebuilt.getNodeUids(), "nodeA");
            assertThat(resolved).isEmpty();
        }
    }

    @Test
    void usedLookupsRecorder_marksSurvivorsAndSweepsEverythingElse(@TempDir final Path root) {
        // Task P1.2: recordUsed marks a UID as still-referenced; deleteUnused then removes any UID in the
        // namespace that was never marked - mirrors Plan B's TemporalStateDb.deleteOldData's record-then-sweep
        // pattern (env.write { ...; env.read { recorder.deleteUnused(readTxn, writer) } }).
        try (GraphStores stores = GraphStores.provision(root.resolve("graph-recorder-test"), DOC)) {
            final long usedUid = intern(stores, stores.getNodeUids(), "used", GraphStores.NODE_UID_WIDTH);
            final long unusedUid = intern(stores, stores.getNodeUids(), "unused", GraphStores.NODE_UID_WIDTH);

            stores.write(writer -> {
                stores.getNodeUidRecorder().recordUsed(writer, usedUid);
                stores.read(readTxn -> {
                    stores.getNodeUidRecorder().deleteUnused(readTxn, writer);
                    return null;
                });
                return null;
            });

            assertThat(lookup(stores, stores.getNodeUids(), "used")).contains(usedUid);
            assertThat(lookup(stores, stores.getNodeUids(), "unused")).isEmpty();
            assertThat(unusedUid).isNotEqualTo(usedUid);
        }
    }

    @Test
    void deleteOldData_noOpWhenRetentionAbsentOrDisabled(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph-retention-noop"), DOC)) {
            final long labelUid = intern(stores, stores.getLabelUids(), "Thing", GraphStores.TYPE_UID_WIDTH);
            final long nodeUid = intern(stores, stores.getNodeUids(), "n1", GraphStores.NODE_UID_WIDTH);
            stores.write(writer -> {
                stores.getNodes().insert(
                        writer, nodeUid, Instant.now().minus(Duration.ofDays(365)), List.of(labelUid), Map.of());
                return null;
            });

            assertThat(stores.deleteOldData(DOC)).as("no retention field at all").isZero();

            final GraphDbDoc disabledRetention = DOC.copy()
                    .retention(new RetentionSettings.Builder().enabled(false).build())
                    .build();
            assertThat(stores.deleteOldData(disabledRetention)).as("retention present but disabled").isZero();

            // Nothing was deleted either time - the old version is still there.
            final Optional<GraphNodeDb.NodeVersion> stillThere = stores.read(
                    readTxn -> stores.getNodes().getNode(readTxn, nodeUid, Instant.now()));
            assertThat(stillThere).isPresent();
        }
    }

    @Test
    void deleteOldData_enabledRetentionDeletesOldVersionsAndSweepsUnusedLabels(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph-retention-active"), DOC)) {
            final long veryOldLabel = intern(stores, stores.getLabelUids(), "VeryOld", GraphStores.TYPE_UID_WIDTH);
            final long oldLabel = intern(stores, stores.getLabelUids(), "Old", GraphStores.TYPE_UID_WIDTH);
            final long recentLabel = intern(stores, stores.getLabelUids(), "Recent", GraphStores.TYPE_UID_WIDTH);
            final long nodeUid = intern(stores, stores.getNodeUids(), "n1", GraphStores.NODE_UID_WIDTH);

            // veryOld/old are both outside the 1-hour retention window (so old supersedes veryOld and becomes
            // the surviving floor); recent is within it (survives as newer-than-cutoff).
            final Instant veryOld = Instant.now().minus(Duration.ofDays(730));
            final Instant old = Instant.now().minus(Duration.ofDays(365));
            final Instant recent = Instant.now().minus(Duration.ofSeconds(1));
            stores.write(writer -> {
                stores.getNodes().insert(writer, nodeUid, veryOld, List.of(veryOldLabel), Map.of());
                stores.getNodes().insert(writer, nodeUid, old, List.of(oldLabel), Map.of());
                stores.getNodes().insert(writer, nodeUid, recent, List.of(recentLabel), Map.of());
                return null;
            });

            final GraphDbDoc retainOneHour = DOC.copy()
                    .retention(new RetentionSettings.Builder()
                            .enabled(true)
                            .duration(SimpleDuration.builder().time(1).timeUnit(TimeUnit.HOURS).build())
                            .build())
                    .build();

            final long deletedCount = stores.deleteOldData(retainOneHour);
            assertThat(deletedCount).isEqualTo(1);

            final Optional<GraphNodeDb.NodeVersion> deletedVersion = stores.read(
                    readTxn -> stores.getNodes().getNode(readTxn, nodeUid, veryOld));
            assertThat(deletedVersion)
                    .as("veryOld was superseded (within the retention-eligible range) by old and is now gone")
                    .isEmpty();
            final Optional<GraphNodeDb.NodeVersion> floor = stores.read(
                    readTxn -> stores.getNodes().getNode(readTxn, nodeUid, old));
            assertThat(floor).isPresent();
            assertThat(floor.get().labelUids()).containsExactly(oldLabel);
            final Optional<GraphNodeDb.NodeVersion> current = stores.read(
                    readTxn -> stores.getNodes().getNode(readTxn, nodeUid, Instant.now()));
            assertThat(current).isPresent();
            assertThat(current.get().labelUids()).containsExactly(recentLabel);

            // "VeryOld" is no longer referenced by any surviving version - swept. "Old"/"Recent" survive.
            assertThat(lookup(stores, stores.getLabelUids(), "VeryOld")).isEmpty();
            assertThat(lookup(stores, stores.getLabelUids(), "Old")).contains(oldLabel);
            assertThat(lookup(stores, stores.getLabelUids(), "Recent")).contains(recentLabel);
        }
    }

    @Test
    void delete_removesTheDirectory(@TempDir final Path root) {
        final Path dir = root.resolve("graph3");

        try (GraphStores stores = GraphStores.provision(dir, DOC)) {
            intern(stores, stores.getNodeUids(), "nodeA", GraphStores.NODE_UID_WIDTH);
        }
        assertThat(Files.exists(dir)).isTrue();

        GraphStores.delete(dir);

        assertThat(Files.exists(dir)).isFalse();
    }

    private static long intern(final GraphStores stores,
                               final UidLookupDb db,
                               final String key,
                               final int expectedWidth) {
        return stores.write(writer -> db.put(
                writer.getWriteTxn(),
                directBuffer(key),
                uidBuffer -> {
                    assertThat(uidBuffer.remaining()).isEqualTo(expectedWidth);
                    return UnsignedBytesInstances.ofLength(expectedWidth).get(uidBuffer.duplicate());
                }));
    }

    private static Optional<Long> lookup(final GraphStores stores,
                                         final UidLookupDb db,
                                         final String key) {
        return stores.read(readTxn -> db.get(
                readTxn,
                directBuffer(key),
                maybeUid -> maybeUid.map(uidBuffer ->
                        UnsignedBytesInstances.ofLength(uidBuffer.remaining()).get(uidBuffer.duplicate()))));
    }

    /**
     * LMDB (via {@code org.lmdbjava}) requires direct byte buffers for keys/values - a heap buffer from
     * {@link ByteBuffer#wrap} throws {@code BufferMustBeDirectException}.
     */
    private static ByteBuffer directBuffer(final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }
}
