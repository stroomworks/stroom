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

package stroom.query.grammar.ast.cypher;

import stroom.query.grammar.ast.AstPosition;

import java.util.Objects;

/**
 * One {@code -[edge]-> (node)} step in a path pattern, pairing the traversed edge with the node it leads to.
 *
 * @param edge     never null.
 * @param node     never null; the node this hop leads to.
 * @param position never null.
 */
public record AstPatternHop(AstEdgePattern edge, AstNodePattern node, AstPosition position) {

    public AstPatternHop {
        Objects.requireNonNull(edge, "edge");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(position, "position");
    }
}
