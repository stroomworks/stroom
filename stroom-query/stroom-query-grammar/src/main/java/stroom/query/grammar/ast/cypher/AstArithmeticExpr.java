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
 * An infix arithmetic expression, e.g. {@code a.balance * 1.2} or {@code a.hi - a.lo}. The AST already encodes
 * operator precedence (via the grammar's {@code addExpr}/{@code mulExpr}/{@code powExpr} hierarchy), so a consumer
 * can render or evaluate it structurally without re-applying precedence.
 *
 * @param left     never null; the left operand.
 * @param op       never null.
 * @param right    never null; the right operand.
 * @param position never null.
 */
public record AstArithmeticExpr(AstExpression left, AstArithmeticOp op, AstExpression right, AstPosition position)
        implements AstExpression {

    public AstArithmeticExpr {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(position, "position");
    }
}
