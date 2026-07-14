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

package stroom.pathways.impl;

import stroom.bytebuffer.ByteBufferUtils;
import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.cluster.lock.api.ClusterLockService;
import stroom.docref.DocRef;
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
import stroom.planb.impl.data.ShardManager;
import stroom.planb.impl.db.Count;
import stroom.planb.impl.db.Db;
import stroom.planb.impl.db.LmdbWriter;
import stroom.planb.impl.db.trace.PathwayEventsDb;
import stroom.planb.impl.db.trace.PathwaysDb;
import stroom.planb.impl.db.trace.TraceDb;
import stroom.planb.impl.serde.trace.HexStringUtil;
import stroom.planb.shared.PlanBDocument;
import stroom.util.io.PathCreator;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.util.shared.PageResponse;

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class PathwaysProcessor {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(PathwaysProcessor.class);

    /**
     * Traces whose root-span merge time is older than this threshold are
     * considered complete and eligible for pathways processing.
     */
    private static final long DEFAULT_GRACE_PERIOD_MS = 10_000L; // 10 seconds

    /**
     * Interim safeguard: the maximum number of events {@link #findPathwayEvents} will hold in memory
     * for a single recall. Recall currently buffers matching events before paginating; this cap keeps
     * that bounded regardless of how large a pathway's event history grows. When the cap is hit the
     * result is marked non-exact.
     */
    private static final int MAX_RECALL_EVENTS = 10_000;

    private final PathwaysStore pathwaysStore;
    private final MessageReceiverFactory messageReceiverFactory;
    private final ByteBuffers byteBuffers;
    private final Path dbPath;
    private final Map<String, PathwaysDb> pathwaysDbMap = new ConcurrentHashMap<>();
    private final Map<String, PathwayEventsDb> pathwayEventsDbMap = new ConcurrentHashMap<>();
    private final PathwaySerde pathwaySerde;
    private final ShardManager shardManager;
    private final NodeInfo nodeInfo;
    private final ClusterLockService clusterLockService;
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
                             final PathwayEventsSerde pathwayEventsSerde) {
        this.pathwaysStore = pathwaysStore;
        this.messageReceiverFactory = messageReceiverFactory;
        this.byteBuffers = byteBuffers;
        this.pathwaySerde = pathwaySerde;
        this.shardManager = shardManager;
        this.nodeInfo = nodeInfo;
        this.clusterLockService = clusterLockService;
        this.pathwayEventsSerde = pathwayEventsSerde;

        dbPath = pathCreator.toAppPath("${stroom.home}/pathways");
    }

    /**
     * Scheduled entry point. Computes the grace-period cutoff and delegates to
     * {@link #processCompletedTraces} for each PathwaysDoc assigned to this node.
     * Uses the {@code trace-roots-merge-time} DBI for an O(eligible) range scan,
     * processing only traces whose root span was merged more than
     * {@link #DEFAULT_GRACE_PERIOD_MS} ago.
     */
    public void exec() {
        final long cutoffMs = Instant.now().toEpochMilli() - DEFAULT_GRACE_PERIOD_MS;

        for (final DocRef docRef : NullSafe.list(pathwaysStore.list())) {
            final PathwaysDoc doc = pathwaysStore.readDocument(docRef);
            if (doc != null
                && doc.getTracesDocRef() != null
                && Objects.equals(doc.getProcessingNode(), nodeInfo.getThisNodeName())) {
                try {
                    processCompletedTraces(doc, cutoffMs);
                } catch (final Exception e) {
                    LOGGER.error("Error during trace completion processing for doc {}", doc.getName(), e);
                }
            }
        }
    }

    /**
     * Resolves the root directory for a pathways doc's output. Pathways output lives on the shared
     * filesystem of the linked traces doc so it survives node reassignment and can be picked up by a
     * new processing node; it falls back to node-local storage when no shared path is configured
     * (e.g. dev / single-node setups). Layout:
     * <pre>
     *   &lt;root&gt;/model                 - pathway model + processing-status (one env per doc)
     *   &lt;root&gt;/events/&lt;shardIndex4&gt;  - pathway events (one env per trace shard)
     * </pre>
     */
    private Path pathwaysRoot(final DocRef docRef) {
        Path base = dbPath;
        final PathwaysDoc doc = pathwaysStore.readDocument(docRef);
        if (doc != null && doc.getTracesDocRef() != null) {
            final PlanBDocument tracesDoc = shardManager.getDoc(doc.getTracesDocRef().getName());
            if (tracesDoc != null
                && tracesDoc.getSharedPath() != null
                && !tracesDoc.getSharedPath().isBlank()) {
                base = Path.of(tracesDoc.getSharedPath());
            }
        }
        return base.resolve("pathways").resolve(docRef.getUuid());
    }

    public PathwaysDb getPathwaysDb(final DocRef docRef) {
        return pathwaysDbMap.computeIfAbsent(docRef.getUuid(), k -> {
            try {
                final Path modelPath = pathwaysRoot(docRef).resolve("model");
                Files.createDirectories(modelPath);
                return PathwaysDb.create(modelPath, byteBuffers, false);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * Returns the (cached) per-shard pathway events store. Events are sharded by the trace shard that
     * produced them - i.e. the shard index currently being processed - so no re-hashing is needed.
     */
    public PathwayEventsDb getPathwayEventsDb(final DocRef docRef, final int shardIndex) {
        final int idx = Math.max(shardIndex, 0);
        return pathwayEventsDbMap.computeIfAbsent(docRef.getUuid() + "_" + idx, k -> {
            try {
                final Path eventsPath = pathwaysRoot(docRef)
                        .resolve("events")
                        .resolve(String.format("%04d", idx));
                Files.createDirectories(eventsPath);
                return PathwayEventsDb.create(eventsPath, false);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    public PathwayResultPage findPathways(final FindPathwayCriteria criteria) {
        final PathwaysDb pathwaysDb = getPathwaysDb(criteria.getDataSourceRef());
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
            final PathwaysDb modelDb = getPathwaysDb(docRef);
            final byte[] nameBytes = pathwayName.getBytes(StandardCharsets.UTF_8);
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
        }

        final Long fromMs = criteria.getFromMs();
        final Long toMs = criteria.getToMs();
        final String filter = NullSafe.isNonBlankString(criteria.getFilter())
                ? criteria.getFilter().toLowerCase(Locale.ROOT)
                : null;

        final Path eventsBase = pathwaysRoot(docRef).resolve("events");
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
            if (tracesDoc != null && tracesDoc.getSharedPath() != null && tracesDoc.getShardCount() > 0) {
                return tracesDoc.getShardCount();
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

    /**
     * For a single PathwaysDoc, finds all eligible traces across every shard of
     * the linked TracesDoc and runs pathways processing on each one.
     *
     * <p>Handles both sharded ({@code shardCount > 0}) and unsharded TracesDoc
     * configurations. In the sharded case a per-shard lock is used so that in a
     * multi-node cluster, different nodes can process different shards concurrently
     * without blocking each other.
     */
    private void processCompletedTraces(final PathwaysDoc doc, final long cutoffMs) {
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

        final PathwaysDb pathwaysDb = getPathwaysDb(doc.asDocRef());
        final DocRef infoFeed = doc.getInfoFeed();
        final boolean isSharded = tracesDoc.getSharedPath() != null && tracesDoc.getShardCount() > 0;

        if (isSharded) {
            for (int i = 0; i < tracesDoc.getShardCount(); i++) {
                final int shardIdx = i;
                // Per-shard lock: nodes in a cluster can process different shards in parallel.
                final String lockName = "pathways-write-" + doc.getUuid() + "-" + shardIdx;
                clusterLockService.tryLock(lockName, () ->
                        shardManager.get(doc.getTracesDocRef().getName(), shardIdx, db ->
                                processShardTraces(db, pathwaysDb,
                                        getPathwayEventsDb(doc.asDocRef(), shardIdx),
                                        infoFeed, doc, cutoffMs)));
            }
        } else {
            final String lockName = "pathways-write-" + doc.getUuid();
            clusterLockService.tryLock(lockName, () ->
                    shardManager.get(doc.getTracesDocRef().getName(), db ->
                            processShardTraces(db, pathwaysDb,
                                    getPathwayEventsDb(doc.asDocRef(), 0),
                                    infoFeed, doc, cutoffMs)));
        }
    }

    /**
     * Processes eligible completed traces from a single TracesDoc shard into the
     * PathwaysDb. Must be called while the caller holds the appropriate
     * {@code pathways-write-*} cluster lock for this shard.
     */
    private Void processShardTraces(final Db<?, ?> db,
                                    final PathwaysDb pathwaysDb,
                                    final PathwayEventsDb eventsDb,
                                    final DocRef infoFeed,
                                    final PathwaysDoc doc,
                                    final long cutoffMs) {
        if (!(db instanceof final TraceDb traceDb)) {
            return null;
        }

        // Collect traceIds past the grace period. iterateRootsMergedBefore stops
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
            // The model (+ processing-status replay guard) and the events live in separate
            // environments so each has its own writer. Events are committed first so that, on a
            // crash between the two commits, at worst a trace is reprocessed and its events
            // re-appended (tolerable duplicates) rather than events being lost.
            try (final LmdbWriter modelWriter = pathwaysDb.createWriter();
                    final LmdbWriter eventWriter = eventsDb.createWriter()) {
                messageReceiverFactory.create(eventsDb, eventWriter, infoFeed.getName(), messageReceiver -> {
                    final TraceProcessor traceProcessor =
                            new TraceProcessor(byteBuffers, pathwaySerde);
                    for (final byte[] traceId : eligible) {
                        traceProcessor.processTrace(
                                modelWriter,
                                pathwaysDb,
                                traceId,
                                traceDb::findTrace,
                                doc,
                                messageReceiver);
                    }
                });
                eventWriter.commit();
                modelWriter.commit();
            }
        }
        return null;
    }
}
