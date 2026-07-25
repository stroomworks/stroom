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
 * {@code operand IS NULL} (or {@code operand IS NOT NULL} when {@code negated}) - a null/existence test, a leaf of
 * a {@code WHERE} boolean expression tree.
 *
 * @param operand  never null; the property (or variable) whose presence is tested.
 * @param negated  {@code true} for {@code IS NOT NULL}, {@code false} for {@code IS NULL}.
 * @param position never null.
 */
public record AstIsNullPredicate(AstExpression operand, boolean negated, AstPosition position)
        implements AstBooleanExpr {

    public AstIsNullPredicate {
        Objects.requireNonNull(operand, "operand");
        Objects.requireNonNull(position, "position");
    }
}
