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

package stroom.planb.impl.dao;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit tests of {@link LmdbWriter}'s commit/abort boundary - the mechanism {@code GraphFilter}
 * (pre-production review finding F4; see {@code docs/query-graphdb-review-report.md} and the "Batch 1"
 * write-up in {@code docs/query-graphdb-review-findings.md}) relies on to make each ingested record an
 * all-or-nothing unit, instead of {@link LmdbWriter}'s own batched {@link LmdbWriter#tryCommit()} auto-commit
 * threshold - which would otherwise let one record's partial writes ride along with, and later be silently
 * committed alongside, every already-succeeded record ahead of it.
 */
class TestLmdbWriter {

    @Test
    void commit_persistsWrites(@TempDir final Path tempDir) {
        try (final PlanBEnv env = newEnv(tempDir)) {
            final Dbi<ByteBuffer> dbi = env.openDbi("test", DbiFlags.MDB_CREATE);
            try (final LmdbWriter writer = env.createWriter()) {
                dbi.put(writer.getWriteTxn(), key("a"), value("1"));
                writer.commit();
            }

            env.read(readTxn -> {
                assertThat(dbi.get(readTxn, key("a"))).isNotNull();
                return null;
            });
        }
    }

    @Test
    void abort_discardsOnlyItsOwnUncommittedWrites_precedingAndFollowingWritesUnaffected(
            @TempDir final Path tempDir) {
        // Mirrors GraphFilter.perRecord's contract: one writer spans many records; each record either commits
        // (handler succeeded) or aborts (handler threw) as a whole - a failure must not disturb any record
        // already committed ahead of it, nor prevent a later record on the same writer from writing normally.
        try (final PlanBEnv env = newEnv(tempDir)) {
            final Dbi<ByteBuffer> dbi = env.openDbi("test", DbiFlags.MDB_CREATE);
            try (final LmdbWriter writer = env.createWriter()) {
                // "Record 1" - succeeds and is committed.
                dbi.put(writer.getWriteTxn(), key("rec1"), value("v1"));
                writer.commit();

                // "Record 2" - one write succeeds, then (simulating a later write in the same record throwing,
                // e.g. GraphFilter's in-edge insert after its out-edge insert already landed) the record is
                // aborted instead of committed.
                dbi.put(writer.getWriteTxn(), key("rec2-partial"), value("partial"));
                writer.abort();

                // "Record 3" - a fresh write on the same writer, after the abort, must still succeed normally.
                dbi.put(writer.getWriteTxn(), key("rec3"), value("v3"));
                writer.commit();
            }

            env.read(readTxn -> {
                assertThat(dbi.get(readTxn, key("rec1")))
                        .as("record 1 (committed before the failing record)").isNotNull();
                assertThat(dbi.get(readTxn, key("rec2-partial")))
                        .as("record 2's partial write (must be rolled back by abort())").isNull();
                assertThat(dbi.get(readTxn, key("rec3")))
                        .as("record 3 (written on the same writer after the abort)").isNotNull();
                return null;
            });
        }
    }

    @Test
    void abort_withNoOpenWriteTransaction_isANoOp(@TempDir final Path tempDir) {
        try (final PlanBEnv env = newEnv(tempDir)) {
            final Dbi<ByteBuffer> dbi = env.openDbi("test", DbiFlags.MDB_CREATE);
            try (final LmdbWriter writer = env.createWriter()) {
                // Nothing written yet - no write transaction is open at all.
                writer.abort();

                dbi.put(writer.getWriteTxn(), key("x"), value("y"));
                writer.commit();
            }

            env.read(readTxn -> {
                assertThat(dbi.get(readTxn, key("x"))).isNotNull();
                return null;
            });
        }
    }

    @Test
    void abort_doesNotInvokeTheCommitListener(@TempDir final Path tempDir) {
        // Unlike commit(), abort() must not run the commit listener - its writes are being discarded, not
        // persisted, so any commit-time bookkeeping the listener performs (e.g. Plan B's hash-clash recording)
        // must not fire for data that was never actually committed.
        try (final PlanBEnv env = newEnv(tempDir)) {
            final Dbi<ByteBuffer> dbi = env.openDbi("test", DbiFlags.MDB_CREATE);
            final AtomicInteger commitListenerCalls = new AtomicInteger();
            final ReentrantLock dbCommitLock = new ReentrantLock();
            final ReentrantLock writeTxnLock = new ReentrantLock();

            try (final LmdbWriter writer = new LmdbWriter(
                    env.env, dbCommitLock, txn -> commitListenerCalls.incrementAndGet(), writeTxnLock)) {
                dbi.put(writer.getWriteTxn(), key("a"), value("1"));
                writer.abort();
                assertThat(commitListenerCalls).hasValue(0);

                dbi.put(writer.getWriteTxn(), key("b"), value("2"));
                writer.commit();
                assertThat(commitListenerCalls).hasValue(1);
            }
        }
    }

    private static PlanBEnv newEnv(final Path tempDir) {
        return new PlanBEnv(tempDir, null, 10, false, new HashClashCommitRunnable());
    }

    private static ByteBuffer key(final String value) {
        return directBuffer(value);
    }

    private static ByteBuffer value(final String value) {
        return directBuffer(value);
    }

    private static ByteBuffer directBuffer(final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }
}
