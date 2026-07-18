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

import stroom.query.api.ExpressionOperator;
import stroom.query.grammar.ast.AstPosition;

import java.util.Objects;

/**
 * A post-aggregation predicate - the bound form of a {@code having} clause (feeds
 * {@code TableSettings.aggregateFilter} at execution time - see {@code SearchRequestFactory.addTableSettings}).
 * Unlike {@link Filter}'s {@code where}/{@code filter}, {@code having} has no pushdown-eligible counterpart (it
 * runs after aggregation, so there is nothing to push to a datasource), hence its own node rather than a third
 * slot on {@link Filter}.
 *
 * @param input     never null.
 * @param predicate never null.
 * @param position  never null.
 */
public record Having(LogicalPlan input, ExpressionOperator predicate, AstPosition position) implements LogicalPlan {

    public Having {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(position, "position");
    }
}
