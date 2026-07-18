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
import stroom.query.planner.logical.EquiKey;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.Join;
import stroom.query.planner.logical.JoinType;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.QualifiedField;
import stroom.query.planner.logical.Scan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 2.3: isolation tests for {@link PushFiltersBelowJoinsRule}.
 */
class TestPushFiltersBelowJoinsRule {

    private static final AstPosition POS = new AstPosition(1, 0);
    private final PushFiltersBelowJoinsRule rule = new PushFiltersBelowJoinsRule();

    private static ExpressionTerm term(final String field, final String value) {
        return ExpressionTerm.builder().field(field).condition(Condition.EQUALS).value(value).build();
    }

    private static ExpressionOperator wrap(final ExpressionTerm... terms) {
        return ExpressionOperator.builder().op(Op.AND).children(List.of(terms)).build();
    }

    private Join join(final LogicalPlan left, final LogicalPlan right) {
        return new Join(left, right, JoinType.INNER,
                List.of(new EquiKey(new QualifiedField("e", "UserId"), new QualifiedField("u", "Id"))), POS);
    }

    @Test
    void leftOnlyWherePredicate_isPushedOntoLeftSide() {
        final Scan left = new Scan("e", "Events", POS);
        final Scan right = new Scan("u", "Users", POS);
        final ExpressionOperator where = wrap(term("e.StreamId", "1"));
        final Filter input = new Filter(join(left, right), where, null, POS);

        final LogicalPlan result = rule.apply(input);

        assertThat(result).isInstanceOf(Join.class);
        final Join resultJoin = (Join) result;
        assertThat(resultJoin.left()).isEqualTo(new Filter(left, where, null, POS));
        assertThat(resultJoin.right()).isEqualTo(right);
    }

    @Test
    void rightOnlyFilterPredicate_isPushedOntoRightSide() {
        final Scan left = new Scan("e", "Events", POS);
        final Scan right = new Scan("u", "Users", POS);
        final ExpressionOperator filterPredicate = wrap(term("u.Name", "bob"));
        final Filter input = new Filter(join(left, right), null, filterPredicate, POS);

        final LogicalPlan result = rule.apply(input);

        final Join resultJoin = (Join) result;
        assertThat(resultJoin.left()).isEqualTo(left);
        assertThat(resultJoin.right()).isEqualTo(new Filter(right, null, filterPredicate, POS));
    }

    @Test
    void whereAndFilterEachPushToDifferentSides_removesEnclosingFilter() {
        final Scan left = new Scan("e", "Events", POS);
        final Scan right = new Scan("u", "Users", POS);
        final ExpressionOperator where = wrap(term("e.StreamId", "1"));
        final ExpressionOperator filterPredicate = wrap(term("u.Name", "bob"));
        final Filter input = new Filter(join(left, right), where, filterPredicate, POS);

        final LogicalPlan result = rule.apply(input);

        assertThat(result).isInstanceOf(Join.class);
        final Join resultJoin = (Join) result;
        assertThat(resultJoin.left()).isEqualTo(new Filter(left, where, null, POS));
        assertThat(resultJoin.right()).isEqualTo(new Filter(right, null, filterPredicate, POS));
    }

    @Test
    void predicateSpanningBothSides_isNotPushed() {
        final Scan left = new Scan("e", "Events", POS);
        final Scan right = new Scan("u", "Users", POS);
        final ExpressionOperator where = wrap(term("e.StreamId", "1"), term("u.Id", "2"));
        final Filter input = new Filter(join(left, right), where, null, POS);

        final LogicalPlan result = rule.apply(input);

        assertThat(result).isInstanceOf(Filter.class);
        final Filter resultFilter = (Filter) result;
        assertThat(resultFilter.wherePredicate()).isEqualTo(where);
        assertThat(resultFilter.input()).isEqualTo(join(left, right));
    }

    @Test
    void unqualifiedFieldReference_isNotPushed() {
        final Scan left = new Scan("e", "Events", POS);
        final Scan right = new Scan("u", "Users", POS);
        final ExpressionOperator where = wrap(term("StreamId", "1"));
        final Filter input = new Filter(join(left, right), where, null, POS);

        final LogicalPlan result = rule.apply(input);

        assertThat(result).isInstanceOf(Filter.class);
        assertThat(((Filter) result).wherePredicate()).isEqualTo(where);
    }

    @Test
    void pushingOntoAnAlreadyFilteredSide_mergesWithAnd() {
        final ExpressionOperator existingLeftWhere = wrap(term("e.EventTime", "2026-01-01"));
        final Scan leftScan = new Scan("e", "Events", POS);
        final Filter leftAlreadyFiltered = new Filter(leftScan, existingLeftWhere, null, POS);
        final Scan right = new Scan("u", "Users", POS);
        final ExpressionOperator pushedWhere = wrap(term("e.StreamId", "1"));
        final Filter input = new Filter(join(leftAlreadyFiltered, right), pushedWhere, null, POS);

        final LogicalPlan result = rule.apply(input);

        final Join resultJoin = (Join) result;
        assertThat(resultJoin.left()).isEqualTo(new Filter(
                leftScan,
                ExpressionOperator.builder().op(Op.AND).children(List.of(existingLeftWhere, pushedWhere)).build(),
                null,
                POS));
    }

    @Test
    void filterNotAboveAJoin_isUnchanged() {
        final Scan scan = new Scan("s", "Events", POS);
        final ExpressionOperator where = wrap(term("StreamId", "1"));
        final Filter input = new Filter(scan, where, null, POS);

        final LogicalPlan result = rule.apply(input);

        assertThat(result).isEqualTo(input);
    }
}
