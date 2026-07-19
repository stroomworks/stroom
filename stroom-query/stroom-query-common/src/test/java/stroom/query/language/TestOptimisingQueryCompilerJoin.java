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
}
