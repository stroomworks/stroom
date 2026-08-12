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

package stroom.planb.impl.data;

import stroom.docstore.api.DocumentNotFoundException;
import stroom.security.api.SecurityContext;
import stroom.task.api.TaskContextFactory;
import stroom.util.io.FileUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.string.StringIdUtil;
import stroom.util.zip.ZipUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Receives fragment zips, unzips them and merges each contained fragment into its target store, one queue per
 * target document.
 *
 * <p>This is the feature-neutral half of {@link MergeProcessor}, extracted so Graph DB can run the same
 * receive-stage-unzip-merge lifecycle without either feature depending on the other's document type. Everything
 * feature-specific arrives through the constructor: the directories to work in, the names to log and report tasks
 * under, and a {@link MergeTargetResolver} that turns a fragment's document UUID into something mergeable.</p>
 *
 * <p><b>Two callers must not share directories.</b> A fragment whose document cannot be resolved is treated as
 * belonging to a deleted document and deleted, so a Plan B processor pointed at graph staging directories would
 * silently discard graph fragments, and vice versa. Each caller passes its own roots.</p>
 *
 * <p>It lives in this package because {@link DirQueue} and {@link Dir} have package-private constructors, and
 * their queue semantics - a durable, restart-recoverable, id-ordered directory queue - are the substance of what
 * is being reused.</p>
 *
 * <p><b>A fragment is durable until it has been merged.</b> Its zip stays in the staging store until the
 * fragment has been moved into a merge queue under the merging directory, and the merging directory in turn
 * survives a restart: the constructor clears only the unzip scratch directory, and {@link #merge()} requeues
 * whatever the merging directory still holds. A fragment whose merge fails is retained where it is and retried
 * once per process start - each failed attempt logs at ERROR and notifies the failure listener - so a fragment
 * that can never merge (for example a corrupt one) must be removed by an operator; it is never discarded
 * silently.</p>
 */
public class PartMergeProcessor {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(PartMergeProcessor.class);

    private final Map<String, DirQueue> mergeQueues = new ConcurrentHashMap<>();
    private final SequentialFileStore receiveStore;
    private final Path mergingDir;
    private final Path unzipDir;
    private final AtomicLong unzipSequenceId = new AtomicLong();
    private final SecurityContext securityContext;
    private final TaskContextFactory taskContextFactory;
    private final Executor executor;
    private final MergeTargetResolver mergeTargetResolver;
    private final String featureName;
    private final String mergeTaskName;
    private final Runnable mergeFailureListener;
    private volatile boolean merging;

    /**
     * <p><b>Preconditions:</b> no parameter is null; {@code stagingDir}, {@code mergingDir} and {@code unzipDir}
     * are used by no other {@link PartMergeProcessor}.
     * <b>Postconditions:</b> the unzip directory exists and is empty - anything left in it by a previous run
     * still has its source zip in the staging store, so it is safe to discard and will be redone. The merging
     * directory exists and its contents are preserved: a fragment there has outlived its source zip, so it is
     * the only remaining copy of that data, and {@link #merge()} requeues it.
     * <b>Null status:</b> no parameter is nullable.
     *
     * @param featureName          the feature's name, used as a log-message prefix, e.g. {@code "Plan B"}.
     * @param mergeTaskName        the task name to report unzip and merge work under.
     * @param stagingDir           the sequential store received zips are moved into.
     * @param mergingDir           the root of the per-document merge queues.
     * @param unzipDir             the scratch root received zips are expanded into.
     * @param securityContext      used to run merge work as the processing user.
     * @param taskContextFactory   used to report progress.
     * @param executor             runs the unzip loop and one merge loop per document.
     * @param mergeTargetResolver  resolves a fragment's document UUID to its store.
     * @param mergeFailureListener called when a merge throws, in addition to the ERROR log; the fragment is
     *                             retained either way.
     */
    public PartMergeProcessor(final String featureName,
                              final String mergeTaskName,
                              final Path stagingDir,
                              final Path mergingDir,
                              final Path unzipDir,
                              final SecurityContext securityContext,
                              final TaskContextFactory taskContextFactory,
                              final Executor executor,
                              final MergeTargetResolver mergeTargetResolver,
                              final Runnable mergeFailureListener) {
        this.featureName = Objects.requireNonNull(featureName, "featureName must not be null");
        this.mergeTaskName = Objects.requireNonNull(mergeTaskName, "mergeTaskName must not be null");
        this.receiveStore = new SequentialFileStore(
                Objects.requireNonNull(stagingDir, "stagingDir must not be null"));
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext must not be null");
        this.taskContextFactory = Objects.requireNonNull(taskContextFactory, "taskContextFactory must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.mergeTargetResolver =
                Objects.requireNonNull(mergeTargetResolver, "mergeTargetResolver must not be null");
        this.mergeFailureListener =
                Objects.requireNonNull(mergeFailureListener, "mergeFailureListener must not be null");

        this.mergingDir = Objects.requireNonNull(mergingDir, "mergingDir must not be null");
        // The merging dir is deliberately NOT cleared. A fragment in a merge queue has outlived its source zip,
        // so it is the only remaining copy of that data; merge() requeues whatever is found here, which is how
        // fragments awaiting or failed merge survive a restart.
        FileUtil.ensureDirExists(mergingDir);
        this.unzipDir = Objects.requireNonNull(unzipDir, "unzipDir must not be null");
        FileUtil.ensureDirExists(unzipDir);
        if (!FileUtil.deleteContents(unzipDir)) {
            throw new RuntimeException("Unable to delete contents of: " + FileUtil.getCanonicalPath(unzipDir));
        }
    }

    /**
     * Adds a received fragment zip to the staging store, optionally waiting until it has been merged.
     *
     * <p><b>Preconditions:</b> {@code fileDescriptor} and {@code file} are not null and the file's hash matches
     * the descriptor.
     * <b>Postconditions:</b> {@code file} has been moved into the staging store; if {@code synchroniseMerge} then
     * it has also been merged before returning.
     * <b>Null status:</b> neither {@code fileDescriptor} nor {@code file} is nullable.
     *
     * @param fileDescriptor   identifies the fragment and carries its hash.
     * @param file             the zip to take ownership of.
     * @param synchroniseMerge whether to block until the fragment has been merged.
     * @throws IOException if the file cannot be hashed or moved into the store.
     */
    public void add(final FileDescriptor fileDescriptor,
                    final Path file,
                    final boolean synchroniseMerge) throws IOException {
        final FileInfo fileInfo = fileDescriptor.getInfo(file);
        if (synchroniseMerge) {
            final CountDownLatch countDownLatch = new CountDownLatch(1);
            LOGGER.debug(() -> featureName + " adding part for synchronous merge : " + fileInfo);
            receiveStore.add(fileDescriptor, file, countDownLatch);
            try {
                countDownLatch.await();
            } catch (final InterruptedException e) {
                LOGGER.debug(e::getMessage, e);
                Thread.currentThread().interrupt();
            }
        } else {
            LOGGER.debug(() -> featureName + " adding part for merge : " + fileInfo);
            receiveStore.add(fileDescriptor, file, null);
        }
    }

    /**
     * Starts the background unzip loop and one merge loop per document queue, if not already running.
     *
     * <p><b>Postconditions:</b> the loops are running; the call returns without waiting for them. Any fragments
     * left under the merging directory by a previous run - queued but not yet merged, or retained by a failed
     * merge - have been requeued: each recreated {@link DirQueue} resumes from the minimum id still on disk.
     * Calling this again while the loops run does nothing.</p>
     */
    public void merge() {
        if (!merging) {
            synchronized (this) {
                if (!merging) {
                    merging = true;

                    // Start merge processing for all existing dir queues.
                    try (final Stream<Path> stream = Files.list(mergingDir)) {
                        stream.forEach(path -> {
                            final String docUuid = path.getFileName().toString();
                            getOrCreateDirQueue(docUuid);
                        });
                    } catch (final IOException e) {
                        LOGGER.error(e::getMessage, e);
                    }

                    CompletableFuture.runAsync(() -> {
                        try {
                            unzipPartFiles();
                        } finally {
                            merging = false;
                        }
                    }, executor);
                }
            }
        }
    }

    private void unzipPartFiles() {
        securityContext.asProcessingUser(() -> {
            final long minStoreId = receiveStore.getMinStoreId();
            final long maxStoreId = receiveStore.getMaxStoreId();
            LOGGER.info(() -> LogUtil.message("Min store id = {}, max store id = {}",
                    minStoreId,
                    maxStoreId));

            long storeId = minStoreId;
            if (storeId == -1) {
                LOGGER.info("Store is empty");
                storeId = 0;
            }

            while (!Thread.currentThread().isInterrupted()) {
                // Wait until new data is available.
                final long currentStoreId = storeId;
                final SequentialFile sequentialFile = receiveStore.awaitNext(currentStoreId);
                taskContextFactory.context(mergeTaskName, taskContext -> {
                    taskContext.info(() -> "Decompressing received data: " + currentStoreId);
                    unzipPartFile(sequentialFile);
                }).run();

                // Increment store id.
                storeId++;
            }
        });
    }

    /**
     * Merges every fragment currently in the staging store, synchronously.
     *
     * <p><b>Postconditions:</b> every fragment staged when the call began has been merged or logged as failed;
     * a zip containing a failed fragment is retained in the staging store, so calling this again retries it.</p>
     */
    public void mergeCurrent() {
        long start = receiveStore.getMinStoreId();

        if (start == -1) {
            LOGGER.info("Merge current store is empty");
            start = 0;
        }

        final long end = receiveStore.getMaxStoreId();
        for (long storeId = start; storeId <= end; storeId++) {
            merge(storeId);
        }
    }

    /**
     * Merges one staged fragment zip synchronously, bypassing the queues.
     *
     * <p><b>Postconditions:</b> if every fragment in the zip merged (or belonged to a deleted document) the zip
     * has been deleted; if any fragment failed, the failure has been logged and the zip is retained in the
     * staging store - it is the only remaining copy of the failed fragment, and a later merge pass (or a
     * restart) retries it. Either way the unzip scratch directory has been removed.
     *
     * @param storeId the staging store id to merge.
     */
    public void merge(final long storeId) {
        // Wait until new data is available.
        final SequentialFile sequentialFile = receiveStore.awaitNext(storeId);
        taskContextFactory.context(mergeTaskName, parentContext -> {
            try {
                final Path zipFile = sequentialFile.getZip();
                if (Files.isRegularFile(zipFile)) {
                    final String dirName = StringIdUtil.idToString(unzipSequenceId.incrementAndGet());
                    final Path dir = unzipDir.resolve(dirName);
                    ZipUtil.unzip(zipFile, dir);

                    // We ought to have one or more stores to merge in this part zip file.
                    final AtomicBoolean allMerged = new AtomicBoolean(true);
                    try (final Stream<Path> stream = Files.list(dir)) {
                        stream.forEach(source -> {
                            final String docUuid = source.getFileName().toString();
                            if (!mergeDir(source, docUuid)) {
                                allMerged.set(false);
                            }
                        });
                    }

                    // Delete the unzip dir. When a merge failed the retained copy of the fragment is the source
                    // zip, not this expansion of it, which would not survive a restart anyway.
                    FileUtil.deleteDir(dir);

                    if (allMerged.get()) {
                        // Delete the original zip file.
                        receiveStore.delete(sequentialFile);
                    } else {
                        // Retain the zip so the failed fragment survives a restart and can be merged once the
                        // cause is fixed.
                        LOGGER.warn(() -> LogUtil.message(
                                "{} retaining {} in the staging store because a fragment in it failed to merge",
                                featureName, zipFile));
                    }
                }
            } catch (final IOException | RuntimeException e) {
                LOGGER.error(e::getMessage, e);
            }
        }).run();
    }

    private void unzipPartFile(final SequentialFile sequentialFile) {
        // Create a map to track the max positions of each of the items we add to the processing queue.
        try {
            final Path zipFile = sequentialFile.getZip();
            if (Files.isRegularFile(zipFile)) {
                final String dirName = StringIdUtil.idToString(unzipSequenceId.incrementAndGet());
                final Path dir = unzipDir.resolve(dirName);
                ZipUtil.unzip(zipFile, dir);

                // We ought to have one or more stores to merge in this part zip file.
                final List<Path> dirs = FileUtil.listChildDirs(dir);

                // If the parent process is waiting for merge then create a countdown latch to cover all dirs that need
                // processing.
                final CountDownLatch countDownLatch;
                if (sequentialFile.getCountDownLatch() != null) {
                    countDownLatch = new CountDownLatch(dirs.size());
                } else {
                    countDownLatch = null;
                }

                // Start processing all dirs.
                dirs.forEach(source -> {
                    final String docUuid = source.getFileName().toString();
                    final DirQueue queue = getOrCreateDirQueue(docUuid);
                    queue.add(source, countDownLatch);
                });

                // If the parent process is waiting for merge to complete then wait.
                if (countDownLatch != null) {
                    try {
                        countDownLatch.await();
                    } catch (final InterruptedException e) {
                        LOGGER.debug(e::getMessage, e);
                        Thread.currentThread().interrupt();
                    }
                    sequentialFile.getCountDownLatch().countDown();
                }

                // Delete unzip dir.
                FileUtil.deleteDir(dir);

                // Delete the original zip file.
                receiveStore.delete(sequentialFile);
            }
        } catch (final IOException | RuntimeException e) {
            LOGGER.error(e::getMessage, e);
        }
    }

    private DirQueue getOrCreateDirQueue(final String docUuid) {
        return mergeQueues.computeIfAbsent(docUuid, k -> {
            try {
                final Path uuidDir = mergingDir.resolve(docUuid);
                Files.createDirectories(uuidDir);
                final DirQueue dirQueue = new DirQueue(uuidDir, docUuid);
                // Start processing this queue.
                CompletableFuture.runAsync(() -> mergeStore(dirQueue, docUuid), executor);
                return dirQueue;
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private void mergeStore(final DirQueue dirQueue,
                            final String uuid) {
        securityContext.asProcessingUser(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                // Wait until new data is available.
                try (final Dir dir = dirQueue.next()) {
                    mergeDir(dir.getPath(), uuid);

                    // If synchronisation is happening on merge then let the parent process know we finished merging
                    // this dir.
                    if (dir.getCountDownLatch() != null) {
                        dir.getCountDownLatch().countDown();
                    }
                }
            }
        });
    }

    /**
     * Merges one fragment directory into its document's store.
     *
     * <p><b>Preconditions:</b> neither parameter is null; the directory is named after the document UUID.
     * <b>Postconditions:</b> on success, or when the document no longer exists, the fragment directory has been
     * deleted and true is returned; on failure the directory is deliberately left in place so the merge can be
     * retried once the cause is fixed, the failure has been logged and reported to the failure listener, and
     * false is returned.
     * <b>Null status:</b> neither parameter is nullable.
     *
     * @param path the fragment directory to merge.
     * @param uuid the UUID of the document the fragment belongs to.
     * @return true if the fragment was merged or discarded, false if it failed and was retained.
     */
    private boolean mergeDir(final Path path,
                             final String uuid) {
        try {
            final MergeTarget target = mergeTargetResolver.resolve(uuid);
            final String name = target.getDisplayName();
            taskContextFactory.context("Merging " + featureName + " Data '" + name + "'", taskContext -> {
                taskContext.info(() -> "Merging data into '" + name + "'");
                target.merge(path);
                FileUtil.deleteDir(path);
            }).run();
            return true;
        } catch (final DocumentNotFoundException e) {
            // Expected exception if a doc has been deleted.
            LOGGER.debug(e::getMessage, e);
            FileUtil.deleteDir(path);
            return true;
        } catch (final RuntimeException e) {
            // The fragment is deliberately retained so the merge can be retried once the cause is fixed.
            LOGGER.error(() -> LogUtil.message(
                    "{} failed to merge fragment {} into document {}. " +
                    "The fragment is retained and will be retried after a restart: {}",
                    featureName, LogUtil.path(path), uuid, e.getMessage()), e);
            mergeFailureListener.run();
            return false;
        }
    }
}
