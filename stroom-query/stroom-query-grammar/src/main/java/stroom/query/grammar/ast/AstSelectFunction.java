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
 * A function-call-based select column, e.g. {@code max(toFloat(day()-10d))}. Legacy defaults the column's name
 * to the function call's exact original source text (see {@code SearchRequestFactory.processSelect}'s
 * {@code columnName = functionGroup.getText()}) when no {@code as <alias>} is given - hence
 * {@link #expressionText()} must be an exact source slice (whitespace preserved), not a reconstruction, and is
 * handed verbatim to the existing {@code ExpressionParser} by Task 1.4.
 *
 * @param expressionText never null; the exact original source text of the whole function call.
 * @param alias          nullable.
 * @param position       never null.
 */
public record AstSelectFunction(String expressionText, @Nullable AstToken alias,
                                AstPosition position) implements AstSelectItem {

    public AstSelectFunction {
        Objects.requireNonNull(expressionText, "expressionText");
        Objects.requireNonNull(position, "position");
    }
}
