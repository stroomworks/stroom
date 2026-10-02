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
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.otel.trace.Span;
import stroom.pathways.shared.otel.trace.SpanKind;
import stroom.pathways.shared.otel.trace.Trace;
import stroom.pathways.shared.pathway.NamePathKey;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.RouteUse;
import stroom.pathways.shared.pathway.Routes;
import stroom.planb.impl.dao.trace.CanonicalSpanOrder;
import stroom.planb.impl.dao.trace.IgnoredSpans;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a pathway records about the routes its traces took.
 *
 * <p>A route is the whole walk: which nodes the trace reached, in sequence, and which steps each one
 * took. Identical walks share a row carrying a count. The point of recording the walk rather than
 * each node on its own is coupling — that node B's steps have always followed node A's is a fact no
 * per-node record holds.
 */
class TestRouteRecording {

    private static final IgnoredAttributes NO_IGNORED = new IgnoredAttributes(List.of());

    private static final String OPERATION = "ProcessorTaskCreatorJob.run";
    private static final String PING = "Ping";
    private static final String COMMIT = "Commit";
    private static final String PREPARE = "Prepare statement";
    private static final String DRAIN = "drain";
    private static final long BASE = 1_700_000_000_000_000_000L;

    @Test
    void twoTracesDoingTheSameThingShareOneRoute() {
        Routes routes = Routes.empty();
        PathNode root = null;

        for (int i = 0; i < 2; i++) {
            final NodeMutatorImpl mutator = mutator();
            root = process(mutator, sequential(PING, COMMIT), root);
            routes = RouteRecorder.add(
                    routes, mutator.getRouteShape(), time(i), "trace-" + i);
        }

        assertThat(routes.getRoutes()).hasSize(1);
        assertThat(routes.getRoutes().getFirst().getTimesUsed()).isEqualTo(2);
    }

    @Test
    void tracesDoingDifferentThingsGetARouteEach() {
        Routes routes = Routes.empty();

        final NodeMutatorImpl first = mutator();
        PathNode root = process(first, sequential(PING, COMMIT), null);
        routes = RouteRecorder.add(routes, first.getRouteShape(), time(0), "trace-0");

        final NodeMutatorImpl second = mutator();
        root = process(second, sequential(COMMIT, PING), root);
        routes = RouteRecorder.add(routes, second.getRouteShape(), time(1), "trace-1");

        assertThat(routes.getRoutes())
                .as("the same children in a different sequence is a different route")
                .hasSize(2);
        assertThat(routes.getRoutes()).allSatisfy(route ->
                assertThat(route.getTimesUsed()).isEqualTo(1));
    }

    @Test
    void routeCountsSumToTheTracesApplied() {
        Routes routes = Routes.empty();
        PathNode root = null;
        final List<Trace> traces = List.of(
                sequential(PING, COMMIT),
                sequential(COMMIT, PING),
                sequential(PING, COMMIT),
                sequential(PING),
                sequential(PING, COMMIT));

        for (int i = 0; i < traces.size(); i++) {
            final NodeMutatorImpl mutator = mutator();
            root = process(mutator, traces.get(i), root);
            routes = RouteRecorder.add(
                    routes, mutator.getRouteShape(), time(i), "trace-" + i);
        }

        final long counted = routes.getRoutes().stream().mapToLong(RouteUse::getTimesUsed).sum();
        assertThat(counted)
                .as("every trace takes exactly one route, so the counts account for all of them")
                .isEqualTo(traces.size());
    }

    @Test
    void aTraceThatRanNothingBelowTheRootStillTakesARoute() {
        final NodeMutatorImpl mutator = mutator();
        process(mutator, sequential(), null);
        final Routes routes = RouteRecorder.add(Routes.empty(), mutator.getRouteShape(),
                time(0), "trace-0");

        assertThat(routes.getRoutes())
                .as("doing nothing is something the trace did, and the counts have to add up")
                .hasSize(1);
        assertThat(routes.getRoutes().getFirst().getRoot())
                .as("the shape is the root on its own, which is a leaf")
                .isEqualTo(0);
    }

    @Test
    void aNodePositionNamesTheSameNodeAfterLaterNodesAppear() {
        Routes routes = Routes.empty();

        final NodeMutatorImpl first = mutator();
        final PathNode root = process(first, sequential(PING), null);
        routes = RouteRecorder.add(routes, first.getRouteShape(), time(0), "trace-0");
        final int rootPosition = 0;

        // Two more nodes start taking routes, so the list grows past the one position handed out.
        final NodeMutatorImpl second = mutator();
        process(second, twoLevels(PING, COMMIT), root);
        routes = RouteRecorder.add(routes, second.getRouteShape(), time(1), "trace-1");
        assertThat(routes.getNodes()).hasSizeGreaterThan(2);

        assertThat(routes.getNodes().get(rootPosition))
                .as("positions are handed out once and never moved, so an old route still reads")
                .isEqualTo(root.getUuid());
        assertThat(routes.getSteps().get(routes.getRoutes().getFirst().getRoot()).getNode())
                .isEqualTo(rootPosition);
    }

    @Test
    void theFirstTraceToTakeARouteIsTheOneKeptAsTheExample() {
        Routes routes = Routes.empty();
        PathNode root = null;

        for (int i = 0; i < 3; i++) {
            final NodeMutatorImpl mutator = mutator();
            root = process(mutator, sequential(PING, COMMIT), root);
            routes = RouteRecorder.add(
                    routes, mutator.getRouteShape(), time(i), "trace-" + i);
        }

        final RouteUse route = routes.getRoutes().getFirst();
        assertThat(route.getCreatedByTraceId())
                .as("an example that has been looked at stays put rather than moving to the newest")
                .isEqualTo("trace-0");
        assertThat(route.getFirstUsedTime()).isEqualTo(time(0));
        assertThat(route.getLastUsedTime()).isEqualTo(time(2));
    }

    @Test
    void aRouteNamesEveryNodeTheTraceReached() {
        final NodeMutatorImpl mutator = mutator();
        final PathNode root = process(mutator, sequential(PING, COMMIT), null);
        final Routes routes = RouteRecorder.add(Routes.empty(), mutator.getRouteShape(),
                time(0), "trace-0");

        // The shape names them all the same, because it holds what ran rather than only what ran
        // something. Ping and Commit are leaves on it, and a leaf means they ran nothing.
        assertThat(routes.getNodes()).containsExactly(
                root.getUuid(), child(root, PING).getUuid(), child(root, COMMIT).getUuid());
    }

    @Test
    void aNewCombinationOfKnownStepsIsANewRouteAndNoChangeAtAll() {
        Routes routes = Routes.empty();
        PathNode root = null;

        // Three traces to settle the model: both children of each node have to have been seen
        // running and seen not running, because the first time either happens is itself a change.
        final List<Trace> settling = List.of(
                twoLevels(PING, COMMIT),
                twoLevels(PREPARE, DRAIN),
                twoLevels(PING, COMMIT));
        for (int i = 0; i < settling.size(); i++) {
            final NodeMutatorImpl mutator = mutator();
            root = process(mutator, settling.get(i), root);
            routes = RouteRecorder.add(
                    routes, mutator.getRouteShape(), time(i), "trace-" + i);
        }

        // The fourth pairs them a way they have not been paired before. Every node takes steps it has
        // taken and every count has been seen, so the model learns nothing at all.
        final NodeMutatorImpl fourth = mutator();
        process(fourth, twoLevels(PING, DRAIN), root);
        routes = RouteRecorder.add(routes, fourth.getRouteShape(), time(3), "trace-3");

        assertThat(fourth.getMutations())
                .as("nothing about any one node is new, so there is no change to record")
                .isEmpty();
        assertThat(fourth.isChanged()).isFalse();
        assertThat(routes.getRoutes())
                .as("the pairing is new even though neither half is, which is the whole reason a "
                    + "route is recorded rather than each node on its own")
                .hasSize(3);
    }

    // A root running two children, each with one child of its own, so the two can vary
    // independently. What one does says nothing about what the other does.
    private static Trace twoLevels(final String underA, final String underB) {
        final Map<String, List<Span>> byParent = new HashMap<>();
        byParent.put("", List.of(span(OPERATION, "r0", "", 0)));
        byParent.put("r0", List.of(span("A", "a0", "r0", 1), span("B", "b0", "r0", 20)));
        byParent.put("a0", List.of(span(underA, "a1", "a0", 2)));
        byParent.put("b0", List.of(span(underB, "b1", "b0", 21)));
        return new Trace("0a0b0c0d0e0f00010203040506070809", byParent);
    }

    @Test
    void aNodeReachedManyTimesTakingTheSameStepsAppearsOnce() {
        final NodeMutatorImpl mutator = mutator();
        process(mutator, repeatedChild(6), null);
        final Routes routes = RouteRecorder.add(Routes.empty(), mutator.getRouteShape(),
                time(0), "trace-0");

        assertThat(routes.getNodes())
                .as("the root, the repeated node and what it ran — not one entry per span; six calls "
                    + "rather than five is how much work there was, not a different path through "
                    + "the code")
                .hasSize(3);
    }

    @Test
    void doingTheSameWorkMoreTimesIsTheSameRoute() {
        Routes routes = Routes.empty();

        final NodeMutatorImpl fewer = mutator();
        final PathNode root = process(fewer, repeatedChild(3), null);
        routes = RouteRecorder.add(routes, fewer.getRouteShape(), time(0), "trace-0");

        final NodeMutatorImpl more = mutator();
        process(more, repeatedChild(9), root);
        routes = RouteRecorder.add(routes, more.getRouteShape(), time(1), "trace-1");

        assertThat(routes.getRoutes())
                .as("a busier minute is not a different route")
                .hasSize(1);
        assertThat(routes.getRoutes().getFirst().getTimesUsed()).isEqualTo(2);
    }

    @Test
    void aNodeRunningTwoWaysOnOneTraceKeepsBoth() {
        final NodeMutatorImpl mutator = mutator();
        final PathNode root = process(mutator, twoRuns(PING, COMMIT), null);
        RouteRecorder.add(Routes.empty(), mutator.getRouteShape(), time(0), "trace-0");

        // The node ran twice, each time doing something different, and the shape holds both where
        // they happened. Saying it once would lose the second way entirely.
        // Both turns are there. Which way round they read is settled by what they are rather than by
        // which ran first, so nothing here may depend on that order.
        assertThat(RouteShapeText.of(mutator.getRouteShape(), root))
                .contains("A (" + PING + ")")
                .contains("A (" + COMMIT + ")");
    }

    @Test
    void whichOfTwoParallelRunsBehavedWhichWayIsNotARouteApart() {
        Routes routes = Routes.empty();

        final NodeMutatorImpl first = mutator();
        final PathNode root = process(first, twoRuns(PING, COMMIT), null);
        routes = RouteRecorder.add(routes, first.getRouteShape(), time(0), "trace-0");

        // The same two behaviours, the runs the other way round. Where the runs are concurrent which
        // came first is decided by which thread read the clock first, so it is not a different route.
        final NodeMutatorImpl second = mutator();
        process(second, twoRuns(COMMIT, PING), root);
        routes = RouteRecorder.add(routes, second.getRouteShape(), time(1), "trace-1");

        assertThat(routes.getRoutes())
                .as("the node ran both ways in both traces, so both traces took the same route")
                .hasSize(1);
        assertThat(routes.getRoutes().getFirst().getTimesUsed()).isEqualTo(2);
    }

    // A root running one child twice, each run taking a different child of its own. One node reached
    // twice behaving differently each time, which is what a drain loop interleaving work looks like.
    private static Trace twoRuns(final String firstUnder, final String secondUnder) {
        final Map<String, List<Span>> byParent = new HashMap<>();
        byParent.put("", List.of(span(OPERATION, "r0", "", 0)));
        byParent.put("r0", List.of(span("A", "a0", "r0", 1), span("A", "a1", "r0", 20)));
        byParent.put("a0", List.of(span(firstUnder, "c0", "a0", 2)));
        byParent.put("a1", List.of(span(secondUnder, "c1", "a1", 21)));
        return new Trace("0a0b0c0d0e0f00010203040506070809", byParent);
    }

    private static PathNode child(final PathNode parent, final String name) {
        return parent.getChildren().stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .orElseThrow();
    }

    // A root running one child a given number of times, each running a child of its own the same way
    // every time. What a job whose work is driven by how much is queued actually does.
    private static Trace repeatedChild(final int times) {
        final Map<String, List<Span>> byParent = new HashMap<>();
        byParent.put("", List.of(span(OPERATION, "r0", "", 0)));
        final List<Span> calls = new ArrayList<>(times);
        for (int i = 0; i < times; i++) {
            calls.add(span("A", "a" + i, "r0", (i * 3) + 1));
            byParent.put("a" + i, List.of(span(PING, "p" + i, "a" + i, (i * 3) + 2)));
        }
        byParent.put("r0", calls);
        return new Trace("0a0b0c0d0e0f00010203040506070809", byParent);
    }

    private static NanoTime time(final int seconds) {
        return new NanoTime(1_700_000_000L + seconds, 0);
    }

    private static PathNode process(final NodeMutatorImpl mutator,
                                    final Trace trace,
                                    final PathNode current) {
        return mutator.process(trace, new NamePathKey(OPERATION), current, (severity, message) -> {
        }, doc());
    }

    private static NodeMutatorImpl mutator() {
        return new NodeMutatorImpl(
                new CanonicalSpanOrder(doc().getTemporalOrderingTolerance()), NO_IGNORED,
                new IgnoredSpans(List.of()));
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

    // Children spaced wider than the ordering tolerance, so the sequence given is the sequence the
    // model records rather than one settled by name.
    private static Trace sequential(final String... childNames) {
        final Span root = span(OPERATION, "r0", "", 0);
        final List<Span> children = new ArrayList<>(childNames.length);
        for (int i = 0; i < childNames.length; i++) {
            children.add(span(childNames[i], "c" + i, "r0", (i * 3) + 1));
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
