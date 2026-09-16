/*
 * Copyright 2026 Crown Copyright
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

package stroom.pathways.impl;

import stroom.bytebuffer.impl6.ByteBufferFactoryImpl;
import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.node.api.NodeInfo;
import stroom.pathways.shared.PathwaysDoc;
import stroom.planb.impl.PlanBConstants;
import stroom.planb.impl.PlanBPaths;
import stroom.planb.impl.fs.MergeCompletionStrategy;
import stroom.planb.impl.fs.SharedFileStorePublisher;
import stroom.planb.shared.SharedFileStoreSettings;
import stroom.planb.shared.StateType;
import stroom.util.io.PathCreator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import org.lmdbjava.Txn;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whether a shard's model survives the round trip to the shared filesystem and back.
 *
 * <p>Exercised against a counter in an LMDB environment rather than a real model. That is the point:
 * the moving of the file has nothing to do with what is in it, and it can be settled before the model
 * exists. The counter is a fixture and is not, and should not become, shipped code.
 */
class TestPathwaysShardStore {

    private static final ByteBufferFactoryImpl BYTE_BUFFER_FACTORY = new ByteBufferFactoryImpl();
    private static final ByteBuffers BYTE_BUFFERS = new ByteBuffers(BYTE_BUFFER_FACTORY);

    private static final int SHARD = 3;

    @TempDir
    Path tempDir;

    @Mock
    private PathCreator pathCreator;
    @Mock
    private NodeInfo nodeInfo;

    private Path shared;
    private PathwaysDoc doc;
    private PathwaysShardStore store;

    @BeforeEach
    void setUp() throws IOException {
        MockitoAnnotations.openMocks(this);
        shared = Files.createDirectories(tempDir.resolve("pathways_shared"));
        doc = PathwaysDoc.builder()
                .uuid(UUID.randomUUID().toString())
                .name("Test Pathways")
                .sharedFileStore(new SharedFileStoreSettings(8, shared.toString()))
                .build();

        Mockito.when(pathCreator.toAppPath(Mockito.anyString()))
                .thenReturn(tempDir.resolve("local"));
        // push stamps .version with the pushing node's name, unlike pushArchive which needs no node.
        Mockito.when(nodeInfo.getThisNodeName()).thenReturn("test-node");
        store = new PathwaysShardStore(
                new SharedFileStorePublisher(
                        nodeInfo,
                        BYTE_BUFFERS,
                        BYTE_BUFFER_FACTORY,
                        new PlanBPaths(tempDir.resolve("local_state")),
                        Map.<StateType, MergeCompletionStrategy>of()),
                pathCreator);
    }

    @Test
    void aChangeSurvivesBeingPushedAndTakenBackDown() throws IOException {
        assertThat(store.withShard(doc, SHARD, localDir -> {
            setCounter(localDir, 7);
            return true;
        })).as("a changed shard is pushed").isTrue();

        final long[] seen = {-1};
        store.withShard(doc, SHARD, localDir -> {
            seen[0] = readCounter(localDir);
            return false;
        });
        assertThat(seen[0]).isEqualTo(7);
    }

    @Test
    void changesAccumulateAcrossHolds() throws IOException {
        for (int i = 0; i < 3; i++) {
            store.withShard(doc, SHARD, localDir -> {
                setCounter(localDir, readCounter(localDir) + 1);
                return true;
            });
        }

        final long[] seen = {-1};
        store.withShard(doc, SHARD, localDir -> {
            seen[0] = readCounter(localDir);
            return false;
        });
        assertThat(seen[0]).as("each hold started from what the last one left").isEqualTo(3);
    }

    @Test
    void aShardNobodyHasWrittenStartsEmpty() throws IOException {
        final long[] seen = {-1};
        store.withShard(doc, SHARD, localDir -> {
            assertThat(localDir.resolve(PlanBConstants.DATA_FILE_NAME))
                    .as("nothing to copy down yet").doesNotExist();
            seen[0] = readCounter(localDir);
            return false;
        });
        assertThat(seen[0]).isZero();
    }

    @Test
    void anUnchangedShardIsNotPushed() throws IOException {
        store.withShard(doc, SHARD, localDir -> {
            setCounter(localDir, 5);
            return true;
        });
        final long pushedAt = lastModified(sharedShardDir().resolve(PlanBConstants.DATA_FILE_NAME));

        assertThat(store.withShard(doc, SHARD, localDir -> false))
                .as("a shard that was only read is left alone").isFalse();
        assertThat(lastModified(sharedShardDir().resolve(PlanBConstants.DATA_FILE_NAME)))
                .isEqualTo(pushedAt);
    }

    @Test
    void workThatThrowsLeavesTheSharedCopyAsItWas() throws IOException {
        store.withShard(doc, SHARD, localDir -> {
            setCounter(localDir, 4);
            return true;
        });

        assertThatThrownBy(() -> store.withShard(doc, SHARD, localDir -> {
            setCounter(localDir, 99);
            throw new IllegalStateException("applying blew up");
        })).isInstanceOf(IllegalStateException.class);

        final long[] seen = {-1};
        store.withShard(doc, SHARD, localDir -> {
            seen[0] = readCounter(localDir);
            return false;
        });
        assertThat(seen[0]).as("the half-done work never reached the shared store").isEqualTo(4);
    }

    @Test
    void noLmdbEnvironmentIsOpenedOnTheSharedStore() throws IOException {
        store.withShard(doc, SHARD, localDir -> {
            setCounter(localDir, 1);
            return true;
        });

        // A lock.mdb anywhere under the shared tree means an env was opened there, which is the one
        // thing the shared file store does not allow.
        try (final Stream<Path> files = Files.walk(shared)) {
            assertThat(files.filter(p -> p.getFileName().toString()
                            .equals(PlanBConstants.LOCK_FILE_NAME)).toList())
                    .isEmpty();
        }
    }

    @Test
    void theLocalWorkingCopyIsNotLeftBehind() throws IOException {
        final Path[] used = {null};
        store.withShard(doc, SHARD, localDir -> {
            used[0] = localDir;
            setCounter(localDir, 1);
            return true;
        });
        assertThat(used[0]).doesNotExist();
    }

    // -----------------------------------------------------------------------
    // Fixture — a counter in an LMDB env, standing in for the model
    // -----------------------------------------------------------------------

    private Path sharedShardDir() {
        return shared.resolve(PathwaysShardStore.SHARDS_DIR_NAME)
                .resolve(doc.getUuid())
                .resolve(PlanBConstants.formatShardIndex(SHARD));
    }

    private static void setCounter(final Path dir, final long value) {
        withEnv(dir, (env, dbi) -> env.txnWrite(), (dbi, txn) -> {
            dbi.put(txn, key(), value(value));
            return null;
        });
    }

    private static long readCounter(final Path dir) {
        return withEnv(dir, (env, dbi) -> env.txnRead(), (dbi, txn) -> {
            final ByteBuffer found = dbi.get(txn, key());
            return found == null
                    ? 0L
                    : found.getLong();
        });
    }

    private static <R> R withEnv(final Path dir,
                                 final java.util.function.BiFunction<Env<ByteBuffer>,
                                         Dbi<ByteBuffer>, Txn<ByteBuffer>> txnFactory,
                                 final java.util.function.BiFunction<Dbi<ByteBuffer>,
                                         Txn<ByteBuffer>, R> work) {
        try (final Env<ByteBuffer> env = Env.create()
                .setMapSize(1024L * 1024L)
                .setMaxDbs(1)
                .open(dir.toFile())) {
            final Dbi<ByteBuffer> dbi = env.openDbi("counter", DbiFlags.MDB_CREATE);
            try (final Txn<ByteBuffer> txn = txnFactory.apply(env, dbi)) {
                final R result = work.apply(dbi, txn);
                if (!txn.isReadOnly()) {
                    txn.commit();
                }
                return result;
            }
        }
    }

    private static ByteBuffer key() {
        final ByteBuffer buf = ByteBuffer.allocateDirect(1);
        buf.put("c".getBytes(StandardCharsets.UTF_8)).flip();
        return buf;
    }

    private static ByteBuffer value(final long value) {
        final ByteBuffer buf = ByteBuffer.allocateDirect(Long.BYTES);
        buf.putLong(value).flip();
        return buf;
    }

    private static long lastModified(final Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
