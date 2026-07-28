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

import stroom.docref.DocRef;
import stroom.graphdb.impl.GraphShardWriters.GraphShardWriter;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.lmdb.serde.UnsignedBytesInstances;
import stroom.meta.shared.Meta;
import stroom.node.api.NodeInfo;
import stroom.security.mock.MockSecurityContext;
import stroom.task.api.ExecutorProvider;
import stroom.task.api.SimpleTaskContextFactory;
import stroom.task.shared.ThreadPool;
import stroom.test.common.MockMetrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the write path a node actually takes: ingest writes a fragment, the fragment is shipped, and the merge
 * processor merges it into the authoritative store.
 *
 * <p>The point of interest is not that one fragment survives a round trip - {@code TestGraphStoresMerge} covers
 * merge itself - but that the pieces are wired to each other correctly: that the fragment is named so the merge
 * side can resolve its document, that shipping and staging preserve it, and that a fragment for a graph that no
 * longer exists is discarded rather than left to accumulate or, worse, merged into the wrong store.</p>
 */
class TestGraphMergePipeline {

    private static final String DOC_UUID = "graph-uuid-1";
    private static final GraphDbDoc DOC = GraphDbDoc
            .builder()
            .uuid(DOC_UUID)
            .name("Graph1")
            .build();

    /**
     * The headline case: two streams, each writing its own fragment, both merged into one store. This is what a
     * two-node cluster does, and what writing directly into a live store could never achieve - before this, each
     * node's store held only the streams that node processed.
     */
    @Test
    void fragmentsFromSeparateStreams_bothReachTheAuthoritativeStore(@TempDir final Path root) {
        final Fixture fixture = new Fixture(root);
        try {
            fixture.writeFragment(1L, "alice");
            fixture.writeFragment(2L, "bob");

            fixture.mergeProcessor.mergeCurrent();

            final GraphStores target = fixture.storeManager.getOrOpen(DOC);
            assertThat(nodeIds(target)).containsExactlyInAnyOrder("alice", "bob");
            assertThat(fixture.mergeProcessor.getMergeFailureCount()).isZero();
        } finally {
            fixture.close();
        }
    }

    /**
     * An empty stream must not produce a fragment at all. Otherwise every stream through a pipeline containing a
     * graph filter would cost a zip, a transfer and a merge cycle for no data.
     */
    @Test
    void streamThatWroteNothing_shipsNoFragment(@TempDir final Path root) {
        final Fixture fixture = new Fixture(root);
        try {
            final GraphShardWriter writer = fixture.shardWriters.createWriter(meta(1L), DOC);
            writer.close();

            assertThat(fixture.shipped).isEmpty();
        } finally {
            fixture.close();
        }
    }

    /**
     * A fragment whose graph has been deleted is discarded. Retaining it would leave the merge queue permanently
     * blocked behind data that can never be merged.
     */
    @Test
    void fragmentForADeletedGraph_isDiscardedWithoutCountingAsAFailure(@TempDir final Path root) {
        final Fixture fixture = new Fixture(root);
        try {
            fixture.writeFragment(1L, "alice");
            fixture.docExists = false;

            fixture.mergeProcessor.mergeCurrent();

            assertThat(fixture.mergeProcessor.getMergeFailureCount()).isZero();
        } finally {
            fixture.close();
        }
    }

    private static List<String> nodeIds(final GraphStores stores) {
        return stores.read(txn -> {
            final List<String> ids = new ArrayList<>();
            stores.getNodeUids().forEachName(txn, nameBuffer ->
                    ids.add(StandardCharsets.UTF_8.decode(nameBuffer.duplicate()).toString()));
            return ids;
        });
    }

    private static Meta meta(final long id) {
        return Meta.builder().id(id).build();
    }

    /**
     * The pieces of one node's write path, wired together over a single temporary directory tree.
     */
    private static final class Fixture {

        private final GraphPaths graphPaths;
        private final GraphStoreManagerImpl storeManager;
        private final GraphShardWriters shardWriters;
        private final GraphMergeProcessor mergeProcessor;
        private final List<Path> shipped = new ArrayList<>();
        private boolean docExists = true;

        private Fixture(final Path root) {
            graphPaths = new GraphPaths(root.resolve("graphdb"));
            storeManager = new GraphStoreManagerImpl(graphPaths, GraphDbConfig::new);

            final GraphDbDocStore docStore = mock(GraphDbDocStore.class);
            when(docStore.readDocument(any())).thenAnswer(invocation -> {
                final DocRef docRef = invocation.getArgument(0);
                return docExists && DOC_UUID.equals(docRef.getUuid())
                        ? DOC
                        : null;
            });
            mergeProcessor = new GraphMergeProcessor(
                    graphPaths,
                    docStore,
                    storeManager,
                    new MockSecurityContext(),
                    new SimpleTaskContextFactory(),
                    new SameThreadExecutorProvider(),
                    new MockMetrics());

            final GraphPartDestination partDestination =
                    new GraphPartDestination(new MockSecurityContext(), graphPaths, () -> mergeProcessor);
            // The real client, with no node list configured, so it takes its local-delivery branch - the same code
            // path a single-node deployment runs.
            final NodeInfo nodeInfo = () -> "node1";
            final GraphFileTransferClient realClient = new GraphFileTransferClientImpl(
                    GraphDbConfig::new,
                    null,
                    nodeInfo,
                    null,
                    null,
                    partDestination,
                    new MockSecurityContext(),
                    new SameThreadExecutorProvider());
            final GraphFileTransferClient transferClient = (fileDescriptor, path, synchroniseMerge) -> {
                shipped.add(path);
                realClient.storePart(fileDescriptor, path, synchroniseMerge);
            };
            shardWriters = new GraphShardWriters(graphPaths, transferClient, GraphDbConfig::new);
        }

        /**
         * Writes one node into a fragment for {@code streamId} and closes it, which ships it.
         */
        private void writeFragment(final long streamId, final String nodeId) {
            try (final GraphShardWriter writer = shardWriters.createWriter(meta(streamId), DOC)) {
                final GraphStores stores = writer.getStores();
                final long nodeUid = stores.getNodeUids().put(
                        writer.getWriter().getWriteTxn(),
                        directBuffer(nodeId),
                        uidBuffer -> UnsignedBytesInstances
                                .ofLength(GraphStores.NODE_UID_WIDTH)
                                .get(uidBuffer.duplicate()));
                stores.getNodes().insert(
                        writer.getWriter(), nodeUid, Instant.parse("2026-01-01T00:00:00Z"), List.of(), Map.of());
                writer.getWriter().commit();
                writer.markDirty();
            }
        }

        private void close() {
            storeManager.delete(DOC_UUID);
        }
    }

    /**
     * Runs merge work on the calling thread. {@code mergeCurrent()} is synchronous, so the tests see the merged
     * result without waiting on a background loop.
     */
    private static final class SameThreadExecutorProvider implements ExecutorProvider {

        @Override
        public Executor get(final ThreadPool threadPool) {
            return Runnable::run;
        }

        @Override
        public Executor get() {
            return Runnable::run;
        }
    }

    private static ByteBuffer directBuffer(final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }
}
