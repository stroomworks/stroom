/*
 * Copyright 2016-2026 Crown Copyright
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

import stroom.pathways.impl.events.PathwayEvent;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.otel.trace.AnyValue;
import stroom.pathways.shared.otel.trace.KeyValue;
import stroom.pathways.shared.otel.trace.NanoDuration;
import stroom.pathways.shared.otel.trace.Span;
import stroom.pathways.shared.otel.trace.SpanKind;
import stroom.pathways.shared.otel.trace.Trace;
import stroom.pathways.shared.pathway.Constraint;
import stroom.pathways.shared.pathway.ConstraintValue;
import stroom.pathways.shared.pathway.LockState;
import stroom.pathways.shared.pathway.LongSet;
import stroom.pathways.shared.pathway.LongValue;
import stroom.pathways.shared.pathway.PathKey;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.PathwayLocks;
import stroom.util.shared.Severity;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link NodeMutatorImpl} accepts or rejects constraint value mutation according to the
 * effective lock resolved from the constraint, then the pathways doc. A pathway is first built from one
 * trace (creating the constraint), a lock is set, and a second trace with a wider value is processed to
 * see whether the value is allowed to widen.
 */
class TestNodeMutatorLocks {

    private static final String ATTR = "attribute.x";

    private final NodeMutatorImpl mutator =
            new NodeMutatorImpl(new CloseSpanComparator(NanoDuration.ofMillis(10)), new PathKeyFactoryImpl());

    private final MessageReceiver messageReceiver = new MessageReceiver() {
        @Override
        public void log(final Severity severity, final Supplier<String> message) {
        }

        @Override
        public void beginTrace(final byte[] traceId) {
        }

        @Override
        public void event(final PathwaysDoc pathwaysDoc, final String pathwayName, final PathwayEvent event) {
        }
    };

    @Test
    void testPerConstraintValueLock_rejectsWidening() {
        final PathwaysDoc doc = PathwaysDoc.builder().uuid("1").build();

        final PathNode created = process(traceWithAttr(5), null, doc);
        assertLongValue(created, 5);

        final PathNode locked = withConstraintValueLock(created, LockState.LOCKED);
        final PathNode result = process(traceWithAttr(10), locked, doc);

        // Value is locked, so the new value must be rejected and the constraint left unchanged.
        assertLongValue(result, 5);
    }

    @Test
    void testUnlockedValue_allowsWidening() {
        final PathwaysDoc doc = PathwaysDoc.builder().uuid("1").build();

        final PathNode created = process(traceWithAttr(5), null, doc);
        final PathNode result = process(traceWithAttr(10), created, doc);

        // Nothing is locked, so the value widens to a set.
        assertLongSet(result, 5, 10);
    }

    @Test
    void testDocLevelValueLock_rejectsWidening() {
        final PathwaysDoc permissive = PathwaysDoc.builder().uuid("1").build();
        final PathNode created = process(traceWithAttr(5), null, permissive);

        final PathwaysDoc lockedDoc = PathwaysDoc.builder()
                .uuid("1")
                .locks(PathwayLocks.builder().value(LockState.LOCKED).build())
                .build();
        final PathNode result = process(traceWithAttr(10), created, lockedDoc);

        // The constraint inherits the doc-level value lock, so widening is rejected.
        assertLongValue(result, 5);
    }

    @Test
    void testConstraintUnlockOverridesDocLock() {
        final PathwaysDoc lockedDoc = PathwaysDoc.builder()
                .uuid("1")
                .locks(PathwayLocks.builder().value(LockState.LOCKED).build())
                .build();

        final PathNode created = process(traceWithAttr(5), null, lockedDoc);
        final PathNode unlocked = withConstraintValueLock(created, LockState.UNLOCKED);
        final PathNode result = process(traceWithAttr(10), unlocked, lockedDoc);

        // The more specific constraint-level UNLOCKED overrides the doc-level LOCKED, so widening is allowed.
        assertLongSet(result, 5, 10);
    }

    private PathNode process(final Trace trace, final PathNode existing, final PathwaysDoc doc) {
        final PathKey pathKey = new PathKeyFactoryImpl().create(List.of(trace.root()));
        return mutator.process(trace, pathKey, existing, messageReceiver, doc, PathwayLocks.builder().build());
    }

    private PathNode withConstraintValueLock(final PathNode node, final LockState valueLock) {
        final Constraint constraint = node.getConstraints().get(ATTR);
        final Constraint relocked = constraint.copy()
                .locks(constraint.getLocks().copy().value(valueLock).build())
                .build();
        final Map<String, Constraint> constraints = new HashMap<>(node.getConstraints());
        constraints.put(ATTR, relocked);
        return node.copy().constraints(constraints).build();
    }

    private Trace traceWithAttr(final long attrValue) {
        final Span root = Span.builder()
                .traceId("t")
                .spanId("root")
                .parentSpanId("")
                .name("root")
                .kind(SpanKind.SPAN_KIND_INTERNAL)
                .startTimeUnixNano("1000000000000000000")
                .endTimeUnixNano("1000000000000001000")
                .attributes(List.of(new KeyValue("x", AnyValue.intValue(attrValue))))
                .build();
        final Map<String, List<Span>> parentSpanIdMap = new HashMap<>();
        parentSpanIdMap.put("", List.of(root));
        return Trace.builder().traceId("t").parentSpanIdMap(parentSpanIdMap).build();
    }

    private void assertLongValue(final PathNode node, final long expected) {
        final ConstraintValue value = node.getConstraints().get(ATTR).getValue();
        assertThat(value).isInstanceOf(LongValue.class);
        assertThat(((LongValue) value).getValue()).isEqualTo(expected);
    }

    private void assertLongSet(final PathNode node, final long first, final long second) {
        final ConstraintValue value = node.getConstraints().get(ATTR).getValue();
        assertThat(value).isInstanceOf(LongSet.class);
        assertThat(((LongSet) value).getSet()).containsExactlyInAnyOrder(first, second);
    }
}
