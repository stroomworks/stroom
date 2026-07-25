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

import stroom.query.grammar.ast.cypher.AstAggregateFunction;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A compiled {@code count}/{@code sum}/{@code avg}/{@code min}/{@code max} aggregate call, one of three mutually
 * exclusive argument shapes (see the compact constructor's precondition): {@code count(*)} ({@link #star()}),
 * {@code count(v)} over a bare pattern variable ({@link #argIsVariable()} - equivalent to {@code count(*)} since
 * this PoC subset has no {@code OPTIONAL MATCH} to make {@code v} ever null), or an aggregate over a property
 * ({@link #argRowKey()}). Built only by {@code CypherToLogicalPlan.compileAggregateColumn}, which rejects every
 * other shape (e.g. {@code sum(*)}, {@code sum(v)}) at compile time - see that method's Javadoc.
 *
 * @param function     never null; which aggregate function.
 * @param argRowKey    nullable; the {@code "variable.property"} row key to aggregate over (see
 *                     {@link GroupKeyColumn#rowKey()}) - null unless this is the property-argument shape.
 * @param star         true for the {@code count(*)} form (only ever {@code true} when {@code function} is
 *                     {@code COUNT} - enforced where this is built, not here).
 * @param argIsVariable true for {@code count(v)} over a bare pattern variable (only ever {@code true} when
 *                      {@code function} is {@code COUNT} - enforced where this is built, not here).
 * @param distinct     true for {@code count(DISTINCT a.property)} - the group's values at {@code argRowKey} are
 *                     de-duplicated before reduction. Orthogonal to the argument-mode invariant; only ever
 *                     {@code true} together with a non-null {@code argRowKey} and {@code COUNT} in this version
 *                     (enforced where this is built, not here).
 */
public record AggregateColumn(
        AstAggregateFunction function,
        @Nullable String argRowKey,
        boolean star,
        boolean argIsVariable,
        boolean distinct) implements OutputColumn {

    /**
     * <b>Preconditions:</b> exactly one of {@code star}, {@code argIsVariable}, or a non-null {@code argRowKey}
     * holds - never more than one, never none (mirrors {@link stroom.query.grammar.ast.cypher.AstAggregateExpr}'s
     * own "exactly one of star or argument" invariant, one level further resolved).
     */
    public AggregateColumn {
        Objects.requireNonNull(function, "function");
        final int argumentModes = (star ? 1 : 0) + (argIsVariable ? 1 : 0) + (argRowKey != null ? 1 : 0);
        if (argumentModes != 1) {
            throw new IllegalArgumentException(
                    "exactly one of star, argIsVariable, or a non-null argRowKey must hold (star=" + star
                    + ", argIsVariable=" + argIsVariable + ", argRowKey=" + argRowKey + ")");
        }
    }
}
