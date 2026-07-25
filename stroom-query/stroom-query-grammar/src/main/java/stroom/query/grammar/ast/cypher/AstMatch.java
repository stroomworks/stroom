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

import java.util.Objects;

/**
 * {@code MATCH <pattern> [<temporal>] [WHERE <expr>]} - the temporal clause (Stroom-specific; see
 * {@code Cypher.g4}'s file header) attaches directly to the pattern it governs, before any {@code WHERE}.
 *
 * @param pattern  never null.
 * @param temporal nullable - absent means "latest" (no as-of/window restriction).
 * @param where    nullable - absent means no filter predicate.
 * @param optional true for {@code OPTIONAL MATCH} (left-outer semantics).
 * @param position never null.
 */
public record AstMatch(
        AstPathPattern pattern,
        @Nullable AstTemporal temporal,
        @Nullable AstWhere where,
        boolean optional,
        AstPosition position) implements AstReadingClause {

    public AstMatch {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(position, "position");
    }
}
