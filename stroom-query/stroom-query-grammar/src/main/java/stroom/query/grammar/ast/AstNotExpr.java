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

package stroom.query.grammar.ast;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Either a {@code not <notExpr>} (negation) or a plain {@link AstPrimary}. The grammar permits stacking
 * ({@code not not x}) recursively, but legacy only resolves a NOT against a single following term/group in one
 * left-to-right pass (see {@code SearchRequestFactory.applyNotOperators}) and rejects a bare double negation with
 * "Expected term after NOT" - Task 1.4 reproduces that rejection; this node does not forbid it structurally.
 *
 * @param negated  true iff this node is {@code not <inner>}.
 * @param inner    set iff {@link #negated}; null otherwise.
 * @param primary  set iff {@code !negated}; null otherwise.
 * @param position never null.
 */
public record AstNotExpr(boolean negated, @Nullable AstNotExpr inner, @Nullable AstPrimary primary,
                         AstPosition position) {

    public AstNotExpr {
        Objects.requireNonNull(position, "position");
        if (negated == (inner == null)) {
            throw new IllegalArgumentException("inner must be set if and only if negated is true");
        }
        if (negated == (primary != null)) {
            throw new IllegalArgumentException("primary must be set if and only if negated is false");
        }
    }
}
