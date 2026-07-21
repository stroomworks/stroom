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

/**
 * A {@link AstJoin}'s source: either a plain datasource name ({@link AstNamedJoinSource}, e.g.
 * {@code join "Users"}) or a bracketed sub-query ({@link AstSubQueryJoinSource}, e.g.
 * {@code join ( from "Graph" match ... return ... )}) - see
 * {@code docs/graphdb-stroomql-join-implementation-plan.md}, Phase P1. Today the only sub-query body the binder
 * knows how to interpret is a Cypher graph traversal (Workstream C's whole scope); the grammar itself stays
 * agnostic and simply preserves the bracketed body's raw source text for whichever later stage re-parses it.
 */
public sealed interface AstJoinSource permits AstNamedJoinSource, AstSubQueryJoinSource {

    AstPosition position();
}
