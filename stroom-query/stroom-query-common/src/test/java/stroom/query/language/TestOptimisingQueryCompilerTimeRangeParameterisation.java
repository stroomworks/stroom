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
import stroom.query.api.Query;
import stroom.query.api.SearchRequest;
import stroom.query.api.TimeRange;
import stroom.query.api.datasource.FieldType;
import stroom.query.api.datasource.QueryField;
import stroom.query.language.functions.ExpressionContext;
import stroom.query.planner.port.FieldInfoSource;
import stroom.security.mock.MockSecurityContext;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 5.2: proves {@link OptimisingQueryCompiler#create} derives {@code Query.timeRange} from a bare {@code
 * where}-clause time bound - the "safe, additive slice" of Phase 5's plan-driven execution - and that this
 * enhancement is fail-open (never worse than {@link AstToSearchRequestMapper}'s own output on any failure).
 */
class TestOptimisingQueryCompilerTimeRangeParameterisation {

    private static final QueryField STREAM_ID = QueryField.builder()
            .fldName("StreamId").fldType(FieldType.LONG).build();
    private static final QueryField EVENT_TIME = QueryField.builder()
            .fldName("EventTime").fldType(FieldType.DATE).build();

    private static final FieldInfoSource FIELD_INFO_SOURCE = new FieldInfoSource() {
        @Override
        public List<QueryField> getFields(final String dataSourceName) {
            return "Events".equals(dataSourceName) ? List.of(STREAM_ID, EVENT_TIME) : List.of();
        }

        @Override
        public Optional<QueryField> getTimeField(final String dataSourceName) {
            return "Events".equals(dataSourceName) ? Optional.of(EVENT_TIME) : Optional.empty();
        }
    };

    private OptimisingQueryCompiler compiler() {
        return new OptimisingQueryCompiler(
                (keywordGroup, parentTableSettings) -> null,
                MockDataSourceResolver.getInstance(),
                () -> criteria -> null,
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
    void bareWhereClauseTimeBound_populatesQueryTimeRange() {
        final SearchRequest result = compiler().create(
                "from \"Events\" where EventTime > 2020-01-01T00:00:00.000Z select StreamId",
                emptySeedRequest(),
                expressionContext());

        final TimeRange timeRange = result.getQuery().getTimeRange();
        assertThat(timeRange).isNotNull();
        assertThat(timeRange.getFrom()).isEqualTo("2020-01-01T00:00:00.000Z");
        assertThat(timeRange.getTo()).isBlank();
    }

    @Test
    void bareWhereClauseBetween_populatesBothBounds() {
        final SearchRequest result = compiler().create(
                "from \"Events\" where EventTime between 2020-01-01T00:00:00.000Z and 2020-02-01T00:00:00.000Z "
                + "select StreamId",
                emptySeedRequest(),
                expressionContext());

        final TimeRange timeRange = result.getQuery().getTimeRange();
        assertThat(timeRange).isNotNull();
        assertThat(timeRange.getFrom()).isEqualTo("2020-01-01T00:00:00.000Z");
        assertThat(timeRange.getTo()).isEqualTo("2020-02-01T00:00:00.000Z");
    }

    @Test
    void explicitUiTimeRange_isNeverOverridden() {
        final TimeRange uiSuppliedRange = new TimeRange("Last week", "2024-01-01T00:00:00.000Z",
                "2024-01-08T00:00:00.000Z");
        final SearchRequest seed = SearchRequest.builder()
                .query(Query.builder().timeRange(uiSuppliedRange).build())
                .build();

        final SearchRequest result = compiler().create(
                "from \"Events\" where EventTime > 2020-01-01T00:00:00.000Z select StreamId",
                seed,
                expressionContext());

        assertThat(result.getQuery().getTimeRange()).isEqualTo(uiSuppliedRange);
    }

    @Test
    void noTimeBoundInWhereClause_leavesTimeRangeNull() {
        final SearchRequest result = compiler().create(
                "from \"Events\" where StreamId = 1 select StreamId",
                emptySeedRequest(),
                expressionContext());

        assertThat(result.getQuery().getTimeRange()).isNull();
    }

    @Test
    void binderRejectsQueryThatTheMapperAccepts_fallsBackToUnmodifiedOutput() {
        // StreamId is LONG (DEFAULT_NUMERIC ConditionSet: EQUALS/NOT_EQUALS/BETWEEN/GT(E)/LT(E) only - no IN).
        // AstToSearchRequestMapper (which create() still relies on for the request itself) never validates
        // ConditionSet at all (Task 2.2), so an "in (...)" term compiles fine there, but Binder.bind(...) throws
        // BindException on the enhancement side. The overall create() call must still succeed and return
        // AstToSearchRequestMapper's plain output, not throw.
        final SearchRequest result = compiler().create(
                "from \"Events\" where StreamId in (1, 2, 3) select StreamId",
                emptySeedRequest(),
                expressionContext());

        assertThat(result.getQuery()).isNotNull();
        assertThat(result.getQuery().getTimeRange()).isNull();
    }
}
