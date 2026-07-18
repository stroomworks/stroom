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

import java.util.Objects;

/**
 * {@code eval <name> = <expression>}. {@link #expressionText()} is handed verbatim to the existing, unchanged
 * {@code stroom.query.language.functions.ExpressionParser} by Task 1.4 - this grammar/AST does not itself
 * interpret function calls or arithmetic (see {@code StroomQL.g4}'s file header).
 *
 * @param name           never null; the variable this expression's result is bound to.
 * @param expressionText never null; the exact original source text of the expression (whitespace preserved).
 * @param position       never null.
 */
public record AstEvalClause(AstToken name, String expressionText, AstPosition position) implements AstClause {

    public AstEvalClause {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(expressionText, "expressionText");
        Objects.requireNonNull(position, "position");
    }
}
