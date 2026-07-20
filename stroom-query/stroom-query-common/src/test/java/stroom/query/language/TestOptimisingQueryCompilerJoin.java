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

import stroom.query.api.DateTimeSettings;
import stroom.query.api.JoinSpec;
import stroom.query.api.SearchRequest;
import stroom.query.api.datasource.FieldType;
import stroom.query.api.datasource.QueryField;
import stroom.query.api.token.TokenException;
import stroom.query.common.v2.JoinDataSourceType;
import stroom.query.grammar.parse.SyntaxException;
import stroom.query.language.functions.ExpressionContext;
import stroom.query.planner.port.FieldInfoSource;
import stroom.security.mock.MockSecurityContext;
import stroom.util.shared.ResultPage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 6.1x: proves {@link OptimisingQueryCompiler#create} now compiles a two-source join query into a
 * {@link SearchRequest} whose {@code Query.dataSource} routes to the sentinel {@link JoinDataSourceType#TYPE}
 * with a populated {@code JoinSpec} - see {@code docs/query-optimiser-implementation-plan.md}, Phase 6, for the
 * finding that made this task much smaller than originally scoped (the mapper's existing blind field-text
 * passthrough already handles alias-qualified references correctly, for explicitly-aliased fields).
 */
class TestOptimisingQueryCompilerJoin {

    private static final QueryField EVENTS_STREAM_ID = QueryField.builder()
            .fldName("StreamId").fldType(FieldType.LONG).build();
    private static final QueryField EVENTS_USER_ID = QueryField.builder()
            .fldName("UserId").fldType(FieldType.LONG).build();
    private static final QueryField USERS_ID = QueryField.builder()
            .fldName("Id").fldType(FieldType.LONG).build();
    private static final QueryField USERS_NAME = QueryField.builder()
            .fldName("Name").fldType(FieldType.TEXT).build();

    private static final FieldInfoSource FIELD_INFO_SOURCE = new FieldInfoSource() {
        @Override
        public List<QueryField> getFields(final String dataSourceName) {
            return switch (dataSourceName) {
                case "Events" -> List.of(EVENTS_STREAM_ID, EVENTS_USER_ID);
                case "Users" -> List.of(USERS_ID, USERS_NAME);
                default -> List.of();
            };
        }

        @Override
        public Optional<QueryField> getTimeField(final String dataSourceName) {
            return Optional.empty();
        }
    };

    private OptimisingQueryCompiler compiler() {
        return new OptimisingQueryCompiler(
                (keywordGroup, parentTableSettings) -> null,
                MockDataSourceResolver.getInstance(),
                () -> criteria -> ResultPage.createUnboundedList(
                        FIELD_INFO_SOURCE.getFields(criteria.getDataSourceRef().getName())),
                MockSecurityContext.getInstance(),
                FIELD_INFO_SOURCE,
                (feedName, from, to) -> Optional.empty(),
                (indexName, from, to) -> Optional.empty(),
                storeName -> Optional.empty());
    }

    private ExpressionContext expressionContext() {
        return ExpressionContext.builder()
                .dateTimeSettings(DateTimeSettings.builder().referenceTime(0L).build())
                .maxStringLength(100)
                .build();
    }

    private static SearchRequest emptySeedRequest() {
        return new SearchRequest(null, null, null, null, null, false, null);
    }

    @Test
    void joinQuery_compilesToSentinelDataSourceWithPopulatedJoinSpec() {
        final SearchRequest result = compiler().create(
                "from \"Events\" as a join \"Users\" as b on a.UserId = b.Id "
                + "where a.StreamId = 1 select a.StreamId, b.Name",
                emptySeedRequest(), expressionContext());

        assertThat(result.getQuery().getDataSource().getType()).isEqualTo(JoinDataSourceType.TYPE);

        final JoinSpec joinSpec = result.getQuery().getJoinSpec();
        assertThat(joinSpec).isNotNull();
        assertThat(joinSpec.getJoinType()).isEqualTo(JoinSpec.JoinType.INNER);
        assertThat(joinSpec.getLeft().getQuery().getDataSource().getName()).isEqualTo("Events");
        assertThat(joinSpec.getRight().getQuery().getDataSource().getName()).isEqualTo("Users");
        assertThat(joinSpec.getEquiKeys()).hasSize(1);
        assertThat(joinSpec.getEquiKeys().getFirst().toString()).isEqualTo("a.UserId = b.Id");

        // The outer where/select clauses (alias-qualified) compiled without needing any new machinery.
        assertThat(result.getQuery().getExpression().toString()).contains("StreamId");
    }

    @Test
    void leftJoin_mapsToWireLeftJoinType() {
        final SearchRequest result = compiler().create(
                "from \"Events\" as a left join \"Users\" as b on a.UserId = b.Id select a.StreamId",
                emptySeedRequest(), expressionContext());

        assertThat(result.getQuery().getJoinSpec().getJoinType()).isEqualTo(JoinSpec.JoinType.LEFT);
    }

    @Test
    void starredSelectInJoin_rejectedCleanly() {
        assertThatThrownBy(() -> compiler().create(
                "from \"Events\" as a join \"Users\" as b on a.UserId = b.Id select *",
                emptySeedRequest(), expressionContext()))
                .isInstanceOf(TokenException.class);
    }

    @Test
    void multiJoinChain_isRejectedCleanly() {
        // Only a single join is supported for now; a chain of more than one join must be rejected with a clear
        // dedicated message rather than silently mis-compiled (createJoin/findJoin only handle one Join node).
        assertThatThrownBy(() -> compiler().create(
                "from \"Events\" as a join \"Users\" as b on a.UserId = b.Id "
                + "join \"Events\" as c on b.Id = c.UserId select a.StreamId",
                emptySeedRequest(), expressionContext()))
                .isInstanceOf(TokenException.class)
                .hasMessageContaining("single join");
    }

    // ------------------------------------------------------------------------------------------------------
    // Task A1 (see docs/join-scalability-implementation-plan.md): per-side predicate push-down, end-to-end.
    // A separate, index-eligible field-info source is used here (rather than the plain FIELD_INFO_SOURCE above,
    // whose fields have no ConditionSet and are therefore never index-eligible) so these tests actually exercise
    // a push. See TestJoinPredicateSplitter for the split logic's own thorough unit coverage.
    // ------------------------------------------------------------------------------------------------------

    private static final FieldInfoSource ELIGIBLE_FIELD_INFO_SOURCE = new FieldInfoSource() {
        @Override
        public List<QueryField> getFields(final String dataSourceName) {
            return switch (dataSourceName) {
                case "Events" -> List.of(QueryField.createLong("StreamId"), QueryField.createLong("UserId"));
                case "Users" -> List.of(QueryField.createLong("Id"));
                default -> List.of();
            };
        }

        @Override
        public Optional<QueryField> getTimeField(final String dataSourceName) {
            return Optional.empty();
        }
    };

    private OptimisingQueryCompiler eligibleCompiler() {
        return new OptimisingQueryCompiler(
                (keywordGroup, parentTableSettings) -> null,
                MockDataSourceResolver.getInstance(),
                () -> criteria -> ResultPage.createUnboundedList(
                        ELIGIBLE_FIELD_INFO_SOURCE.getFields(criteria.getDataSourceRef().getName())),
                MockSecurityContext.getInstance(),
                ELIGIBLE_FIELD_INFO_SOURCE,
                (feedName, from, to) -> Optional.empty(),
                (indexName, from, to) -> Optional.empty(),
                storeName -> Optional.empty());
    }

    @Test
    void eligibleLeftWhereTerm_isPushedToTheLeftSide_andRemovedFromTheResidual() {
        final SearchRequest result = eligibleCompiler().create(
                "from \"Events\" as a join \"Users\" as b on a.UserId = b.Id "
                + "where a.StreamId = 1 select a.StreamId, b.Id",
                emptySeedRequest(), expressionContext());

        final JoinSpec joinSpec = result.getQuery().getJoinSpec();
        // Pushed onto the left side's own sub-query, alias stripped: it knows the field as "StreamId", not
        // "a.StreamId" (a single-source side has no concept of the outer join's alias).
        assertThat(joinSpec.getLeft().getQuery().getExpression().toString())
                .contains("StreamId").doesNotContain("a.StreamId");
        // Removed from the outer residual - nothing left to gate the combined row on.
        assertThat(result.getQuery().getExpression().getChildren()).isNullOrEmpty();
    }

    @Test
    void eligibleRightWhereTerm_innerJoin_isPushedToTheRightSide() {
        final SearchRequest result = eligibleCompiler().create(
                "from \"Events\" as a join \"Users\" as b on a.UserId = b.Id "
                + "where b.Id = 2 select a.StreamId, b.Id",
                emptySeedRequest(), expressionContext());

        final JoinSpec joinSpec = result.getQuery().getJoinSpec();
        assertThat(joinSpec.getRight().getQuery().getExpression().toString())
                .contains("Id").doesNotContain("b.Id");
        assertThat(result.getQuery().getExpression().getChildren()).isNullOrEmpty();
    }

    @Test
    void eligibleRightWhereTerm_leftJoin_isNeverPushed_staysInTheResidual() {
        // The right side of a LEFT join is the null-supplying side: pre-filtering it before the join would
        // silently exclude candidate rows a LEFT join is supposed to null-pad, not drop - see
        // JoinPredicateSplitter.split's Javadoc. This is the single most correctness-sensitive case in A1.
        final SearchRequest result = eligibleCompiler().create(
                "from \"Events\" as a left join \"Users\" as b on a.UserId = b.Id "
                + "where b.Id = 2 select a.StreamId, b.Id",
                emptySeedRequest(), expressionContext());

        final JoinSpec joinSpec = result.getQuery().getJoinSpec();
        // Nothing pushed to the right side's sub-query: it is still a plain "select *" with no where clause
        // (an empty, not null, expression - the same shape any unfiltered compileJoinSide call produces).
        assertThat(joinSpec.getRight().getQuery().getExpression().getChildren()).isNullOrEmpty();
        // The b.Id = 2 predicate is still alias-qualified and present in the outer residual, to be evaluated
        // across the combined (possibly null-padded) row by JoinSearchProvider.whereRowPredicate.
        assertThat(result.getQuery().getExpression().toString()).contains("b.Id");
    }

    // ------------------------------------------------------------------------------------------------------
    // Task A3 (see docs/join-scalability-implementation-plan.md, §3): a pushed time-bound predicate must prune
    // shards on that side, not just filter rows after they're read - NodeSearchTaskCreator.getPartitionTimeRange
    // only ever reads Query.timeRange, never derives bounds from Query.expression, so createJoin must promote a
    // pushed time predicate into that side's Query.timeRange (reusing Task 5.2's applyTimeRange).
    // ------------------------------------------------------------------------------------------------------

    private static final FieldInfoSource TIME_FIELD_INFO_SOURCE = new FieldInfoSource() {
        @Override
        public List<QueryField> getFields(final String dataSourceName) {
            return switch (dataSourceName) {
                case "Events" -> List.of(QueryField.createLong("UserId"), QueryField.createDate("EventTime"));
                case "Users" -> List.of(QueryField.createLong("Id"));
                default -> List.of();
            };
        }

        @Override
        public Optional<QueryField> getTimeField(final String dataSourceName) {
            return "Events".equals(dataSourceName)
                    ? Optional.of(QueryField.createDate("EventTime"))
                    : Optional.empty();
        }
    };

    private OptimisingQueryCompiler timeAwareCompiler() {
        return new OptimisingQueryCompiler(
                (keywordGroup, parentTableSettings) -> null,
                MockDataSourceResolver.getInstance(),
                () -> criteria -> ResultPage.createUnboundedList(
                        TIME_FIELD_INFO_SOURCE.getFields(criteria.getDataSourceRef().getName())),
                MockSecurityContext.getInstance(),
                TIME_FIELD_INFO_SOURCE,
                (feedName, from, to) -> Optional.empty(),
                (indexName, from, to) -> Optional.empty(),
                storeName -> Optional.empty());
    }

    @Test
    void pushedTimeBoundPredicate_promotedToTheSideSQueryTimeRange_forShardPruning() {
        final SearchRequest result = timeAwareCompiler().create(
                "from \"Events\" as a join \"Users\" as b on a.UserId = b.Id "
                + "where a.EventTime > '2020-01-01T00:00:00.000Z' select a.UserId, b.Id",
                emptySeedRequest(), expressionContext());

        final JoinSpec joinSpec = result.getQuery().getJoinSpec();
        // Pushed onto the left side's sub-query (alias stripped) ...
        assertThat(joinSpec.getLeft().getQuery().getExpression().toString())
                .contains("EventTime").doesNotContain("a.EventTime");
        // ... AND promoted into that side's Query.timeRange, so shard pruning actually fires - a filtered
        // Query.expression alone is not enough (NodeSearchTaskCreator.getPartitionTimeRange never looks at it).
        assertThat(joinSpec.getLeft().getQuery().getTimeRange()).isNotNull();
        assertThat(joinSpec.getLeft().getQuery().getTimeRange().getFrom()).isNotBlank();
    }

    @Test
    void noTimeBoundPredicate_leavesSideSQueryTimeRangeNull() {
        final SearchRequest result = timeAwareCompiler().create(
                "from \"Events\" as a join \"Users\" as b on a.UserId = b.Id select a.UserId, b.Id",
                emptySeedRequest(), expressionContext());

        final JoinSpec joinSpec = result.getQuery().getJoinSpec();
        assertThat(joinSpec.getLeft().getQuery().getTimeRange()).isNull();
        assertThat(joinSpec.getRight().getQuery().getTimeRange()).isNull();
    }

    /**
     * Task B5 (see {@code docs/join-scalability-implementation-plan.md}, decision D1's Phase 0): {@code RIGHT}
     * (and {@code FULL}) joins are not reserved keywords in the join clause's grammar rule at all
     * ({@code joinType=(LEFT | INNER)?} - only those two keywords are recognised there), so attempting one is a
     * plain parse failure - a clear {@link SyntaxException}, not a silently mis-executed or wrong-result join.
     */
    @Test
    void rightJoin_isRejectedAtParseTime_notASilentMisexecution() {
        assertThatThrownBy(() -> compiler().create(
                "from \"Events\" as a right join \"Users\" as b on a.UserId = b.Id select a.StreamId",
                emptySeedRequest(), expressionContext()))
                .isInstanceOf(SyntaxException.class);
    }
}
