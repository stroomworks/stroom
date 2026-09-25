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

import stroom.bytebuffer.impl6.ByteBufferFactoryImpl;
import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.otel.trace.AnyValue;
import stroom.pathways.shared.otel.trace.KeyValue;
import stroom.pathways.shared.otel.trace.NanoDuration;
import stroom.pathways.shared.otel.trace.Span;
import stroom.pathways.shared.otel.trace.SpanKind;
import stroom.pathways.shared.otel.trace.Trace;
import stroom.pathways.shared.pathway.MutationType;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Pathway;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.pathways.shared.pathway.PathwayReplay;
import stroom.pathways.shared.pathway.PathwayUsage;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.trace.PathwaysDb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Several traces applied the way the consumer applies them: through {@link TraceProcessor}, against a
 * real store, sharing one transaction for the whole batch.
 *
 * <p>The other tests here build a history by calling {@link NodeMutatorImpl} straight and numbering
 * the changes themselves, which is not what the application does and so cannot see what the
 * application gets wrong. A batch commits once at the end, so a trace numbering its changes from what
 * is already stored has to see what the traces before it in the same batch wrote.
 */
class TestMutationsInOneBatch {

    private static final ByteBufferFactoryImpl BYTE_BUFFER_FACTORY = new ByteBufferFactoryImpl();
    private static final ByteBuffers BYTE_BUFFERS = new ByteBuffers(BYTE_BUFFER_FACTORY);
    private static final IgnoredAttributes NO_IGNORED = new IgnoredAttributes(List.of());

    private static final String OPERATION = "GitRepoPush.run";
    private static final String PING = "Ping";
    private static final String COMMIT = "Commit";
    private static final long BASE = 1_700_000_000_000_000_000L;
    private static final byte MUTATION_MARKER = 0;

    @Test
    void everyChangeIsKeptAndNumberedInOrder(@TempDir final Path dir) {
        final List<PathwayMutation> stored = applyBatch(dir,
                trace("t1", "GET", 20, PING),
                trace("t2", "POST", 5, PING, COMMIT),
                trace("t3", "PUT", 90, PING, COMMIT));

        assertThat(stored)
                .as("three traces that each taught the model something, none of them lost")
                .hasSizeGreaterThan(10);

        final List<Long> sequences = stored.stream().map(PathwayMutation::getSequence).toList();
        final List<Long> expected = new ArrayList<>();
        for (long i = 1; i <= stored.size(); i++) {
            expected.add(i);
        }
        assertThat(sequences)
                .as("counted on across the batch, not started again by every trace")
                .isEqualTo(expected);
    }

    @Test
    void thePathwayComingIntoBeingIsRecorded(@TempDir final Path dir) {
        final List<PathwayMutation> stored = applyBatch(dir, trace("t1", "GET", 20, PING));

        assertThat(stored.getFirst().getType())
                .as("the first thing that happened to this model was the model appearing")
                .isEqualTo(MutationType.PATHWAY_ADDED);
        assertThat(stored.getFirst().getNodeUuid()).isNotBlank();
    }

    @Test
    void theStoredHistoryWindsTheStoredModelBackToNothing(@TempDir final Path dir) {
        final List<PathwayMutation> stored = applyBatch(dir,
                trace("t1", "GET", 20, PING),
                trace("t2", "POST", 5, PING, COMMIT));

        assertThat(PathwayReplay.rewind(readPathway(dir).getRoot(), stored))
                .as("what was stored is enough to take the model apart again")
                .isNull();
    }

    @Test
    void everyTraceIsCounted(@TempDir final Path dir) {
        applyBatch(dir,
                trace("t1", "GET", 20, PING),
                trace("t2", "GET", 20, PING),
                trace("t3", "GET", 20, PING));

        assertThat(readPathway(dir).getTimesUsed())
                .as("counted for every trace that took the route, not only the ones that taught it "
                    + "something — the second and third here are the same shape as the first")
                .isEqualTo(3);
    }

    @Test
    void theTraceThatCreatedThePathwayIsNotCountedAsAnUpdate(@TempDir final Path dir) {
        applyBatch(dir, trace("t1", "GET", 20, PING));

        final Pathway pathway = readPathway(dir);
        assertThat(pathway.getTimesUsed()).isEqualTo(1);
        assertThat(pathway.getTimesUpdated())
                .as("it taught the model everything it knew, which is not the model being updated")
                .isZero();
    }

    @Test
    void onlyTracesThatTaughtTheModelSomethingAreCountedAsUpdates(@TempDir final Path dir) {
        applyBatch(dir,
                trace("t1", "GET", 20, PING),
                trace("t2", "GET", 20, PING),
                trace("t3", "POST", 90, PING, COMMIT),
                trace("t4", "POST", 90, PING, COMMIT));

        final Pathway pathway = readPathway(dir);
        assertThat(pathway.getTimesUsed())
                .as("every trace took the route")
                .isEqualTo(4);
        assertThat(pathway.getTimesUpdated())
                .as("t1 created it and t2 and t4 repeated what was already known, leaving t3")
                .isEqualTo(1);
    }

    @Test
    void nodesAndConstraintsCountEverySpanThatReachedThem(@TempDir final Path dir) {
        applyBatch(dir,
                trace("t1", "GET", 20, PING, PING, COMMIT),
                trace("t2", "GET", 20, PING, PING, COMMIT));

        final PathNode ping = child(readPathway(dir).getRoot(), PING);
        assertThat(ping.getTimesUsed())
                .as("two spans of this name in each of two traces, counted per span not per trace")
                .isEqualTo(4);
        assertThat(ping.getLastUsedTime()).isNotNull();

        assertThat(ping.getConstraints().get("duration").getTimesUsed())
                .as("every span carried a duration, so the constraint saw as many values")
                .isEqualTo(4);
        assertThat(ping.getConstraints().get("occurrences").getTimesUsed())
                .as("worked out once per trace from how many spans there were, not once per span")
                .isEqualTo(2);
    }

    @Test
    void aNodeNoTraceReachedIsNotCounted(@TempDir final Path dir) {
        // The second trace has no Commit, so the model keeps the node and records that it happened no
        // times. Reaching a node and recording its absence are not the same thing.
        applyBatch(dir,
                trace("t1", "GET", 20, PING, COMMIT),
                trace("t2", "GET", 20, PING));

        assertThat(child(readPathway(dir).getRoot(), COMMIT).getTimesUsed()).isEqualTo(1);
        assertThat(child(readPathway(dir).getRoot(), PING).getTimesUsed()).isEqualTo(2);
    }

    @Test
    void windingBackKeepsTheCounts(@TempDir final Path dir) {
        final List<PathwayMutation> stored = applyBatch(dir,
                trace("t1", "GET", 20, PING),
                trace("t2", "POST", 90, PING));

        final PathNode current = readPathway(dir).getRoot();
        final List<PathwayMutation> last = new ArrayList<>();
        for (final PathwayMutation mutation : stored) {
            if (mutation.getSequence() == stored.get(stored.size() - 1).getSequence()) {
                last.add(mutation);
            }
        }

        final PathNode wound = PathwayReplay.rewind(current, last);
        assertThat(child(wound, PING).getConstraints().get("duration").getTimesUsed())
                .as("how often a constraint was used is what the model holds now, not something the "
                    + "changes describe, so it is not wound back with the value")
                .isEqualTo(child(current, PING).getConstraints().get("duration").getTimesUsed());
    }

    private static PathNode child(final PathNode parent, final String name) {
        for (final PathNode child : parent.getChildren()) {
            if (name.equals(child.getName())) {
                return child;
            }
        }
        throw new AssertionError("No child called " + name);
    }

    @Test
    void oneUsageReadingIsKeptPerChangingTrace(@TempDir final Path dir) {
        // t2 and t4 repeat what is already known, so they teach the model nothing and leave nothing.
        applyBatch(dir,
                trace("t1", "GET", 20, PING),
                trace("t2", "GET", 20, PING),
                trace("t3", "POST", 90, PING, COMMIT),
                trace("t4", "POST", 90, PING, COMMIT));

        final List<PathwayUsage> readings = readUsage(dir);
        assertThat(readings)
                .as("one per trace that changed the model, not one per change it made")
                .hasSize(2);

        final PathwayUsage last = readings.get(readings.size() - 1);
        assertThat(last.getNodes())
                .as("every node of the model, so a replay can say how busy any of them was")
                .hasSize(3);
        assertThat(last.getNodes().get(0).getTimesUsed())
                .as("the root, reached by all three traces applied by the time this was taken")
                .isEqualTo(3);
    }

    private static List<PathwayUsage> readUsage(final Path dir) {
        final PathwaySerde serde = new PathwaySerde(BYTE_BUFFER_FACTORY);
        final List<PathwayUsage> readings = new ArrayList<>();
        try (final PathwaysDb db = PathwaysDb.create(dir, BYTE_BUFFERS, true)) {
            db.getMutations().iterate((key, val) -> {
                if (key.get(key.limit() - Long.BYTES - 1) != MUTATION_MARKER) {
                    readings.add(serde.readUsage(val));
                }
            });
        }
        return readings;
    }

    // Applies each trace through TraceProcessor on one writer, as a batch does, then reads back what
    // was stored rather than what was recorded in memory.
    private static List<PathwayMutation> applyBatch(final Path dir, final Trace... traces) {
        final PathwaySerde serde = new PathwaySerde(BYTE_BUFFER_FACTORY);
        try (final PathwaysDb db = PathwaysDb.create(dir, BYTE_BUFFERS, false);
                final LmdbWriter writer = db.createWriter()) {
            final TraceProcessor processor = new TraceProcessor(BYTE_BUFFERS, serde, NO_IGNORED);
            for (final Trace trace : traces) {
                processor.processTrace(writer,
                        db,
                        trace.getTraceId().getBytes(StandardCharsets.UTF_8),
                        id -> Optional.of(trace),
                        doc(),
                        (severity, message) -> {
                        });
            }
            writer.commit();
        }

        final List<PathwayMutation> stored = new ArrayList<>();
        try (final PathwaysDb db = PathwaysDb.create(dir, BYTE_BUFFERS, true)) {
            // The table holds a usage reading per changing trace as well as the changes themselves,
            // marked apart in the key. Reading one as the other gets nonsense, so only the changes are
            // taken here — which is what the prefix the application reads by does for it.
            db.getMutations().iterate((key, val) -> {
                if (key.get(key.limit() - Long.BYTES - 1) == MUTATION_MARKER) {
                    stored.add(serde.readMutation(val));
                }
            });
        }
        return stored;
    }

    private static Pathway readPathway(final Path dir) {
        final PathwaySerde serde = new PathwaySerde(BYTE_BUFFER_FACTORY);
        final Pathway[] found = new Pathway[1];
        try (final PathwaysDb db = PathwaysDb.create(dir, BYTE_BUFFERS, true)) {
            db.getPathways().iterate((key, val) -> found[0] = serde.readPathway(val));
        }
        return found[0];
    }

    private static PathwaysDoc doc() {
        return PathwaysDoc.builder()
                .uuid(UUID.randomUUID().toString())
                .name("Test Pathways")
                .allowPathwayCreation(true)
                .allowPathwayMutation(true)
                .allowConstraintCreation(true)
                .allowConstraintMutation(true)
                .build();
    }

    private static Trace trace(final String id,
                               final String method,
                               final int rootMillis,
                               final String... childNames) {
        final Span root = span(OPERATION, "r0", "", 0, rootMillis, method);
        final List<Span> children = new ArrayList<>(childNames.length);
        for (int i = 0; i < childNames.length; i++) {
            children.add(span(childNames[i], "c" + i, "r0", i + 1, 1, null));
        }

        final Map<String, List<Span>> byParent = new HashMap<>();
        byParent.put("", List.of(root));
        if (!children.isEmpty()) {
            byParent.put("r0", children);
        }
        return new Trace(id, byParent);
    }

    private static Span span(final String name,
                             final String spanId,
                             final String parentSpanId,
                             final int startMillis,
                             final int durationMillis,
                             final String method) {
        final long start = BASE + NanoDuration.ofMillis(startMillis).getNanos();
        final Span.Builder builder = Span.builder()
                .name(name)
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .kind(SpanKind.SPAN_KIND_INTERNAL)
                .startTimeUnixNano(Long.toString(start))
                .endTimeUnixNano(Long.toString(start + NanoDuration.ofMillis(durationMillis).getNanos()));
        if (method != null) {
            builder.attributes(List.of(KeyValue.builder()
                    .key("http.method")
                    .value(new AnyValue(method, null, null, null, null, null, null))
                    .build()));
        }
        return builder.build();
    }
}
