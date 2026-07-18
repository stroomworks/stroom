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

package stroom.query.planner.logical;

import stroom.query.grammar.ast.AstPosition;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * One column produced by a {@link Project} node - either an {@code eval}-defined computed column or a
 * {@code select} output column (plain field, function call, or star-expansion placeholder).
 *
 * @param name           never null; the column's name (the {@code eval} target variable, the field name, or the
 *                       {@code select ... as} alias).
 * @param rawExpression  never null; the original source text of the expression (whitespace-preserving - handed
 *                       to {@code ExpressionParser} unchanged at execution time, exactly as
 *                       {@code AstToSearchRequestMapper} already does for eval/select spans). For a plain field
 *                       reference this is just the field's own text (e.g. {@code "${Field Name}"} or
 *                       {@code "FieldName"}).
 * @param visible        true if this column appears in the query's final output (a {@code select} item); false
 *                       for an {@code eval}-only column never also selected.
 * @param alias          the {@code select ... as} alias, or null if none was given.
 * @param position       never null.
 */
public record ProjectField(
        String name,
        String rawExpression,
        boolean visible,
        @Nullable String alias,
        AstPosition position) {

    public ProjectField {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(rawExpression, "rawExpression");
        Objects.requireNonNull(position, "position");
    }
}
