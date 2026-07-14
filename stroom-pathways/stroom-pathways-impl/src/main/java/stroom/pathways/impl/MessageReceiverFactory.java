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

import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.data.store.api.OutputStreamProvider;
import stroom.data.store.api.Store;
import stroom.data.store.api.Target;
import stroom.meta.api.MetaProperties;
import stroom.pathways.impl.events.PathwayEvent;
import stroom.pathways.shared.PathwaysDoc;
import stroom.planb.impl.db.LmdbWriter;
import stroom.planb.impl.db.trace.NanoTimeUtil;
import stroom.planb.impl.db.trace.PathwayEventsDb;
import stroom.planb.impl.db.trace.PathwaysDb.SimpleDb;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.Severity;

import com.google.inject.Inject;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class MessageReceiverFactory {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(MessageReceiverFactory.class);

    private static final byte[] EMPTY_TRACE_ID = new byte[0];

    private final Store streamStore;
    private final PathwayEventsSerde pathwayEventsSerde;
    private final ByteBuffers byteBuffers;


    @Inject
    public MessageReceiverFactory(final Store streamStore,
                                  final PathwayEventsSerde pathwayEventsSerde,
                                  final ByteBuffers byteBuffers) {
        this.streamStore = streamStore;
        this.pathwayEventsSerde = pathwayEventsSerde;
        this.byteBuffers = byteBuffers;
    }

    /** A buffered event together with the trace id it was generated from and its per-run sequence. */
    private record BufferedEvent(byte[] traceId, long seq, PathwayEvent event) {

    }

    public void create(final PathwayEventsDb eventsDb,
                       final LmdbWriter lmdbWriter,
                       final String feedName,
                       final Consumer<MessageReceiver> messageReceiverConsumer) {
        final MetaProperties metaProperties = MetaProperties.builder()
                .feedName(feedName)
                .typeName("Report")
//                .pipelineUuid(reportDoc.getUuid())
                .build();
        try {
            try (final Target streamTarget = streamStore.openTarget(metaProperties)) {
                try (final OutputStreamProvider outputStreamProvider = streamTarget.next()) {
                    try (final Writer writer = new OutputStreamWriter(outputStreamProvider.get())) {
                        class BufferingMessageReceiver implements MessageReceiver {
                            private final Map<String, List<BufferedEvent>> buffer = new HashMap<>();
                            private int eventCount = 0;
                            // Monotonic within this processing run. Combined with the source trace id in the
                            // key it makes every event key unique - a given trace is processed at most once,
                            // so its (traceId, seq) pairs can never recur across runs. This replaces the old
                            // per-run sequenceId that reset to 0 each run and silently overwrote earlier events.
                            private long seq = 0;
                            private byte[] currentTraceId = EMPTY_TRACE_ID;
                            private static final int MAX_BUFFER_SIZE = 10000;

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

                        final BufferingMessageReceiver receiver = new BufferingMessageReceiver();
                        messageReceiverConsumer.accept(receiver);
                        receiver.flush();
                    }

//                        StreamUtil.streamToStream(inputStream, outputStreamProvider.get());
//
//                        try (final Writer writer = new OutputStreamWriter(outputStreamProvider.get(
//                                StreamTypeNames.META))) {
//                            write(writer, "ReportName", reportDoc.getName());
//                            write(writer,
//                                    "ReportDescription",
//                                    reportDoc.getDescription() != null
//                                            ? reportDoc.getDescription().replaceAll("\n", "")
//                                            : "");
//                            write(writer, "ExecutionTime",
//                                    DateUtil.createNormalDateTimeString(executionTime));
//                            write(writer, "EffectiveExecutionTime",
//                                    DateUtil.createNormalDateTimeString(effectiveExecutionTime));
//                        }
                }
            }
        } catch (final IOException | RuntimeException e) {
            LOGGER.error(e::getMessage, e);
        }
    }
}
