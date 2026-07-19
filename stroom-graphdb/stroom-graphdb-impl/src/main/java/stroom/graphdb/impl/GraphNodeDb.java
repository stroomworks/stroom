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

import stroom.bytebuffer.ByteBufferUtils;
import stroom.lmdb.serde.UnsignedBytes;
import stroom.lmdb.serde.UnsignedBytesInstances;
import stroom.lmdb.stream.LmdbEntry;
import stroom.lmdb.stream.LmdbIterable;
import stroom.lmdb.stream.LmdbKeyRange;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.PlanBEnv;
import stroom.planb.impl.dao.UidLookupRecorder;
import stroom.planb.impl.serde.time.MillisecondTimeSerde;
import stroom.query.language.functions.Val;

import org.jspecify.annotations.Nullable;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The node store (design doc &sect;5.1; implementation plan Task PoC.4): {@code nodeUid} &rarr; the node's
 * labels + property blob, time-stamped so a node's property history is an as-of lookup. Key layout frozen by
 * P0.1: {@code [nodeUid:6][validFrom:6]} (12 bytes) - the reverse-cursor floor scan this class performs is
 * modelled directly on {@code TemporalStateDb.getState}.
 *
 * <p>Value encoding (an implementation detail the P0.1 frozen model left open - only the key layout was fixed):
 * a 1-byte tag ({@link #TOMBSTONE} or {@link #PRESENT}), then (if present) a 1-byte label count, that many
 * 4-byte label UIDs, then the node's named properties via {@link GraphPropsCodec} (added in Task PoC.5, once
 * the traversal engine needed to actually resolve {@code a.property} references against real values, not an
 * opaque blob).</p>
 */
public final class GraphNodeDb {

    private static final UnsignedBytes NODE_UID_BYTES = UnsignedBytesInstances.ofLength(GraphStores.NODE_UID_WIDTH);
    private static final UnsignedBytes LABEL_UID_BYTES = UnsignedBytesInstances.ofLength(GraphStores.TYPE_UID_WIDTH);
    private static final MillisecondTimeSerde TIME_SERDE = new MillisecondTimeSerde();
    private static final int KEY_WIDTH = GraphStores.NODE_UID_WIDTH + 6;

    private static final byte TOMBSTONE = 0;
    private static final byte PRESENT = 1;

    private final Dbi<ByteBuffer> dbi;

    GraphNodeDb(final PlanBEnv env) {
        Objects.requireNonNull(env, "env");
        this.dbi = env.openDbi("graph-node", DbiFlags.MDB_CREATE);
    }

    /**
     * Writes a new version of {@code nodeUid}, valid from {@code validFrom}.
     *
     * <p><b>Preconditions:</b> no parameter is null; {@code labelUids} elements must each fit in
     * {@link GraphStores#TYPE_UID_WIDTH} bytes (enforced by the underlying {@link UnsignedBytes}, which throws on
     * an out-of-range value). <b>Postconditions:</b> a subsequent {@link #getNode} as-of any instant
     * &ge; {@code validFrom} (and before the next version, if any) returns this version.
     */
    public void insert(final LmdbWriter writer, final long nodeUid, final Instant validFrom,
                       final List<Long> labelUids, final Map<String, Val> properties) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(labelUids, "labelUids");
        Objects.requireNonNull(properties, "properties");

        final byte[] propsBlob = GraphPropsCodec.encode(properties);
        final ByteBuffer key = buildKey(nodeUid, validFrom);
        final ByteBuffer value = ByteBuffer.allocateDirect(1 + 1 + labelUids.size() * 4 + propsBlob.length);
        value.put(PRESENT);
        value.put((byte) labelUids.size());
        for (final long labelUid : labelUids) {
            LABEL_UID_BYTES.put(value, labelUid);
        }
        value.put(propsBlob);
        value.flip();
        dbi.put(writer.getWriteTxn(), key, value);
    }

    /**
     * Writes a tombstone version of {@code nodeUid} at {@code validFrom} - a supersede/delete (design doc
     * &sect;5.4): the node is absent from {@code validFrom} onward, until (if ever) a later {@link #insert}.
     */
    public void delete(final LmdbWriter writer, final long nodeUid, final Instant validFrom) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(validFrom, "validFrom");

        final ByteBuffer key = buildKey(nodeUid, validFrom);
        final ByteBuffer value = ByteBuffer.allocateDirect(1);
        value.put(TOMBSTONE);
        value.flip();
        dbi.put(writer.getWriteTxn(), key, value);
    }

    /**
     * The as-of floor lookup (design doc &sect;5.4 / P0.3 outcome: {@code AS OF} applied per-edge/per-node at a
     * single instant): the most recent version of {@code nodeUid} at or before {@code asOf}.
     *
     * <p><b>Postconditions:</b> empty if no version of {@code nodeUid} exists at or before {@code asOf}, or if
     * the floor version found is a tombstone (the node was deleted by then).
     */
    public Optional<NodeVersion> getNode(final Txn<ByteBuffer> readTxn, final long nodeUid, final Instant asOf) {
        Objects.requireNonNull(readTxn, "readTxn");
        Objects.requireNonNull(asOf, "asOf");

        final ByteBuffer seekKey = buildKey(nodeUid, asOf);
        final ByteBuffer prefix = ByteBuffer.allocateDirect(GraphStores.NODE_UID_WIDTH);
        NODE_UID_BYTES.put(prefix, nodeUid);
        prefix.flip();

        final LmdbKeyRange keyRange = LmdbKeyRange.builder().start(seekKey).reverse().build();
        try (LmdbIterable iterable = LmdbIterable.create(readTxn, dbi, keyRange)) {
            for (final LmdbEntry entry : iterable) {
                if (!ByteBufferUtils.containsPrefix(entry.getKey(), prefix)) {
                    return Optional.empty();
                }
                return Optional.ofNullable(decodeValue(entry.getVal()));
            }
        }
        return Optional.empty();
    }

    /**
     * The window-intersection lookup (Task P4.1; design doc &sect;5.4 / P0.3 outcome: {@code AROUND}/
     * {@code BETWEEN} resolve to an inclusive window {@code [from, to]}, and a version
     * {@code [validFrom, nextValidFrom)} intersects it iff {@code validFrom <= to && nextValidFrom > from}):
     * among {@code nodeUid}'s versions whose interval intersects {@code [from, to]}, returns the one with the
     * greatest {@code validFrom} (the state as it stood by the end of the window, restricted to versions live
     * at some point within it) - mirroring {@link #getNode}'s own "last-applicable-version" rule, just with an
     * intersection test instead of a less-than-or-equal test.
     *
     * <p>Unlike {@link #getNode}'s reverse-bounded floor scan, this cannot early-exit - every version in
     * {@code nodeUid}'s run must be seen to know each entry's own {@code nextValidFrom} (the following entry's
     * {@code validFrom}, or {@code Instant.MAX} for the run's last entry), so the whole run is buffered first.
     *
     * <p><b>Postconditions:</b> empty if no version intersects {@code [from, to]}, or if the latest intersecting
     * version is a tombstone (the node was absent for the remainder of the window).
     */
    public Optional<NodeVersion> getNodeWindow(final Txn<ByteBuffer> readTxn, final long nodeUid,
                                               final Instant from, final Instant to) {
        Objects.requireNonNull(readTxn, "readTxn");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");

        final ByteBuffer prefix = ByteBuffer.allocateDirect(GraphStores.NODE_UID_WIDTH);
        NODE_UID_BYTES.put(prefix, nodeUid);
        prefix.flip();

        final List<VersionRunEntry> run = new ArrayList<>();
        final LmdbKeyRange keyRange = LmdbKeyRange.builder().prefix(prefix).build();
        try (LmdbIterable iterable = LmdbIterable.create(readTxn, dbi, keyRange)) {
            for (final LmdbEntry entry : iterable) {
                final ByteBuffer key = entry.getKey().duplicate();
                key.position(key.position() + GraphStores.NODE_UID_WIDTH);
                run.add(new VersionRunEntry(TIME_SERDE.read(key), copy(entry.getVal())));
            }
        }
        return Optional.ofNullable(latestIntersecting(run, from, to));
    }

    private static @Nullable NodeVersion latestIntersecting(
            final List<VersionRunEntry> run, final Instant from, final Instant to) {
        NodeVersion candidate = null;
        for (int i = 0; i < run.size(); i++) {
            final VersionRunEntry entry = run.get(i);
            final Instant nextValidFrom = i + 1 < run.size() ? run.get(i + 1).validFrom() : Instant.MAX;
            if (!entry.validFrom().isAfter(to) && nextValidFrom.isAfter(from)) {
                candidate = decodeValue(ByteBuffer.wrap(entry.valueBytes()));
            }
        }
        return candidate;
    }

    /** One raw version in a node's run, buffered so its successor's {@code validFrom} can be inspected. */
    private record VersionRunEntry(Instant validFrom, byte[] valueBytes) {
    }

    /**
     * Retention (Task P1.4): within each node's own {@code validFrom} version run, keeps only the single latest
     * version at-or-before {@code deleteBefore} (the floor version still needed to answer an {@code AS OF}/
     * expand-as-of query for any retained instant) and deletes every strictly-older version of that node -
     * mirroring {@code TemporalStateDb.deleteOldData}'s per-key-prefix walk. For every surviving version (the
     * retained floor, plus every version newer than {@code deleteBefore}), records {@code nodeUid} and its
     * label UIDs as still-used via {@code nodeUidRecorder}/{@code labelUidRecorder} (Task P1.2's mark-and-sweep -
     * the caller must not run either recorder's {@code deleteUnused} until every DAO referencing that namespace
     * has finished its own {@code deleteOldData} pass).
     *
     * <p><b>Preconditions:</b> no parameter is null. <b>Postconditions:</b> returns the number of versions
     * deleted (0 if nothing was eligible).</p>
     */
    public long deleteOldData(final LmdbWriter writer, final Instant deleteBefore,
                              final UidLookupRecorder nodeUidRecorder, final UidLookupRecorder labelUidRecorder) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(deleteBefore, "deleteBefore");
        Objects.requireNonNull(nodeUidRecorder, "nodeUidRecorder");
        Objects.requireNonNull(labelUidRecorder, "labelUidRecorder");

        final List<VersionEntry> group = new ArrayList<>();
        final List<byte[]> toDelete = new ArrayList<>();
        final List<VersionEntry> survivors = new ArrayList<>();
        Long currentNodeUid = null;

        try (LmdbIterable iterable = LmdbIterable.create(writer.getWriteTxn(), dbi, LmdbKeyRange.all())) {
            for (final LmdbEntry entry : iterable) {
                final byte[] keyBytes = copy(entry.getKey());
                final byte[] valueBytes = copy(entry.getVal());
                final long nodeUid = NODE_UID_BYTES.get(ByteBuffer.wrap(keyBytes, 0, GraphStores.NODE_UID_WIDTH));

                if (!Objects.equals(currentNodeUid, nodeUid)) {
                    planGroupDeletions(group, deleteBefore, toDelete, survivors);
                    group.clear();
                    currentNodeUid = nodeUid;
                }
                group.add(new VersionEntry(nodeUid, keyBytes, valueBytes));
            }
        }
        planGroupDeletions(group, deleteBefore, toDelete, survivors);

        for (final VersionEntry survivor : survivors) {
            nodeUidRecorder.recordUsed(writer, survivor.nodeUid);
            final NodeVersion decoded = decodeValue(ByteBuffer.wrap(survivor.valueBytes));
            if (decoded != null) {
                for (final long labelUid : decoded.labelUids()) {
                    labelUidRecorder.recordUsed(writer, labelUid);
                }
            }
        }
        for (final byte[] keyBytes : toDelete) {
            dbi.delete(writer.getWriteTxn(), directCopy(keyBytes));
        }
        return toDelete.size();
    }

    /**
     * Within one node's ascending-{@code validFrom} version run, decides which versions are superseded (every
     * {@code validFrom <= deleteBefore} version except the last one seen) and which survive (that last one, plus
     * every version {@code > deleteBefore}).
     */
    private static void planGroupDeletions(final List<VersionEntry> group, final Instant deleteBefore,
                                           final List<byte[]> toDelete, final List<VersionEntry> survivors) {
        VersionEntry pendingFloor = null;
        for (final VersionEntry entry : group) {
            final Instant validFrom = TIME_SERDE.read(
                    ByteBuffer.wrap(entry.keyBytes, GraphStores.NODE_UID_WIDTH, 6));
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

    /** One raw, in-memory copy of a stored version's key/value, retained past the read iterator's lifetime. */
    private record VersionEntry(long nodeUid, byte[] keyBytes, byte[] valueBytes) {

    }

    private static NodeVersion decodeValue(final ByteBuffer value) {
        final ByteBuffer v = value.duplicate();
        final byte tag = v.get();
        if (tag == TOMBSTONE) {
            return null;
        }
        final int labelCount = v.get() & 0xFF;
        final List<Long> labelUids = new ArrayList<>(labelCount);
        for (int i = 0; i < labelCount; i++) {
            labelUids.add(LABEL_UID_BYTES.get(v));
        }
        final byte[] propsBlob = new byte[v.remaining()];
        v.get(propsBlob);
        return new NodeVersion(labelUids, GraphPropsCodec.decode(propsBlob));
    }

    private static ByteBuffer buildKey(final long nodeUid, final Instant validFrom) {
        final ByteBuffer key = ByteBuffer.allocateDirect(KEY_WIDTH);
        NODE_UID_BYTES.put(key, nodeUid);
        TIME_SERDE.write(key, validFrom);
        key.flip();
        return key;
    }

    /**
     * A node's labels and named properties, as returned by {@link #getNode}.
     *
     * @param labelUids  never null; the label namespace UIDs this node version carries.
     * @param properties never null; possibly empty.
     */
    public record NodeVersion(List<Long> labelUids, Map<String, Val> properties) {

        public NodeVersion {
            Objects.requireNonNull(labelUids, "labelUids");
            Objects.requireNonNull(properties, "properties");
        }
    }
}
