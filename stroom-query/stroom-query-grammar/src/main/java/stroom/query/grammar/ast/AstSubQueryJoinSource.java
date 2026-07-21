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

import java.util.Objects;

/**
 * A bracketed sub-query join source, e.g. {@code join ( from "Graph" match (u:User) return u.id as userId ) as g}
 * - see {@code docs/graphdb-stroomql-join-implementation-plan.md}, Phase P1. {@code rawText} is the exact,
 * unparsed source text between the brackets (the grammar's {@code subQueryBody} rule only balances nested
 * parentheses - it does not, and cannot, know the body's own grammar), preserved for a later stage to re-parse:
 * today that is always Cypher (the whole of Workstream C's scope is a graph sub-query as a join side), resolved
 * by {@code stroom.query.planner.bind.Binder} via {@code CypherQueryParser}/{@code CypherToLogicalPlan}.
 *
 * <p>An {@link AstJoin} whose source is this type must always carry an explicit alias - there is no datasource
 * name to default one from (enforced by {@code AstBuilder}, a positioned parse error, not by this record).</p>
 *
 * @param rawText  never null; the exact, unparsed source text of the bracketed body (excluding the brackets
 *                 themselves).
 * @param position never null; the position of the opening bracket.
 */
public record AstSubQueryJoinSource(String rawText, AstPosition position) implements AstJoinSource {

    public AstSubQueryJoinSource {
        Objects.requireNonNull(rawText, "rawText");
        Objects.requireNonNull(position, "position");
    }
}
