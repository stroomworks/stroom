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

package stroom.query.planner.logical;

import stroom.query.api.ExpressionOperator;
import stroom.query.grammar.ast.AstPosition;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * A leaf node finding a graph's anchor/start node(s) by label and (optionally) a property predicate - the bound
 * form of a Cypher {@code MATCH} pattern's first node, e.g. {@code (d:Device {id: 'd-42'})} (see
 * {@code docs/temporal-cypher-graph-implementation-plan.md}, Task PoC.2/PoC.3). Physically an access-path scan
 * over the graph's property index (design doc &sect;5.1), the graph analogue of {@link Scan} - kept as a
 * distinct node type (rather than reusing {@link Scan}) because a graph anchor has no {@code dataSourceName}/
 * {@code alias} in the relational sense, only a pattern variable, labels, and an optional property predicate.
 *
 * @param variable       never null; the Cypher pattern variable bound to this node (e.g. {@code "d"}).
 * @param labels         never null; possibly empty (an unlabelled node pattern, e.g. {@code (n)}, matches any
 *                       label); in source order.
 * @param propertyAnchor the node pattern's inline property map, lowered to an equality predicate tree (e.g.
 *                       {@code {id: 'd-42'}} becomes {@code id = 'd-42'}), or {@code null} if the pattern had no
 *                       properties (a label-only or fully bare anchor).
 * @param position       never null.
 */
public record NodeScan(
        String variable,
        List<String> labels,
        @Nullable ExpressionOperator propertyAnchor,
        AstPosition position) implements LogicalPlan {

    public NodeScan {
        Objects.requireNonNull(variable, "variable");
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(position, "position");
        labels = List.copyOf(labels);
    }
}
