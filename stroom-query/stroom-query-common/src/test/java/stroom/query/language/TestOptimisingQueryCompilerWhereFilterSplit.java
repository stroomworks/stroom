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
import stroom.query.api.ResultRequest;
import stroom.query.api.SearchRequest;
import stroom.query.api.TableSettings;
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
 * Task 5.3: proves {@link OptimisingQueryCompiler#create} routes the index-ineligible remainder of a bare {@code
 * where} clause to extraction-time filtering ({@code TableSettings.valueFilter}) instead of leaving it in the
 * scan-time {@code Query.expression}, Phase 5, and
 * {@code query-optimiser-known-differences.md} for the legacy divergence this fixes.
 */
class TestOptimisingQueryCompilerWhereFilterSplit {

    // StreamId is queryable with the default numeric ConditionSet - eligible for pushdown.
    private static final QueryField STREAM_ID = QueryField.builder()
            .fldName("StreamId").fldType(FieldType.LONG).queryable(true).build();
    // NonIndexedField is NOT queryable - AutoWhereFilterSplitRule treats it as ineligible and moves it to filter.
    private static final QueryField NON_INDEXED_FIELD = QueryField.builder()
            .fldName("NonIndexedField").fldType(FieldType.TEXT).queryable(false).build();

    private static final FieldInfoSource FIELD_INFO_SOURCE = new FieldInfoSource() {
        @Override
        public List<QueryField> getFields(final String dataSourceName) {
            return "Events".equals(dataSourceName) ? List.of(STREAM_ID, NON_INDEXED_FIELD) : List.of();
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
    void mixedEligibility_movesIneligibleTermToValueFilter() {
        final SearchRequest result = compiler().create(
                "from \"Events\" where StreamId = 1 and NonIndexedField = 'x' select StreamId",
                emptySeedRequest(),
                expressionContext());

        assertThat(result.getQuery().getExpression().toString()).contains("StreamId");
        assertThat(result.getQuery().getExpression().toString()).doesNotContain("NonIndexedField");

        final TableSettings tableSettings = tableSettingsOf(result);
        assertThat(tableSettings.getValueFilter()).isNotNull();
        assertThat(tableSettings.getValueFilter().toString()).contains("NonIndexedField");
    }

    @Test
    void allTermsEligible_leavesExpressionAndValueFilterUnchanged() {
        final SearchRequest result = compiler().create(
                "from \"Events\" where StreamId = 1 select StreamId",
                emptySeedRequest(),
                expressionContext());

        assertThat(result.getQuery().getExpression().toString()).contains("StreamId");
        assertThat(tableSettingsOf(result).getValueFilter()).isNull();
    }

    @Test
    void explicitFilterClauseAlready_isUntouched() {
        final SearchRequest result = compiler().create(
                "from \"Events\" where StreamId = 1 filter NonIndexedField = 'x' select StreamId",
                emptySeedRequest(),
                expressionContext());

        // AutoWhereFilterSplitRule is a documented no-op when the query already has its own filter clause -
        // valueFilter is exactly what the user wrote, unmodified by this task's override.
        assertThat(result.getQuery().getExpression().toString()).contains("StreamId");
        assertThat(tableSettingsOf(result).getValueFilter()).isNotNull();
        assertThat(tableSettingsOf(result).getValueFilter().toString()).contains("NonIndexedField");
    }

    private static TableSettings tableSettingsOf(final SearchRequest searchRequest) {
        final ResultRequest tableResultRequest = searchRequest.getResultRequests().stream()
                .filter(rr -> SearchRequestFactory.TABLE_COMPONENT_ID.equals(rr.getComponentId()))
                .findFirst()
                .orElseThrow();
        return tableResultRequest.getMappings().getFirst();
    }
}
