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

import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.pathways.impl.events.PathwayEvent;
import stroom.pathways.shared.PathwaysDoc;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.trace.NanoTimeUtil;
import stroom.planb.impl.dao.trace.PathwayEventsDb;
import stroom.planb.impl.dao.trace.PathwaysDb.SimpleDb;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.Severity;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A {@link MessageReceiver} that streams log lines to an open info-feed {@link Writer} and buffers
 * pathway events in memory, flushing them in batches to the per-shard {@link PathwayEventsDb}.
 *
 * <p>The caller (see {@link MessageReceiverFactory#create}) owns the lifecycle of the supplied
 * {@code writer}, {@code eventsDb} and {@code lmdbWriter}: this receiver must only be used while
 * they are open, and {@link #flush()} must be called once the caller has finished feeding events.
 */
final class BufferingMessageReceiver implements MessageReceiver {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(BufferingMessageReceiver.class);

    private static final byte[] EMPTY_TRACE_ID = new byte[0];
    private static final int MAX_BUFFER_SIZE = 10000;

    /** A buffered event together with the trace id it was generated from and its per-run sequence. */
    private record BufferedEvent(byte[] traceId, long seq, PathwayEvent event) {

    }

    private final Writer writer;
    private final PathwayEventsDb eventsDb;
    private final LmdbWriter lmdbWriter;
    private final ByteBuffers byteBuffers;
    private final PathwayEventsSerde pathwayEventsSerde;

    private final Map<String, List<BufferedEvent>> buffer = new HashMap<>();
    private int eventCount = 0;
    // Monotonic within this processing run. Combined with the source trace id in the
    // key it makes every event key unique - a given trace is processed at most once,
    // so its (traceId, seq) pairs can never recur across runs. This replaces the old
    // per-run sequenceId that reset to 0 each run and silently overwrote earlier events.
    private long seq = 0;
    private byte[] currentTraceId = EMPTY_TRACE_ID;

    BufferingMessageReceiver(final Writer writer,
                             final PathwayEventsDb eventsDb,
                             final LmdbWriter lmdbWriter,
                             final ByteBuffers byteBuffers,
                             final PathwayEventsSerde pathwayEventsSerde) {
        this.writer = writer;
        this.eventsDb = eventsDb;
        this.lmdbWriter = lmdbWriter;
        this.byteBuffers = byteBuffers;
        this.pathwayEventsSerde = pathwayEventsSerde;
    }

    @Override
    public void log(final Severity severity, final Supplier<String> message) {
        try {
            writer.write(severity.getDisplayValue());
            writer.write(": ");
            writer.write(message.get());
            writer.write("\n");
        } catch (final IOException | RuntimeException e) {
            LOGGER.error(e::getMessage, e);
        }
    }

    @Override
    public void beginTrace(final byte[] traceId) {
        this.currentTraceId = traceId != null ? traceId : EMPTY_TRACE_ID;
    }

    @Override
    public void event(final PathwaysDoc pathwaysDoc,
                      final String pathwayName,
                      final PathwayEvent event) {
        buffer.computeIfAbsent(pathwayName, k -> new ArrayList<>())
                .add(new BufferedEvent(currentTraceId, seq++, event));
        eventCount++;
        if (eventCount >= MAX_BUFFER_SIZE) {
            flush();
        }
    }

    public void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        try {
            final SimpleDb pathwayEvents = eventsDb.getPathwayEvents();
            for (final Map.Entry<String, List<BufferedEvent>> entry : buffer.entrySet()) {
                final byte[] pathBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
                for (final BufferedEvent buffered : entry.getValue()) {
                    final byte[] traceId = buffered.traceId();
                    // Key: <pathwayName>\0 <timestampNanos:8B> <seq:8B> <traceId>
                    // Time-first (after the name prefix) so a prefix scan of a pathway
                    // returns its events in time order; the trailing trace id makes the
                    // key globally unique. All longs are big-endian so LMDB's byte
                    // ordering matches ascending time.
                    final int keyLen = pathBytes.length + 1 + 8 + 8 + traceId.length;
                    byteBuffers.use(keyLen, keyBuf -> {
                        keyBuf.put(pathBytes);
                        keyBuf.put((byte) 0);
                        keyBuf.putLong(NanoTimeUtil.toEpoch2000Nanos(
                                buffered.event().getTimestamp()));
                        keyBuf.putLong(buffered.seq());
                        keyBuf.put(traceId);

                        pathwayEventsSerde.writePathwayEvent(buffered.event(), valBuf ->
                                pathwayEvents.insert(lmdbWriter, keyBuf.flip(), valBuf));
                    });
                }
            }
        } catch (final RuntimeException e) {
            LOGGER.error("Failed to flush PathwayEvent buffer to LMDB: " + e.getMessage(), e);
        } finally {
            buffer.clear();
            eventCount = 0;
        }
    }
}
