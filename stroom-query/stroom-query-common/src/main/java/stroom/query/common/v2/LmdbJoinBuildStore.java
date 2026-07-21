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

package stroom.query.common.v2;

import stroom.lmdb.LmdbConfig;
import stroom.lmdb.stream.LmdbKeyRange;
import stroom.lmdb2.LmdbDb;
import stroom.lmdb2.LmdbEnv;
import stroom.lmdb2.LmdbEnvDir;
import stroom.lmdb2.WriteTxn;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValSerialiser;
import stroom.query.language.functions.ref.KryoDataReader;
import stroom.query.language.functions.ref.KryoDataWriter;
import stroom.query.planner.join.BuildSideLookup;

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The disk-backed {@link BuildSideLookup} - the spill store for a hash join whose build side is too large for the
 * heap (see {@code docs/join-scalability-implementation-plan.md}, item C1). It reuses the same off-heap LMDB
 * machinery ordinary searches use, but as a plain keyed multimap rather than {@code LmdbDataStore}'s grouping
 * store (which collapses rows in a group via {@code Generator.merge} - a join build side must instead preserve
 * every row), so it is built directly on the low-level {@code stroom.lmdb2} primitives, modelled on Plan B's
 * {@code PlanBEnv} ad-hoc-env pattern.
 *
 * <p><b>Storage layout - one non-DUPSORT DB.</b> Each build row is stored under a globally unique key
 * {@code encode(joinKey) ++ 8-byte sequence}; the value is the row's {@link ValSerialiser} bytes. The sequence
 * suffix makes every stored key unique, so (a) no {@code put} ever overwrites another and (b) byte-identical
 * duplicate rows under the same join key are all retained (a join emits one output row per build row, so
 * collapsing duplicates would drop results). {@code MDB_DUPSORT} is deliberately <i>not</i> used: it would cap
 * each stored value at LMDB's ~511-byte max-key size, which a serialised multi-column row easily exceeds.</p>
 *
 * <p><b>Key encoding is prefix-free for a fixed key arity.</b> {@link #encodeKey} writes each equi-key component
 * length-prefixed ({@code [4-byte length][UTF-8 bytes]}). Every key in one store has the same arity (the join's
 * equi-key column count), which makes the encoding prefix-free: {@code encode(a)} is a byte-prefix of
 * {@code encode(b)} only when {@code a.equals(b)}. So {@link #get} can retrieve exactly one join key's rows with a
 * prefix scan over {@code encode(joinKey)} (every stored key for that join key starts with it and no other join
 * key's does), then skip past the 8-byte sequence suffix.</p>
 *
 * <p><b>Lifecycle</b> follows the two-phase {@link BuildSideLookup} contract: a build phase of {@link #put} calls
 * writing through one long-lived {@link WriteTxn} (committed every {@link #COMMIT_INTERVAL_ROWS} rows so
 * uncommitted dirty pages stay bounded - the point of spilling), then a probe phase of {@link #get} calls. The
 * first {@link #get} finalises the build (commits and closes the write txn). {@link #close} closes the
 * environment and deletes its temporary directory.</p>
 *
 * <p><b>Not thread-safe.</b> Build from one thread, then probe from one thread - matching the
 * {@link BuildSideLookup} contract and the way {@code JoinSearchProvider} realises then probes a side.</p>
 */
public final class LmdbJoinBuildStore implements BuildSideLookup {

    /** The single DB's name within the temporary environment. */
    private static final String DB_NAME = "join-build";

    /**
     * LMDB's default maximum key size (compile-time {@code MDB_MAXKEYSIZE}). A stored key is
     * {@code encode(joinKey) ++ 8-byte sequence}; if a join key's encoded form plus that suffix would exceed this,
     * {@link #put} fails with a clear error rather than an opaque LMDB failure (hashing over-long keys to sidestep
     * the limit is a documented future enhancement - see the plan doc).
     */
    private static final int MAX_KEY_SIZE = 511;

    /** How many rows to buffer in the write txn before committing, to bound uncommitted dirty-page memory. */
    private static final int COMMIT_INTERVAL_ROWS = 50_000;

    private final LmdbEnvDir envDir;
    private final LmdbEnv env;
    private final LmdbDb db;

    /** Reused across {@link #put}/{@link #get} - direct, sized to LMDB's max key. Safe to reuse because build
     * and probe are single-threaded and each call fully consumes it before returning. */
    private final ByteBuffer keyBuffer = ByteBuffer.allocateDirect(MAX_KEY_SIZE);
    /** Reused across {@link #put}; grown (reallocated) only when a row's serialised form needs more space. */
    private ByteBuffer valueBuffer = ByteBuffer.allocateDirect(1_024);

    private WriteTxn writeTxn;
    private boolean building = true;
    private boolean closed;
    private long rowCount;

    /**
     * Opens a fresh temporary LMDB environment for a join build side.
     *
     * <p><b>Preconditions:</b> {@code envDir} must be non-null and dedicated to this store (it is deleted whole on
     * {@link #close}); {@code lmdbConfig} must be non-null (supplies the map size / reader limits - the same
     * config an ordinary result store uses).<br>
     * <b>Postconditions:</b> the environment and its single DB exist and are ready for the build phase.</p>
     *
     * @param envDir     the (dedicated) directory the environment is created in.
     * @param lmdbConfig the LMDB sizing config.
     */
    public LmdbJoinBuildStore(final LmdbEnvDir envDir, final LmdbConfig lmdbConfig) {
        Objects.requireNonNull(envDir, "envDir");
        Objects.requireNonNull(lmdbConfig, "lmdbConfig");
        this.envDir = envDir;
        envDir.ensureExists();
        this.env = LmdbEnv.builder()
                .config(lmdbConfig)
                .lmdbEnvDir(envDir)
                .maxDbs(1)
                .build();
        this.db = env.openDb(DB_NAME, DbiFlags.MDB_CREATE);
    }

    @Override
    public void put(final List<String> key, final Val[] row) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(row, "row");
        if (!building) {
            throw new IllegalStateException("put(...) called after the build phase ended (first get)");
        }
        if (writeTxn == null) {
            // Obtained lazily so the write txn binds to the build thread, not whatever thread constructed us.
            writeTxn = env.writeTxn();
        }

        translateMapFull(() -> db.put(writeTxn, dbKey(key, rowCount), valueBytes(row)));
        rowCount++;
        if (rowCount % COMMIT_INTERVAL_ROWS == 0) {
            // Flush to disk so uncommitted dirty pages don't grow unbounded; the txn reopens on the next put.
            translateMapFull(writeTxn::commit);
        }
    }

    /**
     * Runs an LMDB write/commit, translating LMDB's {@link Env.MapFullException} into a clear, join-specific
     * error. Without this, exhausting the spill store's on-disk map surfaces as an opaque LMDB exception; the
     * caller ({@code JoinSearchProvider}) still captures it on the result store, but the message would not tell
     * the operator what to do about it - unlike the row-count guardrail's message.
     */
    private void translateMapFull(final Runnable lmdbOp) {
        try {
            lmdbOp.run();
        } catch (final Env.MapFullException e) {
            throw new RuntimeException(
                    "Join build side too large to spill to disk: the LMDB spill store's map size ("
                    + env.getMaxStoreSize() + ") is full. Increase the result-store LMDB maxStoreSize, or add a "
                    + "filter / narrow the join so its build side is smaller.", e);
        }
    }

    @Override
    public boolean forEachMatch(final List<String> key, final Consumer<Val[]> matchConsumer) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(matchConsumer, "matchConsumer");
        finishBuilding();

        final ByteBuffer prefix = prefixKey(key);
        final boolean[] matched = {false};
        // Prefix scan over encode(key) yields this key's rows in insertion order (dbKey = encode(key) ++ monotonic
        // sequence). Each row is deserialised and handed to the consumer as it is read - never accumulated - so a
        // hot key with a huge group streams in bounded memory rather than materialising a list (the OOM this
        // streaming primitive exists to prevent - see the class Javadoc / OOM-reduction plan).
        env.read(readTxn -> db.iterate(
                readTxn,
                LmdbKeyRange.builder().prefix(prefix).build(),
                iterator -> {
                    while (iterator.hasNext()) {
                        matched[0] = true;
                        matchConsumer.accept(deserialiseRow(iterator.next().getVal()));
                    }
                }));
        return matched[0];
    }

    @Override
    public long rowCount() {
        return rowCount;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (building && writeTxn != null) {
                // Never probed - abort the (possibly uncommitted) build txn before tearing the env down.
                writeTxn.close();
            }
        } finally {
            try {
                env.close();
            } finally {
                envDir.delete();
            }
        }
    }

    /** Commits and closes the build write txn on the transition from build to probe; idempotent. */
    private void finishBuilding() {
        if (building) {
            building = false;
            if (writeTxn != null) {
                translateMapFull(writeTxn::commit);
                writeTxn.close();
                writeTxn = null;
            }
        }
    }

    /** Fills {@link #keyBuffer} with {@code encode(key) ++ sequence} for a {@link #put}. */
    private ByteBuffer dbKey(final List<String> key, final long sequence) {
        final byte[] encoded = encodeKey(key);
        if (encoded.length + Long.BYTES > MAX_KEY_SIZE) {
            throw new IllegalArgumentException(
                    "Join key too large to spill to disk: its encoded form (" + encoded.length + " bytes) plus the "
                    + "row-sequence suffix exceeds the LMDB maximum key size of " + MAX_KEY_SIZE + " bytes. Reduce "
                    + "the join key size, or add a filter so the join fits in memory without spilling.");
        }
        keyBuffer.clear();
        keyBuffer.put(encoded);
        keyBuffer.putLong(sequence);
        keyBuffer.flip();
        return keyBuffer;
    }

    /** Fills {@link #keyBuffer} with just {@code encode(key)} - the prefix scanned by {@link #get}. */
    private ByteBuffer prefixKey(final List<String> key) {
        keyBuffer.clear();
        keyBuffer.put(encodeKey(key));
        keyBuffer.flip();
        return keyBuffer;
    }

    /**
     * Length-prefixed encoding of a composite key ({@code [4-byte length][UTF-8 bytes]} per component) - prefix-free
     * within a store, where every key shares the same arity (see the class Javadoc).
     */
    private static byte[] encodeKey(final List<String> key) {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (final DataOutputStream out = new DataOutputStream(baos)) {
            for (final String component : key) {
                final byte[] bytes = component.getBytes(StandardCharsets.UTF_8);
                out.writeInt(bytes.length);
                out.write(bytes);
            }
        } catch (final IOException e) {
            // ByteArrayOutputStream never actually throws IOException; rethrow unchecked to keep callers clean.
            throw new UncheckedIOException(e);
        }
        return baos.toByteArray();
    }

    /** Serialises {@code row} into (a possibly grown) {@link #valueBuffer}, ready for a {@link #put}. */
    private ByteBuffer valueBytes(final Val[] row) {
        final byte[] bytes;
        try (final Output output = new Output(64, -1)) {
            try (final KryoDataWriter writer = new KryoDataWriter(output)) {
                ValSerialiser.writeArray(writer, row);
            }
            bytes = output.toBytes();
        }
        if (valueBuffer.capacity() < bytes.length) {
            valueBuffer = ByteBuffer.allocateDirect(bytes.length);
        }
        valueBuffer.clear();
        valueBuffer.put(bytes);
        valueBuffer.flip();
        return valueBuffer;
    }

    /** Reads back a row previously written by {@link #valueBytes}. */
    private static Val[] deserialiseRow(final ByteBuffer value) {
        final byte[] bytes = new byte[value.remaining()];
        value.get(bytes);
        try (final Input input = new Input(bytes);
                final KryoDataReader reader = new KryoDataReader(input)) {
            return ValSerialiser.readArray(reader);
        }
    }
}
