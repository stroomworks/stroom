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

package stroom.graphdb.impl;

import stroom.query.language.functions.Val;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Enough per-element detail, captured during a {@code RETURN GRAPH} traversal ({@link
 * GraphTraversalEngine#executeGraphBindings}/{@link GraphTraversalEngine#executeGraphBindingsAsOf}), to render one
 * element row: the element's own label set (a node) or edge type (an edge),
 * its own raw property map, and - for an edge only - its endpoint node identities.
 *
 * <p>Deliberately holds the element's <b>own</b> unprefixed property map, unlike {@link DiffMatch#flatRow}'s
 * {@code "variable.property"}-keyed path row: {@code properties} here is exactly what {@link
 * GraphElementExecutor} needs both to render the {@code properties} output column and - reused verbatim as a
 * {@link DiffMatch#flatRow} with a singleton {@link ElementId} identity - to feed {@link DiffOperator#classify}
 * for the annotated-subgraph mode's per-element {@code changeKind} (property-set equality is exactly what that
 * classification compares; labels/endpoints are deliberately excluded from it, matching &sect;5.3's "changed"
 * meaning "the property set differs", not "the label set or connectivity differs").</p>
 *
 * @param labels     never null. For a node: every label name on that specific version of the node (may be a
 *                   superset of the pattern's own label constraint - a real label set, not the matched
 *                   constraint). For an edge: always a single-element list holding the edge type name (this
 *                   grammar's edge pattern carries at most one type - see {@code Cypher.g4}'s file header).
 * @param properties never null; the element's own property map, as stored (node or edge properties - never a
 *                   merge of any other bound variable's).
 * @param source     the edge's source node identity, or {@code null} for a node's detail.
 * @param target     the edge's target node identity, or {@code null} for a node's detail.
 */
public record ElementDetail(
        List<String> labels,
        Map<String, Val> properties,
        ElementId.@Nullable Node source,
        ElementId.@Nullable Node target) {

    public ElementDetail {
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(properties, "properties");
        labels = List.copyOf(labels);
        if ((source == null) != (target == null)) {
            throw new IllegalArgumentException("source and target must both be null (a node) or both set (an edge)");
        }
    }
}
