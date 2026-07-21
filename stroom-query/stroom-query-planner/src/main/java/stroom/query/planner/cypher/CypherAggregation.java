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

import java.util.List;
import java.util.Objects;

/**
 * The Cypher-only aggregation description for a {@code RETURN} clause that mixes an aggregate
 * ({@code count}/{@code sum}/{@code avg}/{@code min}/{@code max}) with other items - carried on
 * {@link CompiledCypherPlan} (like {@code distinct}), not as a shared-IR {@code LogicalPlan} node, because implicit
 * {@code GROUP BY} inference is a Cypher front-end concept the relational core's sealed IR has no node for (see
 * that record's Javadoc for the same reasoning applied to {@code DISTINCT}).
 *
 * <p>{@link #columns()} is aligned <b>1:1 and in order</b> with the compiled plan's terminal
 * {@link stroom.query.planner.logical.Project}'s {@code fields()} - position {@code i} of one list describes how
 * to compute position {@code i} of the other's output row. This alignment is a build-time invariant of
 * {@code CypherToLogicalPlan.compile} (both lists are built by iterating the same {@code RETURN} item list, in the
 * same order), not re-checked here - a caller must not reorder either list independently.</p>
 *
 * @param columns never null; never empty (only built when the {@code RETURN} clause has at least one aggregate
 *                item - see {@code CypherToLogicalPlan.buildAggregation}).
 */
public record CypherAggregation(List<OutputColumn> columns) {

    public CypherAggregation {
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("columns must not be empty");
        }
        columns = List.copyOf(columns);
    }
}
