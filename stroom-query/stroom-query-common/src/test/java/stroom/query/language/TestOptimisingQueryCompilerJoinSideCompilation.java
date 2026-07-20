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
import stroom.query.api.SearchRequest;
import stroom.query.api.datasource.FieldType;
import stroom.query.api.datasource.QueryField;
import stroom.query.grammar.ast.AstPosition;
import stroom.query.language.functions.ExpressionContext;
import stroom.query.planner.logical.Scan;
import stroom.security.mock.MockSecurityContext;
import stroom.util.shared.ResultPage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 6.1b: proves {@link OptimisingQueryCompiler#compileJoinSide} turns a join side's bare {@code Scan} (with
 * no {@code Filter} - the {@code null} second argument throughout this class) into a perfectly ordinary, valid
 * single-source {@link SearchRequest} - reusing {@link AstToSearchRequestMapper} via a synthesised
 * {@code select <fields>} sub-query rather than hand-building wire types. Task A2 (see
 * {@code docs/join-scalability-implementation-plan.md}, decision D4) restricts that select list to exactly the
 * {@code selectFields} passed in, rather than {@code select *} as it did before A2 - see {@link
 * TestOptimisingQueryCompilerJoin} for the full join-query compilation this feeds into (Task 6.1x, and A1/A2's
 * end-to-end tests), and {@code compileJoinSide}'s own Javadoc for the {@code Filter}-present case this class
 * doesn't exercise.
 */
class TestOptimisingQueryCompilerJoinSideCompilation {

    private static final QueryField STREAM_ID = QueryField.builder()
            .fldName("StreamId").fldType(FieldType.LONG).build();
    private static final QueryField USER_ID = QueryField.builder()
            .fldName("UserId").fldType(FieldType.LONG).build();

    private static final EmptyFieldInfoSource FIELD_INFO_SOURCE_STAND_IN = EmptyFieldInfoSource.INSTANCE;

    private OptimisingQueryCompiler compiler() {
        return new OptimisingQueryCompiler(
                (keywordGroup, parentTableSettings) -> null,
                MockDataSourceResolver.getInstance(),
                () -> criteria -> ResultPage.createUnboundedList(List.of(STREAM_ID, USER_ID)),
                MockSecurityContext.getInstance(),
                FIELD_INFO_SOURCE_STAND_IN,
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

    @Test
    void compilesTheScanAsAnOrdinarySingleSourceSearchRequest() {
        final Scan scan = new Scan("a", "Events", new AstPosition(1, 0));

        final SearchRequest result = compiler().compileJoinSide(scan, null, List.of("StreamId"), expressionContext());

        assertThat(result.getQuery()).isNotNull();
        assertThat(result.getQuery().getDataSource().getName()).isEqualTo("Events");
        assertThat(result.getResultRequests()).isNotEmpty();
    }

    @Test
    void selectsExactlyTheRequestedFields_notEveryFieldTheDataSourceExposes() {
        // Task A2: only StreamId is requested, even though the datasource also exposes UserId - proving the
        // sub-query is restricted, not select * (the pre-A2 behaviour). The mapper auto-adds its own navigation
        // columns (__stream_id__ etc.) regardless of what's requested - unrelated to A2, not asserted on here.
        final Scan scan = new Scan("a", "Events", new AstPosition(1, 0));

        final SearchRequest result = compiler().compileJoinSide(scan, null, List.of("StreamId"), expressionContext());

        final List<String> columnNames = result.getResultRequests().stream()
                .flatMap(rr -> rr.getMappings().stream())
                .flatMap(ts -> ts.getFields().stream())
                .map(stroom.query.api.Column::getName)
                .toList();
        assertThat(columnNames).contains("StreamId").doesNotContain("UserId");
    }

    @Test
    void multipleRequestedFields_allSelected() {
        final Scan scan = new Scan("a", "Events", new AstPosition(1, 0));

        final SearchRequest result = compiler().compileJoinSide(
                scan, null, List.of("StreamId", "UserId"), expressionContext());

        final List<String> columnNames = result.getResultRequests().stream()
                .flatMap(rr -> rr.getMappings().stream())
                .flatMap(ts -> ts.getFields().stream())
                .map(stroom.query.api.Column::getName)
                .toList();
        assertThat(columnNames).contains("StreamId", "UserId");
    }

    @Test
    void emptySelectFields_rejectedClearly() {
        // A join side always needs at least its own equi-key field - an empty select list is a caller
        // programming error (JoinProjectionAnalyzer.fieldsNeededFor never returns empty), not a valid request.
        final Scan scan = new Scan("a", "Events", new AstPosition(1, 0));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> compiler().compileJoinSide(scan, null, List.of(), expressionContext()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void differentAliasesCompileToTheSameDataSource_aliasIsNotEncodedInTheSubRequest() {
        final Scan scanA = new Scan("a", "Events", new AstPosition(1, 0));
        final Scan scanB = new Scan("b", "Events", new AstPosition(1, 0));

        final SearchRequest resultA = compiler().compileJoinSide(
                scanA, null, List.of("StreamId"), expressionContext());
        final SearchRequest resultB = compiler().compileJoinSide(
                scanB, null, List.of("StreamId"), expressionContext());

        assertThat(resultA.getQuery().getDataSource()).isEqualTo(resultB.getQuery().getDataSource());
    }
}
