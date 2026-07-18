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
 * {@code left op right}, e.g. {@code a.id = 'd-42'} - the leaf of a {@code WHERE} boolean expression tree.
 *
 * @param left     never null.
 * @param op       never null.
 * @param right    never null.
 * @param position never null.
 */
public record AstComparisonPredicate(
        AstExpression left,
        AstComparisonOp op,
        AstExpression right,
        AstPosition position) implements AstBooleanExpr {

    public AstComparisonPredicate {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(position, "position");
    }
}
