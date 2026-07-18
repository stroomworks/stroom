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

package stroom.query.planner.cypher;

import stroom.query.grammar.ast.AstPosition;

import java.util.Objects;

/**
 * A precise Cypher compile-time error: a human-readable message plus the position of the offending clause -
 * mirrors {@code stroom.query.planner.bind.BindException}'s "fail fast with good messages" contract. Used both
 * for genuine compile errors and for a query shape the grammar accepts but {@link CypherToLogicalPlan} does not
 * yet lower (e.g. multi-hop chains, variable-length paths - see that class's Javadoc), so a query outside the
 * PoC subset gets a clear message rather than a silently wrong plan.
 */
public final class CypherCompileException extends RuntimeException {

    private final AstPosition position;

    /**
     * @param message  never null; a human-readable description of the problem.
     * @param position never null; where in the query the problem was found.
     */
    public CypherCompileException(final String message, final AstPosition position) {
        super(message);
        this.position = Objects.requireNonNull(position, "position");
    }

    /** @return never null; where in the query the problem was found. */
    public AstPosition getPosition() {
        return position;
    }

    @Override
    public String toString() {
        return "CypherCompileException{" +
               "position=" + position +
               ", message=" + getMessage() +
               '}';
    }
}
