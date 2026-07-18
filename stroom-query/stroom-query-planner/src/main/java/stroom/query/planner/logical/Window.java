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
 * The bound form of a {@code window <field> by <duration> [advance <duration>] [using <function>]} clause (a
 * hopping window over the time field).
 *
 * @param input        never null.
 * @param field        never null.
 * @param windowSize   never null; the raw duration text (e.g. {@code "1h"}) - parsing/validating it is an
 *                     execution-time concern, matching how {@code eval}/{@code select} expressions are handled.
 * @param advanceSize  the raw {@code advance} duration text, or null if not specified (defaults to
 *                     {@code windowSize}, matching legacy - a binder concern only if/when this is wired into
 *                     execution).
 * @param usingFunction the {@code using} aggregation function name, or null if not specified.
 * @param position     never null.
 */
public record Window(
        LogicalPlan input,
        QualifiedField field,
        String windowSize,
        @Nullable String advanceSize,
        @Nullable String usingFunction,
        AstPosition position) implements LogicalPlan {

    public Window {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(windowSize, "windowSize");
        Objects.requireNonNull(position, "position");
    }
}
