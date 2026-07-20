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

import stroom.query.api.Column;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.api.ResultRequest;
import stroom.query.api.SearchRequest;
import stroom.query.api.TableSettings;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link JoinProjectionAnalyzer#fieldsNeededFor} gathers exactly the fields a join side needs - see
 * {@code docs/join-scalability-implementation-plan.md}, decision D4 (Phase 1, item A2).
 */
class TestJoinProjectionAnalyzer {

    private static Column column(final String expression) {
        return Column.builder().id(expression).name(expression).expression(expression).build();
    }

    private static SearchRequest withColumns(final Column... columns) {
        final TableSettings tableSettings = TableSettings.builder().addColumns(columns).build();
        final ResultRequest resultRequest = ResultRequest.builder().mappings(List.of(tableSettings)).build();
        return SearchRequest.builder().resultRequests(List.of(resultRequest)).build();
    }

    @Test
    void equiKeyField_alwaysIncluded_evenWithNothingElseReferencingIt() {
        final SearchRequest outer = withColumns(column("b.Name"));

        final Set<String> fields = JoinProjectionAnalyzer.fieldsNeededFor(
                outer, null, "a", List.of("UserId"));

        assertThat(fields).containsExactly("UserId");
    }

    @Test
    void bareSelectColumnReferencingThisAlias_isIncluded() {
        final SearchRequest outer = withColumns(column("a.StreamId"));

        final Set<String> fields = JoinProjectionAnalyzer.fieldsNeededFor(
                outer, null, "a", List.of("UserId"));

        assertThat(fields).containsExactlyInAnyOrder("UserId", "StreamId");
    }

    @Test
    void selectColumnReferencingTheOtherAlias_isNotIncluded() {
        final SearchRequest outer = withColumns(column("b.Name"));

        final Set<String> fields = JoinProjectionAnalyzer.fieldsNeededFor(
                outer, null, "a", List.of("UserId"));

        assertThat(fields).containsExactly("UserId");
    }

    @Test
    void functionCallSelectExpression_stillFindsTheFieldReferenceInside() {
        // Column.getExpression() is arbitrary StroomQL, not just a bare field - a regex scan (not a parse) must
        // still find "a.Amount" inside "sum(a.Amount)". See JoinProjectionAnalyzer's class Javadoc.
        final SearchRequest outer = withColumns(column("sum(a.Amount)"));

        final Set<String> fields = JoinProjectionAnalyzer.fieldsNeededFor(
                outer, null, "a", List.of("UserId"));

        assertThat(fields).containsExactlyInAnyOrder("UserId", "Amount");
    }

    @Test
    void multiArgFunctionCall_findsFieldsFromBothAliases_onlyThisAliasesAreKept() {
        final SearchRequest outer = withColumns(column("concat(a.Name, b.Name)"));

        final Set<String> aFields = JoinProjectionAnalyzer.fieldsNeededFor(
                outer, null, "a", List.of("UserId"));
        final Set<String> bFields = JoinProjectionAnalyzer.fieldsNeededFor(
                outer, null, "b", List.of("Id"));

        assertThat(aFields).containsExactlyInAnyOrder("UserId", "Name");
        assertThat(bFields).containsExactlyInAnyOrder("Id", "Name");
    }

    @Test
    void residualWhereField_isIncluded() {
        final SearchRequest outer = withColumns(column("b.Name"));
        final ExpressionOperator residual = ExpressionOperator.builder()
                .addTerm("a.StreamId", Condition.EQUALS, "1")
                .build();

        final Set<String> fields = JoinProjectionAnalyzer.fieldsNeededFor(
                outer, residual, "a", List.of("UserId"));

        assertThat(fields).containsExactlyInAnyOrder("UserId", "StreamId");
    }

    @Test
    void valueFilterField_isIncluded() {
        final ExpressionOperator valueFilter = ExpressionOperator.builder()
                .addTerm("a.Region", Condition.EQUALS, "north")
                .build();
        final TableSettings tableSettings = TableSettings.builder()
                .addColumns(column("b.Name"))
                .valueFilter(valueFilter)
                .build();
        final SearchRequest outer = SearchRequest.builder()
                .resultRequests(List.of(ResultRequest.builder().mappings(List.of(tableSettings)).build()))
                .build();

        final Set<String> fields = JoinProjectionAnalyzer.fieldsNeededFor(
                outer, null, "a", List.of("UserId"));

        assertThat(fields).containsExactlyInAnyOrder("UserId", "Region");
    }

    @Test
    void aggregateFilterField_isIncluded() {
        final ExpressionOperator aggregateFilter = ExpressionOperator.builder()
                .addTerm("a.Total", Condition.GREATER_THAN, "10")
                .build();
        final TableSettings tableSettings = TableSettings.builder()
                .addColumns(column("b.Name"))
                .aggregateFilter(aggregateFilter)
                .build();
        final SearchRequest outer = SearchRequest.builder()
                .resultRequests(List.of(ResultRequest.builder().mappings(List.of(tableSettings)).build()))
                .build();

        final Set<String> fields = JoinProjectionAnalyzer.fieldsNeededFor(
                outer, null, "a", List.of("UserId"));

        assertThat(fields).containsExactlyInAnyOrder("UserId", "Total");
    }

    @Test
    void duplicateReferences_deduplicated() {
        final SearchRequest outer = withColumns(column("a.StreamId"), column("a.StreamId"));

        final Set<String> fields = JoinProjectionAnalyzer.fieldsNeededFor(
                outer, null, "a", List.of("StreamId"));

        assertThat(fields).containsExactly("StreamId");
    }

    @Test
    void noResultRequests_stillReturnsEquiKeyFields() {
        final SearchRequest outer = SearchRequest.builder().build();

        final Set<String> fields = JoinProjectionAnalyzer.fieldsNeededFor(
                outer, null, "a", List.of("UserId"));

        assertThat(fields).containsExactly("UserId");
    }

    @Test
    void multipleEquiKeyFields_allIncluded() {
        final SearchRequest outer = withColumns(column("b.Name"));

        final Set<String> fields = JoinProjectionAnalyzer.fieldsNeededFor(
                outer, null, "a", List.of("UserId", "Region"));

        assertThat(fields).containsExactlyInAnyOrder("UserId", "Region");
    }
}
