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
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.otel.trace.AnyValue;
import stroom.pathways.shared.otel.trace.KeyValue;
import stroom.pathways.shared.otel.trace.NanoDuration;
import stroom.pathways.shared.otel.trace.Span;
import stroom.pathways.shared.otel.trace.SpanKind;
import stroom.pathways.shared.otel.trace.Trace;
import stroom.pathways.shared.pathway.MutationType;
import stroom.pathways.shared.pathway.NamePathKey;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.pathways.shared.pathway.StringSet;
import stroom.pathways.shared.pathway.StringValue;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record of what each trace did to the model.
 *
 * <p>Enough is kept to replay the change rather than only to report it: where in the model it landed,
 * what the constraint held before and what it holds now. The trace and span are on every record so a
 * replay can say what caused each step.
 */
class TestPathwayMutations {

    private static final ByteBufferFactoryImpl BYTE_BUFFER_FACTORY = new ByteBufferFactoryImpl();
    private static final String OPERATION = "ProcessorTaskCreatorJob.run";
    private static final String PING = "Ping";
    private static final String TRACE_ID = "0a0b0c0d0e0f00010203040506070809";
    private static final long BASE = 1_700_000_000_000_000_000L;

    @Test
    void theFirstTraceRecordsThePathwayAndItsSteps() {
        final NodeMutatorImpl mutator = mutator();
        mutator.process(trace("GET", PING), key(), null, quiet(), doc());

        assertThat(mutator.getMutations()).extracting(PathwayMutation::getType)
                .as("the pathway itself, then everything learnt under it")
                .startsWith(MutationType.PATHWAY_ADDED)
                .contains(MutationType.NODE_ADDED, MutationType.CONSTRAINT_ADDED);

        assertThat(mutator.getMutations().getFirst().getPath())
                .as("named like any node, so a replay can put the root back")
                .containsExactly(OPERATION);

        assertThat(mutator.getMutations()).allSatisfy(mutation -> {
            assertThat(mutation.getTraceId()).isEqualTo(TRACE_ID);
            assertThat(mutation.getSpanId()).isNotBlank();
            assertThat(mutation.getTime()).isNotNull();
        });
    }

    @Test
    void aWideningCarriesWhatItWasAndWhatItBecame() {
        final PathNode root = mutator().process(trace("GET", PING), key(), null, quiet(), doc());

        final NodeMutatorImpl mutator = mutator();
        mutator.process(trace("POST", PING), key(), root, quiet(), doc());

        final PathwayMutation widened = mutator.getMutations().stream()
                .filter(m -> "attribute.http.method".equals(m.getConstraint()))
                .findFirst()
                .orElseThrow();

        assertThat(widened.getType()).isEqualTo(MutationType.CONSTRAINT_SET_EXPANDED);
        assertThat(widened.getOldValue()).isEqualTo(new StringValue("GET"));
        assertThat(widened.getNewValue()).isEqualTo(new StringSet(Set.of("GET", "POST")));
        assertThat(widened.getPath())
                .as("the root node is where this attribute lives")
                .containsExactly(OPERATION);
    }

    @Test
    void aNewStepNamesWhereItWasAdded() {
        final PathNode root = mutator().process(trace("GET", PING), key(), null, quiet(), doc());

        final NodeMutatorImpl mutator = mutator();
        mutator.process(trace("GET", PING, "Commit"), key(), root, quiet(), doc());

        final PathwayMutation added = mutator.getMutations().stream()
                .filter(m -> MutationType.NODE_ADDED.equals(m.getType()))
                .findFirst()
                .orElseThrow();

        assertThat(added.getPath())
                .as("named from the root down, so a replay knows what to draw")
                .containsExactly(OPERATION, "Commit");
    }

    @Test
    void aRangeRecordsWhichEndOfItMoved() {
        // One duration seen, so the constraint holds a single time. A shorter one pushes the bottom of
        // a range down; a longer one then pushes the top up.
        PathNode root = mutator().process(trace("GET", 20, PING), key(), null, quiet(), doc());

        final NodeMutatorImpl shorter = mutator();
        root = shorter.process(trace("GET", 5, PING), key(), root, quiet(), doc());
        assertThat(durationChange(shorter)).isEqualTo(MutationType.CONSTRAINT_MIN_EXPANDED);

        final NodeMutatorImpl longer = mutator();
        longer.process(trace("GET", 90, PING), key(), root, quiet(), doc());
        assertThat(durationChange(longer)).isEqualTo(MutationType.CONSTRAINT_MAX_EXPANDED);
    }

    @Test
    void aValueJoiningASetSaysSo() {
        final PathNode root = mutator().process(trace("GET", 20, PING), key(), null, quiet(), doc());

        final NodeMutatorImpl mutator = mutator();
        mutator.process(trace("POST", 20, PING), key(), root, quiet(), doc());

        assertThat(methodChange(mutator))
                .as("one value became a set of two")
                .isEqualTo(MutationType.CONSTRAINT_SET_EXPANDED);
    }

    @Test
    void tooManyValuesToListSaysTheConstraintGaveUp() {
        // The first value is held on its own and the next nine fill the set to MAX_SET_SIZE, so the
        // eleventh value seen is the one that takes it past listing them.
        PathNode root = null;
        for (int i = 0; i < 10; i++) {
            root = mutator().process(trace("method-" + i, 20, PING), key(), root, quiet(), doc());
        }

        final NodeMutatorImpl mutator = mutator();
        mutator.process(trace("method-10", 20, PING), key(), root, quiet(), doc());

        assertThat(methodChange(mutator))
                .as("past the point of listing them, so it stopped saying anything")
                .isEqualTo(MutationType.CONSTRAINT_GENERALISED);
    }

    @Test
    void anAttributeNamedInTheConfigurationSaysItWasIgnored() {
        final IgnoredAttributes ignored = new IgnoredAttributes(List.of("http.method"));
        final NodeMutatorImpl mutator = new NodeMutatorImpl(
                new CanonicalSpanOrder(doc().getTemporalOrderingTolerance()), ignored);
        mutator.process(trace("GET", 20, PING), key(), null, quiet(), doc());

        assertThat(methodChange(mutator))
                .as("configured away, which is not the same as a type it could not handle")
                .isEqualTo(MutationType.CONSTRAINT_IGNORED);
    }

    @Test
    void aTraceThatTeachesNothingRecordsNothing() {
        final PathNode root = mutator().process(trace("GET", PING), key(), null, quiet(), doc());

        final NodeMutatorImpl mutator = mutator();
        mutator.process(trace("GET", PING), key(), root, quiet(), doc());

        assertThat(mutator.getMutations()).isEmpty();
        assertThat(mutator.isChanged()).isFalse();
    }

    @Test
    void aMutationSurvivesTheRoundTrip() {
        final NodeMutatorImpl mutator = mutator();
        mutator.process(trace("GET", PING), key(), null, quiet(), doc());
        final PathwaySerde serde = new PathwaySerde(BYTE_BUFFER_FACTORY);

        for (final PathwayMutation written : mutator.getMutations()) {
            final PathwayMutation[] read = new PathwayMutation[1];
            serde.writeMutation(written, buffer -> {
                final ByteBuffer copy = ByteBuffer.allocateDirect(buffer.remaining());
                copy.put(buffer).flip();
                read[0] = serde.readMutation(copy);
            });
            assertThat(read[0]).isEqualTo(written);
        }
    }

    private static MutationType methodChange(final NodeMutatorImpl mutator) {
        return mutator.getMutations().stream()
                .filter(m -> "attribute.http.method".equals(m.getConstraint()))
                .findFirst()
                .orElseThrow()
                .getType();
    }

    private static MutationType durationChange(final NodeMutatorImpl mutator) {
        return mutator.getMutations().stream()
                .filter(m -> "duration".equals(m.getConstraint()))
                .findFirst()
                .orElseThrow()
                .getType();
    }

    private static NodeMutatorImpl mutator() {
        return new NodeMutatorImpl(
                new CanonicalSpanOrder(doc().getTemporalOrderingTolerance()),
                new IgnoredAttributes(List.of()));
    }

    private static NamePathKey key() {
        return new NamePathKey(OPERATION);
    }

    private static MessageReceiver quiet() {
        return (severity, message) -> {
        };
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

    private static Trace trace(final String method, final String... childNames) {
        return trace(method, 1, childNames);
    }

    private static Trace trace(final String method, final int rootMillis, final String... childNames) {
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
        return new Trace(TRACE_ID, byParent);
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
