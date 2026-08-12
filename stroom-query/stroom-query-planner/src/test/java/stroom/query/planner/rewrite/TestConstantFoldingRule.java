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
import stroom.query.planner.logical.Having;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.Scan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 2.3: isolation tests for {@link ConstantFoldingRule} - hand-built input plan &rarr; expected output plan.
 */
class TestConstantFoldingRule {

    private static final AstPosition POS = new AstPosition(1, 0);
    private final ConstantFoldingRule rule = new ConstantFoldingRule();

    private static ExpressionTerm term(final String field, final String value) {
        return ExpressionTerm.builder().field(field).condition(Condition.EQUALS).value(value).build();
    }

    private LogicalPlan withWhere(final ExpressionOperator predicate) {
        return new Filter(new Scan("s", "Source", POS), predicate, null, POS);
    }

    @Test
    void doubleNegation_collapsesToInnerTerm() {
        final ExpressionOperator input = ExpressionOperator.builder()
                .op(Op.AND)
                .children(List.of(
                        ExpressionOperator.builder().op(Op.NOT).children(List.of(
                                ExpressionOperator.builder().op(Op.NOT).children(List.of(term("a", "1"))).build()
                        )).build(),
                        term("b", "2")))
                .build();

        final LogicalPlan result = rule.apply(withWhere(input));

        assertThat(((Filter) result).wherePredicate()).isEqualTo(
                ExpressionOperator.builder().op(Op.AND).children(List.of(term("a", "1"), term("b", "2"))).build());
    }

    @Test
    void singleChildAndOr_collapsesAtNestedLevel() {
        final ExpressionOperator input = ExpressionOperator.builder()
                .op(Op.AND)
                .children(List.of(
                        ExpressionOperator.builder().op(Op.OR).children(List.of(
                                term("a", "1"),
                                ExpressionOperator.builder().op(Op.AND).children(List.of(term("b", "2"))).build()
                        )).build(),
                        term("c", "3")))
                .build();

        final LogicalPlan result = rule.apply(withWhere(input));

        assertThat(((Filter) result).wherePredicate()).isEqualTo(
                ExpressionOperator.builder()
                        .op(Op.AND)
                        .children(List.of(
                                ExpressionOperator.builder().op(Op.OR)
                                        .children(List.of(term("a", "1"), term("b", "2")))
                                        .build(),
                                term("c", "3")))
                        .build());
    }

    @Test
    void alreadySimplifiedPredicate_isUnchanged() {
        final ExpressionOperator input = ExpressionOperator.builder()
                .op(Op.AND)
                .children(List.of(term("a", "1"), term("b", "2")))
                .build();

        final LogicalPlan result = rule.apply(withWhere(input));

        assertThat(((Filter) result).wherePredicate()).isEqualTo(input);
    }

    @Test
    void nullPredicateSlots_areLeftNull() {
        final LogicalPlan result = rule.apply(withWhere(null));

        assertThat(((Filter) result).wherePredicate()).isNull();
        assertThat(((Filter) result).filterPredicate()).isNull();
    }

    private static ExpressionOperator foldableDoubleNegation() {
        // AND(NOT(NOT(a)), b) - folds to AND(a, b); see doubleNegation_collapsesToInnerTerm.
        return ExpressionOperator.builder()
                .op(Op.AND)
                .children(List.of(
                        ExpressionOperator.builder().op(Op.NOT).children(List.of(
                                ExpressionOperator.builder().op(Op.NOT).children(List.of(term("a", "1"))).build()
                        )).build(),
                        term("b", "2")))
                .build();
    }

    private static ExpressionOperator foldedDoubleNegation() {
        return ExpressionOperator.builder().op(Op.AND).children(List.of(term("a", "1"), term("b", "2"))).build();
    }

    @Test
    void foldsTheFilterPredicateSlot_notJustWhere() {
        // PlanRewriteUtil.mapPredicates must transform the Filter.filterPredicate slot too, not only wherePredicate.
        final LogicalPlan plan = new Filter(new Scan("s", "Source", POS), null, foldableDoubleNegation(), POS);

        final Filter result = (Filter) rule.apply(plan);

        assertThat(result.wherePredicate()).isNull();
        assertThat(result.filterPredicate()).isEqualTo(foldedDoubleNegation());
    }

    @Test
    void foldsTheHavingPredicate() {
        // PlanRewriteUtil.mapPredicates must transform a Having node's predicate.
        final LogicalPlan plan = new Having(new Scan("s", "Source", POS), foldableDoubleNegation(), POS);

        final Having result = (Having) rule.apply(plan);

        assertThat(result.predicate()).isEqualTo(foldedDoubleNegation());
    }

    @Test
    void notWithNonNotInner_isNotCollapsed() {
        // NOT(term) has no double negation to fold - must be left as-is.
        final ExpressionOperator input = ExpressionOperator.builder()
                .op(Op.NOT)
                .children(List.of(term("a", "1")))
                .build();

        final LogicalPlan result = rule.apply(withWhere(input));

        assertThat(((Filter) result).wherePredicate()).isEqualTo(input);
    }

    // ------------------------------------------------------------------------------------------------------
    // Task 8.4: a disabled item is opaque - never recursed into, never collapsed through, never hoisted.
    // ------------------------------------------------------------------------------------------------------

    @Test
    void disabledSubTree_isOpaque_notRecursedInto() {
        // A disabled AND containing a foldable NOT(NOT(x)) must come back byte-identical - the evaluator
        // ignores the whole sub-tree, so there is nothing in it the fold's equivalences apply to.
        final ExpressionOperator disabledSubTree = ExpressionOperator.builder()
                .op(Op.AND)
                .enabled(false)
                .children(List.of(
                        ExpressionOperator.builder().op(Op.NOT).children(List.of(
                                ExpressionOperator.builder().op(Op.NOT).children(List.of(term("a", "1"))).build()
                        )).build()))
                .build();
        final ExpressionOperator input = ExpressionOperator.builder()
                .op(Op.AND)
                .children(List.of(disabledSubTree, term("b", "2")))
                .build();

        final LogicalPlan result = rule.apply(withWhere(input));

        assertThat(((Filter) result).wherePredicate()).isEqualTo(input);
    }

    @Test
    void disabledSingleChildAnd_isNotCollapsed_theCollapseWouldDropTheDisabledFlag() {
        // AND(AND'(x), y) where AND' is disabled: collapsing AND'(x) -> x would silently re-enable x.
        final ExpressionOperator disabledWrapper = ExpressionOperator.builder()
                .op(Op.AND)
                .enabled(false)
                .children(List.of(term("a", "1")))
                .build();
        final ExpressionOperator input = ExpressionOperator.builder()
                .op(Op.AND)
                .children(List.of(disabledWrapper, term("b", "2")))
                .build();

        final LogicalPlan result = rule.apply(withWhere(input));

        assertThat(((Filter) result).wherePredicate()).isEqualTo(input);
    }

    @Test
    void enabledNotOverDisabledNot_isNotCollapsed() {
        // NOT(NOT'(x)) with the inner NOT disabled is not a double negation to the evaluator (it sees NOT with
        // no effective children) - collapsing to x would both change the logic and re-enable x's sub-tree.
        final ExpressionOperator input = ExpressionOperator.builder()
                .op(Op.NOT)
                .children(List.of(
                        ExpressionOperator.builder().op(Op.NOT).enabled(false)
                                .children(List.of(term("a", "1"))).build()))
                .build();

        final LogicalPlan result = rule.apply(withWhere(input));

        assertThat(((Filter) result).wherePredicate()).isEqualTo(input);
    }

    @Test
    void doubleNegation_overADisabledInnermostItem_isNotCollapsed() {
        // NOT(NOT(x')) with x' disabled: the evaluator sees an inner NOT with no effective children, which is
        // not equivalent to a bare disabled x' - the hoist must not happen.
        final ExpressionTerm disabledTerm = ExpressionTerm.builder()
                .field("a").condition(Condition.EQUALS).value("1").enabled(false).build();
        final ExpressionOperator input = ExpressionOperator.builder()
                .op(Op.NOT)
                .children(List.of(
                        ExpressionOperator.builder().op(Op.NOT).children(List.of(disabledTerm)).build()))
                .build();

        final LogicalPlan result = rule.apply(withWhere(input));

        assertThat(((Filter) result).wherePredicate()).isEqualTo(input);
    }

    @Test
    void singleChildCollapse_doesNotHoistADisabledChild() {
        // AND(OR(x'), y) with x' disabled: OR(x') -> x' would splice a disabled item into the parent AND,
        // erasing the caller's "an enabled OR wrapper around a switched-off predicate" structure.
        final ExpressionTerm disabledTerm = ExpressionTerm.builder()
                .field("a").condition(Condition.EQUALS).value("1").enabled(false).build();
        final ExpressionOperator input = ExpressionOperator.builder()
                .op(Op.AND)
                .children(List.of(
                        ExpressionOperator.builder().op(Op.OR).children(List.of(disabledTerm)).build(),
                        term("b", "2")))
                .build();

        final LogicalPlan result = rule.apply(withWhere(input));

        assertThat(((Filter) result).wherePredicate()).isEqualTo(input);
    }
}
