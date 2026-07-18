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
 * One item of a {@code RETURN}/{@code WITH} item list: {@code <expression> [AS alias]}.
 *
 * @param expression never null.
 * @param alias      nullable - absent means the column is named from the expression's own text (a naming
 *                   convention for {@code CypherToLogicalPlan}, Task PoC.3, not decided at this AST layer).
 * @param position   never null.
 */
public record AstReturnItem(AstExpression expression, @Nullable String alias, AstPosition position) {

    public AstReturnItem {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(position, "position");
    }
}
