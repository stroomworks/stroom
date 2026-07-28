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

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * {@code RETURN [DISTINCT] <items> [ORDER BY ...] [SKIP n] [LIMIT n]} - a query's final, mandatory clause - or,
 * in its element-row form, bare {@code RETURN GRAPH} (see {@code Cypher.g4}'s {@code returnClause} rule and
 *  &sect;4.4 /
 * &sect;3). Kept as one record with a {@link #graph} flag, rather than a sealed hierarchy, since every other
 * field is simply absent (grammar-enforced empty/null) in the {@code RETURN GRAPH} form - callers that only care
 * about the scalar form are unaffected as long as they check {@link #graph} first.
 *
 * @param graph    true for the element-row form ({@code RETURN GRAPH}); when true every other field below is
 *                 empty/false/null (enforced by the compact constructor) - the grammar's {@code returnGraphClause}
 *                 alternative produces exactly that shape.
 * @param distinct true if {@code DISTINCT} was specified (always false when {@link #graph}).
 * @param items    never null; possibly empty in theory but never empty in practice for the scalar form - the
 *                 grammar requires at least one item there; always empty when {@link #graph}.
 * @param orderBy  nullable; always null when {@link #graph}.
 * @param skip     nullable; always null when {@link #graph}.
 * @param limit    nullable; permitted in <em>both</em> forms - the scalar {@code LIMIT n}, and, for
 *                 {@code RETURN GRAPH}, an optional cap on the nodes returned (plus the edges between them).
 * @param position never null.
 */
public record AstReturnClause(
        boolean graph,
        boolean distinct,
        List<AstReturnItem> items,
        @Nullable AstOrderBy orderBy,
        @Nullable AstSkip skip,
        @Nullable AstLimit limit,
        AstPosition position) {

    public AstReturnClause {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(position, "position");
        items = List.copyOf(items);
        // RETURN GRAPH accepts an optional LIMIT (see Cypher.g4's returnGraphClause) but none of the per-item
        // modifiers, which have no item list to apply to.
        if (graph && (distinct || !items.isEmpty() || orderBy != null || skip != null)) {
            throw new IllegalArgumentException(
                    "RETURN GRAPH does not accept DISTINCT, an item list, ORDER BY or SKIP");
        }
    }
}
