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

package stroom.pathways.client.presenter;

import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.MutationType;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.util.shared.NullSafe;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How much each node and each constraint of a pathway had changed by a point in its history.
 *
 * <p>Worked out here rather than wherever it is shown, because it was worked out in two places and
 * the two came to disagree: the drawing counted traces and the constraint list counted changes, one
 * stopped at the point being looked at and the other did not, and both were labelled the same thing
 * on screen. Three rules settle it, and they hold at both scopes:
 *
 * <ul>
 *     <li><b>Traces, not changes.</b> A trace that widened nine constraints of a node taught it one
 *     thing on one occasion, the same as a trace that widened one.</li>
 *     <li><b>Coming into being is not changing.</b> A node just learnt has changed no times, however
 *     much was learnt about it, which is how a pathway's own Times Updated counts.</li>
 *     <li><b>The point being looked at is honoured.</b> Winding a model back and then counting
 *     changes it had not yet been through says something that was never true.</li>
 * </ul>
 *
 * <p>The one place the two scopes part company is a node a trace did not carry. It is not counted
 * against the node, because no trace reached it, but it is counted against its occurrences
 * constraint, whose value really did widen to admit none. Different questions, answered differently
 * on purpose.
 */
class MutationCounts {

    // Paths are lists and maps are keyed by strings, so the parts are joined by something a span name
    // cannot hold.
    private static final String SEPARATOR = "\u0000";

    private final Map<String, NodeChange> nodes;
    private final Map<String, NodeChange> constraints;
    private final long mostChangedNode;

    private MutationCounts(final Map<String, NodeChange> nodes,
                           final Map<String, NodeChange> constraints,
                           final long mostChangedNode) {
        this.nodes = nodes;
        this.constraints = constraints;
        this.mostChangedNode = mostChangedNode;
    }

    /**
     * @param upTo the change being looked at, or zero to count the whole history.
     */
    static MutationCounts of(final List<PathwayMutation> history, final long upTo) {
        final Counter nodes = new Counter();
        final Counter constraints = new Counter();

        for (final PathwayMutation mutation : NullSafe.list(history)) {
            if (upTo > 0 && mutation.getSequence() > upTo) {
                continue;
            }
            if (isCreation(mutation.getType())) {
                continue;
            }

            final String path = key(mutation.getPath());
            if (!MutationType.NODE_ABSENT.equals(mutation.getType())) {
                nodes.add(path, mutation);
            }
            if (mutation.getConstraint() != null) {
                constraints.add(path + SEPARATOR + mutation.getConstraint(), mutation);
            }
        }

        final Map<String, NodeChange> byNode = nodes.counted();
        long most = 0;
        for (final NodeChange change : byNode.values()) {
            most = Math.max(most, change.getCount());
        }
        return new MutationCounts(byNode, constraints.counted(), most);
    }

    /**
     * What a node had changed by the point counted to, or null where it had not changed at all.
     */
    NodeChange node(final List<String> path) {
        return nodes.get(key(path));
    }

    NodeChange constraint(final List<String> path, final String name) {
        return constraints.get(key(path) + SEPARATOR + name);
    }

    /**
     * The most any one node had changed, which is what node sizes are measured against.
     */
    long getMostChangedNode() {
        return mostChangedNode;
    }

    private static String key(final List<String> path) {
        return String.join(SEPARATOR, NullSafe.list(path));
    }

    private static boolean isCreation(final MutationType type) {
        return MutationType.PATHWAY_ADDED.equals(type)
               || MutationType.NODE_ADDED.equals(type)
               || MutationType.CONSTRAINT_ADDED.equals(type);
    }


    // --------------------------------------------------------------------------------


    // The traces that touched each thing, and when the last of them did. Newest by sequence rather
    // than by time, because every change a trace made shares one timestamp.
    private static class Counter {

        private final Map<String, Set<String>> traces = new HashMap<>();
        private final Map<String, Long> newest = new HashMap<>();
        private final Map<String, NanoTime> times = new HashMap<>();

        private void add(final String key, final PathwayMutation mutation) {
            traces.computeIfAbsent(key, k -> new HashSet<>()).add(mutation.getTraceId());

            final Long seen = newest.get(key);
            if (seen == null || mutation.getSequence() > seen) {
                newest.put(key, mutation.getSequence());
                times.put(key, mutation.getTime());
            }
        }

        private Map<String, NodeChange> counted() {
            final Map<String, NodeChange> counted = new HashMap<>();
            traces.forEach((key, traceIds) ->
                    counted.put(key, new NodeChange(traceIds.size(), times.get(key))));
            return counted;
        }
    }
}
