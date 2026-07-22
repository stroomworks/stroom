/*
 * Copyright 2016-2025 Crown Copyright
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

import org.lmdbjava.Env;
import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public class LmdbWriter implements AutoCloseable {

    private final Env<ByteBuffer> env;
    private final ReentrantLock dbCommitLock;
    private final Consumer<Txn<ByteBuffer>> commitListener;
    private final ReentrantLock writeTxnLock;
    private Txn<ByteBuffer> writeTxn;
    private int changeCount = 0;

    public LmdbWriter(final Env<ByteBuffer> env,
                      final ReentrantLock dbCommitLock,
                      final Consumer<Txn<ByteBuffer>> commitListener,
                      final ReentrantLock writeTxnLock) {
        this.env = env;
        this.dbCommitLock = dbCommitLock;
        this.commitListener = commitListener;
        this.writeTxnLock = writeTxnLock;

        // We are only allowed a single write txn and we can only write with a single thread so ensure this is the
        // case.
        writeTxnLock.lock();
    }

    public Txn<ByteBuffer> getWriteTxn() {
        if (writeTxn == null) {
            writeTxn = env.txnWrite();
        }
        return writeTxn;
    }

    public void tryCommit() {
        incrementChangeCount();
        if (shouldCommit()) {
            commit();
        }
    }

    public void incrementChangeCount() {
        changeCount++;
    }

    public boolean shouldCommit() {
        return changeCount > 10000;
    }

    public void commit() {
        dbCommitLock.lock();
        try {
            if (writeTxn != null) {
                try {
                    commitListener.accept(writeTxn);
                    writeTxn.commit();
                } finally {
                    try {
                        writeTxn.close();
                    } finally {
                        writeTxn = null;
                    }
                }
            }

            changeCount = 0;
        } finally {
            dbCommitLock.unlock();
        }
    }

    /**
     * Discards the current write transaction (if one is open) without committing it, so that any writes made
     * against it since the last {@link #commit()}/{@link #abort()} are rolled back and a subsequent
     * {@link #getWriteTxn()} call starts a fresh transaction.
     *
     * <p>Unlike {@link #commit()}, the commit listener supplied at construction is <b>not</b> invoked, since the
     * transaction's writes are being discarded rather than persisted. This is a no-op if no write transaction is
     * currently open (e.g. nothing has been written since the last commit/abort).</p>
     *
     * <p>Callers that need per-record atomicity (rather than this class's own batched auto-commit threshold, see
     * {@link #tryCommit()}) should call this from a catch block covering a single record's writes, and
     * {@link #commit()} on that record's success - each record then becomes one all-or-nothing transaction.</p>
     *
     * <p><b>Preconditions:</b> none. <b>Postconditions:</b> no write transaction is open; the pending change count
     * is reset to zero; any writes made since the last commit/abort are not visible to subsequent reads.</p>
     */
    public void abort() {
        dbCommitLock.lock();
        try {
            if (writeTxn != null) {
                try {
                    writeTxn.abort();
                } finally {
                    try {
                        writeTxn.close();
                    } finally {
                        writeTxn = null;
                    }
                }
            }

            changeCount = 0;
        } finally {
            dbCommitLock.unlock();
        }
    }

    @Override
    public void close() {
        try {
            commit();
        } finally {
            writeTxnLock.unlock();
        }
    }
}
