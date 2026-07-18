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
import stroom.query.planner.logical.EquiKey;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.Join;
import stroom.query.planner.logical.JoinType;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.QualifiedField;
import stroom.query.planner.logical.Scan;
import stroom.query.planner.port.FieldInfoSource;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Task 2.3: a combination test running the fixed {@link RewritePipeline#standard(FieldInfoSource)} sequence,
 * where each rule's output is what the next rule needs - not just each rule tested alone (see
 * {@link TestConstantFoldingRule}, {@link TestRedundantTermPruningRule}, {@link TestAutoWhereFilterSplitRule},
 * {@link TestPushFiltersBelowJoinsRule} for the isolation tests).
 */
class TestRewritePipeline {

    private static final AstPosition POS = new AstPosition(1, 0);

    private static final QueryField STREAM_ID = QueryField.builder()
            .fldName("StreamId").fldType(FieldType.LONG)
            .queryable(true).conditionSet(ConditionSet.DEFAULT_NUMERIC)
            .build();
    private static final QueryField DESCRIPTION = QueryField.builder()
            .fldName("Description").fldType(FieldType.TEXT)
            .queryable(false).conditionSet(ConditionSet.DEFAULT_KEYWORD)
            .build();

    private static final FieldInfoSource FIELD_INFO_SOURCE = new FakeFieldInfoSource(Map.of(
            "Events", List.of(STREAM_ID, DESCRIPTION),
            "Users", List.of()));

    private static ExpressionTerm term(final String field, final Condition condition, final String value) {
        return ExpressionTerm.builder().field(field).condition(condition).value(value).build();
    }

    @Test
    void structuralCleanup_splitAndPushdown_allApplyInSequence() {
        // AND(AND(e.StreamId=1, e.StreamId=1), NOT(NOT(e.Description="x"))) - noisy, but every reference is on
        // the "e" (Events) side of the join.
        final ExpressionOperator noisyWhere = ExpressionOperator.builder()
                .op(Op.AND)
                .children(List.of(
                        ExpressionOperator.builder().op(Op.AND)
                                .children(List.of(
                                        term("e.StreamId", Condition.EQUALS, "1"),
                                        term("e.StreamId", Condition.EQUALS, "1")))
                                .build(),
                        ExpressionOperator.builder().op(Op.NOT).children(List.of(
                                ExpressionOperator.builder().op(Op.NOT)
                                        .children(List.of(term("e.Description", Condition.EQUALS, "x")))
                                        .build()
                        )).build()))
                .build();

        final Scan events = new Scan("e", "Events", POS);
        final Scan users = new Scan("u", "Users", POS);
        final Join join = new Join(events, users, JoinType.INNER,
                List.of(new EquiKey(new QualifiedField("e", "UserId"), new QualifiedField("u", "Id"))), POS);
        final LogicalPlan input = new Filter(join, noisyWhere, null, POS);

        final LogicalPlan result = RewritePipeline.standard(FIELD_INFO_SOURCE).run(input);

        // Constant folding collapses the double negation and the nested single-child AND; redundant-term
        // pruning drops the duplicate StreamId=1; auto where/filter split separates the queryable StreamId
        // (stays "where") from the non-queryable Description (moves to "filter"); push-filters-below-joins then
        // relocates BOTH (every reference is to "e") onto the left side, removing the now-empty enclosing
        // Filter entirely - the result is the bare Join, left side wrapped in its own Filter.
        assertThat(result).isInstanceOf(Join.class);
        final Join resultJoin = (Join) result;
        assertThat(resultJoin.right()).isEqualTo(users);
        assertThat(resultJoin.left()).isEqualTo(new Filter(
                events,
                ExpressionOperator.builder().children(List.of(term("e.StreamId", Condition.EQUALS, "1"))).build(),
                ExpressionOperator.builder().children(List.of(term("e.Description", Condition.EQUALS, "x"))).build(),
                POS));
    }

    @Test
    void runRejectsNullPlan() {
        assertThatNullPointerException()
                .isThrownBy(() -> RewritePipeline.standard(FIELD_INFO_SOURCE).run(null));
    }

    @Test
    void constructorRejectsNullRules() {
        assertThatNullPointerException().isThrownBy(() -> new RewritePipeline(null));
    }
}
