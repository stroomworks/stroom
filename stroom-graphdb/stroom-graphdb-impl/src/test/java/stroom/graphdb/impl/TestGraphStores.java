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
import java.nio.file.Files;
import java.nio.file.Path;
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
