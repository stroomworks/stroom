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
 * {@code count(*)}, {@code count(a)}, {@code sum(a.value)}, etc.
 *
 * @param function never null.
 * @param argument nullable - null only when {@link #star()} is true ({@code count(*)}); present otherwise.
 * @param star     true for the {@code count(*)} form.
 * @param position never null.
 */
public record AstAggregateExpr(
        AstAggregateFunction function,
        @Nullable AstExpression argument,
        boolean star,
        AstPosition position) implements AstExpression {

    /**
     * <b>Preconditions:</b> exactly one of {@code star} or a non-null {@code argument} holds - never both, never
     * neither.
     */
    public AstAggregateExpr {
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(position, "position");
        if (star == (argument != null)) {
            throw new IllegalArgumentException(
                    "exactly one of star or argument must be set (star=" + star + ", argument=" + argument + ")");
        }
    }
}
