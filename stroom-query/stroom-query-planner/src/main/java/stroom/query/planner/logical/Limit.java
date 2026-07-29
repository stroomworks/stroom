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
 * The bound form of a row-window clause: how many rows to discard before the answer starts, and how many to
 * return. StroomQL's {@code limit} and Cypher's {@code SKIP}/{@code LIMIT} pair both land here.
 *
 * <p>Legacy accepts (and this record preserves) multiple comma-separated limit values (see
 * {@code SearchRequestFactory.processLimit}); values are parsed to {@code long} at bind time (a fail-fast check -
 * legacy defers parsing to execution via {@code Long.parseLong}).</p>
 *
 * <h2>Why {@code offset} is a component here and not a node of its own</h2>
 *
 * <p>An {@code Offset} node would have to be ordered against this one by every consumer, and there are several
 * places that walk past a {@code Limit} to reach what it wraps ({@code CompiledCypherPlan.outputFields},
 * {@code CypherJoinSchema}, {@code GraphTraversalEngine.unwrap}, three rewrite rules). Each would need a second,
 * parallel unwrap loop whose absence would be a silent wrong answer rather than a compile error. One node that
 * carries both halves of the window keeps the shape above a {@code Project} at
 * {@code [Limit ->] [Sort ->] Project}, which is what those consumers already expect.</p>
 *
 * <h2>Why the canonical constructor takes {@code offset} second</h2>
 *
 * <p>Deliberately, so that adding it broke every existing construction site rather than defaulting quietly. The
 * dangerous consumers are the rewrite rules, which rebuild a {@code Limit} around a transformed input: one that
 * forgot to carry the offset through would drop a {@code SKIP} and return the wrong page, with nothing to
 * indicate it. A compile error at each site forced that decision to be made explicitly. <b>Do not add a
 * convenience overload that defaults the offset</b> - it would reintroduce exactly that failure mode.</p>
 *
 * @param input    never null.
 * @param offset   &ge; 0; the number of rows to discard before the answer starts. Zero when there is no
 *                 {@code SKIP} - which is always, on the StroomQL path, whose grammar has no such clause.
 * @param values   never null. <b>May be empty</b>, for a {@code SKIP} with no {@code LIMIT} (legal Cypher): the
 *                 window then runs from {@code offset} to the end. Consumers reading a maximum row count must
 *                 therefore check for empty rather than taking the first value blind.
 * @param position never null.
 */
public record Limit(LogicalPlan input, long offset, List<Long> values, AstPosition position) implements LogicalPlan {

    public Limit {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(position, "position");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative, was " + offset);
        }
        if (values.isEmpty() && offset == 0) {
            // Neither half of the window is set, so the node bounds nothing and should not have been created.
            // Rejected rather than tolerated as a no-op, because it can only arise from a compiler bug.
            throw new IllegalArgumentException("a Limit must bound something: values must not be empty unless "
                                               + "offset is greater than zero");
        }
        values = List.copyOf(values);
    }
}
