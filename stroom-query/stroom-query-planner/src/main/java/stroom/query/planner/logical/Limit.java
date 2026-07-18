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

import java.util.List;
import java.util.Objects;

/**
 * The bound form of a {@code limit} clause. Legacy accepts (and this record preserves) multiple comma-separated
 * limit values (see {@code SearchRequestFactory.processLimit}); values are parsed to {@code long} at bind time
 * (a new fail-fast check - legacy defers parsing to execution via {@code Long.parseLong}).
 *
 * @param input    never null.
 * @param values   never null; never empty (the grammar requires at least one limit value).
 * @param position never null.
 */
public record Limit(LogicalPlan input, List<Long> values, AstPosition position) implements LogicalPlan {

    public Limit {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(position, "position");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        values = List.copyOf(values);
    }
}
