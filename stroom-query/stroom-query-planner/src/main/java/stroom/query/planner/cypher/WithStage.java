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

import stroom.query.api.ExpressionOperator;
import stroom.query.planner.logical.ProjectField;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * The second stage of a single {@code MATCH ... WITH ... [WHERE] RETURN ...} pipe. The {@code WITH} clause is
 * compiled as stage one's terminal projection/aggregation (on {@link CompiledCypherPlan#plan()}/
 * {@link CompiledCypherPlan#aggregation()}), producing one row per {@code WITH} column named by
 * {@link #stageColumns()}. This descriptor is what the executor applies to those rows: an optional {@code HAVING}
 * filter (the {@code WITH}'s own {@code WHERE}, a post-aggregation filter over the projected columns), then the
 * final {@code RETURN} projection over those columns.
 *
 * <p>The compiler validates that every reference in {@code having}/{@code finalFields} names a {@code WITH} column
 * (Cypher's {@code WITH}-scoping rule - only the projected names survive), so an out-of-scope reference fails at
 * compile time rather than resolving to null.</p>
 *
 * @param stageColumns  never null; the {@code WITH}'s output column names, in the order stage one emits them
 *                      (aligned 1:1 with stage one's terminal {@code Project} fields), used to key each stage-one
 *                      output row by name for the {@code HAVING}/final-projection step.
 * @param having        the {@code WITH}'s {@code WHERE} lowered to a predicate over {@code stageColumns}, or null
 *                      if the {@code WITH} had no {@code WHERE}.
 * @param finalFields   never null; the final {@code RETURN}'s projection, each a reference to (or scalar function
 *                      over) a {@code WITH} column.
 * @param finalDistinct whether the final {@code RETURN} was {@code RETURN DISTINCT}.
 */
public record WithStage(
        List<String> stageColumns,
        @Nullable ExpressionOperator having,
        List<ProjectField> finalFields,
        boolean finalDistinct) {

    public WithStage {
        Objects.requireNonNull(stageColumns, "stageColumns");
        Objects.requireNonNull(finalFields, "finalFields");
        stageColumns = List.copyOf(stageColumns);
        finalFields = List.copyOf(finalFields);
    }
}
