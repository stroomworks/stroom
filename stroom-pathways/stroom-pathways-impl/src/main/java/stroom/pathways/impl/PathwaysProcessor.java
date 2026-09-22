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

package stroom.pathways.impl;

import stroom.bytebuffer.ByteBufferUtils;
import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.cluster.lock.api.ClusterLockService;
import stroom.docref.DocRef;
import stroom.docstore.api.DocumentNotFoundException;
import stroom.lmdb.stream.LmdbKeyRange;
import stroom.node.api.NodeInfo;
import stroom.pathways.impl.events.PathwayEvent;
import stroom.pathways.shared.FindPathwayCriteria;
import stroom.pathways.shared.FindPathwayEventCriteria;
import stroom.pathways.shared.PathwayEventResultPage;
import stroom.pathways.shared.PathwayEventRow;
import stroom.pathways.shared.PathwayResultPage;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.PathNodeSequence;
import stroom.pathways.shared.pathway.Pathway;
import stroom.planb.impl.dao.Count;
import stroom.planb.impl.dao.Db;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.trace.PathwayEventsDb;
import stroom.planb.impl.dao.trace.PathwaysDb;
import stroom.planb.impl.dao.trace.TraceDb;
import stroom.planb.impl.data.archive.ArchiveShardLocator;
import stroom.planb.impl.data.archive.ArchiveShardRef;
import stroom.planb.impl.data.shard.ShardManager;
import stroom.planb.impl.fs.SharedFileStore;
import stroom.planb.impl.serde.trace.HexStringUtil;
import stroom.planb.shared.HasHoldingAreaSettings;
import stroom.planb.shared.HoldingAreaSettings;
import stroom.planb.shared.PlanBDocument;
import stroom.util.concurrent.StripedLock;
import stroom.util.io.FileUtil;
import stroom.util.io.PathCreator;
import stroom.util.io.PathSegmentUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.util.shared.PageResponse;
import stroom.util.shared.time.SimpleDuration;
import stroom.util.time.SimpleDurationUtil;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Stream;

@Singleton
public class PathwaysProcessor {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(PathwaysProcessor.class);

    // Interim memory safeguard: the number of events findPathwayEvents will buffer before it stops
    // and reports the result as capped, rather than risk holding an unbounded recall in memory.
    private static final int MAX_RECALL_EVENTS = 10_000;

    private final PathwaysStore pathwaysStore;
    private final MessageReceiverFactory messageReceiverFactory;
    private final ByteBuffers byteBuffers;
    private final Path dbPath;
    private final Map<String, Store> pathwaysDbMap = new ConcurrentHashMap<>();
    private final Map<String, PathwayEventsDb> pathwayEventsDbMap = new ConcurrentHashMap<>();
    // Serialises creation of a given store without holding a lock on the map while we do it.
    private final StripedLock creationLocks = new StripedLock();
    private final PathwaySerde pathwaySerde;
    private final ShardManager shardManager;
    private final NodeInfo nodeInfo;
    private final ClusterLockService clusterLockService;
    private final ArchiveShardLocator archiveShardLocator;
    final PathwayEventsSerde pathwayEventsSerde;

    @Inject
    public PathwaysProcessor(final PathwaysStore pathwaysStore,
                             final MessageReceiverFactory messageReceiverFactory,
                             final PathCreator pathCreator,
                             final ByteBuffers byteBuffers,
                             final PathwaySerde pathwaySerde,
                             final ShardManager shardManager,
                             final NodeInfo nodeInfo,
                             final ClusterLockService clusterLockService,
                             final ArchiveShardLocator archiveShardLocator,
                             final PathwayEventsSerde pathwayEventsSerde) {
        this.pathwaysStore = pathwaysStore;
        this.messageReceiverFactory = messageReceiverFactory;
        this.byteBuffers = byteBuffers;
        this.pathwaySerde = pathwaySerde;
        this.shardManager = shardManager;
        this.nodeInfo = nodeInfo;
        this.clusterLockService = clusterLockService;
        this.archiveShardLocator = archiveShardLocator;
        this.pathwayEventsSerde = pathwayEventsSerde;

        dbPath = pathCreator.toAppPath("${stroom.home}/pathways");
    }

    /**
     * Scheduled entry point. Delegates to {@link #processCompletedTraces} for each PathwaysDoc
     * assigned to this node, taking every trace a bucket holds a merge time for.
     *
     * <p>No settling delay is applied here. A trace only reaches a bucket once publishing has
     * waited out the store's {@code Max Wait For Data}, so the wait for a trace's remaining spans
     * has already happened by the time this can see it, and a bucket is only offered for reading
     * once its version marker is in place.
     */
    public void exec() {
        // Reclaim stores for docs that have been deleted since the last run.
        deleteOldStores();

        // Everything stamped up to now; the ordered scan stops there rather than running to the
        // end of the index. Anything stamped during this pass is taken on the next one.
        final long cutoffMs = Instant.now().toEpochMilli();

        for (final DocRef docRef : NullSafe.list(pathwaysStore.list())) {
            try {
                final PathwaysDoc doc = pathwaysStore.readDocument(docRef);
                if (doc != null
                    && doc.getTracesDocRef() != null
                    && Objects.equals(doc.getProcessingNode(), nodeInfo.getThisNodeName())) {
                    // Held under the store's read lock for the whole of the processing below so it
                    // can't be closed and deleted while we read from it.
                    useStore(docRef, true, pathwaysDb -> {
                        if (pathwaysDb != null) {
                            processCompletedTraces(doc, cutoffMs, pathwaysDb);
                        }
                        return null;
                    });
                }
            } catch (final RuntimeException e) {
                // Keep going: a doc deleted since list() above, or one that fails to process,
                // must not stop us processing the rest until the next run of this job.
                LOGGER.error(() -> LogUtil.message("Error processing pathways for '{}': {}",
                        docRef, e.getMessage()), e);
            }
        }
    }

    /**
     * Opens the store for a doc, creating it if needed. Only for tests that need an open
     * store to exercise the close and delete path.
     */
    void openForTesting(final DocRef docRef) {
        useStore(docRef, true, pathwaysDb -> null);
    }

    private Path getPathwaysPath(final DocRef docRef) {
        return dbPath.resolve("pathways").resolve(PathSegmentUtil.requireSafeSegment(docRef.getUuid()));
    }

    /**
     * Runs the supplied function against the doc's store while holding that store's read lock,
     * so the store cannot be closed and its dir deleted while the function is using it. The
     * function is passed null if there is no store, which is only possible when
     * createIfNotExists is false or the store was deleted while we were waiting for the lock.
     *
     * @param createIfNotExists Whether to create the store if we don't already have one. Pass
     *                          false from read only paths so that querying a doc that has
     *                          never been processed doesn't create a store as a side effect.
     */
    private <R> R useStore(final DocRef docRef,
                           final boolean createIfNotExists,
                           final Function<PathwaysDb, R> function) {
        final Store store = getStore(docRef, createIfNotExists);
        if (store == null) {
            return function.apply(null);
        }

        // Excludes deleteStore() for the duration of the call. Without this the store could be
        // closed, and its files unlinked, while we are inside a read txn on it, which is
        // undefined behaviour in LMDB rather than an error we could report.
        store.lock.readLock().lock();
        try {
            if (store.closed) {
                // Deleted between us taking it from the map and acquiring the lock.
                return function.apply(null);
            }
            return function.apply(store.db);
        } finally {
            store.lock.readLock().unlock();
        }
    }

    /**
     * Runs {@code function} against the pathways model store for a doc, holding the store's read lock
     * for the whole call so the store cannot be closed and its files unlinked mid-operation (see
     * {@link #useStore}). The store is passed as {@code null} when it does not exist (nothing has been
     * processed for the doc yet); callers that only mutate existing data should treat that as a no-op.
     */
    public <R> R withPathwaysDb(final DocRef docRef, final Function<PathwaysDb, R> function) {
        return useStore(docRef, false, function);
    }

    private Store getStore(final DocRef docRef, final boolean createIfNotExists) {
        final String uuid = docRef.getUuid();
        final Store existing = pathwaysDbMap.get(uuid);
        if (existing != null) {
            return existing;
        }

        // Deliberately not ConcurrentHashMap.computeIfAbsent: the mapping function would run
        // while holding the lock for the key's bin, so opening the env would block lookups of
        // unrelated docs that hash to the same bin. Creation is serialised by a striped lock
        // outside the map instead, so concurrent callers for the same doc wait rather than
        // both building one. Same reasoning as ShardManager.getOrCreateShard, see gh-5689.
        final Lock lock = creationLocks.getLockForKey(uuid);
        lock.lock();
        try {
            // Another thread may have created it while we were waiting for the lock.
            final Store created = pathwaysDbMap.get(uuid);
            if (created != null) {
                return created;
            }

            final Path processingPath = getPathwaysPath(docRef);
            if (!createIfNotExists && !Files.isDirectory(processingPath)) {
                return null;
            }

            try {
                Files.createDirectories(processingPath);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            final Store store = new Store(PathwaysDb.create(processingPath, byteBuffers, false));
            pathwaysDbMap.put(uuid, store);
            return store;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the (cached) per-shard pathway events store. Events are sharded by the trace shard that
     * produced them - i.e. the shard index currently being processed - so no re-hashing is needed.
     */
    public PathwayEventsDb getPathwayEventsDb(final DocRef docRef, final int shardIndex) {
        final int idx = Math.max(shardIndex, 0);
        return pathwayEventsDbMap.computeIfAbsent(docRef.getUuid() + "_" + idx, k -> {
            try {
                final Path eventsPath = getPathwaysPath(docRef)
                        .resolve("events")
                        .resolve(String.format("%04d", idx));
                Files.createDirectories(eventsPath);
                return PathwayEventsDb.create(eventsPath, false);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * Closes and deletes the stores of any docs that no longer exist. Nothing else reclaims
     * them, so without this a deleted doc leaves its pathway data on disk, and its env open,
     * for the life of the process.
     */
    private void deleteOldStores() {
        final Path pathwaysPath = dbPath.resolve("pathways");
        if (!Files.isDirectory(pathwaysPath)) {
            return;
        }

        try (final Stream<Path> stream = Files.list(pathwaysPath)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                final String uuid = dir.getFileName().toString();
                // Asked per dir rather than diffed against pathwaysStore.list(), so that a
                // list which is empty for any reason other than there being no docs can't
                // delete every store. Anything but a definite "not found" is left alone.
                boolean docDeleted = false;
                try {
                    docDeleted = pathwaysStore.readDocument(
                            DocRef.builder().type(PathwaysDoc.TYPE).uuid(uuid).build()) == null;
                } catch (final DocumentNotFoundException e) {
                    LOGGER.debug(e::getMessage, e);
                    docDeleted = true;
                } catch (final RuntimeException e) {
                    LOGGER.error(() -> LogUtil.message(
                            "Error checking whether pathways doc '{}' still exists, keeping its store: {}",
                            uuid, e.getMessage()), e);
                }

                if (docDeleted) {
                    deleteStore(uuid, dir);
                }
            });
        } catch (final IOException e) {
            LOGGER.error(() -> LogUtil.message("Error listing pathways dir '{}': {}",
                    FileUtil.getCanonicalPath(pathwaysPath), e.getMessage()), e);
        }
    }

    private void deleteStore(final String uuid, final Path dir) {
        // Held for the whole delete, not just the map removal: it stops a concurrent caller
        // creating a new store on this dir behind us, which we would then delete the files
        // from. Contention is acceptable as only a deleted doc gets here, and only callers
        // whose uuid shares one of the 2048 stripes are affected.
        final Lock lock = creationLocks.getLockForKey(uuid);
        lock.lock();
        try {
            // Single arg remove is safe here because creation takes the same stripe, so no
            // replacement can have been published since we read it.
            final Store store = pathwaysDbMap.remove(uuid);
            boolean closed = true;
            if (store != null) {
                // Waits for in-flight users, so the env is never closed under a live txn.
                // Plain lock() as this must happen even if the job thread is interrupted; it
                // ignores interrupts and restores the flag once acquired.
                store.lock.writeLock().lock();
                try {
                    store.closed = true;
                    // Must close the env before deleting the dir it lives in.
                    store.db.close();
                } catch (final RuntimeException e) {
                    closed = false;
                    LOGGER.error(() -> LogUtil.message("Error closing pathways store '{}': {}",
                            FileUtil.getCanonicalPath(dir), e.getMessage()), e);
                } finally {
                    store.lock.writeLock().unlock();
                }
            }

            if (closed) {
                LOGGER.info(() -> "Deleting pathways store for deleted doc: " + uuid);
                if (!FileUtil.deleteDir(dir)) {
                    LOGGER.error(() -> "Failed to delete all of pathways store " +
                                       FileUtil.getCanonicalPath(dir));
                }
            } else {
                // Leave the data alone rather than unlink it for an env we could not close.
                LOGGER.error(() -> "Keeping pathways store " + FileUtil.getCanonicalPath(dir) +
                                   " as its env could not be closed");
            }
        } catch (final RuntimeException e) {
            LOGGER.error(() -> LogUtil.message("Error deleting pathways store '{}': {}",
                    FileUtil.getCanonicalPath(dir), e.getMessage()), e);
        } finally {
            lock.unlock();
        }
    }


    // --------------------------------------------------------------------------------


    /**
     * A store and the lock that keeps its use apart from its closure.
     */
    private static class Store {

        private final PathwaysDb db;
        private final ReadWriteLock lock = new ReentrantReadWriteLock();
        // Guarded by lock.
        private boolean closed;

        private Store(final PathwaysDb db) {
            this.db = db;
        }
    }

    public PathwayResultPage findPathways(final FindPathwayCriteria criteria) {
        // Read only, so don't create a store for a doc that has never been processed. Held
        // under the store's read lock for the whole scan, which can be long as it walks every
        // entry to count them, so the store can't be closed and deleted underneath it.
        return useStore(criteria.getDataSourceRef(), false, pathwaysDb -> {
            if (pathwaysDb == null) {
                // Nothing has been processed for this doc yet.
                return new PathwayResultPage(Collections.emptyList(), PageResponse
                        .builder()
                        .offset(criteria.getPageRequest().getOffset())
                        .length(0)
                        .total(0L)
                        .exact(true)
                        .build());
            }

            return findPathways(criteria, pathwaysDb);
        });
    }

    private PathwayResultPage findPathways(final FindPathwayCriteria criteria, final PathwaysDb pathwaysDb) {
        final Count count = new Count();
        final List<Pathway> list = new ArrayList<>();
        final PageRequest pageRequest = criteria.getPageRequest();
        pathwaysDb.getPathways().iterate((key, val) -> {
            boolean match = false;
            if (NullSafe.isNonEmptyString(criteria.getFilter())) {
                final String string = ByteBufferUtils.byteBufferToString(key);
                if (string.contains(criteria.getFilter())) {
                    match = true;
                }
            } else {
                match = true;
            }

            if (match) {
                final long pos = count.getAndIncrement();
                if (pos >= criteria.getPageRequest().getOffset() &&
                    pos < criteria.getPageRequest().getOffset() + criteria.getPageRequest().getLength()) {
                    list.add(pathwaySerde.readPathway(val));
                }
            }
        });

        final PageResponse pageResponse = PageResponse
                .builder()
                .offset(pageRequest.getOffset())
                .length(list.size())
                .total(count.get())
                .exact(true)
                .build();
        return new PathwayResultPage(list, pageResponse);
    }

    /**
     * For a single PathwaysDoc, finds all eligible traces across every shard of
     * the linked TracesDoc and runs pathways processing on each one.
     *
     * <p>Takes a per-shard lock so that in a multi-node cluster, different nodes can process
     * different shards concurrently without blocking each other.
     */
    private void processCompletedTraces(final PathwaysDoc doc,
                                        final long cutoffMs,
                                        final PathwaysDb pathwaysDb) {
        if (shardManager.isSnapshotNode()) {
            // Trace completion runs only on merge (shard-owning) nodes.
            return;
        }

        final PlanBDocument tracesDoc = shardManager.getDoc(doc.getTracesDocRef().getName());
        if (tracesDoc == null) {
            LOGGER.warn("No PlanB doc found for traces doc ref '{}' — skipping for pathways doc {}",
                    doc.getTracesDocRef().getName(), doc.getName());
            return;
        }

        final DocRef infoFeed = doc.getInfoFeed();
        final long toMs = System.currentTimeMillis();
        final long fromMs = bucketWindowStartMs(tracesDoc, toMs);
        for (int i = 0; i < SharedFileStore.shardCountOf(tracesDoc); i++) {
            final int shardIdx = i;
            // Per-shard lock: nodes in a cluster can process different shards in parallel.
            final String lockName = "pathways-write-" + doc.getUuid() + "-" + shardIdx;
            clusterLockService.tryLock(lockName, () -> {
                final PathwayEventsDb eventsDb = getPathwayEventsDb(doc.asDocRef(), shardIdx);
                for (final ArchiveShardRef ref :
                        archiveShardLocator.findRelevantShards(tracesDoc, shardIdx, fromMs, toMs)) {
                    shardManager.getArchive(tracesDoc, shardIdx, ref, db ->
                            processShardTraces(db, pathwaysDb, eventsDb, infoFeed, doc, cutoffMs));
                }
            });
        }
    }

    /**
     * How far back to look for buckets holding traces not yet processed.
     *
     * <p>One maximum wait for data, because that is the furthest back a newly published trace can
     * land. Buckets are labelled by the root's start time, and a trace whose real root never arrived
     * waits the full time and is then published under a root synthesized from its earliest span — so
     * its label can be a whole wait old, but no older. Anything earlier was published in a previous
     * window and has a processed marker already.
     */
    private static long bucketWindowStartMs(final PlanBDocument tracesDoc, final long toMs) {
        final SimpleDuration maxWait = HasHoldingAreaSettings.holdingAreaSettings(tracesDoc.getSettings())
                .map(HoldingAreaSettings::getMaxWaitForData)
                .orElse(HoldingAreaSettings.DEFAULT_MAX_WAIT_FOR_DATA);
        return SimpleDurationUtil.minus(Instant.ofEpochMilli(toMs), maxWait).toEpochMilli();
    }

    /**
     * Processes eligible completed traces from a single TracesDoc shard into the
     * PathwaysDb. Must be called while the caller holds the appropriate
     * {@code pathways-write-*} cluster lock for this shard.
     */
    private Void processShardTraces(final Db<?, ?> db,
                                    final PathwaysDb pathwaysDb,
                                    final PathwayEventsDb pathwayEventsDb,
                                    final DocRef infoFeed,
                                    final PathwaysDoc doc,
                                    final long cutoffMs) {
        if (!(db instanceof final TraceDb traceDb)) {
            return null;
        }

        // Collect the traces this bucket holds a merge time for. iterateRootsMergedBefore stops
        // early once the time-ordered key exceeds cutoffMs — O(eligible) scan.
        // TODO: Replace the full scan from the beginning of trace-roots-merge-time with a
        //  persistent cursor (watermark) stored in PathwaysDb. On each tick the scan would
        //  start from the last-processed (mergeTimeMs, traceId) key rather than the
        //  beginning of the index, making the cost O(new eligible) rather than
        //  O(all eligible since the shard was created). The PathwaysDb processingStatus DBI
        //  currently provides idempotency but not position tracking.
        final List<byte[]> eligible = new ArrayList<>();
        traceDb.iterateRootsMergedBefore(cutoffMs, eligible::add);

        if (eligible.isEmpty()) {
            LOGGER.debug("No traces ready for pathways completion for doc {}", doc.getName());
            return null;
        }

        LOGGER.debug("Processing {} completed trace(s) for pathways doc {}",
                eligible.size(), doc.getName());

        if (infoFeed != null && infoFeed.getName() != null) {
            try (final LmdbWriter modelWriter = pathwaysDb.createWriter();
                    final LmdbWriter eventWriter = pathwayEventsDb.createWriter()) {

                messageReceiverFactory.create(pathwayEventsDb, eventWriter, infoFeed.getName(), messageReceiver -> {
                    final TraceProcessor traceProcessor = new TraceProcessor(byteBuffers, pathwaySerde);
                    for (final byte[] traceId : eligible) {
                        traceProcessor.processTrace(
                                modelWriter,
                                pathwaysDb,
                                traceId,
                                traceDb::findTrace,
                                doc,
                                messageReceiver);
                    }
                    eventWriter.commit();
                    modelWriter.commit();
                });
            }
        }
        return null;
    }

    /**
     * Recalls stored pathway events, scattering the read across every trace shard's event store and
     * gathering the results in time order. Node uuids that events only carry as ids are resolved to
     * names using the pathway model. Runs on the processing node that owns (and wrote) these stores.
     */
    public PathwayEventResultPage findPathwayEvents(final FindPathwayEventCriteria criteria) {
        final DocRef docRef = criteria.getDataSourceRef();
        final PathwaysDoc doc = pathwaysStore.readDocument(docRef);
        final int shardCount = resolveShardCount(doc);
        final String pathwayName = criteria.getPathwayName();
        final boolean hasName = NullSafe.isNonBlankString(pathwayName);

        // Build a uuid->name map from the model so events that only carry a node uuid can be labelled.
        final Map<String, String> uuidToName = new HashMap<>();
        if (hasName) {
            final byte[] nameBytes = pathwayName.getBytes(StandardCharsets.UTF_8);
            useStore(docRef, false, modelDb -> {
                if (modelDb == null) {
                    // No model store yet (deleted/never-created doc) → nothing to label.
                    return null;
                }
                byteBuffers.useBytes(nameBytes, keyBuf -> {
                    final Pathway pathway = modelDb.getPathways()
                            .get(keyBuf, vb -> vb == null
                                    ? null
                                    : pathwaySerde.readPathway(vb));
                    collectNodeNames(pathway == null
                            ? null
                            : pathway.getRoot(), uuidToName);
                    return null;
                });
                return null;
            });
        }

        final Long fromMs = criteria.getFromMs();
        final Long toMs = criteria.getToMs();
        final String filter = NullSafe.isNonBlankString(criteria.getFilter())
                ? criteria.getFilter().toLowerCase(Locale.ROOT)
                : null;

        final Path eventsBase = getPathwaysPath(docRef).resolve("events");
        final List<PathwayEventRow> rows = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) {
            // Recall is read-only: skip (rather than create) event stores for shards that were
            // never written, so we don't materialise empty shard dirs on the shared filesystem.
            if (!Files.exists(eventsBase.resolve(String.format("%04d", i)))) {
                continue;
            }
            final PathwayEventsDb eventsDb = getPathwayEventsDb(docRef, i);
            final LmdbKeyRange keyRange;
            if (hasName) {
                final byte[] nameBytes = pathwayName.getBytes(StandardCharsets.UTF_8);
                final ByteBuffer prefix = ByteBuffer.allocateDirect(nameBytes.length + 1);
                prefix.put(nameBytes).put((byte) 0).flip();
                keyRange = LmdbKeyRange.builder().prefix(prefix).build();
            } else {
                keyRange = LmdbKeyRange.all();
            }

            eventsDb.getPathwayEvents().iterate(keyRange, (key, value) -> {
                if (value == null || rows.size() >= MAX_RECALL_EVENTS) {
                    // Interim memory safeguard - stop buffering once the cap is reached.
                    return;
                }
                final byte[] keyArr = new byte[key.remaining()];
                key.duplicate().get(keyArr);
                final int zero = indexOfZero(keyArr);
                if (zero < 0) {
                    return;
                }
                final String name = new String(keyArr, 0, zero, StandardCharsets.UTF_8);
                // Key tail after the name+separator is: timestamp(8) seq(8) traceId(remaining).
                final int traceStart = zero + 1 + 8 + 8;
                final String traceHex = traceStart < keyArr.length
                        ? HexStringUtil.encode(Arrays.copyOfRange(keyArr, traceStart, keyArr.length))
                        : "";

                final PathwayEvent event = pathwayEventsSerde.readPathwayEvent(value, uuidToName);
                final Long timeMs = event.getTimestamp() != null
                        ? event.getTimestamp().toEpochMillis()
                        : null;
                if (timeMs != null) {
                    if (fromMs != null && timeMs < fromMs) {
                        return;
                    }
                    if (toMs != null && timeMs >= toMs) {
                        return;
                    }
                }

                final String description = event.getDescription();
                if (filter != null
                    && !containsIgnoreCase(description, filter)
                    && !containsIgnoreCase(event.getNodeName(), filter)
                    && !containsIgnoreCase(name, filter)) {
                    return;
                }

                rows.add(new PathwayEventRow(
                        name,
                        event.getCategory(),
                        event.getEventType() != null
                                ? event.getEventType().name()
                                : null,
                        event.getNodeName(),
                        timeMs,
                        traceHex,
                        description));
            });
        }

        rows.sort(Comparator.comparing(r -> r.getTimeMs() == null
                ? Long.MIN_VALUE
                : r.getTimeMs()));

        final PageRequest pageRequest = criteria.getPageRequest();
        final int total = rows.size();
        final long offset = pageRequest != null && pageRequest.getOffset() != null
                ? pageRequest.getOffset()
                : 0L;
        final int length = pageRequest != null && pageRequest.getLength() != null
                ? pageRequest.getLength()
                : total;
        final int from = (int) Math.min(offset, total);
        final int to = (int) Math.min(offset + (long) length, total);
        final List<PathwayEventRow> page = new ArrayList<>(rows.subList(from, to));

        // If we hit the in-memory cap the total is a lower bound, not exact.
        final boolean capped = total >= MAX_RECALL_EVENTS;
        final PageResponse pageResponse = PageResponse
                .builder()
                .offset(offset)
                .length(page.size())
                .total((long) total)
                .exact(!capped)
                .build();
        return new PathwayEventResultPage(page, pageResponse);
    }

    /**
     * Deletes all stored events for a pathway across every trace shard's event store. Called when a
     * pathway is deleted so its events do not linger.
     */
    public void deletePathwayEvents(final DocRef docRef, final String pathwayName) {
        if (!NullSafe.isNonBlankString(pathwayName)) {
            return;
        }
        final PathwaysDoc doc = pathwaysStore.readDocument(docRef);
        final int shardCount = resolveShardCount(doc);
        final byte[] nameBytes = pathwayName.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < shardCount; i++) {
            final PathwayEventsDb eventsDb = getPathwayEventsDb(docRef, i);
            try (final LmdbWriter writer = eventsDb.createWriter()) {
                final ByteBuffer prefix = ByteBuffer.allocateDirect(nameBytes.length + 1);
                prefix.put(nameBytes).put((byte) 0).flip();
                final List<ByteBuffer> keysToDelete = new ArrayList<>();
                eventsDb.getPathwayEvents().iterate(writer.getWriteTxn(),
                        LmdbKeyRange.builder().prefix(prefix).build(), (key, value) -> {
                            final ByteBuffer copy = ByteBuffer.allocateDirect(key.remaining());
                            copy.put(key.duplicate()).flip();
                            keysToDelete.add(copy);
                        });
                for (final ByteBuffer key : keysToDelete) {
                    eventsDb.getPathwayEvents().delete(writer, key);
                }
                writer.commit();
            }
        }
    }

    private int resolveShardCount(final PathwaysDoc doc) {
        if (doc != null && doc.getTracesDocRef() != null) {
            final PlanBDocument tracesDoc = shardManager.getDoc(doc.getTracesDocRef().getName());
            if (tracesDoc != null) {
                final int shardCount = SharedFileStore.shardCountOf(tracesDoc);
                if (shardCount > 0) {
                    return shardCount;
                }
            }
        }
        return 1;
    }

    private static void collectNodeNames(final PathNode node, final Map<String, String> map) {
        if (node == null) {
            return;
        }
        if (node.getUuid() != null) {
            map.put(node.getUuid(), node.getName());
        }
        for (final PathNodeSequence sequence : NullSafe.list(node.getTargets())) {
            for (final PathNode child : NullSafe.list(sequence.getNodes())) {
                collectNodeNames(child, map);
            }
        }
    }

    private static int indexOfZero(final byte[] arr) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    private static boolean containsIgnoreCase(final String value, final String lowerNeedle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

}
