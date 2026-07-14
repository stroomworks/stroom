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

package stroom.pathways.impl;

import stroom.bytebuffer.impl6.ByteBufferFactory;
import stroom.bytebuffer.impl6.ByteBufferFactoryImpl;
import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.lmdb.stream.LmdbKeyRange;
import stroom.pathways.impl.events.PathwayEvent;
import stroom.pathways.impl.events.PathwayEventType;
import stroom.pathways.impl.events.PathwayRootDiscoveryEvent;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.planb.impl.db.LmdbWriter;
import stroom.planb.impl.db.trace.NanoTimeUtil;
import stroom.planb.impl.db.trace.PathwayEventsDb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the pathway events store: the time+trace key scheme keeps events from separate
 * processing runs from overwriting each other (the previous per-run {@code sequenceId} reset
 * silently clobbered earlier events), and a per-pathway prefix scan returns them in time order.
 */
class TestPathwayEventStore {

    private final ByteBufferFactory byteBufferFactory = new ByteBufferFactoryImpl();
    private final PathwaySerde pathwaySerde = new PathwaySerde(byteBufferFactory);
    private final PathwayEventsSerde eventsSerde = new PathwayEventsSerde(byteBufferFactory, pathwaySerde);

    @Test
    void eventsFromMultipleRunsAreNotOverwritten(@TempDir final Path dir) {
        final PathwayEventsDb db = PathwayEventsDb.create(dir, false);

        final String pathwayA = "POST /people";
        final String pathwayB = "GET /users";
        final byte[] traceRun1 = traceId(1);
        final byte[] traceRun2 = traceId(2);

        // Run 1: three events for pathway A (seq 0,1,2) and one for pathway B.
        try (final LmdbWriter writer = db.createWriter()) {
            write(db, writer, pathwayA, traceRun1, 0, nano(10));
            write(db, writer, pathwayA, traceRun1, 1, nano(20));
            write(db, writer, pathwayA, traceRun1, 2, nano(30));
            write(db, writer, pathwayB, traceRun1, 3, nano(15));
            writer.commit();
        }

        // Run 2: a fresh run whose sequence restarts at 0 - under the old key scheme these would
        // overwrite run 1's seq 0/1 events for pathway A. The trace id in the key prevents that.
        try (final LmdbWriter writer = db.createWriter()) {
            write(db, writer, pathwayA, traceRun2, 0, nano(40));
            write(db, writer, pathwayA, traceRun2, 1, nano(50));
            writer.commit();
        }

        // Recall pathway A: all five events must survive, in ascending time order.
        final List<Long> timesA = new ArrayList<>();
        final List<String> namesA = new ArrayList<>();
        db.getPathwayEvents().iterate(prefixRange(pathwayA), (key, value) -> {
            namesA.add(pathwayName(key));
            final PathwayEvent event = eventsSerde.readPathwayEvent(value, new HashMap<>());
            timesA.add(event.getTimestamp().toEpochMillis());
        });

        assertThat(timesA).hasSize(5);
        assertThat(timesA).isSorted();
        assertThat(namesA).allMatch(pathwayA::equals);

        // The other pathway's single event must not be returned by pathway A's scan.
        final List<String> namesB = new ArrayList<>();
        db.getPathwayEvents().iterate(prefixRange(pathwayB), (key, value) -> namesB.add(pathwayName(key)));
        assertThat(namesB).hasSize(1).allMatch(pathwayB::equals);

        db.close();
    }

    /**
     * Writes an event using the same key layout as
     * {@code MessageReceiverFactory}: {@code <name>\0 <timestampNanos:8B> <seq:8B> <traceId>}.
     */
    private void write(final PathwayEventsDb db,
                       final LmdbWriter writer,
                       final String pathwayName,
                       final byte[] traceId,
                       final long seq,
                       final NanoTime timestamp) {
        final byte[] nameBytes = pathwayName.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer key = ByteBuffer.allocateDirect(nameBytes.length + 1 + 8 + 8 + traceId.length);
        key.put(nameBytes);
        key.put((byte) 0);
        key.putLong(NanoTimeUtil.toEpoch2000Nanos(timestamp));
        key.putLong(seq);
        key.put(traceId);
        key.flip();

        final PathwayRootDiscoveryEvent event = new PathwayRootDiscoveryEvent(
                UUID.randomUUID().toString(), pathwayName, PathwayEventType.MUTATION, timestamp);
        eventsSerde.writePathwayEvent(event, valBuf -> db.getPathwayEvents().insert(writer, key, valBuf));
    }

    private static LmdbKeyRange prefixRange(final String pathwayName) {
        final byte[] nameBytes = pathwayName.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer prefix = ByteBuffer.allocateDirect(nameBytes.length + 1);
        prefix.put(nameBytes).put((byte) 0).flip();
        return LmdbKeyRange.builder().prefix(prefix).build();
    }

    private static String pathwayName(final ByteBuffer key) {
        final byte[] arr = new byte[key.remaining()];
        key.duplicate().get(arr);
        int zero = -1;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == 0) {
                zero = i;
                break;
            }
        }
        return new String(arr, 0, zero, StandardCharsets.UTF_8);
    }

    private static NanoTime nano(final long millis) {
        return NanoTime.ofMillis(millis);
    }

    private static byte[] traceId(final int n) {
        final byte[] id = new byte[16];
        id[15] = (byte) n;
        return id;
    }
}
