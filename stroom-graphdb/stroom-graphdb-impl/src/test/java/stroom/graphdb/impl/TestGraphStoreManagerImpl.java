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
import stroom.util.io.PathCreator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GraphStoreManagerImpl} - the {@code GraphStoreManager} implementation every other test in this module
 * fakes via the plain interface (see e.g. {@code TestGraphSearchProvider}). This test exercises the real class:
 * directory resolution (via {@link PathCreator#toAppPath}, resolved against the doc's UUID) and the
 * get-or-open cache (repeated calls for the same doc return the same open {@link GraphStores}; different docs
 * get independent stores under independent directories).
 */
class TestGraphStoreManagerImpl {

    @Test
    void getOrOpen_opensStoresUnderTheConfiguredShardDirectoryPlusDocUuid(@TempDir final Path appPath) {
        final GraphPaths graphPaths = new GraphPaths(appPath.resolve("graphdb"));

        final GraphDbDoc doc = GraphDbDoc.builder().uuid("doc-uuid-1").name("Graph1").build();
        final GraphStoreManagerImpl manager = new GraphStoreManagerImpl(graphPaths);

        final GraphStores stores = manager.getOrOpen(doc);
        try {
            assertThat(Files.isDirectory(appPath.resolve("graphdb").resolve("shards").resolve("doc-uuid-1"))).isTrue();
            // The returned instance is a genuinely open, writable GraphStores.
            final int uidWidth = stores.write(writer -> stores.getNodeUids().put(
                    writer.getWriteTxn(), directBuffer("n1"), ByteBuffer::remaining));
            assertThat(uidWidth).isEqualTo(GraphStores.NODE_UID_WIDTH);
        } finally {
            stores.close();
        }
    }

    @Test
    void getOrOpen_returnsTheSameCachedInstanceForTheSameDoc(@TempDir final Path appPath) {
        final GraphPaths graphPaths = new GraphPaths(appPath.resolve("graphdb"));

        final GraphDbDoc doc = GraphDbDoc.builder().uuid("doc-uuid-2").name("Graph2").build();
        final GraphStoreManagerImpl manager = new GraphStoreManagerImpl(graphPaths);

        final GraphStores first = manager.getOrOpen(doc);
        final GraphStores second = manager.getOrOpen(doc);
        try {
            assertThat(second).isSameAs(first);
        } finally {
            first.close();
        }
    }

    @Test
    void getOrOpen_opensIndependentStoresForDifferentDocs(@TempDir final Path appPath) {
        final GraphPaths graphPaths = new GraphPaths(appPath.resolve("graphdb"));

        final GraphDbDoc docA = GraphDbDoc.builder().uuid("doc-uuid-a").name("GraphA").build();
        final GraphDbDoc docB = GraphDbDoc.builder().uuid("doc-uuid-b").name("GraphB").build();
        final GraphStoreManagerImpl manager = new GraphStoreManagerImpl(graphPaths);

        final GraphStores storesA = manager.getOrOpen(docA);
        final GraphStores storesB = manager.getOrOpen(docB);
        try {
            assertThat(storesA).isNotSameAs(storesB);
            assertThat(Files.isDirectory(appPath.resolve("graphdb").resolve("shards").resolve("doc-uuid-a"))).isTrue();
            assertThat(Files.isDirectory(appPath.resolve("graphdb").resolve("shards").resolve("doc-uuid-b"))).isTrue();
        } finally {
            storesA.close();
            storesB.close();
        }
    }

    @Test
    void delete_closesTheOpenStoreAndRemovesItsDirectory(@TempDir final Path appPath) {
        final GraphPaths graphPaths = new GraphPaths(appPath.resolve("graphdb"));

        final GraphDbDoc doc = GraphDbDoc.builder().uuid("doc-uuid-3").name("Graph3").build();
        final GraphStoreManagerImpl manager = new GraphStoreManagerImpl(graphPaths);

        manager.getOrOpen(doc);
        assertThat(Files.isDirectory(appPath.resolve("graphdb").resolve("shards").resolve("doc-uuid-3"))).isTrue();

        manager.delete("doc-uuid-3");
        assertThat(Files.exists(appPath.resolve("graphdb").resolve("shards").resolve("doc-uuid-3"))).isFalse();

        // A subsequent getOrOpen for the same UUID provisions a fresh, empty store, not a re-open of stale data.
        final GraphStores reopened = manager.getOrOpen(doc);
        try {
            assertThat(Files.isDirectory(appPath.resolve("graphdb").resolve("shards").resolve("doc-uuid-3"))).isTrue();
        } finally {
            reopened.close();
        }
    }

    @Test
    void delete_ofAnUnopenedButExistingDirectory_stillRemovesIt(@TempDir final Path appPath) {
        final GraphPaths graphPaths = new GraphPaths(appPath.resolve("graphdb"));

        final GraphDbDoc doc = GraphDbDoc.builder().uuid("doc-uuid-4").name("Graph4").build();
        final GraphStoreManagerImpl firstManager = new GraphStoreManagerImpl(graphPaths);
        firstManager.getOrOpen(doc).close();
        assertThat(Files.isDirectory(appPath.resolve("graphdb").resolve("shards").resolve("doc-uuid-4"))).isTrue();

        // A fresh manager instance (mirroring a restart) never opened doc-uuid-4 itself, yet its on-disk
        // directory from the previous manager still exists and must still be removable.
        final GraphStoreManagerImpl secondManager = new GraphStoreManagerImpl(graphPaths);
        secondManager.delete("doc-uuid-4");

        assertThat(Files.exists(appPath.resolve("graphdb").resolve("shards").resolve("doc-uuid-4"))).isFalse();
    }

    @Test
    void delete_whosePhysicalDeleteFails_stillEvictsTheClosedStoreAndRethrows(@TempDir final Path appPath) {
        // Code-review fix: delete() runs close()-then-physically-delete inside ConcurrentHashMap.compute for
        // atomicity vs a racing getOrOpen(). But compute() leaves the mapping UNCHANGED if its function throws,
        // so letting GraphStores.delete's UncheckedIOException propagate out of the lambda would leave the
        // now-CLOSED store cached forever - every later getOrOpen() would hand back a closed store, the exact
        // permanent corruption the race fix set out to remove. delete() must always evict, then rethrow.
        final GraphPaths graphPaths = new GraphPaths(appPath.resolve("graphdb"));

        final GraphDbDoc doc = GraphDbDoc.builder().uuid("doc-uuid-iofail").name("GraphIoFail").build();
        final RuntimeException boom = new RuntimeException("simulated undeletable file");
        final GraphStoreManagerImpl manager = new GraphStoreManagerImpl(graphPaths) {
            @Override
            void deleteStoreDirectory(final Path directory) {
                throw boom;
            }
        };

        manager.getOrOpen(doc); // Cache an open store, then make its physical delete fail.

        assertThatThrownBy(() -> manager.delete(doc.getUuid())).isSameAs(boom);

        // Despite the failure, the closed store must NOT still be cached: a fresh getOrOpen() yields a genuinely
        // usable store (the directory is still on disk since the delete threw before removing anything).
        final GraphStores reopened = manager.getOrOpen(doc);
        try {
            final int uidWidth = reopened.write(writer -> reopened.getNodeUids().put(
                    writer.getWriteTxn(), directBuffer("n1"), ByteBuffer::remaining));
            assertThat(uidWidth).isEqualTo(GraphStores.NODE_UID_WIDTH);
        } finally {
            reopened.close();
        }
    }

    @Test
    void delete_ofAUuidWithNoDirectoryAtAll_isANoOp(@TempDir final Path appPath) {
        final GraphPaths graphPaths = new GraphPaths(appPath.resolve("graphdb"));

        final GraphStoreManagerImpl manager = new GraphStoreManagerImpl(graphPaths);

        assertThatCode(() -> manager.delete("never-existed")).doesNotThrowAnyException();
    }

    @Test
    void concurrentDeleteAndGetOrOpen_neverLeavesThePermanentlyCachedStoreBroken(
            @TempDir final Path appPath) throws Exception {
        // Code-review fix: delete() used to remove the map entry, close the old instance, and physically delete
        // the directory as three separate, unlocked steps - a getOrOpen() racing in the gap between the map
        // removal and the physical delete could open a BRAND NEW store on that same directory and cache it,
        // only for the still-in-flight physical delete to then rip that new store's files out from under it.
        // Because the new (now-broken) instance stayed cached, every future getOrOpen() for that UUID would
        // keep returning it forever - a permanent, unrecoverable corruption, not just a transient race.
        //
        // Note what this test deliberately does NOT assert: that a getOrOpen() call which happens to race a
        // concurrent delete() is guaranteed to return a handle that stays usable afterward - it isn't, and was
        // never meant to be (a delete() landing moments after a racing getOrOpen() legitimately closes what it
        // just returned, the same way any shared, deletable resource behaves under concurrency). The actual,
        // fixed invariant is narrower and more important: after any such race settles, a *subsequent*, ordinary
        // getOrOpen() call always yields a fresh, genuinely usable store - the cache can never get permanently
        // stuck holding a broken reference the way it could before this fix (verified via
        // ConcurrentMap.compute(), which makes delete()'s close-then-physically-delete sequence atomic with
        // respect to a concurrent getOrOpen()'s computeIfAbsent() for the same key).
        final GraphPaths graphPaths = new GraphPaths(appPath.resolve("graphdb"));

        final GraphDbDoc doc = GraphDbDoc.builder().uuid("doc-uuid-race").name("GraphRace").build();
        final GraphStoreManagerImpl manager = new GraphStoreManagerImpl(graphPaths);
        manager.getOrOpen(doc); // Seed an initial store so the very first delete() has something to race against.

        final int iterations = 50;
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < iterations; i++) {
                final CyclicBarrier barrier = new CyclicBarrier(2);
                final Future<?> deleteFuture = executor.submit(() -> {
                    barrier.await();
                    manager.delete(doc.getUuid());
                    return null;
                });
                final Future<GraphStores> getOrOpenFuture = executor.submit(() -> {
                    barrier.await();
                    return manager.getOrOpen(doc);
                });

                // Neither call itself should ever throw, regardless of how they interleave.
                deleteFuture.get(10, TimeUnit.SECONDS);
                getOrOpenFuture.get(10, TimeUnit.SECONDS);

                // The invariant that matters: an ordinary, non-racing getOrOpen() taken straight afterwards
                // always returns a fresh, genuinely usable store - proving the cache was never left stuck with
                // a permanently-broken reference by however the race above happened to resolve.
                final GraphStores stores = manager.getOrOpen(doc);
                final String nodeId = "n" + i;
                final int uidWidth = stores.write(writer -> stores.getNodeUids().put(
                        writer.getWriteTxn(), directBuffer(nodeId), ByteBuffer::remaining));
                assertThat(uidWidth).isEqualTo(GraphStores.NODE_UID_WIDTH);

                // Clean teardown before the next iteration's race, so open LMDB environments don't accumulate.
                manager.delete(doc.getUuid());
            }
        } finally {
            executor.shutdownNow();
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
