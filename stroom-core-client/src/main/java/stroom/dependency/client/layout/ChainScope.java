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

package stroom.dependency.client.layout;

import stroom.dependency.shared.EdgeKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// The chain an instance belongs to: what is highlighted when it is selected, while everything else dims.
///
/// The chain is everything upstream and downstream of the instance along flow and query edges, the
/// documents those reference (up to three references deep), and every other copy of the same document.
/// Siblings that merely share a downstream node are not in it, because the walk goes only one way from
/// the selection in each direction. Ported from `scopeOf()` in the reference harness.
public final class ChainScope {

    // How many references deep the chain follows from its flow nodes.
    private static final int REFERENCE_DEPTH = 3;

    private ChainScope() {
        // Static methods only.
    }

    /// @param layout The layout.
    /// @param id     The id of the selected instance.
    /// @return the ids of the instances in the selected instance's chain, including its own; or null if
    /// `id` is null or not an instance of the layout.
    public static Set<String> of(final LayoutResult layout, final String id) {
        if (id == null) {
            return null;
        }
        Instance selected = null;
        for (final Instance instance : layout.getInstances()) {
            if (instance.getId().equals(id)) {
                selected = instance;
                break;
            }
        }
        if (selected == null) {
            return null;
        }

        // Who follows whom along flow and query edges, both ways, built once
        final Map<String, List<String>> downstream = new HashMap<>();
        final Map<String, List<String>> upstream = new HashMap<>();
        for (final LayoutEdge edge : layout.getEdges()) {
            if (!isReference(edge.kind())) {
                downstream.computeIfAbsent(edge.a().getId(), k -> new ArrayList<>()).add(edge.b().getId());
                upstream.computeIfAbsent(edge.b().getId(), k -> new ArrayList<>()).add(edge.a().getId());
            }
        }

        final Set<String> scope = new LinkedHashSet<>(reach(downstream, id));
        scope.addAll(reach(upstream, id));
        for (int i = 0; i < REFERENCE_DEPTH; i++) {
            for (final LayoutEdge edge : layout.getEdges()) {
                if (isReference(edge.kind()) && scope.contains(edge.a().getId())) {
                    scope.add(edge.b().getId());
                }
            }
        }
        // Every copy of the same document. A summary has no document, so has no copies: the reference
        // harness took in every other summary here.
        if (selected.getSrc() != null) {
            for (final Instance instance : layout.getInstances()) {
                if (Objects.equals(instance.getSrc(), selected.getSrc())) {
                    scope.add(instance.getId());
                }
            }
        }
        return scope;
    }

    // Everything reachable from the instance along one direction of flow and query edges.
    private static Set<String> reach(final Map<String, List<String>> next, final String id) {
        final Set<String> seen = new LinkedHashSet<>();
        seen.add(id);
        final List<String> stack = new ArrayList<>();
        stack.add(id);
        while (!stack.isEmpty()) {
            final String current = stack.remove(stack.size() - 1);
            for (final String to : next.getOrDefault(current, List.of())) {
                if (seen.add(to)) {
                    stack.add(to);
                }
            }
        }
        return seen;
    }

    private static boolean isReference(final EdgeKind kind) {
        return kind == EdgeKind.REFERENCE || kind == EdgeKind.INHERITANCE || kind == EdgeKind.HTTP;
    }
}
