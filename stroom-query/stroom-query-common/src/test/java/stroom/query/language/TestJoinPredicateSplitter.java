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

package stroom.query.language;

import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.api.JoinSpec;
import stroom.query.api.datasource.QueryField;
import stroom.query.planner.port.FieldInfoSource;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link JoinPredicateSplitter}'s per-conjunct decision (push left / push right / residual) - see
 * decision D3 (Phase 1, item A1). Uses index-eligible
 * fields (a real {@code ConditionSet}, {@code queryable == true}) so these tests actually exercise a push,
 * unlike the field-metadata-free fixtures used elsewhere in this package for join compilation.
 */
class TestJoinPredicateSplitter {

    private static final String LEFT_SOURCE = "Events";
    private static final String RIGHT_SOURCE = "Users";
    private static final QueryField ELIGIBLE_LEFT_FIELD = QueryField.createLong("StreamId");
    private static final QueryField ELIGIBLE_RIGHT_FIELD = QueryField.createLong("Id");
    private static final QueryField NOT_QUERYABLE_FIELD = QueryField.createLong("Hidden", false);
    private static final QueryField NO_CONDITION_SET_FIELD =
            QueryField.builder().fldName("NoConditionSet").fldType(stroom.query.api.datasource.FieldType.LONG)
                    .build();

    private static final FieldInfoSource FIELD_INFO_SOURCE = new FieldInfoSource() {
        @Override
        public List<QueryField> getFields(final String dataSourceName) {
            return switch (dataSourceName) {
                case LEFT_SOURCE ->
                        List.of(ELIGIBLE_LEFT_FIELD, NOT_QUERYABLE_FIELD, NO_CONDITION_SET_FIELD);
                case RIGHT_SOURCE -> List.of(ELIGIBLE_RIGHT_FIELD);
                default -> List.of();
            };
        }

        @Override
        public Optional<stroom.query.api.datasource.QueryField> getTimeField(final String dataSourceName) {
            return Optional.empty();
        }
    };

    private static final JoinPredicateSplitter SPLITTER = new JoinPredicateSplitter(FIELD_INFO_SOURCE);

    private static JoinPredicateSplitter.Split split(
            final ExpressionOperator where, final JoinSpec.JoinType joinType) {
        return SPLITTER.split(where, "a", LEFT_SOURCE, "b", RIGHT_SOURCE, joinType);
    }

    @Test
    void singleEligibleLeftTerm_pushesToLeft_andEmptiesResidual() {
        final ExpressionOperator where = ExpressionOperator.builder()
                .addTerm("a.StreamId", Condition.EQUALS, "1")
                .build();

        final JoinPredicateSplitter.Split result = split(where, JoinSpec.JoinType.INNER);

        assertThat(result.leftPush()).isNotNull();
        assertThat(result.leftPush().getChildren()).hasSize(1);
        // The alias is stripped: the side's own sub-query knows the field only as "StreamId", not "a.StreamId".
        assertThat(result.leftPush().toString()).contains("StreamId").doesNotContain("a.StreamId");
        assertThat(result.rightPush()).isNull();
        assertThat(result.residual().getChildren()).isNullOrEmpty();
    }

    @Test
    void singleEligibleRightTerm_innerJoin_pushesToRight() {
        final ExpressionOperator where = ExpressionOperator.builder()
                .addTerm("b.Id", Condition.EQUALS, "2")
                .build();

        final JoinPredicateSplitter.Split result = split(where, JoinSpec.JoinType.INNER);

        assertThat(result.rightPush()).isNotNull();
        assertThat(result.rightPush().toString()).contains("Id").doesNotContain("b.Id");
        assertThat(result.leftPush()).isNull();
        assertThat(result.residual().getChildren()).isNullOrEmpty();
    }

    @Test
    void singleEligibleRightTerm_leftJoin_neverPushes_staysResidual() {
        // The right side of a LEFT join is the null-supplying side - pre-filtering it would silently drop
        // candidate matches that a LEFT join is supposed to null-pad, not exclude. See split's Javadoc.
        final ExpressionOperator where = ExpressionOperator.builder()
                .addTerm("b.Id", Condition.EQUALS, "2")
                .build();

        final JoinPredicateSplitter.Split result = split(where, JoinSpec.JoinType.LEFT);

        assertThat(result.rightPush()).isNull();
        assertThat(result.leftPush()).isNull();
        assertThat(result.residual()).isEqualTo(where);
    }

    @Test
    void eligibleLeftTerm_leftJoin_stillPushes_onlyRightIsRestricted() {
        final ExpressionOperator where = ExpressionOperator.builder()
                .addTerm("a.StreamId", Condition.EQUALS, "1")
                .build();

        final JoinPredicateSplitter.Split result = split(where, JoinSpec.JoinType.LEFT);

        assertThat(result.leftPush()).isNotNull();
        assertThat(result.residual().getChildren()).isNullOrEmpty();
    }

    @Test
    void mixedEligibleLeftAndRight_bothPush_onInnerJoin() {
        final ExpressionOperator where = ExpressionOperator.builder()
                .addTerm("a.StreamId", Condition.EQUALS, "1")
                .addTerm("b.Id", Condition.EQUALS, "2")
                .build();

        final JoinPredicateSplitter.Split result = split(where, JoinSpec.JoinType.INNER);

        assertThat(result.leftPush()).isNotNull();
        assertThat(result.rightPush()).isNotNull();
        assertThat(result.residual().getChildren()).isNullOrEmpty();
    }

    @Test
    void unqualifiedField_staysResidual() {
        final ExpressionOperator where = ExpressionOperator.builder()
                .addTerm("StreamId", Condition.EQUALS, "1")
                .build();

        final JoinPredicateSplitter.Split result = split(where, JoinSpec.JoinType.INNER);

        assertThat(result.leftPush()).isNull();
        assertThat(result.rightPush()).isNull();
        assertThat(result.residual()).isEqualTo(where);
    }

    @Test
    void nonQueryableField_staysResidual_evenThoughAliasQualifiedAndUniqueToOneSide() {
        final ExpressionOperator where = ExpressionOperator.builder()
                .addTerm("a.Hidden", Condition.EQUALS, "1")
                .build();

        final JoinPredicateSplitter.Split result = split(where, JoinSpec.JoinType.INNER);

        assertThat(result.leftPush()).isNull();
        assertThat(result.residual()).isEqualTo(where);
    }

    @Test
    void fieldWithNoConditionSet_staysResidual() {
        final ExpressionOperator where = ExpressionOperator.builder()
                .addTerm("a.NoConditionSet", Condition.EQUALS, "1")
                .build();

        final JoinPredicateSplitter.Split result = split(where, JoinSpec.JoinType.INNER);

        assertThat(result.leftPush()).isNull();
        assertThat(result.residual()).isEqualTo(where);
    }

    @Test
    void unknownField_staysResidual() {
        final ExpressionOperator where = ExpressionOperator.builder()
                .addTerm("a.DoesNotExist", Condition.EQUALS, "1")
                .build();

        final JoinPredicateSplitter.Split result = split(where, JoinSpec.JoinType.INNER);

        assertThat(result.leftPush()).isNull();
        assertThat(result.residual()).isEqualTo(where);
    }

    @Test
    void disabledTerm_neverPushed_staysResidual() {
        final ExpressionOperator where = ExpressionOperator.builder()
                .addTerm(stroom.query.api.ExpressionTerm.builder()
                        .enabled(false)
                        .field("a.StreamId")
                        .condition(Condition.EQUALS)
                        .value("1")
                        .build())
                .build();

        final JoinPredicateSplitter.Split result = split(where, JoinSpec.JoinType.INNER);

        assertThat(result.leftPush()).isNull();
        assertThat(result.residual()).isEqualTo(where);
    }

    @Test
    void nestedOperatorConjunct_neverPushed_evenIfEverythingInsideIsEligibleAndOneSided() {
        // A parenthesised sub-expression at the top level is conservatively never pushed - only bare terms are,
        // matching AutoWhereFilterSplitRule's own "can't resolve with full confidence -> leave it" convention.
        final ExpressionOperator nested = ExpressionOperator.builder()
                .op(Op.OR)
                .addTerm("a.StreamId", Condition.EQUALS, "1")
                .addTerm("a.StreamId", Condition.EQUALS, "2")
                .build();
        final ExpressionOperator where = ExpressionOperator.builder().addOperator(nested).build();

        final JoinPredicateSplitter.Split result = split(where, JoinSpec.JoinType.INNER);

        assertThat(result.leftPush()).isNull();
        assertThat(result.residual()).isEqualTo(where);
    }

    @Test
    void topLevelOr_neverDecomposed_wholeThingResidual() {
        final ExpressionOperator where = ExpressionOperator.builder()
                .op(Op.OR)
                .addTerm("a.StreamId", Condition.EQUALS, "1")
                .addTerm("b.Id", Condition.EQUALS, "2")
                .build();

        final JoinPredicateSplitter.Split result = split(where, JoinSpec.JoinType.INNER);

        assertThat(result.leftPush()).isNull();
        assertThat(result.rightPush()).isNull();
        assertThat(result.residual()).isEqualTo(where);
    }

    @Test
    void mixedEligibleAndIneligible_onlyEligibleOnesPush_restStayResidual() {
        final ExpressionOperator where = ExpressionOperator.builder()
                .addTerm("a.StreamId", Condition.EQUALS, "1")
                .addTerm("a.Hidden", Condition.EQUALS, "2")
                .addTerm("StreamId", Condition.EQUALS, "3")
                .build();

        final JoinPredicateSplitter.Split result = split(where, JoinSpec.JoinType.INNER);

        assertThat(result.leftPush().getChildren()).hasSize(1);
        assertThat(result.residual().getChildren()).hasSize(2);
    }
}
