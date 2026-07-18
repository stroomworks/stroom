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
 * The bound form of a {@code sort by} clause.
 *
 * @param input    never null.
 * @param keys     never null; never empty (the grammar requires at least one sort item), in clause order
 *                 (primary sort first).
 * @param position never null.
 */
public record Sort(LogicalPlan input, List<SortKey> keys, AstPosition position) implements LogicalPlan {

    public Sort {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(position, "position");
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("keys must not be empty");
        }
        keys = List.copyOf(keys);
    }
}
