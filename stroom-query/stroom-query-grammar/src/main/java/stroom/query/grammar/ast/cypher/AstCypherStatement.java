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

import java.util.List;
import java.util.Objects;

/**
 * A whole Cypher statement: one or more {@link AstCypherQuery} branches combined with {@code UNION} /
 * {@code UNION ALL}. A plain (non-UNION) query is a single-branch statement.
 *
 * @param branches never null, never empty; the {@code UNION}-separated single queries in source order.
 * @param unionAll never null; size {@code branches.size() - 1}. Entry {@code i} is {@code true} when the union
 *                 operator between branch {@code i} and branch {@code i + 1} is {@code UNION ALL} (keep duplicates),
 *                 {@code false} for a plain {@code UNION} (de-duplicate). Empty for a single-branch statement.
 * @param position never null.
 */
public record AstCypherStatement(
        List<AstCypherQuery> branches,
        List<Boolean> unionAll,
        AstPosition position) {

    public AstCypherStatement {
        Objects.requireNonNull(branches, "branches");
        Objects.requireNonNull(unionAll, "unionAll");
        Objects.requireNonNull(position, "position");
        if (branches.isEmpty()) {
            throw new IllegalArgumentException("a statement must have at least one branch");
        }
        if (unionAll.size() != branches.size() - 1) {
            throw new IllegalArgumentException(
                    "unionAll must have one fewer entry than branches (one per UNION operator)");
        }
        branches = List.copyOf(branches);
        unionAll = List.copyOf(unionAll);
    }

    /** True when this statement is a plain single query (no {@code UNION}). */
    public boolean isSingle() {
        return branches.size() == 1;
    }
}
