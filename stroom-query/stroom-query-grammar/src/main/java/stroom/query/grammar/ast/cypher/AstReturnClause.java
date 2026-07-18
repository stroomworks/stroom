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
 * {@code RETURN [DISTINCT] <items> [ORDER BY ...] [SKIP n] [LIMIT n]} - a query's final, mandatory clause.
 *
 * @param distinct true if {@code DISTINCT} was specified.
 * @param items    never null; possibly empty in theory but never empty in practice - the grammar requires at
 *                 least one item.
 * @param orderBy  nullable.
 * @param skip     nullable.
 * @param limit    nullable.
 * @param position never null.
 */
public record AstReturnClause(
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
    }
}
