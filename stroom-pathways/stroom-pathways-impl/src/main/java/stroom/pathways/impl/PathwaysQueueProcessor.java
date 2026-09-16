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

import stroom.bytebuffer.impl6.ByteBufferFactory;
import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.cluster.lock.api.ClusterLockService;
import stroom.docref.DocRef;
import stroom.pathways.shared.PathwaysDoc;
import stroom.planb.impl.PlanBConstants;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.trace.PathwaysDb;
import stroom.planb.impl.dao.trace.QueueItem;
import stroom.planb.impl.dao.trace.QueueItemReader;
import stroom.planb.impl.serde.trace.HexStringUtil;
import stroom.planb.shared.SharedFileStoreSettings;
import stroom.security.api.SecurityContext;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.metrics.Metrics;
import stroom.util.shared.NullSafe;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Applies the traces a trace store has handed over, one shard at a time.
 *
 * <p>Every node runs this. A shard is claimed by taking its cluster lock, so only one node works a
 * given shard at a time and which node that is does not matter — the queue and the state it builds
 * both live on the shared filesystem.
 */
@Singleton
public class PathwaysQueueProcessor {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(PathwaysQueueProcessor.class);

    /** Where a shard's unreadable items are set aside, under its own queue folder. */
    static final String QUARANTINE_DIR_NAME = "quarantine";

    /**
     * How much one lock hold takes on.
     *
     * <p>Bounded both ways because either bound alone can be wrong: a few enormous items outlast a
     * count bound, and a flood of tiny ones outlasts nothing. Kept well under the minute at which
     * {@code DbClusterLock} warns about a long hold, which also narrows the window in which a lease
     * lost mid-batch can matter.
     *
     * <p>Both numbers are guesses until a run against real traffic replaces them.
     */
    static final int MAX_ITEMS_PER_HOLD = 50;
    static final Duration MAX_TIME_PER_HOLD = Duration.ofSeconds(30);

    /**
     * Shards worked at once by this node.
     *
     * <p>Shards are independent — one lock each, one queue folder each — so working them one after
     * another would make a pass take the sum of their holds rather than the longest of them. At the
     * bound above, a node with eight busy shards would need four minutes for a pass the scheduler
     * starts every minute.
     *
     * <p>Deliberately not the Plan B merge thread count. The two compete for the same shared
     * filesystem but are sized for different work, and silently spending a budget meant for merging
     * would make each harder to reason about.
     */
    static final int SHARD_THREADS = 4;

    private final PathwaysStore pathwaysStore;
    private final PathwaysShardStore shardStore;
    private final MessageReceiverFactory messageReceiverFactory;
    private final PathwaySerde pathwaySerde;
    private final ClusterLockService clusterLockService;
    private final SecurityContext securityContext;
    private final ByteBuffers byteBuffers;
    private final ByteBufferFactory byteBufferFactory;

    private final ExecutorService shardExecutor;
    private final Meter tracesApplied;
    private final Counter itemsQuarantined;

    /**
     * What the last pass saw, so the gauges can report it without listing the shared filesystem
     * themselves. At most one cycle stale, which is fine for a backlog, and it keeps a metrics scrape
     * off the shared mount and out of the permission checks that reading a document needs.
     */
    private volatile long lastQueueDepth;
    private volatile long lastOldestItemAgeMs;

    @Inject
    public PathwaysQueueProcessor(final PathwaysStore pathwaysStore,
                                  final PathwaysShardStore shardStore,
                                  final MessageReceiverFactory messageReceiverFactory,
                                  final PathwaySerde pathwaySerde,
                                  final ClusterLockService clusterLockService,
                                  final SecurityContext securityContext,
                                  final ByteBuffers byteBuffers,
                                  final ByteBufferFactory byteBufferFactory,
                                  final Metrics metrics) {
        this.pathwaysStore = pathwaysStore;
        this.shardStore = shardStore;
        this.messageReceiverFactory = messageReceiverFactory;
        this.pathwaySerde = pathwaySerde;
        this.clusterLockService = clusterLockService;
        this.securityContext = securityContext;
        this.byteBuffers = byteBuffers;
        this.byteBufferFactory = byteBufferFactory;
        this.shardExecutor = createShardExecutor();

        this.tracesApplied = metrics.registrationBuilder(getClass())
                .addNamePart("tracesApplied")
                .meter()
                .createAndRegister();
        this.itemsQuarantined = metrics.registrationBuilder(getClass())
                .addNamePart("itemsQuarantined")
                .counter()
                .createAndRegister();
        metrics.registrationBuilder(getClass())
                .addNamePart("queueDepth")
                .addNamePart(Metrics.COUNT)
                .gauge(() -> lastQueueDepth)
                .register();
        metrics.registrationBuilder(getClass())
                .addNamePart("oldestItem")
                .addNamePart(Metrics.AGE_MS)
                .gauge(() -> lastOldestItemAgeMs)
                .register();
    }

    public void exec() {
        securityContext.asProcessingUser(() -> {
            long depth = 0;
            long oldestAgeMs = 0;
            for (final DocRef docRef : NullSafe.list(pathwaysStore.list())) {
                try {
                    final Backlog backlog = processDocument(docRef);
                    depth += backlog.items();
                    oldestAgeMs = Math.max(oldestAgeMs, backlog.oldestAgeMs());
                } catch (final RuntimeException e) {
                    // One unreadable or mid-deletion document must not stop the others being drained.
                    LOGGER.error(() -> LogUtil.message("Error draining pathways queue for '{}': {}",
                            docRef, e.getMessage()), e);
                }
            }
            lastQueueDepth = depth;
            lastOldestItemAgeMs = oldestAgeMs;
        });
    }

    private Backlog processDocument(final DocRef docRef) {
        final PathwaysDoc doc = pathwaysStore.readDocument(docRef);
        final SharedFileStoreSettings settings = doc == null
                ? null
                : doc.getSharedFileStore();
        if (settings == null || NullSafe.isBlankString(settings.getSharedPath())) {
            // Nothing has been configured, so nothing was ever written for it.
            return Backlog.NONE;
        }
        final Path queueRoot = Path.of(settings.getSharedPath())
                .resolve(TraceMergeCompletionStrategy.QUEUE_DIR_NAME)
                .resolve(doc.getUuid());

        long items = 0;
        long oldestAgeMs = 0;
        final Instant now = Instant.now();
        final List<CompletableFuture<Void>> inFlight = new ArrayList<>();
        for (final int shard : shardsInRandomOrder(Math.max(1, settings.getShardCount()))) {
            if (Thread.currentThread().isInterrupted()) {
                LOGGER.debug("Interrupted, starting no more shards this pass");
                break;
            }
            final Path shardDir = queueRoot.resolve(PlanBConstants.formatShardIndex(shard));
            final List<Path> waiting = itemsIn(shardDir);
            if (waiting.isEmpty()) {
                // Look before locking. A contended tryLock costs a warning and a query against
                // cluster_lock to report who holds it, and most shards have nothing most cycles, so
                // taking the lock only where there is work is most of the difference between quiet
                // logs and sixty a minute.
                continue;
            }
            items += waiting.size();
            oldestAgeMs = Math.max(oldestAgeMs,
                    now.toEpochMilli() - QueueItem.orderKeyOf(waiting.getFirst()));

            final String lockName = lockName(doc.getUuid(), shard);
            inFlight.add(CompletableFuture
                    // Each shard re-establishes the processing user: the identity is held per thread,
                    // so it does not travel to a pool thread on its own.
                    .runAsync(() -> securityContext.asProcessingUser(
                            () -> clusterLockService.tryLock(
                                    lockName, () -> drain(doc, shard, shardDir))),
                            shardExecutor)
                    .exceptionally(t -> {
                        LOGGER.error(() -> LogUtil.message("Error draining {}: {}",
                                shardDir, t.getMessage()), t);
                        return null;
                    }));
        }
        // Wait, so that a pass does not report a backlog it has not finished working and does not
        // overlap the next one.
        CompletableFuture.allOf(inFlight.toArray(new CompletableFuture[0])).join();

        return new Backlog(items, oldestAgeMs);
    }

    static String lockName(final String pathwaysDocUuid, final int shard) {
        return lockPrefix(pathwaysDocUuid) + PlanBConstants.formatShardIndex(shard);
    }

    /**
     * Every shard lock this document will ever take. Lock rows are created on demand and removed only
     * by name, so deleting the document has to delete these too or they accumulate in
     * {@code cluster_lock} for good.
     */
    public static String lockPrefix(final String pathwaysDocUuid) {
        return "pathways-" + pathwaysDocUuid + "-";
    }

    // Applies what this hold has time for into a local copy of the shard's model, pushes the model
    // back, then deletes exactly what went into it.
    private void drain(final PathwaysDoc doc, final int shardIndex, final Path shardDir) {
        final List<Path> applied = new ArrayList<>();
        try {
            shardStore.withShard(doc, shardIndex,
                    localDir -> applyBatch(doc, shardDir, localDir, applied));
        } catch (final IOException e) {
            // The model could not be taken down or put back, so nothing here was committed. Leaving
            // the items queued is the whole point: they are applied again by whoever gets the shard
            // next, rather than being dropped on the floor here.
            LOGGER.error(() -> LogUtil.message("Could not work shard {} of {}, leaving {} item(s) queued: {}",
                    shardIndex, doc.getName(), applied.size(), e.getMessage()), e);
            return;
        }
        deleteAll(applied);
    }

    // Fills `applied` with the items that went in, so the caller can delete exactly those once the
    // model is safely back on the shared store. Returns whether the local model changed, which is what
    // decides whether it is worth pushing.
    private boolean applyBatch(final PathwaysDoc doc,
                               final Path shardDir,
                               final Path localDir,
                               final List<Path> applied) {
        final Instant deadline = Instant.now().plus(MAX_TIME_PER_HOLD);
        final Counts counts = new Counts();

        try (final PathwaysDb pathwaysDb = PathwaysDb.create(localDir, byteBuffers, false)) {
            withMessageReceiver(doc, messageReceiver -> {
                try (final LmdbWriter writer = pathwaysDb.createWriter()) {
                    final TraceProcessor traceProcessor = new TraceProcessor(byteBuffers, pathwaySerde);
                    for (final Path item : itemsIn(shardDir)) {
                        if (Thread.currentThread().isInterrupted()) {
                            // The lock's heartbeat interrupts us when it cannot renew. Carrying on
                            // regardless is how two nodes end up working one shard, so stop and leave
                            // the rest queued.
                            LOGGER.warn(() -> "Interrupted part way through " + shardDir
                                              + ", leaving the rest queued");
                            break;
                        }
                        if (applied.size() >= MAX_ITEMS_PER_HOLD || Instant.now().isAfter(deadline)) {
                            break;
                        }
                        try {
                            apply(item, pathwaysDb, writer, traceProcessor, doc, messageReceiver, counts);
                            applied.add(item);
                        } catch (final Exception e) {
                            quarantine(item, e);
                        }
                    }
                    writer.commit();
                }
            });
        }

        tracesApplied.mark(counts.traces);

        final int itemCount = applied.size();
        final long traceCount = counts.traces;
        LOGGER.debug(() -> LogUtil.message("Applied {} item(s) holding {} trace(s) from {}",
                itemCount, traceCount, shardDir));

        // Only worth copying the model back up if a trace actually landed in it. A batch of traces
        // this shard had already seen changes nothing, and pushing then would move the whole file to
        // say so.
        return counts.changed;
    }

    private void apply(final Path item,
                       final PathwaysDb pathwaysDb,
                       final LmdbWriter writer,
                       final TraceProcessor traceProcessor,
                       final PathwaysDoc doc,
                       final MessageReceiver messageReceiver,
                       final Counts counts) {
        try (final QueueItemReader reader = new QueueItemReader(item, byteBuffers, byteBufferFactory)) {
            reader.forEachTrace((root, trace) -> {
                counts.traces++;
                // The trace is already in hand, so what the old path fetched from an archive bucket is
                // supplied directly. One applying path, whichever side the trace came from.
                counts.changed |= traceProcessor.processTrace(
                        writer,
                        pathwaysDb,
                        HexStringUtil.decode(root.getTraceId()),
                        traceId -> Optional.of(trace),
                        doc,
                        messageReceiver);
            });
        }
    }

    // Findings go to the document's info feed, as one stream per shard per hold. A document with no
    // feed still learns; it just has nowhere to report what it found, which beats not learning at all.
    private void withMessageReceiver(final PathwaysDoc doc, final Consumer<MessageReceiver> work) {
        final DocRef infoFeed = doc.getInfoFeed();
        if (infoFeed == null || infoFeed.getName() == null) {
            work.accept((severity, message) -> {
            });
        } else {
            messageReceiverFactory.create(infoFeed.getName(), work::accept);
        }
    }


    // --------------------------------------------------------------------------------


    /** Mutable across the lambdas that walk a batch. */
    private static final class Counts {

        private long traces;
        private boolean changed;
    }

    // Sets an unreadable item aside rather than deleting or retrying it: retrying stalls the shard
    // forever on the same item, and deleting destroys the only copy of whatever went wrong.
    private void quarantine(final Path item, final Exception cause) {
        itemsQuarantined.inc();
        try {
            final Path quarantineDir = item.getParent().resolve(QUARANTINE_DIR_NAME);
            Files.createDirectories(quarantineDir);
            Files.move(item, quarantineDir.resolve(item.getFileName().toString()),
                    StandardCopyOption.ATOMIC_MOVE);
            LOGGER.error(() -> LogUtil.message("Could not apply queue item {}, moved to {}: {}",
                    item.getFileName(), quarantineDir, cause.getMessage()), cause);
        } catch (final IOException e) {
            LOGGER.error(() -> LogUtil.message(
                    "Could not apply queue item {} and could not set it aside either: {}",
                    item, e.getMessage()), e);
        }
    }

    private void deleteAll(final List<Path> items) {
        for (final Path item : items) {
            try (final Stream<Path> paths = Files.walk(item)) {
                paths.sorted(Collections.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (final IOException e) {
                        LOGGER.warn(() -> "Could not delete " + path + ": " + e.getMessage());
                    }
                });
            } catch (final IOException e) {
                // A leftover item is applied again next cycle, which the design already tolerates.
                LOGGER.warn(() -> "Could not delete applied queue item " + item + ": " + e.getMessage());
            }
        }
    }

    // Oldest first, so a mutation is attributed to the trace that really caused it.
    private List<Path> itemsIn(final Path shardDir) {
        if (!Files.isDirectory(shardDir)) {
            return List.of();
        }
        try (final Stream<Path> stream = Files.list(shardDir)) {
            return stream.filter(QueueItem::isItem).sorted(QueueItem.BY_ORDER_KEY).toList();
        } catch (final IOException e) {
            LOGGER.error(() -> "Could not list queue items in " + shardDir + ": " + e.getMessage(), e);
            return List.of();
        }
    }

    private static ExecutorService createShardExecutor() {
        final AtomicInteger threadNo = new AtomicInteger();
        return new ThreadPoolExecutor(
                SHARD_THREADS,
                SHARD_THREADS,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    final Thread thread = new Thread(
                            runnable, "Pathways Queue Drain #" + threadNo.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setPriority(3);
                    return thread;
                });
    }

    // Randomised so that nodes starting a cycle together do not all queue up on the same shard.
    private static List<Integer> shardsInRandomOrder(final int shardCount) {
        final List<Integer> shards = new ArrayList<>(shardCount);
        for (int i = 0; i < shardCount; i++) {
            shards.add(i);
        }
        Collections.shuffle(shards);
        return shards;
    }


    // --------------------------------------------------------------------------------


    /** What one document had waiting at the start of a pass. */
    private record Backlog(long items, long oldestAgeMs) {

        private static final Backlog NONE = new Backlog(0, 0);
    }
}
