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

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A row-level predicate evaluated pre-aggregation - the bound form of a query's {@code where} and/or {@code
 * filter} clauses.
 *
 * <p>Deliberately two slots rather than one merged predicate: {@code where} and {@code filter} are the same kind
 * of thing semantically (see {@code SearchRequestFactory.addTableSettings} - {@code where} feeds {@code
 * Query.expression} and {@code filter} feeds {@code TableSettings.valueFilter}, but both are row-level,
 * pre-aggregation predicates), just pushed to two different physical positions - one to the datasource, one
 * applied after extraction. Keeping them separate lets a later rewrite (Task 2.3, "auto where/filter split")
 * derive {@code filterPredicate} from {@code wherePredicate} precisely when the user left {@code filterPredicate}
 * empty, and leave both alone (rewrite to itself) when the user already wrote both explicitly.</p>
 *
 * @param input           never null; the plan this filter is applied to.
 * @param wherePredicate  the bound {@code where} clause, or null if the query had none.
 * @param filterPredicate the bound {@code filter} clause, or null if the query had none (including: not yet
 *                        derived by a rewrite rule).
 * @param position        never null.
 */
public record Filter(
        LogicalPlan input,
        @Nullable ExpressionOperator wherePredicate,
        @Nullable ExpressionOperator filterPredicate,
        AstPosition position) implements LogicalPlan {

    public Filter {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(position, "position");
    }
}
