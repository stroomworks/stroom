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
 * The bound form of a query's {@code eval} and {@code select} clauses combined: one node holding every computed
 * and output column, in the order columns are introduced (matching legacy, where a later {@code eval} may
 * reference an earlier one, and {@code select} may reference any {@code eval}-defined name).
 *
 * @param input    never null.
 * @param fields   never null; every {@code eval}-defined column (in clause order) followed by every
 *                 {@code select} item, as {@link ProjectField#visible()} distinguishes them.
 * @param position never null.
 */
public record Project(LogicalPlan input, List<ProjectField> fields, AstPosition position) implements LogicalPlan {

    public Project {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(position, "position");
        fields = List.copyOf(fields);
    }
}
