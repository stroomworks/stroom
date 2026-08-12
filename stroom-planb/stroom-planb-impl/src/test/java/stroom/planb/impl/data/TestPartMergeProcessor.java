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

import stroom.bytebuffer.impl6.ByteBufferFactory;
import stroom.bytebuffer.impl6.ByteBufferFactoryImpl;
import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.docref.DocRef;
import stroom.planb.impl.PlanBConfig;
import stroom.planb.impl.PlanBDocCache;
import stroom.planb.impl.PlanBDocStore;
import stroom.planb.impl.dao.StatePaths;
import stroom.planb.impl.dao.state.StateDb;
import stroom.planb.impl.serde.keyprefix.KeyPrefix;
import stroom.planb.shared.PlanBDoc;
import stroom.planb.shared.StateSettings;
import stroom.planb.shared.StateType;
import stroom.query.language.functions.ValString;
import stroom.security.mock.MockSecurityContext;
import stroom.task.api.ExecutorProvider;
import stroom.task.api.SimpleTaskContextFactory;
import stroom.task.shared.ThreadPool;
import stroom.util.io.FileUtil;
import stroom.util.zip.ZipUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves a fragment is durable until it has been merged, across a restart.
 *
 * <p>These tests exist because the constructor used to wipe the merging directory at startup, which silently
 * destroyed the only remaining copy of any fragment whose merge had failed - its source zip was deleted when the
 * fragment was queued. A "restart" here is constructing a second {@link PartMergeProcessor} (or
 * {@link MergeProcessor}) over the same directories, which is faithful: the only startup state the class has is
 * what its constructor does to those directories, and the queues rebuild themselves from disk.</p>
 */
class TestPartMergeProcessor {

    private static final String FEATURE_NAME = "Test";
    private static final String MERGE_TASK_NAME = "Test Merge Processor";
    private static final String DOC_UUID = "test-doc-uuid";
    private static final String PAYLOAD_FILE = "payload.txt";
    private static final String POISON_PAYLOAD = "poison";

    private static final long AWAIT_SECONDS = 30;

    /**
     * The headline restart case, end to end on the asynchronous path: a fragment's merge fails, the process
     * "crashes", and a new processor over the same directories merges it once the cause is fixed. Before the fix
     * the second constructor deleted the fragment, and the source zip was already gone.
     */
    @Test
    void failedFragment_survivesRestart_andMergesOnceTheCauseIsFixed(@TempDir final Path root) throws Exception {
        final Dirs dirs = new Dirs(root);
        final RecordingStore store = new RecordingStore();
        store.failing = true;

        // First run: the fragment is queued, its zip is deleted, and the merge fails.
        final ExecutorService run1 = Executors.newCachedThreadPool();
        try {
            final CountDownLatch failureLatch = new CountDownLatch(1);
            final PartMergeProcessor processor1 = newProcessor(dirs, run1, store, failureLatch::countDown);
            processor1.add(createFragmentZip(dirs.scratch, "alice"), zipOf(dirs.scratch), false);
            processor1.merge();

            assertThat(failureLatch.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the first merge attempt should fail")
                    .isTrue();
            assertThat(store.merged).isEmpty();

            // The queued-and-failed fragment is now the only copy: the async path deletes the zip on queueing.
            pollUntil(() -> zipsUnder(dirs.stagingDir).isEmpty(), "source zip deleted after queueing");
            assertThat(containsPayload(dirs.mergingDir, "alice"))
                    .as("the failed fragment should be retained under the merging dir")
                    .isTrue();
        } finally {
            crash(run1);
        }

        // "Restart": a new processor over the same directories, with the cause of the failure fixed.
        store.failing = false;
        store.mergeLatch = new CountDownLatch(1);
        final ExecutorService run2 = Executors.newCachedThreadPool();
        try {
            final PartMergeProcessor processor2 = newProcessor(dirs, run2, store, () -> {
            });
            assertThat(containsPayload(dirs.mergingDir, "alice"))
                    .as("the constructor must not delete the retained fragment")
                    .isTrue();

            processor2.merge();

            assertThat(store.mergeLatch.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the retained fragment should be requeued and merged after the restart")
                    .isTrue();
            assertThat(store.merged).containsExactly("alice");
            pollUntil(() -> !containsPayload(dirs.mergingDir, "alice"), "merged fragment removed from the queue");
        } finally {
            crash(run2);
        }
    }

    /**
     * A fragment that was queued but not yet merged when the process stopped - the other survivor the startup
     * wipe used to destroy. Seeding the queue directly through {@link DirQueue} is exactly the on-disk state the
     * asynchronous path leaves behind, because {@code queue.add} is how fragments get there.
     */
    @Test
    void queuedFragment_isRequeuedAndMergedAfterRestart(@TempDir final Path root) throws Exception {
        final Dirs dirs = new Dirs(root);
        seedFragment(dirs, DOC_UUID, "bob");

        final RecordingStore store = new RecordingStore();
        store.mergeLatch = new CountDownLatch(1);
        final ExecutorService executor = Executors.newCachedThreadPool();
        try {
            final PartMergeProcessor processor = newProcessor(dirs, executor, store, () -> {
            });
            processor.merge();

            assertThat(store.mergeLatch.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the queued fragment should be merged after the restart")
                    .isTrue();
            assertThat(store.merged).containsExactly("bob");
        } finally {
            crash(executor);
        }
    }

    /**
     * A fragment that can never merge is retained, retried exactly once per process start (never in a loop), and
     * does not block later fragments in the same queue. Retaining it forever is deliberate: it is the only copy
     * of that data, every attempt logs at ERROR and fires the failure listener, and removing it is an operator
     * decision - see docs/graphdb/11-operations.md.
     */
    @Test
    void permanentlyFailingFragment_isRetried_oncePerStart_withoutBlockingOthers(
            @TempDir final Path root) throws Exception {
        final Dirs dirs = new Dirs(root);
        seedFragment(dirs, DOC_UUID, POISON_PAYLOAD);
        seedFragment(dirs, DOC_UUID, "carol");

        final RecordingStore store = new RecordingStore();
        store.mergeLatch = new CountDownLatch(1);

        // First run: the poison fragment fails once; the good fragment behind it still merges.
        final ExecutorService run1 = Executors.newCachedThreadPool();
        final AtomicInteger failures1 = new AtomicInteger();
        try {
            final CountDownLatch failureLatch = new CountDownLatch(1);
            final PartMergeProcessor processor1 = newProcessor(dirs, run1, store, () -> {
                failures1.incrementAndGet();
                failureLatch.countDown();
            });
            processor1.merge();

            assertThat(failureLatch.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(store.mergeLatch.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                    .as("a poison fragment must not block the fragments queued behind it")
                    .isTrue();
            assertThat(store.merged).containsExactly("carol");

            // Settle briefly and confirm there is no in-process retry loop over the poison fragment.
            Thread.sleep(500);
            assertThat(failures1.get()).isEqualTo(1);
            assertThat(containsPayload(dirs.mergingDir, POISON_PAYLOAD)).isTrue();
        } finally {
            crash(run1);
        }

        // "Restart": the poison fragment is retried once more, fails once more, and is still retained.
        final ExecutorService run2 = Executors.newCachedThreadPool();
        final AtomicInteger failures2 = new AtomicInteger();
        try {
            final CountDownLatch failureLatch = new CountDownLatch(1);
            final PartMergeProcessor processor2 = newProcessor(dirs, run2, store, () -> {
                failures2.incrementAndGet();
                failureLatch.countDown();
            });
            processor2.merge();

            assertThat(failureLatch.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(500);
            assertThat(failures2.get()).isEqualTo(1);
            assertThat(containsPayload(dirs.mergingDir, POISON_PAYLOAD))
                    .as("the poison fragment is never discarded silently")
                    .isTrue();
        } finally {
            crash(run2);
        }
    }

    /**
     * The synchronous path has no queue to retain a failed fragment in, so its durable copy is the source zip:
     * a zip containing a failed fragment must not be deleted, and a merge pass after a restart retries it.
     */
    @Test
    void synchronousMerge_retainsTheZipOnFailure_andRetriesAfterRestart(@TempDir final Path root) throws Exception {
        final Dirs dirs = new Dirs(root);
        final RecordingStore store = new RecordingStore();
        store.failing = true;

        final AtomicInteger failures = new AtomicInteger();
        final PartMergeProcessor processor1 =
                newProcessor(dirs, Runnable::run, store, failures::incrementAndGet);
        processor1.add(createFragmentZip(dirs.scratch, "dave"), zipOf(dirs.scratch), false);
        processor1.mergeCurrent();

        assertThat(failures.get()).isEqualTo(1);
        assertThat(store.merged).isEmpty();
        assertThat(zipsUnder(dirs.stagingDir))
                .as("a zip containing a failed fragment must be retained")
                .hasSize(1);
        assertThat(isEmptyDir(dirs.unzipDir))
                .as("the unzip scratch expansion is removed either way")
                .isTrue();

        // "Restart" with the cause fixed: the retained zip is merged by the next pass.
        store.failing = false;
        final PartMergeProcessor processor2 = newProcessor(dirs, Runnable::run, store, () -> {
        });
        processor2.mergeCurrent();

        assertThat(store.merged).containsExactly("dave");
        assertThat(zipsUnder(dirs.stagingDir)).isEmpty();
    }

    /**
     * The constructor's directory contract: the unzip scratch directory is cleared, the merging directory is
     * preserved.
     */
    @Test
    void constructor_clearsUnzipDir_butPreservesMergingDir(@TempDir final Path root) throws Exception {
        final Dirs dirs = new Dirs(root);
        seedFragment(dirs, DOC_UUID, "erin");
        final Path scratchLeftover = dirs.unzipDir.resolve("001").resolve("leftover.txt");
        Files.createDirectories(scratchLeftover.getParent());
        Files.writeString(scratchLeftover, "scratch");

        newProcessor(dirs, Runnable::run, new RecordingStore(), () -> {
        });

        assertThat(isEmptyDir(dirs.unzipDir)).isTrue();
        assertThat(containsPayload(dirs.mergingDir, "erin")).isTrue();
    }

    /**
     * The same restart recovery, over a real Plan B state store and the real {@link MergeProcessor}, because
     * {@link PartMergeProcessor} is shared and the startup behaviour of Plan B's own shards changed too. A real
     * LMDB fragment is left in the merging queue - the state a failed or interrupted merge leaves behind - and a
     * freshly constructed processor must merge it into the shard.
     */
    @Test
    void fragmentForARealPlanBStore_survivesRestart_andMergesIntoTheShard(
            @TempDir final Path root) throws Exception {
        final String mapUuid = "map-uuid";
        final StateSettings settings = new StateSettings.Builder().build();
        final PlanBDoc doc = PlanBDoc
                .builder()
                .uuid(mapUuid)
                .name("map-name")
                .stateType(StateType.STATE)
                .settings(settings)
                .build();

        final StatePaths statePaths = new StatePaths(root.resolve("planb"));
        final ByteBufferFactory byteBufferFactory = new ByteBufferFactoryImpl();
        final ByteBuffers byteBuffers = new ByteBuffers(byteBufferFactory);

        // Write a real fragment, then leave it in the merging queue as a previous run would have.
        final Path fragmentDir = root.resolve("fragment");
        Files.createDirectories(fragmentDir);
        try (final StateDb db = StateDb.create(fragmentDir, byteBuffers, doc, false)) {
            db.write(writer ->
                    db.insert(writer, new State(KeyPrefix.create("TEST_KEY"), ValString.create("test-value"))));
        }
        final DirQueue queue = new DirQueue(statePaths.getMergingDir().resolve(mapUuid), mapUuid);
        queue.add(fragmentDir, null);

        // "Restart": build the real Plan B merge processor over those directories and start merging.
        final PlanBDocStore docStore = Mockito.mock(PlanBDocStore.class);
        Mockito.when(docStore.readDocument(Mockito.any(DocRef.class))).thenReturn(doc);
        final PlanBDocCache docCache = Mockito.mock(PlanBDocCache.class);
        Mockito.when(docCache.get(Mockito.any(String.class))).thenReturn(doc);
        final PlanBConfig planBConfig = new PlanBConfig(root.toAbsolutePath().toString());

        final ExecutorService executor = Executors.newCachedThreadPool();
        try {
            final ExecutorProvider executorProvider = executorProvider(executor);
            final ShardManager shardManager = new ShardManager(
                    byteBuffers,
                    byteBufferFactory,
                    docCache,
                    docStore,
                    null,
                    () -> planBConfig,
                    statePaths,
                    null,
                    new SimpleTaskContextFactory(),
                    executorProvider);
            final MergeProcessor mergeProcessor = new MergeProcessor(
                    statePaths,
                    new MockSecurityContext(),
                    new SimpleTaskContextFactory(),
                    shardManager,
                    executorProvider);

            mergeProcessor.merge();

            // The fragment directory is deleted only after a successful merge.
            pollUntil(() -> !containsRegularFiles(statePaths.getMergingDir()), "fragment merged into the shard");
            try (final StateDb db = StateDb.create(
                    statePaths.getShardDir().resolve(mapUuid),
                    byteBuffers,
                    doc,
                    true)) {
                assertThat(db.count()).isEqualTo(1);
            }
        } finally {
            crash(executor);
        }
    }


    // --------------------------------------------------------------------------------------------------------
    // Fixture
    // --------------------------------------------------------------------------------------------------------


    /**
     * The directories one processor works over. {@code scratch} stands in for the receive directory a zip is
     * handed over from, and must share a filesystem with the others because handover is an atomic move.
     */
    private static final class Dirs {

        private final Path stagingDir;
        private final Path mergingDir;
        private final Path unzipDir;
        private final Path scratch;

        private Dirs(final Path root) throws IOException {
            stagingDir = root.resolve("staging");
            mergingDir = root.resolve("merging");
            unzipDir = root.resolve("unzip");
            scratch = root.resolve("scratch");
            Files.createDirectories(scratch);
        }
    }

    /**
     * A merge target that records each merged fragment's payload. A fragment whose payload is
     * {@link #POISON_PAYLOAD} always fails; setting {@link #failing} fails every fragment, standing in for a
     * recoverable cause such as a full store.
     */
    private static final class RecordingStore {

        private final List<String> merged = Collections.synchronizedList(new ArrayList<>());
        private volatile CountDownLatch mergeLatch = new CountDownLatch(0);
        private volatile boolean failing;

        private MergeTargetResolver resolver() {
            return docUuid -> new MergeTarget() {
                @Override
                public String getDisplayName() {
                    return docUuid;
                }

                @Override
                public void merge(final Path sourceDir) {
                    final String payload = readPayload(sourceDir);
                    if (failing || POISON_PAYLOAD.equals(payload)) {
                        throw new RuntimeException("Refusing to merge '" + payload + "'");
                    }
                    merged.add(payload);
                    mergeLatch.countDown();
                }
            };
        }
    }

    private static PartMergeProcessor newProcessor(final Dirs dirs,
                                                   final Executor executor,
                                                   final RecordingStore store,
                                                   final Runnable mergeFailureListener) {
        return new PartMergeProcessor(
                FEATURE_NAME,
                MERGE_TASK_NAME,
                dirs.stagingDir,
                dirs.mergingDir,
                dirs.unzipDir,
                new MockSecurityContext(),
                new SimpleTaskContextFactory(),
                executor,
                store.resolver(),
                mergeFailureListener);
    }

    private static ExecutorProvider executorProvider(final ExecutorService executorService) {
        return new ExecutorProvider() {
            @Override
            public Executor get() {
                return executorService;
            }

            @Override
            public Executor get(final ThreadPool threadPool) {
                return executorService;
            }
        };
    }

    /**
     * Stops a run's threads the way a shutdown or crash would: the loops block interruptibly, so interrupting
     * them is how they end.
     */
    private static void crash(final ExecutorService executorService) throws InterruptedException {
        executorService.shutdownNow();
        assertThat(executorService.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Builds a fragment zip for {@link #DOC_UUID} whose single file holds {@code payload}, returning its
     * descriptor. The zip itself is the only zip under the scratch dir - see {@link #zipOf}.
     */
    private static FileDescriptor createFragmentZip(final Path scratch, final String payload) throws IOException {
        final Path partDir = Files.createTempDirectory(scratch, "part");
        final Path docDir = partDir.resolve(DOC_UUID);
        Files.createDirectories(docDir);
        Files.writeString(docDir.resolve(PAYLOAD_FILE), payload);
        final Path zipFile = Files.createTempFile(scratch, "part", ".zip");
        ZipUtil.zip(zipFile, partDir);
        FileUtil.deleteDir(partDir);
        return new FileDescriptor(System.currentTimeMillis(), 1, FileHashUtil.hash(zipFile));
    }

    private static Path zipOf(final Path scratch) {
        final List<Path> zips = zipsUnder(scratch);
        assertThat(zips).hasSize(1);
        return zips.getFirst();
    }

    /**
     * Puts a fragment straight into the merge queue for {@code docUuid}, exactly as the asynchronous path's
     * {@code queue.add} would have before the process stopped.
     */
    private static void seedFragment(final Dirs dirs,
                                     final String docUuid,
                                     final String payload) throws IOException {
        final Path fragmentDir = Files.createTempDirectory(dirs.scratch, "fragment");
        Files.writeString(fragmentDir.resolve(PAYLOAD_FILE), payload);
        final DirQueue queue = new DirQueue(dirs.mergingDir.resolve(docUuid), docUuid);
        queue.add(fragmentDir, null);
    }

    private static String readPayload(final Path fragmentDir) {
        try {
            return Files.readString(fragmentDir.resolve(PAYLOAD_FILE));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean containsPayload(final Path root, final String payload) {
        try (final Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(path -> path.getFileName().toString().equals(PAYLOAD_FILE))
                    .anyMatch(path -> {
                        try {
                            return payload.equals(Files.readString(path));
                        } catch (final IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean containsRegularFiles(final Path root) {
        try (final Stream<Path> stream = Files.walk(root)) {
            return stream.anyMatch(Files::isRegularFile);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> zipsUnder(final Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (final Stream<Path> stream = Files.walk(dir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(SequentialFileStore.ZIP_EXTENSION))
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isEmptyDir(final Path dir) throws IOException {
        try (final Stream<Path> stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        }
    }

    private static void pollUntil(final BooleanSupplier condition, final String description) {
        final long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(AWAIT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for: " + description);
            }
            try {
                Thread.sleep(50);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for: " + description, e);
            }
        }
    }
}
