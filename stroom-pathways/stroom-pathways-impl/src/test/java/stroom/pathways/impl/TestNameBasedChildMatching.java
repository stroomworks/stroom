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
import stroom.pathways.shared.pathway.StepsUse;
import stroom.pathways.shared.pathway.StringSet;
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

    /** Nothing ignored, which is the default. */
    private static final IgnoredAttributes NO_IGNORED = new IgnoredAttributes(List.of());

    private static final String OPERATION = "ProcessorTaskCreatorJob.run";
    private static final String PREPARE = "Prepare statement";
    private static final String PING = "Ping";
    private static final String COMMIT = "Commit";
    private static final String BATCH = "PathwaysProcessor.applyBatch";
    private static final String OCCURRENCES = "occurrences";
    private static final String CHILD_STEPS = "childSteps";
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
    void childrenKeepTheOrderTheyWereFirstReachedIn() {
        PathNode root = learn(null, trace(PING));
        // Alphabetically Commit sorts first, but it was reached second.
        root = learn(root, trace(PING, COMMIT));

        assertThat(root.getChildren().stream().map(PathNode::getName))
                .as("the model holds the order the steps were first reached, not their names sorted")
                .containsExactly(PING, COMMIT);
    }

    @Test
    void takingTheSameStepsAgainJustCountsThem() {
        PathNode root = learn(null, sequential(PING, COMMIT));
        root = learn(root, sequential(PING, COMMIT));

        assertThat(root.getStepsUse())
                .as("the same steps twice is one set taken twice, not two sets")
                .hasSize(1);
        assertThat(root.getStepsUse().getFirst().getSteps()).isEqualTo(PING + " > " + COMMIT);
        assertThat(root.getStepsUse().getFirst().getTimesUsed()).isEqualTo(2L);
    }

    @Test
    void newStepsGoAfterTheOnesAlreadyKnown() {
        PathNode root = learn(null, sequential(PING, COMMIT));
        root = learn(root, sequential(COMMIT, PING));

        assertThat(root.getStepsUse().stream().map(StepsUse::getSteps))
                .as("steps keep the position they were first given, so a position names one set")
                .containsExactly(PING + " > " + COMMIT, COMMIT + " > " + PING);
        assertThat(root.getStepsUse().stream().map(StepsUse::getTimesUsed))
                .containsExactly(1L, 1L);
    }

    @Test
    void countingStepsIsNotAChangeToTheModel() {
        final PathNode root = learn(null, sequential(PING, COMMIT));

        final NodeMutatorImpl mutator = mutator();
        mutator.process(sequential(PING, COMMIT), new NamePathKey(OPERATION), root, (severity, message) -> {
        }, doc());

        assertThat(mutator.isChanged())
                .as("a trace that taught nothing must not count as a change, or Times Updated climbs "
                    + "on every trace and a settled model can no longer be told from a moving one")
                .isFalse();
    }

    @Test
    void aNodeReachedTwiceRecordsBothSetsOfStepsItTook() {
        final NodeMutatorImpl mutator = mutator();
        final PathNode root = process(mutator, twoBatches(PING, COMMIT, COMMIT, PING));
        final PathNode batch = child(root, BATCH);

        assertThat(batch.getStepsUse().stream().map(StepsUse::getSteps))
                .containsExactly(PING + " > " + COMMIT, COMMIT + " > " + PING);
        assertThat(mutator.getStepsTaken().get(batch.getUuid()))
                .as("a node several spans reached can take different steps each time, so one entry "
                    + "for the trace would lose all but the last")
                .containsExactly(0, 1);
    }

    @Test
    void takingOneSetOfStepsTwiceInATraceRecordsItOnce() {
        final NodeMutatorImpl mutator = mutator();
        final PathNode root = process(mutator, twoBatches(PING, COMMIT, PING, COMMIT));

        assertThat(mutator.getStepsTaken().get(child(root, BATCH).getUuid()))
                .as("which steps ran is what tells one way of running apart from another; a node that "
                    + "took one set three times and another once did the same work as one that took "
                    + "each twice")
                .containsExactly(0);
    }

    @Test
    void aNodeThatRanNoChildrenRecordsNoSteps() {
        final NodeMutatorImpl mutator = mutator();
        final PathNode root = process(mutator, trace(PING));

        assertThat(mutator.getStepsTaken())
                .as("a node with nothing below it has no steps to take")
                .doesNotContainKey(child(root, PING).getUuid());
    }

    @Test
    void aStepsPositionNamesTheSameStepsOnALaterTrace() {
        final PathNode first = learn(null, sequential(PING, COMMIT));

        final NodeMutatorImpl mutator = mutator();
        process(mutator, sequential(COMMIT, PING), first);

        assertThat(mutator.getStepsTaken().get(first.getUuid()))
                .as("the steps the model already knew keep position zero, so the new ones are one")
                .containsExactly(1);
    }

    @Test
    void anOptionalChildMissingChangesTheStepsItsParentTook() {
        final NodeMutatorImpl withBoth = mutator();
        final PathNode first = process(withBoth, sequential(PING, COMMIT));

        final NodeMutatorImpl withoutCommit = mutator();
        final PathNode root = process(withoutCommit, sequential(PING), first);

        assertThat(root.getStepsUse().stream().map(StepsUse::getSteps))
                .as("a child that did not run leaves its parent taking different steps, which is the "
                    + "only place a route records that the child was skipped")
                .containsExactly(PING + " > " + COMMIT, PING);
        assertThat(withBoth.getStepsTaken().get(first.getUuid()))
                .as("the trace that ran both took the first steps")
                .containsExactly(0);
        assertThat(withoutCommit.getStepsTaken().get(root.getUuid()))
                .as("the trace that skipped one took different steps, so the two routes differ")
                .containsExactly(1);
    }

    @Test
    void stepsAreNeverGeneralisedHoweverManySetsAreSeen() {
        // Sixty distinct sets, comfortably past the point where any other string constraint gives up
        // and becomes a pattern that matches anything.
        PathNode root = null;
        for (int i = 0; i < 60; i++) {
            root = learn(root, trace("child-" + i));
        }

        assertThat(root.getConstraints().get(CHILD_STEPS).getValue())
                .as("a pattern here would erase which children ran, and traces age out so nothing "
                    + "could work it out again")
                .isInstanceOfSatisfying(StringSet.class, set ->
                        assertThat(set.getSet()).hasSize(60));
    }

    @Test
    void aTraceThatReachedNoChildrenDoesNotRecordBlankSteps() {
        PathNode root = learn(null, trace(PING));
        root = learn(root, trace());

        assertThat(root.getConstraints().get(CHILD_STEPS).getValue())
                .as("doing no work below is a count of zero on the child, not blank steps")
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
        return mutator().process(trace, new NamePathKey(OPERATION), current, (severity, message) -> {
        }, doc());
    }

    private static PathNode process(final NodeMutatorImpl mutator, final Trace trace) {
        return process(mutator, trace, null);
    }

    private static PathNode process(final NodeMutatorImpl mutator,
                                    final Trace trace,
                                    final PathNode current) {
        return mutator.process(trace, new NamePathKey(OPERATION), current, (severity, message) -> {
        }, doc());
    }

    private static PathNode child(final PathNode parent, final String name) {
        return parent.getChildren().stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .orElseThrow();
    }

    private static NodeMutatorImpl mutator() {
        return new NodeMutatorImpl(
                new CanonicalSpanOrder(doc().getTemporalOrderingTolerance()), NO_IGNORED);
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

    // As trace, but with a gap between children wider than the ordering tolerance, so each one is
    // its own timing group and the order given is the order the model records. Children that overlap
    // are deliberately ordered by name instead, which would make every call here give the same order.
    private static Trace sequential(final String... childNames) {
        return trace(3, childNames);
    }

    // One root span with a child per name given, each starting a millisecond after the last.
    private static Trace trace(final String... childNames) {
        return trace(1, childNames);
    }

    // A root running two spans of the same name, each with two children of its own. Reaches one node
    // twice in one trace so it can run its children a different way each time, which is what a job
    // whose drain threads interleave actually does.
    private static Trace twoBatches(final String firstA,
                                    final String firstB,
                                    final String secondA,
                                    final String secondB) {
        final Map<String, List<Span>> byParent = new HashMap<>();
        byParent.put("", List.of(span(OPERATION, "r0", "", 0)));
        byParent.put("r0", List.of(span(BATCH, "b0", "r0", 1), span(BATCH, "b1", "r0", 20)));
        byParent.put("b0", List.of(span(firstA, "b0a", "b0", 2), span(firstB, "b0b", "b0", 5)));
        byParent.put("b1", List.of(span(secondA, "b1a", "b1", 21), span(secondB, "b1b", "b1", 24)));
        return new Trace("0a0b0c0d0e0f00010203040506070809", byParent);
    }

    private static Trace trace(final int gapMillis, final String... childNames) {
        final Span root = span(OPERATION, "r0", "", 0);
        final List<Span> children = new ArrayList<>(childNames.length);
        for (int i = 0; i < childNames.length; i++) {
            children.add(span(childNames[i], "c" + i, "r0", (i * gapMillis) + 1));
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
