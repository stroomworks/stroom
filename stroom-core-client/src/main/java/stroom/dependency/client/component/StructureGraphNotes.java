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
import stroom.dependency.client.layout.LayoutEdge;
import stroom.dependency.client.layout.LayoutResult;
import stroom.dependency.shared.GraphNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// What there is to say about a node beyond its name and type: its stream type, its size, where else it
/// is drawn, whether it is outside the folder, and any problem with its references.
///
/// Said in the hover card and in the table's Notes column, so none of it is told only to a mouse user.
public final class StructureGraphNotes {

    private final Set<Instance> namesAmbiguously = new HashSet<>();
    private final Set<Instance> namedAmbiguously = new HashSet<>();

    /// @param layout The layout whose instances will be described.
    public StructureGraphNotes(final LayoutResult layout) {
        for (final LayoutEdge edge : layout.getEdges()) {
            if (edge.ambiguous()) {
                namesAmbiguously.add(edge.a());
                namedAmbiguously.add(edge.b());
            }
        }
    }

    /// @param instance An instance of the layout this was made for.
    /// @return a line for each thing there is to say about it; empty if there is nothing.
    public List<String> of(final Instance instance) {
        final List<String> notes = new ArrayList<>();
        final GraphNode node = instance.getNode();
        if (node != null && node.getStreamType() != null) {
            notes.add("Stream type: " + node.getStreamType());
        }
        if (node != null && node.getElementCount() != null && node.getElementCount() > 0) {
            notes.add(plural(node.getElementCount(), "element"));
        }
        if (instance.getShared() != null && instance.getShared() > 1) {
            notes.add("Used in " + instance.getShared() + " lanes");
        }
        if (node != null && !node.isInScope()) {
            notes.add("Outside this folder");
        }
        if (node != null && node.getUnresolvedRefs() != null && node.getUnresolvedRefs() > 0) {
            notes.add(node.getUnresolvedRefs() == 1
                    ? "Refers to a document that cannot be found"
                    : "Refers to " + node.getUnresolvedRefs() + " documents that cannot be found");
        }
        if (namesAmbiguously.contains(instance)) {
            notes.add("Refers to a name that more than one document has");
        }
        if (namedAmbiguously.contains(instance)) {
            notes.add("Shares its name with another document, so a reference to it is ambiguous");
        }
        return notes;
    }

    /// @param count A count, or null for none.
    /// @param noun  What is counted, singular.
    /// @return e.g. `1 element` or `6 elements`.
    static String plural(final Integer count, final String noun) {
        final int value = count == null
                ? 0
                : count;
        return value + " " + noun + (value == 1
                ? ""
                : "s");
    }
}
