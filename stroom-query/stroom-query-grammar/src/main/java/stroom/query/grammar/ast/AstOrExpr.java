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

import java.util.List;
import java.util.Objects;

/**
 * A boolean expression: one or more {@link AstAndExpr} operands, originally separated by {@code or}. Deliberately
 * kept as a FLAT list (not pre-folded into a binary tree) - legacy folds runs like this pairwise,
 * left-associatively, into nested {@code ExpressionOperator} nodes (see
 * {@code SearchRequestFactory.applyAndOrOperators}), and Task 1.4 must reproduce that exact nesting shape for
 * byte-identical JSON; folding here would lose the information needed to do that faithfully.
 *
 * @param operands never null; never empty. A single operand means this level contributed no {@code OR} node in
 *                 the original source.
 * @param position never null.
 */
public record AstOrExpr(List<AstAndExpr> operands, AstPosition position) {

    public AstOrExpr {
        Objects.requireNonNull(operands, "operands");
        Objects.requireNonNull(position, "position");
        if (operands.isEmpty()) {
            throw new IllegalArgumentException("operands must not be empty");
        }
        operands = List.copyOf(operands);
    }
}
