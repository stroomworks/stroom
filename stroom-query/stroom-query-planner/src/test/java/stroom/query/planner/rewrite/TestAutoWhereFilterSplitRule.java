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
import stroom.query.api.datasource.ConditionSet;
import stroom.query.api.datasource.FieldType;
import stroom.query.api.datasource.QueryField;
import stroom.query.grammar.ast.AstPosition;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.Scan;
import stroom.query.planner.port.FieldInfoSource;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 2.3: isolation tests for {@link AutoWhereFilterSplitRule}.
 */
class TestAutoWhereFilterSplitRule {

    private static final AstPosition POS = new AstPosition(1, 0);

    private static final QueryField QUERYABLE_NUMERIC = QueryField.builder()
            .fldName("StreamId").fldType(FieldType.LONG)
            .queryable(true).conditionSet(ConditionSet.DEFAULT_NUMERIC)
            .build();
    private static final QueryField NOT_QUERYABLE = QueryField.builder()
            .fldName("Description").fldType(FieldType.TEXT)
            .queryable(false).conditionSet(ConditionSet.DEFAULT_KEYWORD)
            .build();
    private static final QueryField QUERYABLE_BOOLEAN = QueryField.builder()
            .fldName("Flag").fldType(FieldType.BOOLEAN)
            .queryable(true).conditionSet(ConditionSet.DEFAULT_BOOLEAN) // no BETWEEN
            .build();

    private static final FieldInfoSource FIELD_INFO_SOURCE = new FakeFieldInfoSource(Map.of(
            "Events", List.of(QUERYABLE_NUMERIC, NOT_QUERYABLE, QUERYABLE_BOOLEAN)));

    private final AutoWhereFilterSplitRule rule = new AutoWhereFilterSplitRule(FIELD_INFO_SOURCE);

    private static ExpressionTerm term(final String field, final Condition condition, final String value) {
        return ExpressionTerm.builder().field(field).condition(condition).value(value).build();
    }

    private LogicalPlan filter(final ExpressionOperator where, final ExpressionOperator existingFilter) {
        return new Filter(new Scan("s", "Events", POS), where, existingFilter, POS);
    }

    @Test
    void constructorRejectsNullFieldInfoSource() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AutoWhereFilterSplitRule(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void allTermsEligible_isANoOp() {
        final ExpressionOperator where = ExpressionOperator.builder()
                .op(Op.AND)
                .children(List.of(term("StreamId", Condition.EQUALS, "1")))
                .build();

        final Filter result = (Filter) rule.apply(filter(where, null));

        assertThat(result.wherePredicate()).isEqualTo(where);
        assertThat(result.filterPredicate()).isNull();
    }

    @Test
    void mixedEligibility_splitsIntoWhereAndFilter() {
        final ExpressionOperator where = ExpressionOperator.builder()
                .op(Op.AND)
                .children(List.of(
                        term("StreamId", Condition.EQUALS, "1"),
                        term("Description", Condition.EQUALS, "text")))
                .build();

        final Filter result = (Filter) rule.apply(filter(where, null));

        assertThat(result.wherePredicate()).isEqualTo(
                ExpressionOperator.builder().children(List.of(term("StreamId", Condition.EQUALS, "1"))).build());
        assertThat(result.filterPredicate()).isEqualTo(
                ExpressionOperator.builder().children(List.of(term("Description", Condition.EQUALS, "text"))).build());
    }

    @Test
    void unsupportedConditionForField_movesToFilter() {
        final ExpressionOperator where = ExpressionOperator.builder()
                .op(Op.AND)
                .children(List.of(term("Flag", Condition.BETWEEN, "1, 2")))
                .build();

        final Filter result = (Filter) rule.apply(filter(where, null));

        assertThat(result.wherePredicate()).isNull();
        assertThat(result.filterPredicate()).isEqualTo(where);
    }

    @Test
    void unknownField_isTreatedConservativelyAsIneligible() {
        final ExpressionOperator where = ExpressionOperator.builder()
                .op(Op.AND)
                .children(List.of(term("Bogus", Condition.EQUALS, "1")))
                .build();

        final Filter result = (Filter) rule.apply(filter(where, null));

        assertThat(result.wherePredicate()).isNull();
        assertThat(result.filterPredicate()).isEqualTo(where);
    }

    @Test
    void explicitFilterAlreadyPresent_isANoOp() {
        final ExpressionOperator where = ExpressionOperator.builder()
                .op(Op.AND)
                .children(List.of(
                        term("StreamId", Condition.EQUALS, "1"),
                        term("Description", Condition.EQUALS, "text")))
                .build();
        final ExpressionOperator existingFilter = ExpressionOperator.builder()
                .children(List.of(term("Flag", Condition.EQUALS, "true")))
                .build();

        final Filter result = (Filter) rule.apply(filter(where, existingFilter));

        assertThat(result.wherePredicate()).isEqualTo(where);
        assertThat(result.filterPredicate()).isEqualTo(existingFilter);
    }

    @Test
    void nullWherePredicate_isANoOp() {
        final Filter result = (Filter) rule.apply(filter(null, null));

        assertThat(result.wherePredicate()).isNull();
        assertThat(result.filterPredicate()).isNull();
    }

    @Test
    void topLevelOr_isLeftEntirelyInWhere() {
        // A top-level OR can't be partially pushed - either all of it is evaluated at the datasource or none of
        // it is; this rule only ever splits an AND's direct conjuncts (see class Javadoc).
        final ExpressionOperator where = ExpressionOperator.builder()
                .op(Op.OR)
                .children(List.of(
                        term("StreamId", Condition.EQUALS, "1"),
                        term("Description", Condition.EQUALS, "text")))
                .build();

        final Filter result = (Filter) rule.apply(filter(where, null));

        assertThat(result.wherePredicate()).isEqualTo(where);
        assertThat(result.filterPredicate()).isNull();
    }
}
