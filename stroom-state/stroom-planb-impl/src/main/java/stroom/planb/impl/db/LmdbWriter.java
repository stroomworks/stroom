package stroom.planb.impl.db;

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

    @Override
    public void close() {
        try {
            commit();
        } finally {
            writeTxnLock.unlock();
        }
    }
}
