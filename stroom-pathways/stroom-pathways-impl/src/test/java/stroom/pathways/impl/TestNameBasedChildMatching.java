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
import stroom.pathways.shared.pathway.Constraint;
import stroom.pathways.shared.pathway.IntegerValue;
import stroom.pathways.shared.pathway.NamePathKey;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.StringValue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a trace's child spans line up with the model's child nodes.
 *
 * <p>They line up by name. A name that turns up four times under one parent is one thing that
 * happened four times, so the model holds one node for it and records the count as a constraint.
 * The alternative — one node per span, matched by position — makes a request that ran three queries
 * a different route from one that ran four, and the model then grows with every new count seen.
 */
class TestNameBasedChildMatching {

    private static final String OPERATION = "ProcessorTaskCreatorJob.run";
    private static final String PREPARE = "Prepare statement";
    private static final String PING = "Ping";
    private static final String OCCURRENCES = "occurrences";
    private static final String CHILD_ORDER = "childOrder";
    private static final long BASE = 1_700_000_000_000_000_000L;

    @Test
    void repeatsOfOneNameAreOneChild() {
        final PathNode root = learn(null, trace(PREPARE, PREPARE, PREPARE, PREPARE));

        assertThat(root.getChildren())
                .as("four spans of one name are one child")
                .hasSize(1);
        assertThat(root.getChildren().getFirst().getName()).isEqualTo(PREPARE);
        assertThat(count(root.getChildren().getFirst()))
                .as("how many there were is a constraint on the child")
                .isEqualTo(new IntegerValue(4));
    }

    @Test
    void aDifferentCountWidensTheChildRatherThanAddingOne() {
        PathNode root = learn(null, trace(PREPARE, PREPARE, PREPARE, PREPARE));
        root = learn(root, trace(PREPARE, PREPARE, PREPARE, PREPARE, PREPARE, PREPARE));

        assertThat(root.getChildren())
                .as("running the same step more times is the same route")
                .hasSize(1);
    }

    @Test
    void aNameMissingFromOneTraceIsStillTheSameChild() {
        PathNode root = learn(null, trace(PING, PREPARE));
        root = learn(root, trace(PING));

        assertThat(root.getChildren())
                .as("a step that did not run this time does not start a second route")
                .hasSize(2);
        assertThat(root.getChildren().stream().map(PathNode::getName))
                .containsExactlyInAnyOrder(PING, PREPARE);
    }

    @Test
    void aNameNeverSeenBeforeAddsOneChild() {
        PathNode root = learn(null, trace(PING));
        root = learn(root, trace(PING, PREPARE));

        assertThat(root.getChildren()).hasSize(2);
    }

    @Test
    void theModelStopsGrowingOnceEveryNameHasBeenSeen() {
        PathNode root = learn(null, trace(PING, PREPARE));

        // The same two names over and over, in every count and order the workload throws up.
        for (int i = 1; i <= 20; i++) {
            final List<String> names = new ArrayList<>();
            for (int j = 0; j < i; j++) {
                names.add(PREPARE);
            }
            names.add(PING);
            root = learn(root, trace(names.toArray(new String[0])));
            root = learn(root, trace(PING, PREPARE, PING));
        }

        assertThat(root.getChildren())
                .as("no name is new, so nothing is added")
                .hasSize(2);
    }

    @Test
    void aTraceThatReachedNoChildrenDoesNotRecordABlankOrder() {
        PathNode root = learn(null, trace(PING));
        root = learn(root, trace());

        assertThat(root.getConstraints().get(CHILD_ORDER).getValue())
                .as("doing no work below is a count of zero on the child, not a blank order")
                .isEqualTo(new StringValue(PING));
    }

    @Test
    void aTraceTheModelWasBuiltFromStillMatches() {
        final PathwaysDoc doc = doc();
        final Trace learnt = trace(PING, PREPARE, PREPARE);
        final PathNode root = learn(null, learnt);

        final TracePredicate predicate = new TracePredicate(
                new CanonicalSpanOrder(doc.getTemporalOrderingTolerance()),
                new PathKeyFactoryImpl(),
                Map.of(new NamePathKey(OPERATION), root));

        assertThat(predicate.test(learnt))
                .as("the matcher lines children up by name the same way the writer does")
                .isTrue();
    }

    @Test
    void aTraceCarryingAnUnknownNameDoesNotMatch() {
        final PathwaysDoc doc = doc();
        final PathNode root = learn(null, trace(PING));

        final TracePredicate predicate = new TracePredicate(
                new CanonicalSpanOrder(doc.getTemporalOrderingTolerance()),
                new PathKeyFactoryImpl(),
                Map.of(new NamePathKey(OPERATION), root));

        assertThat(predicate.test(trace(PING, PREPARE))).isFalse();
    }

    private static PathNode learn(final PathNode current, final Trace trace) {
        final PathwaysDoc doc = doc();
        return new NodeMutatorImpl(new CanonicalSpanOrder(doc.getTemporalOrderingTolerance()))
                .process(trace, new NamePathKey(OPERATION), current, (severity, message) -> {
                }, doc);
    }

    private static Object count(final PathNode pathNode) {
        final Constraint constraint = pathNode.getConstraints().get(OCCURRENCES);
        return constraint == null
                ? null
                : constraint.getValue();
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

    // One root span with a child per name given, each starting a millisecond after the last.
    private static Trace trace(final String... childNames) {
        final Span root = span(OPERATION, "r0", "", 0);
        final List<Span> children = new ArrayList<>(childNames.length);
        for (int i = 0; i < childNames.length; i++) {
            children.add(span(childNames[i], "c" + i, "r0", i + 1));
        }

        final Map<String, List<Span>> byParent = new HashMap<>();
        byParent.put("", List.of(root));
        if (!children.isEmpty()) {
            byParent.put("r0", children);
        }
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
}
