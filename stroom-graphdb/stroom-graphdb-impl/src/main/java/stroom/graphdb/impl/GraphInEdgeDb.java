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
 * The in-edge adjacency store (design doc &sect;5.1; implementation plan Task P1.1) - the reverse mirror of
 * {@link GraphAdjacencyDb}, structurally identical with {@code src}/{@code dst} swapped throughout. Key layout:
 * {@code [dstUid:6][edgeTypeUid:4][srcUid:6][validFrom:6]} (22 bytes), so a single logical edge
 * {@code (src, edgeType, dst, validFrom)} has one version written here (keyed by {@code dst}) and one written to
 * {@link GraphAdjacencyDb} (keyed by {@code src}) - <b>callers are responsible for writing both</b>; there is no
 * cross-DAO transactional enforcement, exactly as Plan B trusts its own DAO callers to keep companion structures
 * consistent. {@code Direction.IN}/{@code Direction.BOTH} Cypher patterns read from this store (see
 * {@link GraphTraversalEngine}).
 *
 * <p>Value encoding: identical to {@link GraphAdjacencyDb} - a 1-byte tag ({@link #TOMBSTONE}/{@link #PRESENT})
 * then, if present, the edge's named properties via {@link GraphPropsCodec}.</p>
 */
public final class GraphInEdgeDb {

    private static final UnsignedBytes NODE_UID_BYTES = UnsignedBytesInstances.ofLength(GraphStores.NODE_UID_WIDTH);
    private static final UnsignedBytes TYPE_UID_BYTES = UnsignedBytesInstances.ofLength(GraphStores.TYPE_UID_WIDTH);
    private static final MillisecondTimeSerde TIME_SERDE = new MillisecondTimeSerde();
    private static final int DST_PREFIX_WIDTH = GraphStores.NODE_UID_WIDTH + GraphStores.TYPE_UID_WIDTH;
    private static final int KEY_WIDTH = DST_PREFIX_WIDTH + GraphStores.NODE_UID_WIDTH + 6;

    private static final byte TOMBSTONE = 0;
    private static final byte PRESENT = 1;

    private final Dbi<ByteBuffer> dbi;

    GraphInEdgeDb(final PlanBEnv env) {
        Objects.requireNonNull(env, "env");
        this.dbi = env.openDbi("graph-in-edge", DbiFlags.MDB_CREATE);
    }

    /**
     * Writes a new version of the edge {@code (srcUid, edgeTypeUid, dstUid)}, valid from {@code validFrom} - the
     * in-edge companion to {@link GraphAdjacencyDb#insert}; callers must write both for one logical edge.
     *
     * <p><b>Preconditions:</b> no parameter is null. <b>Postconditions:</b> a subsequent {@link #expandIn} as-of
     * any instant &ge; {@code validFrom} (and before this edge's next version, if any) includes {@code srcUid}.
     */
    public void insert(final LmdbWriter writer, final long srcUid, final long edgeTypeUid, final long dstUid,
                       final Instant validFrom, final Map<String, Val> properties) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(properties, "properties");

        final byte[] propsBlob = GraphPropsCodec.encode(properties);
        final ByteBuffer key = buildKey(dstUid, edgeTypeUid, srcUid, validFrom);
        final ByteBuffer value = ByteBuffer.allocateDirect(1 + propsBlob.length);
        value.put(PRESENT);
        value.put(propsBlob);
        value.flip();
        dbi.put(writer.getWriteTxn(), key, value);
    }

    /**
     * Writes a tombstone version of the edge {@code (srcUid, edgeTypeUid, dstUid)} at {@code validFrom} - the
     * in-edge companion to {@link GraphAdjacencyDb#delete}.
     */
    public void delete(final LmdbWriter writer, final long srcUid, final long edgeTypeUid, final long dstUid,
                       final Instant validFrom) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(validFrom, "validFrom");

        final ByteBuffer key = buildKey(dstUid, edgeTypeUid, srcUid, validFrom);
        final ByteBuffer value = ByteBuffer.allocateDirect(1);
        value.put(TOMBSTONE);
        value.flip();
        dbi.put(writer.getWriteTxn(), key, value);
    }

    /**
     * The as-of 1-hop reverse expand: a single forward pass over the {@code [dstUid][edgeTypeUid]} prefix,
     * grouping by {@code srcUid} (each group is contiguous, mirroring {@link GraphAdjacencyDb#expandOut}'s own
     * layout rationale) and emitting, for each source, its most recent version at or before {@code asOf} - unless
     * that version is a tombstone, in which case the source is skipped.
     *
     * <p><b>Postconditions:</b> {@code consumer} is invoked at most once per distinct {@code srcUid} reaching
     * {@code dstUid} via {@code edgeTypeUid}, in ascending {@code srcUid} order.
     */
    public void expandIn(final Txn<ByteBuffer> readTxn, final long dstUid, final long edgeTypeUid,
                         final Instant asOf, final Consumer<Neighbour> consumer) {
        Objects.requireNonNull(readTxn, "readTxn");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(consumer, "consumer");

        final ByteBuffer prefix = ByteBuffer.allocateDirect(DST_PREFIX_WIDTH);
        NODE_UID_BYTES.put(prefix, dstUid);
        TYPE_UID_BYTES.put(prefix, edgeTypeUid);
        prefix.flip();

        final LmdbKeyRange keyRange = LmdbKeyRange.builder().prefix(prefix).build();
        Long currentSrc = null;
        Neighbour floorForCurrentSrc = null;
        try (LmdbIterable iterable = LmdbIterable.create(readTxn, dbi, keyRange)) {
            for (final LmdbEntry entry : iterable) {
                final ByteBuffer key = entry.getKey().duplicate();
                key.position(key.position() + DST_PREFIX_WIDTH);
                final long srcUid = NODE_UID_BYTES.get(key);
                final Instant validFrom = TIME_SERDE.read(key);

                if (!Objects.equals(currentSrc, srcUid)) {
                    emit(consumer, floorForCurrentSrc);
                    currentSrc = srcUid;
                    floorForCurrentSrc = null;
                }
                if (!validFrom.isAfter(asOf)) {
                    floorForCurrentSrc = decodeNeighbour(srcUid, entry.getVal());
                }
            }
        }
        emit(consumer, floorForCurrentSrc);
    }

    private static void emit(final Consumer<Neighbour> consumer, final Neighbour neighbour) {
        if (neighbour != null) {
            consumer.accept(neighbour);
        }
    }

    private static Neighbour decodeNeighbour(final long srcUid, final ByteBuffer value) {
        final ByteBuffer v = value.duplicate();
        final byte tag = v.get();
        if (tag == TOMBSTONE) {
            return null;
        }
        final byte[] propsBlob = new byte[v.remaining()];
        v.get(propsBlob);
        return new Neighbour(srcUid, GraphPropsCodec.decode(propsBlob));
    }

    private static ByteBuffer buildKey(final long dstUid, final long edgeTypeUid, final long srcUid,
                                       final Instant validFrom) {
        final ByteBuffer key = ByteBuffer.allocateDirect(KEY_WIDTH);
        NODE_UID_BYTES.put(key, dstUid);
        TYPE_UID_BYTES.put(key, edgeTypeUid);
        NODE_UID_BYTES.put(key, srcUid);
        TIME_SERDE.write(key, validFrom);
        key.flip();
        return key;
    }

    /**
     * A neighbour reached by {@link #expandIn}, as of the requested instant.
     *
     * @param srcUid          the neighbour (source) node's UID.
     * @param edgeProperties never null; possibly empty; this edge version's named properties.
     */
    public record Neighbour(long srcUid, Map<String, Val> edgeProperties) {

        public Neighbour {
            Objects.requireNonNull(edgeProperties, "edgeProperties");
        }
    }
}
