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
import stroom.planb.impl.dao.Db;
import stroom.planb.impl.dao.HashLookupDb;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.PlanBEnv;
import stroom.planb.impl.dao.UidLookupDb;
import stroom.planb.impl.serde.val.VariableValType;

import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The property-value (anchor) index (design doc &sect;5.1; P0.1 resolved D3 as a Plan B {@code STATE}-style
 * sub-store, which this class is): {@code (labelUid, propKeyUid, value) -> nodeUid}, used to find a
 * {@code MATCH}'s anchor node(s) by label + property equality.
 *
 * <p><b>Key-layout refinement (this class's own spike finding, analogous to P0.1's edge-key reordering):</b> the
 * frozen model's conceptual layout is {@code key [labelUid][propKeyUid][valueBytes] -> value [nodeUid]} - one
 * {@code nodeUid} per key. That silently loses data whenever two different nodes share the same
 * (label, propKey, value) - not a rare case (e.g. two {@code Account} nodes both with {@code status: 'active'}),
 * since a later {@link #insert} for the same value would simply overwrite the first node's entry. This class
 * instead moves {@code nodeUid} into the KEY's trailing component - {@code [labelUid:4][propKeyUid:4][tag:1]
 * [tieredValue][nodeUid:6]}, value = a 1-byte marker - so multiple nodes can share one (label, propKey, value)
 * without collision, while {@link #findAnchors}'s prefix scan (on everything but the trailing {@code nodeUid})
 * is unchanged in shape from the original design.</p>
 *
 * <p><b>Value tiering (Task P1.3):</b> {@code valueBytes} is encoded via the same 3-tier discipline Plan B's own
 * {@code VariableKeySerde} uses for its value component - a leading {@link VariableValType} tag byte, then:
 * {@code DIRECT} (&le;{@value #DIRECT_MAX_LENGTH} bytes - inlined as-is), {@code UID_LOOKUP} (&le;{@code
 * Db.MAX_KEY_LENGTH} (511) bytes - interned via a {@link UidLookupDb}, the lookup UID used in the key instead of
 * the raw bytes), {@code HASH_LOOKUP} (longer - interned via a {@link HashLookupDb} instead, since a sequential
 * {@code UidLookupDb} UID has no natural bound but a value this large would otherwise make the composite key
 * itself exceed {@code Db.MAX_KEY_LENGTH}). Unlike {@code VariableKeySerde}, there is no time-suffix component
 * here to slice around, and no need to <em>decode</em> a value back out of a stored key ({@link #findAnchors}
 * always receives {@code valueBytes} as an input to re-encode and seek by, never reconstructs it from a key) - so
 * this class only ever <em>encodes</em>, deliberately simpler than the full serde it mirrors.</p>
 *
 * <p>{@code valueBytes} equality anchors only (P0.1: range anchors are a documented future extension, not v1)
 * - this class treats it as an opaque byte string it neither interprets nor orders.</p>
 *
 * <p><b>DIRECT-tier length prefix (fixes a bug present since PoC.4, found via Task P1.3's tier-boundary test):</b>
 * the {@code DIRECT} tier's segment is {@code [tag:1][length:1][valueBytes]}, not bare {@code [tag:1][valueBytes]}
 * - without the length byte, {@link #findAnchors}'s prefix scan for a value that is a byte-for-byte prefix of a
 * different, longer {@code DIRECT}-tier value on the same (label, propKey) would incorrectly also match the
 * longer value's anchors (e.g. anchoring on {@code "ab"} would also match nodes anchored on {@code "abc"}), since
 * the scan had nothing to stop it from reading past the shorter value's end into the longer key's remainder. The
 * length byte closes this: two different-length values now always diverge at the length byte itself, before any
 * of {@code valueBytes} is compared. {@code DIRECT_MAX_LENGTH} (32) fits in one unsigned byte with room to spare.
 * The {@code UID_LOOKUP}/{@code HASH_LOOKUP} tiers never had this problem (they key by a fixed-width interned id,
 * not the raw value) and are unchanged.</p>
 *
 * <p><b>On-disk compatibility:</b> this is a key-format change for the {@code DIRECT} tier only. This feature has
 * not shipped in any release (not yet mentioned in {@code CHANGELOG.md}/{@code BREAKING_CHANGES.md}), so there is
 * no back-compat obligation and no migration is provided - per {@link GraphStores#rebuild}'s existing
 * "materialized projection, rebuildable from source" design, any store already provisioned with the pre-fix
 * {@code DIRECT} format must be rebuilt (not merely reopened) before this fix takes effect for it.</p>
 */
public final class GraphPropertyIndex {

    /** &le; this many bytes: inlined directly in the key (see this class's Javadoc). */
    private static final int DIRECT_MAX_LENGTH = 32;

    /** &le; this many bytes (but &gt; {@link #DIRECT_MAX_LENGTH}): a {@link UidLookupDb} entry. */
    private static final int UID_LOOKUP_MAX_LENGTH = Db.MAX_KEY_LENGTH;

    private static final UnsignedBytes NODE_UID_BYTES = UnsignedBytesInstances.ofLength(GraphStores.NODE_UID_WIDTH);
    private static final UnsignedBytes TYPE_UID_BYTES = UnsignedBytesInstances.ofLength(GraphStores.TYPE_UID_WIDTH);
    private static final int PREFIX_WIDTH = GraphStores.TYPE_UID_WIDTH * 2;
    private static final byte MARKER = 1;

    private final Dbi<ByteBuffer> dbi;
    private final UidLookupDb valueUidLookup;
    private final HashLookupDb valueHashLookup;

    GraphPropertyIndex(final PlanBEnv env, final UidLookupDb valueUidLookup, final HashLookupDb valueHashLookup) {
        Objects.requireNonNull(env, "env");
        this.valueUidLookup = Objects.requireNonNull(valueUidLookup, "valueUidLookup");
        this.valueHashLookup = Objects.requireNonNull(valueHashLookup, "valueHashLookup");
        this.dbi = env.openDbi("graph-property-index", DbiFlags.MDB_CREATE);
    }

    /**
     * Associates {@code nodeUid} with the value {@code valueBytes} of property {@code propKeyUid} on label
     * {@code labelUid}. Idempotent: inserting the same (label, propKey, value, node) tuple twice is a no-op.
     *
     * <p><b>Preconditions:</b> no parameter is null; the composite key (a fixed 14-byte prefix, a tiered value
     * encoding bounded well within {@code Db.MAX_KEY_LENGTH} even for arbitrarily long {@code valueBytes} thanks
     * to the {@code HASH_LOOKUP} tier, and 6 trailing bytes) never exceeds {@code Db.MAX_KEY_LENGTH} (511).
     */
    public void insert(final LmdbWriter writer, final long labelUid, final long propKeyUid,
                       final byte[] valueBytes, final long nodeUid) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(valueBytes, "valueBytes");

        final ByteBuffer valueSegment = encodeForInsert(writer, valueBytes);
        final ByteBuffer key = buildKey(labelUid, propKeyUid, valueSegment, nodeUid);
        final ByteBuffer value = ByteBuffer.allocateDirect(1);
        value.put(MARKER);
        value.flip();
        dbi.put(writer.getWriteTxn(), key, value);
    }

    /**
     * The anchor lookup: every node UID with property {@code propKeyUid} equal to {@code valueBytes} on label
     * {@code labelUid}.
     *
     * <p><b>Postconditions:</b> returns an empty list if no node matches - including if {@code valueBytes} was
     * never interned at all (a {@code UID_LOOKUP}/{@code HASH_LOOKUP}-tier value with no existing lookup entry
     * cannot have been anchored by any {@link #insert}, so this short-circuits without a wasted scan). Never
     * null.
     */
    public List<Long> findAnchors(final Txn<ByteBuffer> readTxn, final long labelUid, final long propKeyUid,
                                  final byte[] valueBytes) {
        Objects.requireNonNull(readTxn, "readTxn");
        Objects.requireNonNull(valueBytes, "valueBytes");

        final Optional<ByteBuffer> valueSegment = encodeForLookup(readTxn, valueBytes);
        if (valueSegment.isEmpty()) {
            return List.of();
        }

        final ByteBuffer prefix = ByteBuffer.allocateDirect(PREFIX_WIDTH + valueSegment.get().remaining());
        TYPE_UID_BYTES.put(prefix, labelUid);
        TYPE_UID_BYTES.put(prefix, propKeyUid);
        prefix.put(valueSegment.get().duplicate());
        prefix.flip();

        final List<Long> nodeUids = new ArrayList<>();
        final LmdbKeyRange keyRange = LmdbKeyRange.builder().prefix(prefix).build();
        try (LmdbIterable iterable = LmdbIterable.create(readTxn, dbi, keyRange)) {
            for (final LmdbEntry entry : iterable) {
                final ByteBuffer key = entry.getKey().duplicate();
                key.position(key.limit() - GraphStores.NODE_UID_WIDTH);
                nodeUids.add(NODE_UID_BYTES.get(key));
            }
        }
        return nodeUids;
    }

    /**
     * Encodes {@code valueBytes} into its tagged tier segment for a write, creating a {@code UID_LOOKUP}/
     * {@code HASH_LOOKUP} entry if this is the first time this exact value has been seen at that tier (get-or-
     * create, mirroring {@link UidLookupDb#put}/{@link HashLookupDb#put}'s own idempotence).
     */
    private ByteBuffer encodeForInsert(final LmdbWriter writer, final byte[] valueBytes) {
        if (valueBytes.length <= DIRECT_MAX_LENGTH) {
            return directSegment(valueBytes);
        } else if (valueBytes.length <= UID_LOOKUP_MAX_LENGTH) {
            return valueUidLookup.put(writer.getWriteTxn(), directBuffer(valueBytes),
                    idByteBuffer -> taggedSegment(VariableValType.UID_LOOKUP, idByteBuffer));
        } else {
            return valueHashLookup.put(writer.getWriteTxn(), valueBytes,
                    idByteBuffer -> taggedSegment(VariableValType.HASH_LOOKUP, idByteBuffer));
        }
    }

    /**
     * Encodes {@code valueBytes} into its tagged tier segment for a lookup, without creating anything -
     * {@link Optional#empty()} if a {@code UID_LOOKUP}/{@code HASH_LOOKUP}-tier value has no existing entry
     * (meaning it was never inserted, so no anchor can match it).
     */
    private Optional<ByteBuffer> encodeForLookup(final Txn<ByteBuffer> readTxn, final byte[] valueBytes) {
        if (valueBytes.length <= DIRECT_MAX_LENGTH) {
            return Optional.of(directSegment(valueBytes));
        } else if (valueBytes.length <= UID_LOOKUP_MAX_LENGTH) {
            return valueUidLookup.get(readTxn, directBuffer(valueBytes),
                    maybeId -> maybeId.map(id -> taggedSegment(VariableValType.UID_LOOKUP, id)));
        } else {
            return valueHashLookup.get(readTxn, valueBytes,
                    maybeId -> maybeId.map(id -> taggedSegment(VariableValType.HASH_LOOKUP, id)));
        }
    }

    /**
     * The {@code DIRECT} tier's segment: {@code [tag:1][length:1][valueBytes]}. The length byte (safe as a single
     * unsigned byte since {@code valueBytes.length <= DIRECT_MAX_LENGTH} (32) here) stops {@link #findAnchors}'s
     * prefix scan from matching a longer value that merely starts with the same bytes - see this class's Javadoc.
     */
    private static ByteBuffer directSegment(final byte[] valueBytes) {
        final ByteBuffer segment = ByteBuffer.allocateDirect(2 + valueBytes.length);
        segment.put(VariableValType.DIRECT.getPrimitiveValue());
        segment.put((byte) valueBytes.length);
        segment.put(valueBytes);
        segment.flip();
        return segment;
    }

    private static ByteBuffer taggedSegment(final VariableValType tag, final ByteBuffer encodedValue) {
        final ByteBuffer segment = ByteBuffer.allocateDirect(1 + encodedValue.remaining());
        segment.put(tag.getPrimitiveValue());
        segment.put(encodedValue.duplicate());
        segment.flip();
        return segment;
    }

    private static ByteBuffer directBuffer(final byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer buildKey(final long labelUid, final long propKeyUid, final ByteBuffer valueSegment,
                                       final long nodeUid) {
        final ByteBuffer key = ByteBuffer.allocateDirect(
                PREFIX_WIDTH + valueSegment.remaining() + GraphStores.NODE_UID_WIDTH);
        TYPE_UID_BYTES.put(key, labelUid);
        TYPE_UID_BYTES.put(key, propKeyUid);
        key.put(valueSegment.duplicate());
        NODE_UID_BYTES.put(key, nodeUid);
        key.flip();
        return key;
    }
}
