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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link GraphStoreManagerImpl} - the {@code GraphStoreManager} implementation every other test in this module
 * fakes via the plain interface (see e.g. {@code TestGraphSearchProvider}). This test exercises the real class:
 * directory resolution (via {@link PathCreator#toAppPath}, resolved against the doc's UUID) and the
 * get-or-open cache (repeated calls for the same doc return the same open {@link GraphStores}; different docs
 * get independent stores under independent directories).
 */
class TestGraphStoreManagerImpl {

    @Test
    void getOrOpen_opensStoresUnderPathCreatorResolvedDirectoryPlusDocUuid(@TempDir final Path appPath) {
        final PathCreator pathCreator = mock(PathCreator.class);
        when(pathCreator.toAppPath("graphdb")).thenReturn(appPath.resolve("graphdb"));

        final GraphDbDoc doc = GraphDbDoc.builder().uuid("doc-uuid-1").name("Graph1").build();
        final GraphStoreManagerImpl manager = new GraphStoreManagerImpl(pathCreator);

        final GraphStores stores = manager.getOrOpen(doc);
        try {
            assertThat(Files.isDirectory(appPath.resolve("graphdb").resolve("doc-uuid-1"))).isTrue();
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
        final PathCreator pathCreator = mock(PathCreator.class);
        when(pathCreator.toAppPath("graphdb")).thenReturn(appPath.resolve("graphdb"));

        final GraphDbDoc doc = GraphDbDoc.builder().uuid("doc-uuid-2").name("Graph2").build();
        final GraphStoreManagerImpl manager = new GraphStoreManagerImpl(pathCreator);

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
        final PathCreator pathCreator = mock(PathCreator.class);
        when(pathCreator.toAppPath("graphdb")).thenReturn(appPath.resolve("graphdb"));

        final GraphDbDoc docA = GraphDbDoc.builder().uuid("doc-uuid-a").name("GraphA").build();
        final GraphDbDoc docB = GraphDbDoc.builder().uuid("doc-uuid-b").name("GraphB").build();
        final GraphStoreManagerImpl manager = new GraphStoreManagerImpl(pathCreator);

        final GraphStores storesA = manager.getOrOpen(docA);
        final GraphStores storesB = manager.getOrOpen(docB);
        try {
            assertThat(storesA).isNotSameAs(storesB);
            assertThat(Files.isDirectory(appPath.resolve("graphdb").resolve("doc-uuid-a"))).isTrue();
            assertThat(Files.isDirectory(appPath.resolve("graphdb").resolve("doc-uuid-b"))).isTrue();
        } finally {
            storesA.close();
            storesB.close();
        }
    }

    @Test
    void delete_closesTheOpenStoreAndRemovesItsDirectory(@TempDir final Path appPath) {
        final PathCreator pathCreator = mock(PathCreator.class);
        when(pathCreator.toAppPath("graphdb")).thenReturn(appPath.resolve("graphdb"));

        final GraphDbDoc doc = GraphDbDoc.builder().uuid("doc-uuid-3").name("Graph3").build();
        final GraphStoreManagerImpl manager = new GraphStoreManagerImpl(pathCreator);

        manager.getOrOpen(doc);
        assertThat(Files.isDirectory(appPath.resolve("graphdb").resolve("doc-uuid-3"))).isTrue();

        manager.delete("doc-uuid-3");
        assertThat(Files.exists(appPath.resolve("graphdb").resolve("doc-uuid-3"))).isFalse();

        // A subsequent getOrOpen for the same UUID provisions a fresh, empty store, not a re-open of stale data.
        final GraphStores reopened = manager.getOrOpen(doc);
        try {
            assertThat(Files.isDirectory(appPath.resolve("graphdb").resolve("doc-uuid-3"))).isTrue();
        } finally {
            reopened.close();
        }
    }

    @Test
    void delete_ofAnUnopenedButExistingDirectory_stillRemovesIt(@TempDir final Path appPath) {
        final PathCreator pathCreator = mock(PathCreator.class);
        when(pathCreator.toAppPath("graphdb")).thenReturn(appPath.resolve("graphdb"));

        final GraphDbDoc doc = GraphDbDoc.builder().uuid("doc-uuid-4").name("Graph4").build();
        final GraphStoreManagerImpl firstManager = new GraphStoreManagerImpl(pathCreator);
        firstManager.getOrOpen(doc).close();
        assertThat(Files.isDirectory(appPath.resolve("graphdb").resolve("doc-uuid-4"))).isTrue();

        // A fresh manager instance (mirroring a restart) never opened doc-uuid-4 itself, yet its on-disk
        // directory from the previous manager still exists and must still be removable.
        final GraphStoreManagerImpl secondManager = new GraphStoreManagerImpl(pathCreator);
        secondManager.delete("doc-uuid-4");

        assertThat(Files.exists(appPath.resolve("graphdb").resolve("doc-uuid-4"))).isFalse();
    }

    @Test
    void delete_ofAUuidWithNoDirectoryAtAll_isANoOp(@TempDir final Path appPath) {
        final PathCreator pathCreator = mock(PathCreator.class);
        when(pathCreator.toAppPath("graphdb")).thenReturn(appPath.resolve("graphdb"));

        final GraphStoreManagerImpl manager = new GraphStoreManagerImpl(pathCreator);

        assertThatCode(() -> manager.delete("never-existed")).doesNotThrowAnyException();
    }

    private static ByteBuffer directBuffer(final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }
}
