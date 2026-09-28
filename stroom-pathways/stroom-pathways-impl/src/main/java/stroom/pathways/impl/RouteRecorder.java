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

import stroom.pathways.impl.NodeMutatorImpl.NodeSteps;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.RouteUse;
import stroom.pathways.shared.pathway.RouteVisit;
import stroom.pathways.shared.pathway.Routes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Folds one trace's walk into the routes a pathway has already seen.
 *
 * <p>A route records which steps each node took, not how many times it took them nor in what order
 * its own repeats happened. A node reached fifteen times taking the same steps each time appears
 * once. Recording each visit instead makes a trace that called a routine eight times a different
 * route from one that called it seven, which is the workload rather than the code path.
 *
 * <p>Nodes keep the order they were first reached, so which node ran before which is still recorded
 * and two nodes can still be told to be coupled. What is dropped is the order among one node's own
 * repeats: where those run in parallel that order is decided by which thread read the clock first,
 * which {@link CanonicalSpanOrder} already treats as carrying nothing. Keeping it would split one
 * behaviour across as many rows as there are orders the repeats could land in.
 *
 * <p>Every trace produces a route, including a trace that reached no children at all, so the route
 * counts sum to the number of traces the pathway has been used for.
 */
final class RouteRecorder {

    private RouteRecorder() {
    }

    static Routes add(final Routes current,
                      final List<NodeSteps> visits,
                      final NanoTime time,
                      final String traceId) {
        final List<String> nodes = new ArrayList<>(current.getNodes());
        final Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            positions.put(nodes.get(i), i);
        }

        // A node the routes have not referenced before goes on the end, so every position already
        // handed out keeps naming the node it named.
        //
        // Gathered by node, nodes in the order first reached and each node's steps in ascending
        // order, so the same work always writes the same list however the repeats happened to be
        // timed. A sorted set per node also drops a repeat that took steps the node has already
        // taken this trace.
        final Map<Integer, Set<Integer>> byNode = new LinkedHashMap<>();
        for (final NodeSteps visit : visits) {
            Integer position = positions.get(visit.nodeUuid());
            if (position == null) {
                position = nodes.size();
                nodes.add(visit.nodeUuid());
                positions.put(visit.nodeUuid(), position);
            }
            byNode.computeIfAbsent(position, k -> new TreeSet<>()).add(visit.steps());
        }

        final List<RouteVisit> walk = new ArrayList<>();
        byNode.forEach((node, steps) -> steps.forEach(step -> walk.add(new RouteVisit(node, step))));

        final List<RouteUse> routes = new ArrayList<>(current.getRoutes());
        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i).getVisits().equals(walk)) {
                routes.set(i, routes.get(i).used(time));
                return new Routes(nodes, routes);
            }
        }

        // Kept in the order first taken, so the oldest route stays at the top of the table however
        // the counts move.
        routes.add(new RouteUse(walk, 1L, time, time, traceId));
        return new Routes(nodes, routes);
    }
}
