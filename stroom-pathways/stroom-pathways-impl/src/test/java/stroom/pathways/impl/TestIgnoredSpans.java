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

import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.otel.trace.NanoDuration;
import stroom.pathways.shared.otel.trace.Span;
import stroom.pathways.shared.otel.trace.SpanKind;
import stroom.pathways.shared.otel.trace.Trace;
import stroom.pathways.shared.pathway.NamePathKey;
import stroom.pathways.shared.pathway.PathNode;
import stroom.planb.impl.dao.trace.CanonicalSpanOrder;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spans that are not part of the route, named by {@link PathwaysConfig#getIgnoredSpanNames()}.
 *
 * <p>For work the runtime does when it feels like it rather than when the code says to. A connection
 * pool checking a connection it has not used for a while is the case this was built for: it appears
 * on the first use of each pooled connection and nowhere else, so a trace where it happened took the
 * same route as one where it did not.
 */
class TestIgnoredSpans {

    private static final IgnoredAttributes NO_IGNORED = new IgnoredAttributes(List.of());
    private static final String OPERATION = "job.run";
    private static final String PING = "Ping";
    private static final long BASE = 1_700_000_000_000_000_000L;

    @Test
    void nothingIsIgnoredByDefault() {
        assertThat(new IgnoredSpans(new PathwaysConfig().getIgnoredSpanNames()).test(PING)).isFalse();
    }

    @Test
    void anIgnoredSpanIsLeftOutOfTheRoute() {
        assertThat(routeOf(List.of(PING), trace("a", PING, "b"))).isEqualTo("job.run[a b]");
    }

    @Test
    void namingNothingLeavesTheRouteAsItRan() {
        assertThat(routeOf(List.of(), trace("a", PING, "b"))).isEqualTo("job.run[a " + PING + " b]");
    }

    @Test
    void anIgnoredSpanIsStillANodeOfTheModel() {
        // So the reader can still see it happened and how often, and so a trace holding one still
        // matches the pathway — the route is the only thing it is kept out of.
        final PathNode root = model(List.of(PING), trace("a", PING, "b"));

        assertThat(root.getChildren().stream().map(PathNode::getName))
                .containsExactlyInAnyOrder("a", PING, "b");
    }

    @Test
    void twoTracesDifferingOnlyByAnIgnoredSpanTakeTheSameRoute() {
        // The whole point. Both are folded into one model, because the order steps settle into is
        // fixed for a pathway but says nothing across two built from nothing.
        final NodeMutatorImpl first = mutator(List.of(PING));
        final PathNode root = first.process(trace("a", PING, "b"), new NamePathKey(OPERATION), null,
                quiet(), doc());

        final NodeMutatorImpl second = mutator(List.of(PING));
        second.process(trace("a", "b"), new NamePathKey(OPERATION), root, quiet(), doc());

        assertThat(RouteShapeText.of(second.getRouteShape(), root))
                .isEqualTo(RouteShapeText.of(first.getRouteShape(), root));
    }

    @Test
    void whatAnIgnoredSpanRanGoesWithIt() {
        // A step that is not on the route cannot have steps of its own that are. The model still
        // holds them, so nothing is hidden from a reader looking at the node.
        final NodeMutatorImpl mutator = mutator(List.of(PING));
        final PathNode root = mutator.process(nested("a", PING, "q"), new NamePathKey(OPERATION),
                null, quiet(), doc());

        assertThat(RouteShapeText.of(mutator.getRouteShape(), root)).isEqualTo("job.run[a]");
        assertThat(root.getChildren().stream()
                .filter(child -> PING.equals(child.getName()))
                .flatMap(child -> child.getChildren().stream())
                .map(PathNode::getName))
                .as("the model still holds what it ran")
                .containsExactly("q");
    }

    @Test
    void aNameMayBeGivenWithAWildcard() {
        final IgnoredSpans ignored = new IgnoredSpans(List.of("Pin*"));

        assertThat(ignored.test(PING)).isTrue();
        assertThat(ignored.test("Pin")).isTrue();
        assertThat(ignored.test("ing"))
                .as("the pattern covers the whole name, so it is not a suffix match")
                .isFalse();
        assertThat(ignored.test("Commit")).isFalse();
    }

    // ---------------------------------------------------------------------------------------------

    private static String routeOf(final List<String> ignored, final Trace trace) {
        final NodeMutatorImpl mutator = mutator(ignored);
        final PathNode root = mutator.process(trace, new NamePathKey(OPERATION), null, quiet(), doc());
        return RouteShapeText.of(mutator.getRouteShape(), root);
    }

    private static PathNode model(final List<String> ignored, final Trace trace) {
        return mutator(ignored).process(trace, new NamePathKey(OPERATION), null, quiet(), doc());
    }

    private static NodeMutatorImpl mutator(final List<String> ignored) {
        return new NodeMutatorImpl(new CanonicalSpanOrder(doc().getTemporalOrderingTolerance()),
                NO_IGNORED, new IgnoredSpans(ignored));
    }

    private static MessageReceiver quiet() {
        return (severity, message) -> {
        };
    }

    // Children spaced wider than they are long, so none of them overlap and the sequence given is the
    // sequence the route records.
    private static Trace trace(final String... childNames) {
        final List<Span> children = new ArrayList<>(childNames.length);
        for (int i = 0; i < childNames.length; i++) {
            children.add(span(childNames[i], "c" + i, "r0", (i * 3) + 1));
        }
        final Map<String, List<Span>> byParent = new HashMap<>();
        byParent.put("", List.of(span(OPERATION, "r0", "", 0)));
        byParent.put("r0", children);
        return new Trace("0a0b0c0d0e0f00010203040506070809", byParent);
    }

    // As trace, but the last name given is run by the one before it rather than beside it.
    private static Trace nested(final String first, final String parent, final String child) {
        final Map<String, List<Span>> byParent = new HashMap<>();
        byParent.put("", List.of(span(OPERATION, "r0", "", 0)));
        byParent.put("r0", List.of(span(first, "c0", "r0", 1), span(parent, "c1", "r0", 4)));
        byParent.put("c1", List.of(span(child, "c2", "c1", 4)));
        return new Trace("0a0b0c0d0e0f00010203040506070809", byParent);
    }

    private static Span span(final String name,
                             final String spanId,
                             final String parentSpanId,
                             final int millisIn) {
        final long start = BASE + NanoDuration.ofMillis(millisIn).getNanos();
        return Span.builder()
                .name(name)
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .kind(SpanKind.SPAN_KIND_INTERNAL)
                .startTimeUnixNano(Long.toString(start))
                .endTimeUnixNano(Long.toString(start + NanoDuration.ofMillis(1).getNanos()))
                .build();
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
}
