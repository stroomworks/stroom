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

import java.util.Objects;

/**
 * A join side whose source is a Cypher sub-query rather than a named datasource (docs/graphdb-stroomql-join-
 * implementation-plan.md, Phase P1/P2) - the graph analogue of {@link Scan} as a join operand. Carries the
 * sub-query's raw Cypher source text opaquely, not a compiled plan: {@code Binder} derives this side's schema
 * (Phase P2) by parsing and compiling it once via {@code CypherJoinSchema}, discarding the compiled plan
 * immediately afterwards, and {@code OptimisingQueryCompiler} (Phase P3) re-parses and re-compiles it again to
 * build the side's wire {@code SearchRequest} - exactly as {@code GraphSearchProvider} itself re-parses
 * {@code GraphSpec#getCypher()} at every execution. The text is cheap to re-parse (a single-hop grammar), so no
 * compiled form is threaded through the planner pipeline.
 *
 * <p>Only ever appears as one side of a {@link Join} (never nested further, never wrapped in a {@link Filter} -
 * predicate/projection push-down into the Cypher body is out of scope for v1, see the design doc's risk profile),
 * so every {@link LogicalPlan} consumer that recurses through a tree treats it as a leaf exactly like
 * {@link Scan}/{@link NodeScan}.</p>
 *
 * @param alias      never null; the sub-query's mandatory join alias (see {@code CypherJoinSchema}'s C0 contract
 *                   Javadoc - there is no datasource name to default one from).
 * @param cypherText never null; the sub-query's exact, unparsed Cypher source text.
 * @param position   never null.
 */
public record GraphJoinSource(String alias, String cypherText, AstPosition position) implements LogicalPlan {

    public GraphJoinSource {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(cypherText, "cypherText");
        Objects.requireNonNull(position, "position");
    }
}
