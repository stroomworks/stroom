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
import stroom.planb.impl.data.FileDescriptor;
import stroom.query.api.DateTimeSettings;
import stroom.query.common.v2.ExpressionPredicateFactory;
import stroom.query.grammar.parse.CypherQueryParser;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValString;
import stroom.query.planner.cypher.CompiledCypherPlan;
import stroom.query.planner.cypher.CypherToLogicalPlan;
import stroom.security.mock.MockSecurityContext;
import stroom.task.api.ExecutorProvider;
import stroom.task.api.SimpleTaskContextFactory;
import stroom.task.shared.ThreadPool;
import stroom.test.common.MockMetrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A two-node cluster in one JVM, to test the half of the cluster work that unit tests otherwise cannot reach.
 *
 * <p>{@code TestGraphMergePipeline} covers one node's fragment-to-merge path. What it cannot cover is the reason
 * that path exists: that a graph assembled from fragments written on <b>different</b> nodes answers a traversal
 * crossing them, on <b>either</b> node. That was the original defect - each node held only the streams it happened
 * to process and every query silently returned a partial answer - and it is the case query fan-out could never
 * have fixed, because a traversal follows an edge across the boundary.</p>
 *
 * <p>Each node gets its own {@link GraphPaths} root, store manager, merge processor, part destination and shard
 * writers, so nothing is shared but the test transport. That transport replicates every fragment to both nodes,
 * delivering locally through {@code receiveLocalPart} and remotely through {@code receiveRemotePart} - the real
 * remote entry point, reading the zip as a stream, so the hash verification in the staging store is exercised
 * rather than stubbed past. Only the Jersey hop is absent.</p>
 */
class TestGraphTwoNodeCluster {

    private static final String DOC_UUID = "cluster-graph";
    private static final GraphDbDoc DOC = GraphDbDoc
            .builder()
            .uuid(DOC_UUID)
            .name("ClusterGraph")
            .build();

    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00.000Z");
    private static final String KNOWS = "KNOWS";

    /**
     * The discriminating case. Node one ingests {@code alice -> bob}; node two ingests {@code bob -> carol}.
     * Neither node's own fragment contains the whole chain, so a two-hop traversal can only succeed once both
     * fragments have reached both nodes - which is exactly what replication is for.
     */
    @Test
    void traversalCrossingTwoNodesFragments_resolvesOnBothNodes(@TempDir final Path root) {
        try (Cluster cluster = new Cluster(root)) {
            cluster.nodeOne.writeEdgeFragment(1L, "alice", "bob");
            cluster.nodeTwo.writeEdgeFragment(2L, "bob", "carol");
            cluster.mergeEverywhere();

            for (final Node node : cluster.nodes()) {
                assertThat(twoHopTargets(node, "alice"))
                        .as("two-hop traversal on " + node.name)
                        .containsExactly("carol");
            }
        }
    }

    /**
     * Both nodes must end up with the same graph, not merely both able to answer one query. Asserted on the full
     * node-id set, because a node missing one fragment can still satisfy a narrow traversal by luck.
     */
    @Test
    void bothNodesConvergeOnTheSameGraph(@TempDir final Path root) {
        try (Cluster cluster = new Cluster(root)) {
            cluster.nodeOne.writeEdgeFragment(1L, "alice", "bob");
            cluster.nodeTwo.writeEdgeFragment(2L, "bob", "carol");
            cluster.nodeOne.writeEdgeFragment(3L, "carol", "dave");
            cluster.mergeEverywhere();

            for (final Node node : cluster.nodes()) {
                assertThat(nodeIds(node))
                        .as("node ids on " + node.name)
                        .containsExactlyInAnyOrder("alice", "bob", "carol", "dave");
            }
        }
    }

    /**
     * A fragment delivered twice must change nothing. This is what makes the partial-send hazard survivable: a send
     * that fails after reaching one node is retried to both, so the first node sees it again.
     */
    @Test
    void redeliveringAFragment_changesNothing(@TempDir final Path root) {
        try (Cluster cluster = new Cluster(root)) {
            cluster.nodeOne.writeEdgeFragment(1L, "alice", "bob");
            cluster.mergeEverywhere();
            final List<String> afterFirst = nodeIds(cluster.nodeOne);

            cluster.replayLastFragment();
            cluster.mergeEverywhere();

            assertThat(nodeIds(cluster.nodeOne)).containsExactlyElementsOf(afterFirst);
            assertThat(twoHopTargets(cluster.nodeOne, "alice")).isEmpty();
            assertThat(oneHopTargets(cluster.nodeOne, "alice")).containsExactly("bob");
        }
    }

    /**
     * The defect backfill exists to fix, asserted before the fix so the fix has something to prove. Replication
     * only ever ships new fragments, so a node that joins later holds nothing from before it joined - and a
     * traversal spanning old and new data fails on it while succeeding on the node that was always there.
     */
    @Test
    void nodeThatJoinedLate_answersPartially(@TempDir final Path root) {
        try (Cluster cluster = new Cluster(root)) {
            cluster.detach(cluster.nodeTwo);
            cluster.nodeOne.writeEdgeFragment(1L, "alice", "bob");
            cluster.mergeEverywhere();

            cluster.attach(cluster.nodeTwo);
            cluster.nodeOne.writeEdgeFragment(2L, "bob", "carol");
            cluster.mergeEverywhere();

            assertThat(twoHopTargets(cluster.nodeOne, "alice")).as("the node that was always there")
                    .containsExactly("carol");
            assertThat(twoHopTargets(cluster.nodeTwo, "alice")).as("the node that joined late").isEmpty();
        }
    }

    /**
     * Backfill converges the late node. The whole store is copied from a node that holds it and shipped down the
     * ordinary fragment path, so the traversal that failed above now succeeds on both.
     */
    @Test
    void backfillingAJoinedNode_convergesItWithTheRest(@TempDir final Path root) {
        try (Cluster cluster = new Cluster(root)) {
            cluster.detach(cluster.nodeTwo);
            cluster.nodeOne.writeEdgeFragment(1L, "alice", "bob");
            cluster.mergeEverywhere();

            cluster.attach(cluster.nodeTwo);
            cluster.nodeOne.writeEdgeFragment(2L, "bob", "carol");
            cluster.mergeEverywhere();

            cluster.nodeOne.backfillService.backfill(DOC);
            cluster.mergeEverywhere();

            for (final Node node : cluster.nodes()) {
                assertThat(nodeIds(node)).as("node ids on " + node.name)
                        .containsExactlyInAnyOrder("alice", "bob", "carol");
                assertThat(twoHopTargets(node, "alice")).as("two-hop traversal on " + node.name)
                        .containsExactly("carol");
            }
        }
    }

    /**
     * Backfill reaches every configured node, including the one it was run from - the transport has no notion of
     * which node needs the data. Merge idempotence is what makes that safe, so it is asserted rather than assumed:
     * a node already holding everything must be unchanged, not doubled.
     */
    @Test
    void backfillingANodeThatNeedsNothing_changesNothing(@TempDir final Path root) {
        try (Cluster cluster = new Cluster(root)) {
            cluster.nodeOne.writeEdgeFragment(1L, "alice", "bob");
            cluster.nodeTwo.writeEdgeFragment(2L, "bob", "carol");
            cluster.mergeEverywhere();

            final List<String> before = nodeIds(cluster.nodeTwo);
            final long versionsBefore = nodeVersionCount(cluster.nodeTwo);

            cluster.nodeOne.backfillService.backfill(DOC);
            cluster.mergeEverywhere();

            assertThat(nodeIds(cluster.nodeTwo)).containsExactlyInAnyOrderElementsOf(before);
            assertThat(nodeVersionCount(cluster.nodeTwo)).as("node versions").isEqualTo(versionsBefore);
            assertThat(twoHopTargets(cluster.nodeTwo, "alice")).containsExactly("carol");
        }
    }

    /**
     * Backfill leaves nothing behind. It copies a whole graph rather than one stream's worth, so a leaked working
     * copy or zip is the size of the graph - on a repeatedly backfilled node that fills the volume the store
     * itself needs.
     */
    @Test
    void backfill_leavesNoWorkingFiles(@TempDir final Path root) {
        try (Cluster cluster = new Cluster(root)) {
            cluster.nodeOne.writeEdgeFragment(1L, "alice", "bob");
            cluster.mergeEverywhere();

            cluster.nodeOne.backfillService.backfill(DOC);

            assertThat(listOf(cluster.nodeOne.graphPaths.getWriterDir())).isEmpty();
        }
    }

    /**
     * A fragment whose bytes do not match its declared hash must be refused. Corruption in transit that was
     * accepted would be merged as though it were sound, and a truncated LMDB environment is not a recoverable
     * kind of wrong.
     */
    @Test
    void fragmentWhoseHashDoesNotMatch_isRefused(@TempDir final Path root) {
        try (Cluster cluster = new Cluster(root)) {
            cluster.nodeOne.writeEdgeFragment(1L, "alice", "bob");
            final Path zip = cluster.sentZips.getLast();

            assertThatThrownBy(() -> cluster.nodeTwo.receiveRemote(
                    new FileDescriptor(System.currentTimeMillis(), 99L, "not-the-real-hash"), zip))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("hash");
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Assertions expressed as real queries, so they exercise the engine rather than the DAOs directly.
    // ------------------------------------------------------------------------------------------------------

    private static List<String> twoHopTargets(final Node node, final String from) {
        return query(node, "MATCH (a:Person {id: '" + from + "'})-[:" + KNOWS + "]->()-[:" + KNOWS
                          + "]->(c:Person) RETURN c.id");
    }

    private static List<String> oneHopTargets(final Node node, final String from) {
        return query(node, "MATCH (a:Person {id: '" + from + "'})-[:" + KNOWS + "]->(c:Person) RETURN c.id");
    }

    private static List<String> query(final Node node, final String cypher) {
        final GraphStores stores = node.storeManager.getOrOpen(DOC);
        final CompiledCypherPlan compiled = new CypherToLogicalPlan().compile(CypherQueryParser.parse(cypher));
        final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
        final List<Val[]> rows = stores.read(readTxn -> engine.execute(
                readTxn, compiled.plan(), compiled.temporalContext(), DateTimeSettings.builder().build()));
        return rows.stream().map(row -> row[0].toString()).toList();
    }

    /** Every node version in the store, so a merge that duplicated rather than deduplicated is visible. */
    private static long nodeVersionCount(final Node node) {
        final GraphStores stores = node.storeManager.getOrOpen(DOC);
        return stores.read(txn -> {
            final long[] seen = {0};
            stores.getNodes().forEachVersion(txn, (uid, validFrom, version) -> seen[0]++);
            return seen[0];
        });
    }

    private static List<Path> listOf(final Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var children = Files.list(dir)) {
            return children.toList();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> nodeIds(final Node node) {
        final GraphStores stores = node.storeManager.getOrOpen(DOC);
        return stores.read(txn -> {
            final List<String> ids = new ArrayList<>();
            stores.getNodeUids().forEachName(txn, name ->
                    ids.add(StandardCharsets.UTF_8.decode(name.duplicate()).toString()));
            return ids;
        });
    }

    // ------------------------------------------------------------------------------------------------------
    // The cluster
    // ------------------------------------------------------------------------------------------------------

    /**
     * Two nodes plus the transport between them. Every fragment either node produces is delivered to both, which
     * is what {@code GraphFileTransferClientImpl} does in production for a configured node list.
     */
    private final class Cluster implements AutoCloseable {

        private final Node nodeOne;
        private final Node nodeTwo;
        private final List<Path> sentZips = new ArrayList<>();
        /** The nodes the transport currently delivers to - the cluster's node list, in effect. */
        private final List<Node> attached = new ArrayList<>();

        private Cluster(final Path root) {
            nodeOne = new Node("node1", root.resolve("node1"), this);
            nodeTwo = new Node("node2", root.resolve("node2"), this);
            attached.add(nodeOne);
            attached.add(nodeTwo);
        }

        private List<Node> nodes() {
            return List.of(nodeOne, nodeTwo);
        }

        /** Removes a node from the transport's targets, so it misses everything sent while it is out. */
        private void detach(final Node node) {
            attached.remove(node);
        }

        /** Puts a node back in the targets - it now receives new fragments but nothing that predates this. */
        private void attach(final Node node) {
            if (!attached.contains(node)) {
                attached.add(node);
            }
        }

        /**
         * Replicates a fragment to both nodes. The zip is copied out first because the producing writer deletes it
         * as soon as this returns, and the redelivery test needs it afterwards.
         */
        private void replicate(final Node origin, final FileDescriptor descriptor, final Path zip) {
            final Path kept = keep(zip);
            sentZips.add(kept);
            deliver(descriptor, kept);
        }

        private void replayLastFragment() {
            final Path zip = sentZips.getLast();
            deliver(descriptorFor(zip), zip);
        }

        private void deliver(final FileDescriptor descriptor, final Path zip) {
            for (final Node node : List.copyOf(attached)) {
                try {
                    // Both go through the remote entry point, which is the code with no other test coverage. The
                    // only thing missing versus production is the Jersey hop.
                    node.receiveRemote(descriptor, zip);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }

        private void mergeEverywhere() {
            nodes().forEach(node -> node.mergeProcessor.mergeCurrent());
        }

        private Path keep(final Path zip) {
            try {
                final Path kept = Files.createTempFile("fragment", ".zip");
                Files.copy(zip, kept, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return kept;
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private FileDescriptor descriptorFor(final Path zip) {
            try {
                return new FileDescriptor(
                        System.currentTimeMillis(), 1L, stroom.planb.impl.data.FileHashUtil.hash(zip));
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            nodes().forEach(node -> node.storeManager.delete(DOC_UUID));
        }
    }

    /**
     * One node's worth of graph machinery, entirely separate from the other's except for the transport.
     */
    private final class Node {

        private final String name;
        private final GraphPaths graphPaths;
        private final GraphStoreManagerImpl storeManager;
        private final GraphMergeProcessor mergeProcessor;
        private final GraphPartDestination partDestination;
        private final GraphShardWriters shardWriters;
        private final GraphBackfillService backfillService;

        private Node(final String name, final Path root, final Cluster cluster) {
            this.name = name;
            graphPaths = new GraphPaths(root.resolve("graphdb"));

            final GraphDbDocStore docStore = mock(GraphDbDocStore.class);
            when(docStore.readDocument(any())).thenAnswer(invocation -> {
                final DocRef docRef = invocation.getArgument(0);
                return DOC_UUID.equals(docRef.getUuid())
                        ? DOC
                        : null;
            });

            storeManager = new GraphStoreManagerImpl(
                    graphPaths, GraphDbConfig::new, () -> docStore, new MockMetrics());
            mergeProcessor = new GraphMergeProcessor(
                    graphPaths,
                    docStore,
                    storeManager,
                    new MockSecurityContext(),
                    new SimpleTaskContextFactory(),
                    new SameThreadExecutorProvider(),
                    new MockMetrics());
            partDestination =
                    new GraphPartDestination(new MockSecurityContext(), graphPaths, () -> mergeProcessor);

            // The transport hands every fragment this node produces to the cluster, which replicates it to both.
            final GraphFileTransferClient transport =
                    (descriptor, path, synchroniseMerge) -> cluster.replicate(this, descriptor, path);
            shardWriters = new GraphShardWriters(graphPaths, transport, GraphDbConfig::new);
            // Backfill deliberately shares that transport - shipping a whole store down the fragment path is the
            // whole design, so a test that gave it its own route would prove nothing.
            backfillService = new GraphBackfillService(
                    graphPaths, storeManager, transport, new MockSecurityContext());
        }

        private void receiveRemote(final FileDescriptor descriptor, final Path zip) throws IOException {
            try (InputStream inputStream = new BufferedInputStream(Files.newInputStream(zip))) {
                partDestination.receiveRemotePart(
                        descriptor.createTimeMs(),
                        descriptor.metaId(),
                        descriptor.fileHash(),
                        zip.getFileName().toString(),
                        false,
                        inputStream);
            }
        }

        /**
         * Writes a fragment containing one edge and its two nodes, as one stream's ingest would.
         */
        private void writeEdgeFragment(final long streamId, final String from, final String to) {
            try (GraphShardWriter writer = shardWriters.createWriter(meta(streamId), DOC)) {
                final GraphStores stores = writer.getStores();
                final long fromUid = intern(stores.getNodeUids(), writer, from, GraphStores.NODE_UID_WIDTH);
                final long toUid = intern(stores.getNodeUids(), writer, to, GraphStores.NODE_UID_WIDTH);
                final long labelUid = intern(stores.getLabelUids(), writer, "Person", GraphStores.TYPE_UID_WIDTH);
                final long edgeTypeUid = intern(
                        stores.getEdgeTypeUids(), writer, KNOWS, GraphStores.TYPE_UID_WIDTH);
                final long idKeyUid = intern(
                        stores.getPropertyKeyUids(), writer, "id", GraphStores.TYPE_UID_WIDTH);

                writeNode(stores, writer, fromUid, labelUid, idKeyUid, from);
                writeNode(stores, writer, toUid, labelUid, idKeyUid, to);
                stores.getOutEdges().insert(writer.getWriter(), fromUid, edgeTypeUid, toUid, T1, Map.of());
                stores.getInEdges().insert(writer.getWriter(), fromUid, edgeTypeUid, toUid, T1, Map.of());

                writer.getWriter().commit();
                writer.markDirty();
            }
        }

        private void writeNode(final GraphStores stores,
                               final GraphShardWriter writer,
                               final long nodeUid,
                               final long labelUid,
                               final long idKeyUid,
                               final String externalId) {
            final Val id = ValString.create(externalId);
            stores.getNodes().insert(
                    writer.getWriter(), nodeUid, T1, List.of(labelUid), Map.of("id", id));
            stores.getPropertyIndex().insert(writer.getWriter(), labelUid, idKeyUid,
                    GraphAnchorEncoding.anchorValueBytes(id), nodeUid);
        }
    }

    private static long intern(final stroom.planb.impl.dao.UidLookupDb db,
                               final GraphShardWriter writer,
                               final String name,
                               final int unusedWidth) {
        return db.put(
                writer.getWriter().getWriteTxn(),
                directBuffer(name),
                buffer -> UnsignedBytesInstances.ofLength(buffer.remaining()).get(buffer.duplicate()));
    }

    private static Meta meta(final long id) {
        return Meta.builder().id(id).build();
    }

    private static ByteBuffer directBuffer(final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }

    /** Runs merge work on the calling thread, so {@code mergeCurrent()} is fully synchronous. */
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
}
