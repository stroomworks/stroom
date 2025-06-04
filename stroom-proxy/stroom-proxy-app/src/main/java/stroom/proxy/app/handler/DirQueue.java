package stroom.proxy.app.handler;

import stroom.proxy.repo.queue.QueueMonitor;
import stroom.proxy.repo.queue.QueueMonitors;
import stroom.proxy.repo.store.FileStores;
import stroom.util.concurrent.UncheckedInterruptedException;
import stroom.util.exception.ThrowingSupplier;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class DirQueue {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(DirQueue.class);
    private final Path rootDir;

    /**
     * ID last written to, i.e. 0 if never written to
     */
    private long writeId;
    /**
     * ID to read from next, i.e. 1 if not read yet
     */
    private long readId;

    private final Lock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private final QueueMonitor queueMonitor;
    private final String name;

    DirQueue(final Path rootDir,
             final QueueMonitors queueMonitors,
             final FileStores fileStores,
             final int order,
             final String name) {
        this.rootDir = rootDir;
        this.queueMonitor = queueMonitors.create(order, name);
        this.name = name;

        // Create the root directory
        DirUtil.ensureDirExists(rootDir);

        // Create the store directory and initialise the store id.
        fileStores.add(order, name + " - store", rootDir);

        final long maxId = DirUtil.getMaxDirId(rootDir);
        final long minId = DirUtil.getMinDirId(rootDir);

        if (minId > maxId) {
            throw new IllegalStateException(LogUtil.message("minId {} is greater than maxId {}", minId, maxId));
        }

        writeId = maxId;
        readId = Math.max(1, minId);
        queueMonitor.setWritePos(maxId);
        queueMonitor.setReadPos(minId);
        LOGGER.info("Initialising queue '{}' in {} with readId {} and writeId {}",
                name, LogUtil.path(rootDir), readId, writeId);
    }

    /**
     * Get the next dir that is available in the queue as soon as one is available, block until then.
     *
     * @return A dir that is managed by this queue. The dir should be closed once used to ensure parent dirs are
     * deleted if empty.
     */
    public Dir next() {
        Dir dir = null;
        try {
            lock.lockInterruptibly();
            try {
                while (dir == null) {
                    while (readId > writeId) {
                        condition.await();
                    }
                    // TODO It is possible to have big gaps, e.g. if there was an exception when
                    //  transferring 001 (so it was left on the queue) but 002 -> 901 worked. On next
                    //  reboot, it will have to check each of 002 -> 901 to find them not there.
                    //  May be better to call DirUtil.getMinDirId if we encounter a gap
                    final long id = readId++;
                    final Path path = DirUtil.createPath(rootDir, id);
                    if (Files.isDirectory(path)) {
                        queueMonitor.setReadPos(id);
                        dir = createDir(path);
                    }
                }
            } finally {
                lock.unlock();
            }
        } catch (final InterruptedException e) {
            throw UncheckedInterruptedException.create(e);
        }
        LOGGER.trace("{} ({}) - next() dir: {}", name, rootDir, dir);
        return dir;
    }

    /**
     * Get the next dir that is available in the queue as soon as one is available, block until then or timeout.
     *
     * @return A dir that is managed by this queue. The dir should be closed once used to ensure parent dirs are
     * deleted if empty.
     */
    public Optional<Dir> next(final long time, final TimeUnit unit) {
        Dir dir = null;
        try {
            lock.lockInterruptibly();
            try {
                while (dir == null) {
                    while (readId > writeId) {
                        if (!condition.await(time, unit)) {
                            return Optional.empty();
                        }
                    }
                    final long id = readId++;
                    final Path path = DirUtil.createPath(rootDir, id);
                    if (Files.isDirectory(path)) {
                        queueMonitor.setReadPos(id);
                        dir = createDir(path);
                    }
                }
            } finally {
                lock.unlock();
            }
        } catch (final InterruptedException e) {
            throw UncheckedInterruptedException.create(e);
        }
        LOGGER.trace("{} ({}) - next() time: {}, unit: {}, dir: {}", name, rootDir, time, unit, dir);
        return Optional.of(dir);
    }

    /**
     * Add a dir to the queue. In the process this will move the source dir to a dir managed by the queue so that the
     * process providing the dir will no longer be responsible for managing the supplied dir.
     *
     * @param sourceDir The source dir to move to the queue.
     */
    public void add(final Path sourceDir) {
        try {
            lock.lockInterruptibly();
            try {
                // Increment the sequence id.
                final long id = ++writeId;
                queueMonitor.setWritePos(id);
                final Path targetDir = DirUtil.createPath(rootDir, id);
                final Path targetParent = targetDir.getParent();
                try {
                    DirUtil.ensureDirExists(targetParent);
                    Files.move(sourceDir, targetDir, StandardCopyOption.ATOMIC_MOVE);
                    LOGGER.trace("{} ({}) - Added sourceDir {}", name, rootDir, sourceDir);
                } catch (final IOException e) {
                    final boolean targetParentExists = LogUtil.swallowExceptions(
                                    ThrowingSupplier.unchecked(() -> Files.exists(targetParent)))
                            .orElse(false);
                    final boolean sourceExists = LogUtil.swallowExceptions(
                                    ThrowingSupplier.unchecked(() -> Files.exists(sourceDir)))
                            .orElse(false);
                    LOGGER.error("Error moving {} -> {}, sourceExists: {}, targetParentExists: {}, msg: {}",
                            sourceDir, targetDir, sourceExists, targetParentExists, LogUtil.exceptionMessage(e), e);
                    throw new UncheckedIOException(e);
                }
                condition.signalAll();
            } finally {
                lock.unlock();
            }
        } catch (final InterruptedException e) {
            throw UncheckedInterruptedException.create(e);
        }
    }

    /**
     * When we have finished with a dir we should be in a position where the dir has been
     * moved so can try to delete the parent directories. We never want to delete the dir
     * itself as it should have been moved and any failure to do so is an error.
     *
     * @param dir The dir to close.
     */
    public void close(final Dir dir) {
        try {
            lock.lockInterruptibly();
            try {
                // Try to delete parent directories.
                try {
                    boolean success = true;
                    Path path = dir.getPath().getParent();
                    while (!path.equals(rootDir) && success) {
                        success = Files.deleteIfExists(path);
                        path = path.getParent();
                    }
                } catch (final DirectoryNotEmptyException e) {
                    // Expected error.
                    LOGGER.trace(e::getMessage, e);
                } catch (final IOException e) {
                    LOGGER.error(e::getMessage, e);
                }
            } finally {
                lock.unlock();
            }
        } catch (final InterruptedException e) {
            throw UncheckedInterruptedException.create(e);
        }
    }

    private Dir createDir(final Path path) {
        return new Dir(this, path);
    }

    long getReadId() {
        return readId;
    }

    long getWriteId() {
        return writeId;
    }

    @Override
    public String toString() {
        return "DirQueue{" +
               "rootDir=" + rootDir +
               ", writeId=" + writeId +
               ", readId=" + readId +
               '}';
    }
}
