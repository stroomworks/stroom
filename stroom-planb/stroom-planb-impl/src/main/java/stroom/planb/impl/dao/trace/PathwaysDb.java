/*
 * Copyright 2025 Crown Copyright
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

package stroom.planb.impl.dao.trace;

import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.lmdb.stream.LmdbEntry;
import stroom.lmdb.stream.LmdbIterable;
import stroom.lmdb.stream.LmdbIterable.EntryConsumer;
import stroom.lmdb.stream.LmdbKeyRange;
import stroom.planb.impl.dao.HashClashCommitRunnable;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.PlanBEnv;
import stroom.planb.shared.StateSettings;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.PutFlags;
import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * The learnt pathway model for one shard, the record of which traces have been folded into it, and
 * the changes each of them made.
 *
 * <p>Three plain tables and no serde, so this is not a {@link stroom.planb.impl.dao.Db}. Anything that
 * needs to size or copy the environment without knowing what is in it goes through
 * {@link PlanBEnv#openForMaintenance}.
 */
public class PathwaysDb implements AutoCloseable {

    protected static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(PathwaysDb.class);

    protected final PlanBEnv env;
    protected final ByteBuffers byteBuffers;
    protected final SimpleDb processingStatus;
    protected final SimpleDb pathways;
    protected final SimpleDb mutations;

    private PathwaysDb(final PlanBEnv env,
                       final ByteBuffers byteBuffers) {
        this.env = env;
        this.byteBuffers = byteBuffers;

        // Three plain key/value DBIs, created on first open. Unlike AbstractDb there is no stored
        // schema to read back and validate, because neither key nor value goes through a versioned
        // serde.
        processingStatus = new SimpleDb(
                env,
                env.openDbi("processing-status", DbiFlags.MDB_CREATE),
                new PutFlags[]{});
        pathways = new SimpleDb(
                env,
                env.openDbi("pathways", DbiFlags.MDB_CREATE),
                new PutFlags[]{});
        mutations = new SimpleDb(
                env,
                env.openDbi("mutations", DbiFlags.MDB_CREATE),
                new PutFlags[]{});
    }

    public SimpleDb getProcessingStatus() {
        return processingStatus;
    }

    public SimpleDb getPathways() {
        return pathways;
    }

    /**
     * Every change made to the models in this shard, so their growth can be replayed rather than only
     * seen as it ended up. Append only, and nothing prunes it yet.
     */
    public SimpleDb getMutations() {
        return mutations;
    }

    public LmdbWriter createWriter() {
        return env.createWriter();
    }

    /**
     * Closes the LMDB environment. Must be called before the directory the env lives in is
     * deleted, and after any writers created from this instance have been closed.
     */
    @Override
    public void close() {
        env.close();
    }

    public static PathwaysDb create(final Path path,
                                    final ByteBuffers byteBuffers,
                                    final boolean readOnly) {
        final StateSettings settings = new StateSettings.Builder().build();
        final HashClashCommitRunnable hashClashCommitRunnable = new HashClashCommitRunnable();
        final PlanBEnv env = new PlanBEnv(path,
                settings.getMaxStoreSize(),
                20,
                readOnly,
                hashClashCommitRunnable);
        try {
            return new PathwaysDb(
                    env,
                    byteBuffers);
        } catch (final RuntimeException e) {
            // Close the env if we get any exceptions to prevent them staying open.
            try {
                env.close();
            } catch (final Exception e2) {
                LOGGER.debug(LogUtil.message("message={}", e.getMessage()), e);
            }
            throw e;
        }
    }


    public static class SimpleDb {

        private final PlanBEnv env;
        private final Dbi<ByteBuffer> dbi;
        private final PutFlags[] putFlags;

        public SimpleDb(final PlanBEnv env,
                        final Dbi<ByteBuffer> dbi,
                        final PutFlags[] putFlags) {
            this.env = env;
            this.dbi = dbi;
            this.putFlags = putFlags;
        }

        public void insert(final LmdbWriter writer, final ByteBuffer keyByteBuffer, final ByteBuffer valueByteBuffer) {
            final Txn<ByteBuffer> writeTxn = writer.getWriteTxn();
            dbi.put(writeTxn, keyByteBuffer, valueByteBuffer, putFlags);
            writer.tryCommit();
        }

        public void iterate(final EntryConsumer consumer) {
            env.read(txn -> {
                iterate(txn, consumer);
                return null;
            });
        }

        public void iterate(final Txn<ByteBuffer> txn,
                            final EntryConsumer consumer) {
            LmdbIterable.iterate(txn, dbi, consumer);
        }

        /**
         * The last key beginning with the given bytes, or null where there is none. Seeks straight to
         * it rather than walking, so it costs the same whatever the owner's history holds.
         *
         * <p>Takes the caller's transaction rather than opening one. A reader sees only what has been
         * committed, so a fresh one would not see what the caller has written and not yet committed —
         * which for a caller numbering rows from the last key means it would number them all the same.
         */
        public <R> R lastPrefixed(final Txn<ByteBuffer> txn,
                                  final ByteBuffer prefix,
                                  final Function<ByteBuffer, R> keyConsumer) {
            try (final LmdbIterable iterable = LmdbIterable.create(txn, dbi,
                    LmdbKeyRange.builder().prefix(prefix).reverse().build())) {
                for (final LmdbEntry entry : iterable) {
                    return keyConsumer.apply(entry.getKey());
                }
            }
            return keyConsumer.apply(null);
        }

        /**
         * The entries whose keys begin with the given bytes, in key order. Used where one table holds
         * rows for many owners and only one owner's are wanted.
         */
        public void iteratePrefix(final ByteBuffer prefix, final EntryConsumer consumer) {
            env.read(txn -> {
                LmdbIterable.iterate(txn, dbi, LmdbKeyRange.builder().prefix(prefix).build(), consumer);
                return null;
            });
        }

        public <R> R get(final Txn<ByteBuffer> txn,
                         final ByteBuffer keyByteBuffer,
                         final Function<ByteBuffer, R> byteBufferConsumer) {
            return byteBufferConsumer.apply(dbi.get(txn, keyByteBuffer));
        }

        public <R> R get(final ByteBuffer keyByteBuffer,
                         final Function<ByteBuffer, R> byteBufferConsumer) {
            return env.read(readTxn -> get(readTxn, keyByteBuffer, byteBufferConsumer));
        }
    }
}
