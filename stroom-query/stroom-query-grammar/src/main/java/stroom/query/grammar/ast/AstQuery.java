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

import java.util.List;
import java.util.Objects;

/**
 * The root AST node for a whole StroomQL query: one {@code from} clause followed by any number of other clauses
 * in the order they appeared in the source. Clause order/cardinality is deliberately unconstrained here - see
 * {@code StroomQL.g4}'s file header - the binder (Task 1.4) validates both against the same
 * {@code stroom.query.api.token.TokenType} maps legacy uses, guaranteeing identical accept/reject decisions.
 *
 * @param from     never null.
 * @param clauses  never null; possibly empty; in source order.
 * @param position never null.
 */
public record AstQuery(AstFrom from, List<AstClause> clauses, AstPosition position) {

    public AstQuery {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(clauses, "clauses");
        Objects.requireNonNull(position, "position");
        clauses = List.copyOf(clauses);
    }
}
