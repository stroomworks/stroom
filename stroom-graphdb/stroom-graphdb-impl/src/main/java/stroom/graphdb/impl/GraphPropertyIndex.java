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

import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
 * instead moves {@code nodeUid} into the KEY's trailing component - {@code [labelUid:4][propKeyUid:4]
 * [valueBytes][nodeUid:6]}, value = a 1-byte marker - so multiple nodes can share one (label, propKey, value)
 * without collision, while {@link #findAnchors}'s prefix scan (on everything but the trailing {@code nodeUid})
 * is unchanged in shape from the original design.</p>
 *
 * <p>{@code valueBytes} equality anchors only (P0.1: range anchors are a documented future extension, not v1)
 * - this class treats it as an opaque byte string it neither interprets nor orders.</p>
 */
public final class GraphPropertyIndex {

    private static final UnsignedBytes NODE_UID_BYTES = UnsignedBytesInstances.ofLength(GraphStores.NODE_UID_WIDTH);
    private static final UnsignedBytes TYPE_UID_BYTES = UnsignedBytesInstances.ofLength(GraphStores.TYPE_UID_WIDTH);
    private static final int PREFIX_WIDTH = GraphStores.TYPE_UID_WIDTH * 2;
    private static final byte MARKER = 1;

    private final Dbi<ByteBuffer> dbi;

    GraphPropertyIndex(final PlanBEnv env) {
        Objects.requireNonNull(env, "env");
        this.dbi = env.openDbi("graph-property-index", DbiFlags.MDB_CREATE);
    }

    /**
     * Associates {@code nodeUid} with the value {@code valueBytes} of property {@code propKeyUid} on label
     * {@code labelUid}. Idempotent: inserting the same (label, propKey, value, node) tuple twice is a no-op.
     *
     * <p><b>Preconditions:</b> no parameter is null; the composite key (fixed 14-byte prefix + {@code
     * valueBytes.length} + 6 trailing bytes) must not exceed {@code Db.MAX_KEY_LENGTH} (511) - the caller is
     * responsible for keeping anchor values short (P0.1: long values are a documented future hash-lookup
     * escalation, not handled here).
     */
    public void insert(final LmdbWriter writer, final long labelUid, final long propKeyUid,
                       final byte[] valueBytes, final long nodeUid) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(valueBytes, "valueBytes");

        final ByteBuffer key = buildKey(labelUid, propKeyUid, valueBytes, nodeUid);
        final ByteBuffer value = ByteBuffer.allocateDirect(1);
        value.put(MARKER);
        value.flip();
        dbi.put(writer.getWriteTxn(), key, value);
    }

    /**
     * The anchor lookup: every node UID with property {@code propKeyUid} equal to {@code valueBytes} on label
     * {@code labelUid}.
     *
     * <p><b>Postconditions:</b> returns an empty list if no node matches; never null.
     */
    public List<Long> findAnchors(final Txn<ByteBuffer> readTxn, final long labelUid, final long propKeyUid,
                                  final byte[] valueBytes) {
        Objects.requireNonNull(readTxn, "readTxn");
        Objects.requireNonNull(valueBytes, "valueBytes");

        final ByteBuffer prefix = ByteBuffer.allocateDirect(PREFIX_WIDTH + valueBytes.length);
        TYPE_UID_BYTES.put(prefix, labelUid);
        TYPE_UID_BYTES.put(prefix, propKeyUid);
        prefix.put(valueBytes);
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

    private static ByteBuffer buildKey(final long labelUid, final long propKeyUid, final byte[] valueBytes,
                                       final long nodeUid) {
        final ByteBuffer key = ByteBuffer.allocateDirect(
                PREFIX_WIDTH + valueBytes.length + GraphStores.NODE_UID_WIDTH);
        TYPE_UID_BYTES.put(key, labelUid);
        TYPE_UID_BYTES.put(key, propKeyUid);
        key.put(valueBytes);
        NODE_UID_BYTES.put(key, nodeUid);
        key.flip();
        return key;
    }
}
