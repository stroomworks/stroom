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

package stroom.query.planner.bind;

import stroom.query.grammar.ast.AstPosition;

import java.util.Objects;

/**
 * A precise semantic-binding error: a human-readable message plus the position of the offending clause - the
 * design doc's "fail fast with good messages" contract for {@link Binder}, replacing legacy's ad-hoc/absent
 * checks in {@code SearchRequestFactory} (see {@code docs/query-optimiser-implementation-plan.md}, Task 2.2).
 */
public final class BindException extends RuntimeException {

    private final AstPosition position;

    /**
     * @param message  never null; a human-readable description of the problem.
     * @param position never null; where in the query the problem was found.
     */
    public BindException(final String message, final AstPosition position) {
        super(message);
        this.position = Objects.requireNonNull(position, "position");
    }

    /** @return never null; where in the query the problem was found. */
    public AstPosition getPosition() {
        return position;
    }

    @Override
    public String toString() {
        return "BindException{" +
               "position=" + position +
               ", message=" + getMessage() +
               '}';
    }
}
