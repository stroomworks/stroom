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
import stroom.planb.impl.serde.time.MillisecondTimeSerde;
import stroom.query.language.functions.Val;

import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The out-edge adjacency store (design doc &sect;5.1; implementation plan Task PoC.4). Key layout frozen by
 * P0.1: {@code [srcUid:6][edgeTypeUid:4][dstUid:6][validFrom:6]} (22 bytes) - deliberately {@code dst} before
 * {@code validFrom} (refining the design doc's conceptual sketch) so each individual edge's version history is a
 * contiguous run, making an as-of expand a single forward pass per source (see {@link #expandOut}). The in-edge
 * mirror (for reverse/undirected traversal) is P1, not this class - only out-edges are in the PoC's compiled
 * shape ({@code Direction.OUT}).
 *
 * <p>Value encoding: a 1-byte tag ({@link #TOMBSTONE}/{@link #PRESENT}) then, if present, the edge's named
 * properties via {@link GraphPropsCodec} (as {@link GraphNodeDb} encodes its own).</p>
 */
public final class GraphAdjacencyDb {

    private static final UnsignedBytes NODE_UID_BYTES = UnsignedBytesInstances.ofLength(GraphStores.NODE_UID_WIDTH);
    private static final UnsignedBytes TYPE_UID_BYTES = UnsignedBytesInstances.ofLength(GraphStores.TYPE_UID_WIDTH);
    private static final MillisecondTimeSerde TIME_SERDE = new MillisecondTimeSerde();
    private static final int SRC_PREFIX_WIDTH = GraphStores.NODE_UID_WIDTH + GraphStores.TYPE_UID_WIDTH;
    private static final int KEY_WIDTH = SRC_PREFIX_WIDTH + GraphStores.NODE_UID_WIDTH + 6;

    private static final byte TOMBSTONE = 0;
    private static final byte PRESENT = 1;

    private final Dbi<ByteBuffer> dbi;

    GraphAdjacencyDb(final PlanBEnv env) {
        Objects.requireNonNull(env, "env");
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
                final Instant validFrom = TIME_SERDE.read(key);

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

    private static ByteBuffer buildKey(final long srcUid, final long edgeTypeUid, final long dstUid,
                                       final Instant validFrom) {
        final ByteBuffer key = ByteBuffer.allocateDirect(KEY_WIDTH);
        NODE_UID_BYTES.put(key, srcUid);
        TYPE_UID_BYTES.put(key, edgeTypeUid);
        NODE_UID_BYTES.put(key, dstUid);
        TIME_SERDE.write(key, validFrom);
        key.flip();
        return key;
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
