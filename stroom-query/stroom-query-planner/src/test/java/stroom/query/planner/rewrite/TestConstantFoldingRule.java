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
}
