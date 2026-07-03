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
import stroom.pathways.impl.events.PathwayEvent;
import stroom.pathways.shared.FindTraceCriteria;
import stroom.pathways.shared.GetTraceRequest;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.TracePersistence;
import stroom.pathways.shared.TraceWriter;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.otel.trace.Span;
import stroom.pathways.shared.otel.trace.Trace;
import stroom.pathways.shared.otel.trace.TraceRoot;
import stroom.planb.impl.db.LmdbWriter;
import stroom.planb.impl.db.trace.NanoTimeUtil;
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

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

public class TestPathwayProcessor {

    private static final ByteBufferFactory BYTE_BUFFER_FACTORY = new ByteBufferFactoryImpl();
    private static final ByteBuffers BYTE_BUFFERS = new ByteBuffers(BYTE_BUFFER_FACTORY);
    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(TestPathwayProcessor.class);

    @Test
    void test(@TempDir final Path traceDir,
              @TempDir final Path pathwaysDir) {
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
            testPathways(pathwaysDb, traceDb);

            // Insert one more trace
            new TraceLoader().addOneMore(tracesStore);

            // Build and test more pathways
            testPathways(pathwaysDb, traceDb);
        }
    }

    void testPathways(final PathwaysDb pathwaysDb,
                      final TraceDb traceDb) {
        final Map<String, List<PathwayEvent>> events = new HashMap<>();
        final MessageReceiver messageReceiver = new MessageReceiver() {
            public void log(final Severity severity, final Supplier<String> message)
            {
                switch (severity) {
                    case INFO -> LOGGER.info(message);
                    case WARNING -> LOGGER.warn(message);
                    case ERROR, FATAL_ERROR -> LOGGER.error(message);
                }
            }
            public void event(final PathwaysDoc pathwaysDoc, final String pathwayName, final PathwayEvent event)
            {
                events.computeIfAbsent(pathwayName, _ -> new ArrayList<>());
                events.get(pathwayName).add(event);
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

        final PathwayEventsSerde serde = new PathwayEventsSerde(BYTE_BUFFER_FACTORY, new PathwaySerde(BYTE_BUFFER_FACTORY));
        final PathwaysDb.SimpleDb pathwayEventsDb = pathwaysDb.getPathwayEvents();

        try (final LmdbWriter lmdbWriter = pathwaysDb.createWriter()) {
            events.keySet().stream().sorted().forEach(pathwayName -> {
                final List<PathwayEvent> originalEvents = events.get(pathwayName);
                long sequenceId = 0;
                for (final PathwayEvent event : originalEvents) {
                    final AtomicReference<ByteBuffer> bufferRef = new AtomicReference<>();
                    serde.writePathwayEvent(event, bufferRef::set);

                    final ByteBuffer key = ByteBuffer.allocateDirect(100);
                    final byte[] pathBytes = pathwayName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    key.put(pathBytes);
                    key.put((byte) 0);
                    key.putLong(sequenceId++);
                    key.flip();

                    final ByteBuffer val = bufferRef.get();
                    pathwayEventsDb.insert(lmdbWriter, key, val);
                }
            });
            lmdbWriter.commit();
        }

        final StringBuilder eventString = new StringBuilder();
        events.keySet().stream().sorted().forEach(pathwayName -> {
            final List<PathwayEvent> originalEvents = events.get(pathwayName);

            final Map<String, String> uuidToNameMap = new HashMap<>();
            for (final PathwayEvent event : originalEvents) {
                if (event.getNodeUuid() != null && event.getNodeName() != null) {
                    uuidToNameMap.put(event.getNodeUuid(), event.getNodeName());
                }
            }

            final byte[] expectedPathBytes = pathwayName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            final List<PathwayEvent> deserializedEvents = new ArrayList<>();
            pathwayEventsDb.iterate((keyBb, bb) -> {
                if (bb == null) return;
                boolean matches = true;
                if (keyBb.limit() >= expectedPathBytes.length + 9) {
                    for (int i=0; i<expectedPathBytes.length; i++) {
                        if (keyBb.get(i) != expectedPathBytes[i]) {
                           matches = false; break;
                        }
                    }
                    if (keyBb.get(expectedPathBytes.length) != 0) matches = false;
                } else {
                    matches = false;
                }

                if (matches) {
                    deserializedEvents.add(serde.readPathwayEvent(bb, uuidToNameMap));
                }
            });

            assertThat(deserializedEvents).usingRecursiveComparison().isEqualTo(originalEvents);

            originalEvents.forEach(event -> {
                if (event.getDescription().length() > 1) {
                    eventString.append("\n");
                    eventString.append(DateUtil.createNormalDateTimeString(NanoTimeUtil.toInstant(event.getTimestamp())));
                    eventString.append("\n");
                    eventString.append(event.getDescription());
                } else {
                    LOGGER.error("Event without description\n" + event.getClass().getSimpleName());
                }
                assertThat(event.getDescription().length()).isGreaterThan(1); // Blank description means need more implementing
            });
            eventString.append("\n\n\n\n\n\n");
        });
        LOGGER.info(eventString.toString());
    }
}

