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

import java.util.List;
import java.util.Objects;

/**
 * A node/edge/node/... chain: an anchor node followed by zero or more hops. Zero hops is a bare node pattern
 * (e.g. {@code MATCH (n)}); {@code CypherToLogicalPlan} compiles a fixed-length chain of any number of hops, or a
 * single bounded variable-length hop, within one reading clause (Tasks P3.2/P3.3).
 *
 * @param anchor   never null; the first node in the chain.
 * @param hops     never null; possibly empty; in left-to-right source order.
 * @param position never null.
 */
public record AstPathPattern(AstNodePattern anchor, List<AstPatternHop> hops, AstPosition position) {

    public AstPathPattern {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(hops, "hops");
        Objects.requireNonNull(position, "position");
        hops = List.copyOf(hops);
    }
}
