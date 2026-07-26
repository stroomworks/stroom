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
 * An {@code EXISTS { pattern }} existence subquery used as a boolean predicate in a {@code WHERE} clause. The
 * {@link #pattern} is correlated to the enclosing {@code MATCH}: its anchor names a variable already bound outside.
 * {@code CypherToLogicalPlan} restricts the v1 form (a single fixed-length hop from an outer-bound variable); a
 * {@code NOT EXISTS { ... }} is this predicate wrapped in an {@link AstNotExpr}.
 *
 * @param pattern  never null; the correlated existence pattern.
 * @param position never null.
 */
public record AstExistsPredicate(AstPathPattern pattern, AstPosition position) implements AstBooleanExpr {

    public AstExistsPredicate {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(position, "position");
    }
}
