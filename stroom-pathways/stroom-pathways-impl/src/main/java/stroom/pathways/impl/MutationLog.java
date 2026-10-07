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

import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.pathways.shared.pathway.NodeUsage;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.pathways.shared.pathway.PathwayUsage;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.trace.PathwaysDb;
import stroom.planb.impl.dao.trace.PathwaysDb.SimpleDb;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The record of every change made to a model, numbered in the order the changes were made.
 *
 * <p>A model has one history and everything that changes it writes here: a trace that widened a
 * constraint, and the narrowing that puts one back to what has been seen lately. Numbering them
 * in a single run is what lets a replay walk the model forwards or backwards through both.
 */
@Singleton
public class MutationLog {

    private static final byte USAGE_MARKER = 1;

    private final ByteBuffers byteBuffers;
    private final PathwaySerde pathwaySerde;

    @Inject
    public MutationLog(final ByteBuffers byteBuffers, final PathwaySerde pathwaySerde) {
        this.byteBuffers = byteBuffers;
        this.pathwaySerde = pathwaySerde;
    }

    /**
     * Adds changes to a model's history, numbered on from whatever it already holds, and records how
     * busy every node was once they had been made.
     *
     * <p>Not private because the narrowing appends through it too: a model has one history,
     * and a change made by a trace and a change made by holding the model to its window have to be
     * numbered in the same run or a replay cannot walk them.
     */
    void append(final LmdbWriter writer,
                                final PathwaysDb pathwaysDb,
                                final byte[] pathwayKey,
                                final List<PathwayMutation> mutations,
                                final PathNode root,
                                final int source) {
        if (mutations.isEmpty()) {
            return;
        }

        final SimpleDb db = pathwaysDb.getMutations();
        long sequence = lastSequence(writer, db, pathwayKey);
        for (final PathwayMutation mutation : mutations) {
            sequence++;
            final byte[] key = mutationKey(pathwayKey, sequence);
            final PathwayMutation numbered = mutation.withSequence(sequence, source);
            byteBuffers.useBytes(key, (Consumer<ByteBuffer>) keyByteBuffer ->
                    pathwaySerde.writeMutation(numbered, valueByteBuffer ->
                            db.insert(writer, keyByteBuffer, valueByteBuffer)));
        }

        writeUsage(writer, db, pathwayKey, sequence, root);
    }

    // How much every node had been used once this trace was done with the model. Once for the trace,
    // not once for each change it made — a trace that moved nine constraints leaves nine changes and a
    // single reading. Nothing else records it: how often a node has been used is what the model holds
    // now, and a trace that teaches the model nothing changes it without leaving any trace of having
    // done so. Without this a replay can say what the model allowed at a point but not how busy it was.
    //
    // Numbered with the last change the trace made, so winding back to any change finds the newest
    // reading at or before it.
    private void writeUsage(final LmdbWriter writer,
                            final SimpleDb db,
                            final byte[] pathwayKey,
                            final long sequence,
                            final PathNode root) {
        final List<NodeUsage> nodes = new ArrayList<>();
        collectUsage(root, nodes);

        final byte[] key = usageKey(pathwayKey, sequence);
        byteBuffers.useBytes(key, (Consumer<ByteBuffer>) keyByteBuffer ->
                pathwaySerde.writeUsage(new PathwayUsage(sequence, nodes), valueByteBuffer ->
                        db.insert(writer, keyByteBuffer, valueByteBuffer)));
    }

    private static void collectUsage(final PathNode node, final List<NodeUsage> nodes) {
        if (node != null) {
            nodes.add(new NodeUsage(node.getUuid(), node.getTimesUsed(), node.getLastUsedTime()));
            NullSafe.list(node.getChildren()).forEach(child -> collectUsage(child, nodes));
        }
    }

    // Where this pathway's history got to, taken from the last key rather than kept anywhere, so
    // nothing has to stay in step with it. Read on the batch's own transaction, so it counts on from
    // what earlier traces in the same batch wrote rather than starting them all again from what was
    // last committed.
    private static long lastSequence(final LmdbWriter writer,
                                     final SimpleDb db,
                                     final byte[] pathwayKey) {
        final ByteBuffer prefix = ByteBuffer.allocateDirect(pathwayKey.length + 1);
        prefix.put(pathwayKey).put((byte) 0).flip();
        return db.lastPrefixed(writer.getWriteTxn(), prefix, key -> key == null
                ? 0L
                : key.getLong(key.limit() - Long.BYTES));
    }

    // Pathway first, so one model's changes are a single run of keys, then where each sits in that
    // history. Big endian because LMDB orders keys by their bytes, and that is the order a replay
    // walks them in.
    // Alongside the changes but under a marker of their own, so that reading a pathway's changes does
    // not walk over these as well.
    private static byte[] usageKey(final byte[] pathwayKey, final long sequence) {
        final ByteBuffer buffer = ByteBuffer.allocate(pathwayKey.length + 1 + Long.BYTES);
        buffer.put(pathwayKey);
        buffer.put(USAGE_MARKER);
        buffer.putLong(sequence);
        return buffer.array();
    }

    private static byte[] mutationKey(final byte[] pathwayKey, final long sequence) {
        final ByteBuffer buffer = ByteBuffer.allocate(pathwayKey.length + 1 + Long.BYTES);
        buffer.put(pathwayKey);
        buffer.put((byte) 0);
        buffer.putLong(sequence);
        return buffer.array();
    }
}
