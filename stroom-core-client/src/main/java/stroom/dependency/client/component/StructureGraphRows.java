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

package stroom.dependency.client.component;

import stroom.dependency.client.layout.Instance;
import stroom.dependency.client.layout.InstanceKind;
import stroom.dependency.client.layout.LayoutEdge;
import stroom.dependency.client.layout.LayoutResult;
import stroom.dependency.shared.EdgeKind;
import stroom.docref.DocRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Builds the Structure view's table from a layout at full detail: a row for each document as the graph
/// draws it, in reading order.
///
/// The order follows the graph: lane by lane, and within a lane the flow row from left to right, then
/// the documents it references, then endpoints. So moving down the table walks each chain in the
/// direction its data goes. Every document is listed whatever the canvas has collapsed at its zoom: what
/// a screen reader is told does not depend on a zoom it cannot see.
public final class StructureGraphRows {

    /// What a row says when a document has no connection of a kind.
    public static final String NONE = "None";

    private StructureGraphRows() {
        // Static methods only.
    }

    /// @param layout A layout at full detail, with no pins.
    /// @return a row for each instance, in reading order.
    public static List<StructureGraphRow> of(final LayoutResult layout) {
        final Map<Integer, String> laneNames = laneNames(layout);
        final List<Instance> instances = new ArrayList<>(layout.getInstances());
        instances.sort(readingOrder());

        // How each instance connects, from one pass over the edges
        final Map<Instance, Set<String>> upstream = new HashMap<>();
        final Map<Instance, Set<String>> downstream = new HashMap<>();
        final Map<Instance, Set<String>> references = new HashMap<>();
        final Map<Instance, Set<String>> referencedBy = new HashMap<>();
        for (final LayoutEdge edge : layout.getEdges()) {
            final boolean flow = edge.kind() == EdgeKind.FLOW || edge.kind() == EdgeKind.QUERY;
            (flow
                    ? upstream
                    : referencedBy).computeIfAbsent(edge.b(), k -> new LinkedHashSet<>()).add(edge.a().getName());
            (flow
                    ? downstream
                    : references).computeIfAbsent(edge.a(), k -> new LinkedHashSet<>()).add(edge.b().getName());
        }

        final StructureGraphNotes notes = new StructureGraphNotes(layout);
        final List<StructureGraphRow> rows = new ArrayList<>();
        for (final Instance instance : instances) {
            final DocRef docRef = instance.getNode() == null
                    ? null
                    : instance.getNode().getDocRef();
            rows.add(new StructureGraphRow(instance.getId(),
                    instance.getKind(),
                    docRef,
                    instance.getName(),
                    StructureGraphTypes.label(instance.getType()),
                    instance.getLane(),
                    laneNames.get(instance.getLane()),
                    names(upstream.get(instance)),
                    names(downstream.get(instance)),
                    names(references.get(instance)),
                    names(referencedBy.get(instance)),
                    String.join(". ", notes.of(instance))));
        }
        return rows;
    }

    // Each lane is named by the first document on its flow row, as that is usually where its data comes
    // from. A lane of referenced documents only has no such name.
    private static Map<Integer, String> laneNames(final LayoutResult layout) {
        final Map<Integer, Instance> heads = new HashMap<>();
        for (final Instance instance : layout.getInstances()) {
            if (instance.getKind() != InstanceKind.FLOW) {
                continue;
            }
            final Instance head = heads.get(instance.getLane());
            if (head == null || instance.getCol() < head.getCol()) {
                heads.put(instance.getLane(), instance);
            }
        }
        final Map<Integer, String> names = new HashMap<>();
        for (final Integer lane : layout.getLanes()) {
            final Instance head = heads.get(lane);
            names.put(lane, head == null
                    ? "Lane " + (lane + 1)
                    : "Lane " + (lane + 1) + ": " + head.getName());
        }
        return names;
    }

    private static Comparator<Instance> readingOrder() {
        return Comparator.comparingInt(Instance::getLane)
                .thenComparingInt(StructureGraphRows::rowRank)
                .thenComparingInt(instance -> instance.getCol() == null
                        ? position(instance.getX())
                        : instance.getCol())
                .thenComparingInt(instance -> position(instance.getY()));
    }

    private static int rowRank(final Instance instance) {
        if (instance.getKind() == InstanceKind.FLOW || instance.getKind() == InstanceKind.LANE_SUMMARY) {
            return 0;
        } else if (instance.getKind() == InstanceKind.ENDPOINT) {
            return 2;
        } else {
            return 1;
        }
    }

    private static int position(final Integer value) {
        return value == null
                ? Integer.MAX_VALUE
                : value;
    }

    private static String names(final Set<String> names) {
        return names == null || names.isEmpty()
                ? NONE
                : String.join(", ", names);
    }
}
