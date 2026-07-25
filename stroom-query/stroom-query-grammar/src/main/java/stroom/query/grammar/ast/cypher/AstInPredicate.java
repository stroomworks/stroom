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
 * {@code left IN right} - a list-membership predicate, a leaf of a {@code WHERE} boolean expression tree.
 * {@code right} is a general expression at the grammar level; {@code CypherToLogicalPlan} requires it to be a
 * literal list ({@link AstListValue}).
 *
 * @param left     never null; the value tested for membership (a property access or variable reference).
 * @param right    never null; the list to test against (must lower to a literal list).
 * @param position never null.
 */
public record AstInPredicate(AstExpression left, AstExpression right, AstPosition position)
        implements AstBooleanExpr {

    public AstInPredicate {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(position, "position");
    }
}
