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

import stroom.bytebuffer.impl6.ByteBufferFactory;
import stroom.bytebuffer.impl6.ByteBufferFactoryImpl;
import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.lmdb.stream.LmdbKeyRange;
import stroom.pathways.impl.events.PathwayEvent;
import stroom.pathways.shared.FindTraceCriteria;
import stroom.pathways.shared.GetTraceRequest;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.TracePersistence;
import stroom.pathways.shared.TraceWriter;
import stroom.pathways.shared.otel.trace.Span;
import stroom.pathways.shared.otel.trace.Trace;
import stroom.pathways.shared.otel.trace.TraceRoot;
import stroom.planb.impl.db.LmdbWriter;
import stroom.planb.impl.db.trace.NanoTimeUtil;
import stroom.planb.impl.db.trace.PathwayEventsDb;
import stroom.planb.impl.db.trace.PathwaysDb;
import stroom.planb.impl.db.trace.TraceDb;
import stroom.planb.shared.PlanBDoc;
import stroom.planb.shared.TraceSettings;
import stroom.util.date.DateUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.ResultPage;
import stroom.util.shared.Severity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

public class TestPathwayProcessor {

    private static final ByteBufferFactory BYTE_BUFFER_FACTORY = new ByteBufferFactoryImpl();
    private static final ByteBuffers BYTE_BUFFERS = new ByteBuffers(BYTE_BUFFER_FACTORY);
    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(TestPathwayProcessor.class);

    @Test
    void test(@TempDir final Path traceDir,
              @TempDir final Path pathwaysDir,
              @TempDir final Path eventsDir) {
        // Read in sample data and create a map of traces.
        final PlanBDoc planBDoc = PlanBDoc.builder()
                .uuid(UUID.randomUUID().toString())
                .settings(new TraceSettings.Builder().build())
                .build();
        try (final TraceDb traceDb = TraceDb
                .create(traceDir, BYTE_BUFFERS, BYTE_BUFFER_FACTORY, planBDoc, false)) {
            final TracePersistence tracesStore = new TracePersistence() {
                @Override
                public ResultPage<TraceRoot> findTraces(final FindTraceCriteria criteria) {
                    return traceDb.findTraces(criteria);
                }

                @Override
                public Trace getTrace(final GetTraceRequest request) {
                    return traceDb.getTrace(request);
                }

                @Override
                public TraceWriter createWriter() {
                    return new TraceWriter() {
                        private final LmdbWriter writer = traceDb.createWriter();

                        @Override
                        public void addSpan(final Span span) {
                            traceDb.insert(writer, span);
                        }

                        @Override
                        public void close() {
                            writer.close();
                        }
                    };
                }
            };

            // Load pathways DB for doc
            final PathwaysDb pathwaysDb = PathwaysDb
                    .create(pathwaysDir, BYTE_BUFFERS, false);

            // Insert traces
            new TraceLoader().load(tracesStore);

            // Build and test pathways
            testPathways(pathwaysDb, traceDb, eventsDir);

            // Insert one more trace
            new TraceLoader().addOneMore(tracesStore);

            // Build and test more pathways
            testPathways(pathwaysDb, traceDb, eventsDir);
        }
    }

    void testPathways(final PathwaysDb pathwaysDb,
                      final TraceDb traceDb,
                      final Path eventsBaseDir) {
        // Capture each generated event with the trace it came from and its arrival sequence, so it
        // can be persisted with the same key layout MessageReceiverFactory uses in production.
        final Map<String, List<CapturedEvent>> events = new HashMap<>();
        final MessageReceiver messageReceiver = new MessageReceiver() {
            private byte[] currentTraceId = new byte[0];
            private long seq = 0;

            public void log(final Severity severity, final Supplier<String> message) {
                switch (severity) {
                    case INFO -> LOGGER.info(message);
                    case WARNING -> LOGGER.warn(message);
                    case ERROR, FATAL_ERROR -> LOGGER.error(message);
                }
            }

            public void beginTrace(final byte[] traceId) {
                this.currentTraceId = traceId != null ? traceId : new byte[0];
            }

            public void event(final PathwaysDoc pathwaysDoc, final String pathwayName, final PathwayEvent event) {
                events.computeIfAbsent(pathwayName, e -> new ArrayList<>())
                        .add(new CapturedEvent(currentTraceId, seq++, event));
            }
        };

        try (final LmdbWriter writer = pathwaysDb.createWriter()) {
            final TraceProcessor traceProcessor =
                    new TraceProcessor(BYTE_BUFFERS, new PathwaySerde(BYTE_BUFFER_FACTORY));
            traceDb.iterateTraces((traceId, ignored) ->
                    traceProcessor.processTrace(writer,
                            pathwaysDb,
                            traceId,
                            traceDb::findTrace,
                            PathwaysDoc.builder()
                                    .uuid(UUID.randomUUID().toString())
                                    .name("Dummy DocRef")
                                    .allowPathwayCreation(true)
                                    .allowPathwayMutation(true)
                                    .allowConstraintCreation(true)
                                    .allowConstraintMutation(true)
                                    .build(),
                            messageReceiver));
            writer.commit();
        }

        final PathwayEventsSerde serde = new PathwayEventsSerde(
                BYTE_BUFFER_FACTORY,
                new PathwaySerde(BYTE_BUFFER_FACTORY)
        );
        // Events now live in their own (per-shard) store; use a fresh one for this run.
        final Path eventsDir = eventsBaseDir.resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectories(eventsDir);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        final PathwayEventsDb eventsDb = PathwayEventsDb.create(eventsDir, false);
        final PathwaysDb.SimpleDb pathwayEventsDb = eventsDb.getPathwayEvents();

        // Persist using the production key layout (see MessageReceiverFactory):
        //   <pathwayName>\0 <timestampNanos:8B> <seq:8B> <traceId>
        // Time-first (after the name prefix) so a per-pathway prefix scan returns events in time
        // order; the trailing trace id keeps keys unique across traces and processing runs.
        try (final LmdbWriter lmdbWriter = eventsDb.createWriter()) {
            events.keySet().stream().sorted().forEach(pathwayName -> {
                final byte[] pathBytes = pathwayName.getBytes(StandardCharsets.UTF_8);
                for (final CapturedEvent captured : events.get(pathwayName)) {
                    final byte[] traceId = captured.traceId();
                    final ByteBuffer key = ByteBuffer.allocateDirect(
                            pathBytes.length + 1 + 8 + 8 + traceId.length);
                    key.put(pathBytes);
                    key.put((byte) 0);
                    key.putLong(NanoTimeUtil.toEpoch2000Nanos(captured.event().getTimestamp()));
                    key.putLong(captured.seq());
                    key.put(traceId);
                    key.flip();

                    serde.writePathwayEvent(captured.event(), val ->
                            pathwayEventsDb.insert(lmdbWriter, key, val));
                }
            });
            lmdbWriter.commit();
        }

        final StringBuilder eventString = new StringBuilder();
        events.keySet().stream().sorted().forEach(pathwayName -> {
            final List<PathwayEvent> originalEvents = events.get(pathwayName).stream()
                    .map(CapturedEvent::event)
                    .toList();

            final Map<String, String> uuidToNameMap = new HashMap<>();
            for (final PathwayEvent event : originalEvents) {
                if (event.getNodeUuid() != null && event.getNodeName() != null) {
                    uuidToNameMap.put(event.getNodeUuid(), event.getNodeName());
                }
            }

            // Recall this pathway's events via a prefix scan, exactly as PathwaysProcessor does.
            final byte[] pathBytes = pathwayName.getBytes(StandardCharsets.UTF_8);
            final ByteBuffer prefix = ByteBuffer.allocateDirect(pathBytes.length + 1);
            prefix.put(pathBytes).put((byte) 0).flip();
            final List<PathwayEvent> deserializedEvents = new ArrayList<>();
            pathwayEventsDb.iterate(LmdbKeyRange.builder().prefix(prefix).build(), (keyBb, bb) -> {
                if (bb != null) {
                    deserializedEvents.add(serde.readPathwayEvent(bb, uuidToNameMap));
                }
            });

            assertThat(deserializedEvents).usingRecursiveComparison().isEqualTo(originalEvents);

            originalEvents.forEach(event -> {
                if (event.getDescription().length() > 1) {
                    eventString.append("\n");
                    eventString.append(
                            DateUtil.createNormalDateTimeString(NanoTimeUtil.toInstant(event.getTimestamp()))
                    );
                    eventString.append("\n");
                    eventString.append(event.getDescription());
                } else {
                    LOGGER.error("Event without description\n" + event.getClass().getSimpleName());
                }
                // Blank description means need more implementing, throw error
                assertThat(event.getDescription().length()).isGreaterThan(1);
            });
            eventString.append("\n\n\n\n\n\n");
        });
        LOGGER.info(eventString.toString());
        eventsDb.close();
    }

    /** A generated event together with the trace it came from and its arrival sequence. */
    private record CapturedEvent(byte[] traceId, long seq, PathwayEvent event) {
    }
}

