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

package stroom.graphdb.impl;

import stroom.lmdb.serde.UnsignedBytes;
import stroom.lmdb.serde.UnsignedBytesInstances;
import stroom.lmdb.stream.LmdbEntry;
import stroom.lmdb.stream.LmdbIterable;
import stroom.lmdb.stream.LmdbKeyRange;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.PlanBEnv;
import stroom.planb.impl.dao.UidLookupRecorder;
import stroom.planb.impl.serde.time.TimeSerde;
import stroom.query.language.functions.Val;

import org.jspecify.annotations.Nullable;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The out-edge adjacency store (design doc &sect;5.1; implementation plan Task PoC.4). Key layout frozen by
 * P0.1: {@code [srcUid:6][edgeTypeUid:4][dstUid:6][validFrom:6]} (22 bytes) - deliberately {@code dst} before
 * {@code validFrom} (refining the design doc's conceptual sketch) so each individual edge's version history is a
 * contiguous run, making an as-of expand a single forward pass per source (see {@link #expandOut}). The in-edge
 * mirror ({@link GraphInEdgeDb}, for {@code Direction.IN}/{@code Direction.BOTH} traversal) is a structurally
 * identical, separately-written companion store (Task P1.1) - callers writing an edge must write both.
 *
 * <p>Value encoding: a 1-byte tag ({@link #TOMBSTONE}/{@link #PRESENT}) then, if present, the edge's named
 * properties via {@link GraphPropsCodec} (as {@link GraphNodeDb} encodes its own).</p>
 */
public final class GraphAdjacencyDb {

    private static final UnsignedBytes NODE_UID_BYTES = UnsignedBytesInstances.ofLength(GraphStores.NODE_UID_WIDTH);
    private static final UnsignedBytes TYPE_UID_BYTES = UnsignedBytesInstances.ofLength(GraphStores.TYPE_UID_WIDTH);
    private static final int SRC_PREFIX_WIDTH = GraphStores.NODE_UID_WIDTH + GraphStores.TYPE_UID_WIDTH;

    private static final byte TOMBSTONE = 0;
    private static final byte PRESENT = 1;

    private final Dbi<ByteBuffer> dbi;
    /** Per-document, because Temporal Precision decides how many bytes of each key the time occupies. */
    private final TimeSerde timeSerde;
    private final int keyWidth;

    GraphAdjacencyDb(final PlanBEnv env, final TimeSerde timeSerde) {
        Objects.requireNonNull(env, "env");
        this.timeSerde = Objects.requireNonNull(timeSerde, "timeSerde");
        this.keyWidth = SRC_PREFIX_WIDTH + GraphStores.NODE_UID_WIDTH + timeSerde.getSize();
        this.dbi = env.openDbi("graph-out-edge", DbiFlags.MDB_CREATE);
    }

    /**
     * Writes a new version of the edge {@code (srcUid, edgeTypeUid, dstUid)}, valid from {@code validFrom}.
     *
     * <p><b>Preconditions:</b> no parameter is null. <b>Postconditions:</b> a subsequent {@link #expandOut} as-of
     * any instant &ge; {@code validFrom} (and before this edge's next version, if any) includes {@code dstUid}.
     */
    public void insert(final LmdbWriter writer, final long srcUid, final long edgeTypeUid, final long dstUid,
                       final Instant validFrom, final Map<String, Val> properties) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(properties, "properties");

        final byte[] propsBlob = GraphPropsCodec.encode(properties);
        final ByteBuffer key = buildKey(srcUid, edgeTypeUid, dstUid, validFrom);
        final ByteBuffer value = ByteBuffer.allocateDirect(1 + propsBlob.length);
        value.put(PRESENT);
        value.put(propsBlob);
        value.flip();
        dbi.put(writer.getWriteTxn(), key, value);
    }

    /**
     * Writes a tombstone version of the edge {@code (srcUid, edgeTypeUid, dstUid)} at {@code validFrom} - the
     * edge is absent from {@code validFrom} onward, until (if ever) a later {@link #insert}.
     */
    public void delete(final LmdbWriter writer, final long srcUid, final long edgeTypeUid, final long dstUid,
                       final Instant validFrom) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(validFrom, "validFrom");

        final ByteBuffer key = buildKey(srcUid, edgeTypeUid, dstUid, validFrom);
        final ByteBuffer value = ByteBuffer.allocateDirect(1);
        value.put(TOMBSTONE);
        value.flip();
        dbi.put(writer.getWriteTxn(), key, value);
    }

    /**
     * The as-of 1-hop expand (design doc &sect;5.5 {@code expand} operator): a single forward pass over the
     * {@code [srcUid][edgeTypeUid]} prefix, grouping by {@code dstUid} (each group is contiguous - see this
     * class's Javadoc) and emitting, for each destination, its most recent version at or before {@code asOf} -
     * unless that version is a tombstone, in which case the destination is skipped.
     *
     * <p><b>Postconditions:</b> {@code consumer} is invoked at most once per distinct {@code dstUid} reachable
     * via {@code edgeTypeUid} from {@code srcUid}, in ascending {@code dstUid} order.
     */
    public void expandOut(final Txn<ByteBuffer> readTxn, final long srcUid, final long edgeTypeUid,
                          final Instant asOf, final Consumer<Neighbour> consumer) {
        Objects.requireNonNull(readTxn, "readTxn");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(consumer, "consumer");

        final ByteBuffer prefix = ByteBuffer.allocateDirect(SRC_PREFIX_WIDTH);
        NODE_UID_BYTES.put(prefix, srcUid);
        TYPE_UID_BYTES.put(prefix, edgeTypeUid);
        prefix.flip();

        final LmdbKeyRange keyRange = LmdbKeyRange.builder().prefix(prefix).build();
        Long currentDst = null;
        Neighbour floorForCurrentDst = null;
        try (LmdbIterable iterable = LmdbIterable.create(readTxn, dbi, keyRange)) {
            for (final LmdbEntry entry : iterable) {
                final ByteBuffer key = entry.getKey().duplicate();
                key.position(key.position() + SRC_PREFIX_WIDTH);
                final long dstUid = NODE_UID_BYTES.get(key);
                final Instant validFrom = timeSerde.read(key);

                if (!Objects.equals(currentDst, dstUid)) {
                    emit(consumer, floorForCurrentDst);
                    currentDst = dstUid;
                    floorForCurrentDst = null;
                }
                if (!validFrom.isAfter(asOf)) {
                    floorForCurrentDst = decodeNeighbour(dstUid, entry.getVal());
                }
            }
        }
        emit(consumer, floorForCurrentDst);
    }

    private static void emit(final Consumer<Neighbour> consumer, final Neighbour neighbour) {
        if (neighbour != null) {
            consumer.accept(neighbour);
        }
    }

    /**
     * The window-intersection 1-hop expand (Task P4.1; design doc &sect;5.4 / P0.3 outcome - see
     * {@link GraphNodeDb#getNodeWindow} for the full intersection-rule writeup this mirrors): a single forward
     * pass over the {@code [srcUid][edgeTypeUid]} prefix, grouped by {@code dstUid} exactly as {@link #expandOut}
     * does, but each group is buffered first (a window scan cannot early-exit; it needs every entry's own
     * successor to compute that entry's half-open interval) and resolved to the group's latest version whose
     * interval intersects {@code [from, to]} - the window analogue of {@code expandOut}'s "most recent version
     * at-or-before {@code asOf}" rule.
     *
     * <p><b>Postconditions:</b> {@code consumer} is invoked at most once per distinct {@code dstUid} that has
     * some version intersecting {@code [from, to]} and whose latest such version is present (not a tombstone),
     * in ascending {@code dstUid} order.
     */
    public void expandOutWindow(final Txn<ByteBuffer> readTxn, final long srcUid, final long edgeTypeUid,
                                final Instant from, final Instant to, final Consumer<Neighbour> consumer) {
        Objects.requireNonNull(readTxn, "readTxn");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(consumer, "consumer");

        final ByteBuffer prefix = ByteBuffer.allocateDirect(SRC_PREFIX_WIDTH);
        NODE_UID_BYTES.put(prefix, srcUid);
        TYPE_UID_BYTES.put(prefix, edgeTypeUid);
        prefix.flip();

        final LmdbKeyRange keyRange = LmdbKeyRange.builder().prefix(prefix).build();
        Long currentDst = null;
        final List<VersionRunEntry> group = new ArrayList<>();
        try (LmdbIterable iterable = LmdbIterable.create(readTxn, dbi, keyRange)) {
            for (final LmdbEntry entry : iterable) {
                final ByteBuffer key = entry.getKey().duplicate();
                key.position(key.position() + SRC_PREFIX_WIDTH);
                final long dstUid = NODE_UID_BYTES.get(key);
                final Instant validFrom = timeSerde.read(key);

                if (!Objects.equals(currentDst, dstUid)) {
                    emitWindowGroup(consumer, currentDst, group, from, to);
                    group.clear();
                    currentDst = dstUid;
                }
                group.add(new VersionRunEntry(validFrom, copy(entry.getVal())));
            }
        }
        emitWindowGroup(consumer, currentDst, group, from, to);
    }

    private void emitWindowGroup(final Consumer<Neighbour> consumer, final @Nullable Long dstUid,
                                 final List<VersionRunEntry> group, final Instant from, final Instant to) {
        if (dstUid == null) {
            return;
        }
        emit(consumer, latestIntersectingNeighbour(dstUid, group, from, to));
    }

    private static @Nullable Neighbour latestIntersectingNeighbour(final long dstUid,
                                                                   final List<VersionRunEntry> group,
                                                                   final Instant from, final Instant to) {
        Neighbour candidate = null;
        for (int i = 0; i < group.size(); i++) {
            final VersionRunEntry entry = group.get(i);
            final Instant nextValidFrom = i + 1 < group.size() ? group.get(i + 1).validFrom() : Instant.MAX;
            if (!entry.validFrom().isAfter(to) && nextValidFrom.isAfter(from)) {
                candidate = decodeNeighbour(dstUid, ByteBuffer.wrap(entry.valueBytes()));
            }
        }
        return candidate;
    }

    /** One raw version in a {@code dstUid} group's run, buffered so its successor's {@code validFrom} can be
     * inspected. */
    private record VersionRunEntry(Instant validFrom, byte[] valueBytes) {
    }

    /**
     * Iterates every stored out-edge version in key order.
     *
     * <p>Provided for merge, which must reproduce a fragment's whole edge history rather than a point in time.
     * The in-edge mirror is derived from this rather than iterated separately, so that each logical edge's two
     * rows are written together.</p>
     *
     * <p><b>Preconditions:</b> neither parameter is null; {@code readTxn} is open on this store's environment.
     * <b>Postconditions:</b> {@code consumer} has been called once per stored version. Buffers are not retained.
     * <b>Null status:</b> the properties passed to {@code consumer} are null for a tombstone.
     *
     * @param readTxn  an open read transaction.
     * @param consumer receives each version.
     */
    public void forEachEdge(final Txn<ByteBuffer> readTxn, final EdgeVersionConsumer consumer) {
        Objects.requireNonNull(readTxn, "readTxn");
        Objects.requireNonNull(consumer, "consumer");
        LmdbIterable.iterate(readTxn, dbi, (key, value) -> {
            final ByteBuffer k = key.duplicate();
            final long srcUid = NODE_UID_BYTES.get(k);
            final long edgeTypeUid = TYPE_UID_BYTES.get(k);
            final long dstUid = NODE_UID_BYTES.get(k);
            final Instant validFrom = timeSerde.read(k);
            final Neighbour neighbour = decodeNeighbour(dstUid, value);
            consumer.accept(srcUid, edgeTypeUid, dstUid, validFrom,
                    neighbour == null ? null : neighbour.edgeProperties());
        });
    }

    /**
     * Receives one stored edge version. {@code properties} is null when the version is a tombstone.
     */
    public interface EdgeVersionConsumer {

        void accept(long srcUid, long edgeTypeUid, long dstUid, Instant validFrom,
                    @Nullable Map<String, Val> properties);
    }

    private static Neighbour decodeNeighbour(final long dstUid, final ByteBuffer value) {
        final ByteBuffer v = value.duplicate();
        final byte tag = v.get();
        if (tag == TOMBSTONE) {
            return null;
        }
        final byte[] propsBlob = new byte[v.remaining()];
        v.get(propsBlob);
        return new Neighbour(dstUid, GraphPropsCodec.decode(propsBlob));
    }

    private ByteBuffer buildKey(final long srcUid, final long edgeTypeUid, final long dstUid,
                                       final Instant validFrom) {
        final ByteBuffer key = ByteBuffer.allocateDirect(keyWidth);
        NODE_UID_BYTES.put(key, srcUid);
        TYPE_UID_BYTES.put(key, edgeTypeUid);
        NODE_UID_BYTES.put(key, dstUid);
        timeSerde.write(key, validFrom);
        key.flip();
        return key;
    }

    /**
     * @param readTxn an open read transaction; not null.
     * @return the number of stored out-edge versions, including tombstones.
     */
    public long count(final Txn<ByteBuffer> readTxn) {
        Objects.requireNonNull(readTxn, "readTxn");
        return dbi.stat(readTxn).entries;
    }

    /**
     * Retention (Task P1.4): within each individual edge's ({@code srcUid, edgeTypeUid, dstUid}) own
     * {@code validFrom} version run, keeps only the single latest version at-or-before {@code deleteBefore} and
     * deletes every strictly-older version - see {@link GraphNodeDb#deleteOldData} for the identical algorithm
     * this mirrors. Records {@code srcUid}/{@code dstUid} (via {@code nodeUidRecorder}) and {@code edgeTypeUid}
     * (via {@code edgeTypeUidRecorder}) as still-used for every surviving version.
     *
     * <p><b>Preconditions:</b> no parameter is null. <b>Postconditions:</b> returns the number of versions
     * deleted (0 if nothing was eligible).</p>
     */
    public long deleteOldData(final LmdbWriter writer, final Instant deleteBefore,
                              final UidLookupRecorder nodeUidRecorder, final UidLookupRecorder edgeTypeUidRecorder) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(deleteBefore, "deleteBefore");
        Objects.requireNonNull(nodeUidRecorder, "nodeUidRecorder");
        Objects.requireNonNull(edgeTypeUidRecorder, "edgeTypeUidRecorder");

        final List<EdgeVersionEntry> group = new ArrayList<>();
        final List<byte[]> toDelete = new ArrayList<>();
        final List<EdgeVersionEntry> survivors = new ArrayList<>();
        byte[] currentEntityPrefix = null;

        try (LmdbIterable iterable = LmdbIterable.create(writer.getWriteTxn(), dbi, LmdbKeyRange.all())) {
            for (final LmdbEntry entry : iterable) {
                final byte[] keyBytes = copy(entry.getKey());
                final byte[] valueBytes = copy(entry.getVal());
                final byte[] entityPrefix = Arrays.copyOfRange(keyBytes, 0, keyWidth - timeSerde.getSize());

                if (!Arrays.equals(currentEntityPrefix, entityPrefix)) {
                    planGroupDeletions(group, deleteBefore, toDelete, survivors);
                    group.clear();
                    currentEntityPrefix = entityPrefix;
                }
                group.add(new EdgeVersionEntry(keyBytes, valueBytes));
            }
        }
        planGroupDeletions(group, deleteBefore, toDelete, survivors);

        for (final EdgeVersionEntry survivor : survivors) {
            final long srcUid = NODE_UID_BYTES.get(ByteBuffer.wrap(survivor.keyBytes, 0, GraphStores.NODE_UID_WIDTH));
            final long edgeTypeUid = TYPE_UID_BYTES.get(
                    ByteBuffer.wrap(survivor.keyBytes, GraphStores.NODE_UID_WIDTH, GraphStores.TYPE_UID_WIDTH));
            final long dstUid = NODE_UID_BYTES.get(ByteBuffer.wrap(survivor.keyBytes, SRC_PREFIX_WIDTH,
                    GraphStores.NODE_UID_WIDTH));
            nodeUidRecorder.recordUsed(writer, srcUid);
            nodeUidRecorder.recordUsed(writer, dstUid);
            edgeTypeUidRecorder.recordUsed(writer, edgeTypeUid);
        }
        for (final byte[] keyBytes : toDelete) {
            dbi.delete(writer.getWriteTxn(), directCopy(keyBytes));
        }
        return toDelete.size();
    }

    private void planGroupDeletions(final List<EdgeVersionEntry> group, final Instant deleteBefore,
                                           final List<byte[]> toDelete, final List<EdgeVersionEntry> survivors) {
        EdgeVersionEntry pendingFloor = null;
        for (final EdgeVersionEntry entry : group) {
            final Instant validFrom = timeSerde.read(ByteBuffer.wrap(
                    entry.keyBytes, keyWidth - timeSerde.getSize(), timeSerde.getSize()));
            if (!validFrom.isAfter(deleteBefore)) {
                if (pendingFloor != null) {
                    toDelete.add(pendingFloor.keyBytes);
                }
                pendingFloor = entry;
            } else {
                survivors.add(entry);
            }
        }
        if (pendingFloor != null) {
            survivors.add(pendingFloor);
        }
    }

    /**
     * Collapses runs of consecutive versions of the same out-edge whose stored value is identical, keeping the
     * <b>earliest</b> of each run - the companion to {@code GraphNodeDb.condense}, which documents why this
     * changes no query result.
     *
     * <p>An edge's identity is everything in its key before the {@code validFrom}, and the key order groups
     * those contiguously in ascending time, so a run is detected by comparing each entry's identity prefix with
     * the previous one's.</p>
     *
     * <p><b>Preconditions:</b> {@code writer} is not null.
     * <b>Postconditions:</b> every run of consecutive identical versions has been reduced to its earliest member.
     * <b>Null status:</b> {@code writer} is not nullable.
     *
     * @param writer the write transaction to condense under.
     * @return the number of versions removed.
     */
    public long condense(final LmdbWriter writer) {
        Objects.requireNonNull(writer, "writer");

        final int identityWidth = keyWidth - timeSerde.getSize();
        final List<byte[]> toDelete = new ArrayList<>();
        byte[] previousIdentity = null;
        byte[] previousValue = null;

        try (LmdbIterable iterable = LmdbIterable.create(writer.getWriteTxn(), dbi, LmdbKeyRange.all())) {
            for (final LmdbEntry entry : iterable) {
                final byte[] keyBytes = copy(entry.getKey());
                final byte[] valueBytes = copy(entry.getVal());
                final byte[] identity = Arrays.copyOfRange(keyBytes, 0, identityWidth);

                if (!Arrays.equals(previousIdentity, identity)) {
                    previousIdentity = identity;
                    previousValue = valueBytes;
                    continue;
                }

                if (Arrays.equals(previousValue, valueBytes)) {
                    toDelete.add(keyBytes);
                } else {
                    previousValue = valueBytes;
                }
            }
        }

        for (final byte[] keyBytes : toDelete) {
            dbi.delete(writer.getWriteTxn(), directCopy(keyBytes));
            writer.tryCommit();
        }
        return toDelete.size();
    }

    private static byte[] copy(final ByteBuffer buffer) {
        final ByteBuffer duplicate = buffer.duplicate();
        final byte[] bytes = new byte[duplicate.remaining()];
        duplicate.get(bytes);
        return bytes;
    }

    private static ByteBuffer directCopy(final byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }

    /** One raw, in-memory copy of a stored edge version's key/value, retained past the read iterator's lifetime. */
    private record EdgeVersionEntry(byte[] keyBytes, byte[] valueBytes) {

    }

    /**
     * A neighbour reached by {@link #expandOut}, as of the requested instant.
     *
     * @param dstUid          the neighbour node's UID.
     * @param edgeProperties never null; possibly empty; this edge version's named properties.
     */
    public record Neighbour(long dstUid, Map<String, Val> edgeProperties) {

        public Neighbour {
            Objects.requireNonNull(edgeProperties, "edgeProperties");
        }
    }
}
