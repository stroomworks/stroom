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

package stroom.query.planner.rewrite;

import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.grammar.ast.AstPosition;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.Scan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 2.3: isolation tests for {@link RedundantTermPruningRule}.
 */
class TestRedundantTermPruningRule {

    private static final AstPosition POS = new AstPosition(1, 0);
    private final RedundantTermPruningRule rule = new RedundantTermPruningRule();

    private static ExpressionTerm term(final String field, final String value) {
        return ExpressionTerm.builder().field(field).condition(Condition.EQUALS).value(value).build();
    }

    private LogicalPlan withWhere(final ExpressionOperator predicate) {
        return new Filter(new Scan("s", "Source", POS), predicate, null, POS);
    }

    @Test
    void exactDuplicateTerm_inNestedAnd_isDropped() {
        // Matches the binder's own left-nested pairwise fold shape: AND(AND(x=1, y=2), x=1).
        final ExpressionOperator input = ExpressionOperator.builder()
                .op(Op.AND)
                .children(List.of(
                        ExpressionOperator.builder().op(Op.AND)
                                .children(List.of(term("x", "1"), term("y", "2")))
                                .build(),
                        term("x", "1")))
                .build();

        final LogicalPlan result = rule.apply(withWhere(input));

        assertThat(((Filter) result).wherePredicate()).isEqualTo(
                ExpressionOperator.builder().op(Op.AND).children(List.of(term("x", "1"), term("y", "2"))).build());
    }

    @Test
    void duplicateReducesToSingleTerm_getsWrappedInAnd() {
        final ExpressionOperator input = ExpressionOperator.builder()
                .op(Op.AND)
                .children(List.of(term("x", "1"), term("x", "1")))
                .build();

        final LogicalPlan result = rule.apply(withWhere(input));

        assertThat(((Filter) result).wherePredicate()).isEqualTo(
                ExpressionOperator.builder().children(List.of(term("x", "1"))).build());
    }

    @Test
    void duplicateAcrossOrBoundary_isNotDropped() {
        // (x=1 OR y=2) AND x=1 - the OR's own x=1 is a different logical scope; dropping the outer x=1 would
        // change semantics whenever y=2 is true and the outer x=1 is false.
        final ExpressionOperator orBranch = ExpressionOperator.builder()
                .op(Op.OR)
                .children(List.of(term("x", "1"), term("y", "2")))
                .build();
        final ExpressionOperator input = ExpressionOperator.builder()
                .op(Op.AND)
                .children(List.of(orBranch, term("x", "1")))
                .build();

        final LogicalPlan result = rule.apply(withWhere(input));

        assertThat(((Filter) result).wherePredicate()).isEqualTo(input);
    }

    @Test
    void distinctTerms_areUnchanged() {
        final ExpressionOperator input = ExpressionOperator.builder()
                .op(Op.AND)
                .children(List.of(term("x", "1"), term("y", "2"), term("z", "3")))
                .build();

        final LogicalPlan result = rule.apply(withWhere(input));

        assertThat(((Filter) result).wherePredicate()).isEqualTo(input);
    }

    @Test
    void duplicateInsideOrSubtree_isOutOfScopeAndLeftAlone() {
        // x=1 AND (y=2 OR y=2) - an OR's own internal duplicate is a different rewrite (OR(a,a) = a) than the
        // one this rule implements ("same AND-conjunction" only, per the design doc) - deliberately untouched.
        final ExpressionOperator input = ExpressionOperator.builder()
                .op(Op.AND)
                .children(List.of(
                        term("x", "1"),
                        ExpressionOperator.builder().op(Op.OR)
                                .children(List.of(term("y", "2"), term("y", "2")))
                                .build()))
                .build();

        final LogicalPlan result = rule.apply(withWhere(input));

        assertThat(((Filter) result).wherePredicate()).isEqualTo(input);
    }

    @Test
    void nestedAndInsideAnOrBranch_isStillPrunedWithinItself() {
        // x=1 OR (y=2 AND y=2) - the AND nested inside the OR is its own AND-conjunction, still in scope.
        final ExpressionOperator input = ExpressionOperator.builder()
                .op(Op.OR)
                .children(List.of(
                        term("x", "1"),
                        ExpressionOperator.builder().op(Op.AND)
                                .children(List.of(term("y", "2"), term("y", "2")))
                                .build()))
                .build();

        final LogicalPlan result = rule.apply(withWhere(input));

        assertThat(((Filter) result).wherePredicate()).isEqualTo(
                ExpressionOperator.builder()
                        .op(Op.OR)
                        .children(List.of(term("x", "1"), term("y", "2")))
                        .build());
    }

    @Test
    void nullPredicateSlots_areLeftNull() {
        final LogicalPlan result = rule.apply(withWhere(null));

        assertThat(((Filter) result).wherePredicate()).isNull();
    }
}
