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
 * The bound form of one {@code group by} clause. Legacy allows repeated {@code group by} clauses for nested
 * grouping (see {@code TokenType.KEYWORDS_VALID_BEFORE}'s comment on {@code GROUP}); the binder mirrors that by
 * wrapping one {@code Aggregate} node per clause, in clause order, rather than collapsing them into one node
 * with multiple levels.
 *
 * @param input       never null.
 * @param groupFields never null; never empty (the grammar requires at least one field after {@code group by}).
 * @param position    never null.
 */
public record Aggregate(LogicalPlan input, List<QualifiedField> groupFields, AstPosition position)
        implements LogicalPlan {

    public Aggregate {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(groupFields, "groupFields");
        Objects.requireNonNull(position, "position");
        if (groupFields.isEmpty()) {
            throw new IllegalArgumentException("groupFields must not be empty");
        }
        groupFields = List.copyOf(groupFields);
    }
}
