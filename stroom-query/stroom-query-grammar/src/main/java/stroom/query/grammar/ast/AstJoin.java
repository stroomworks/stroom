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

import java.util.List;
import java.util.Objects;

/**
 * A {@code [left|inner]? join <source> [as <alias>] on <cond> [and <cond>]*} clause, attached to the
 * {@code from} it follows.
 *
 * <p><b>Reserved for Phase 6</b>: parsed here (see the design plan's Phase 1 scope) but rejected by the binder
 * with a clear "joins not yet enabled" message until Phase 6 lands.</p>
 *
 * @param joinType nullable; {@code null} means no explicit {@code left}/{@code inner} keyword was written
 *                 (defaults to inner, matching ordinary SQL convention - not yet meaningful before Phase 6).
 * @param source   never null.
 * @param alias    nullable.
 * @param conditions never null; never empty (the grammar requires at least one {@code on} condition).
 * @param position never null.
 */
public record AstJoin(@Nullable JoinType joinType,
                      AstToken source,
                      @Nullable AstToken alias,
                      List<AstJoinCondition> conditions,
                      AstPosition position) {

    public AstJoin {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(conditions, "conditions");
        Objects.requireNonNull(position, "position");
        conditions = List.copyOf(conditions);
    }

    /** Which side's rows are preserved when a join condition doesn't match - see the design plan's Phase 6. */
    public enum JoinType {
        LEFT,
        INNER
    }
}
