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
 * single-source {@link SearchRequest} - reusing {@link AstToSearchRequestMapper} via a synthesised "select every
 * field" sub-query rather than hand-building wire types. See {@link TestOptimisingQueryCompilerJoin} for the
 * full join-query compilation this feeds into (Task 6.1x), and {@code compileJoinSide}'s own Javadoc for the
 * {@code Filter}-present case this class doesn't exercise.
 */
class TestOptimisingQueryCompilerJoinSideCompilation {

    private static final QueryField STREAM_ID = QueryField.builder()
            .fldName("StreamId").fldType(FieldType.LONG).build();

    private static final EmptyFieldInfoSource FIELD_INFO_SOURCE_STAND_IN = EmptyFieldInfoSource.INSTANCE;

    private OptimisingQueryCompiler compiler() {
        return new OptimisingQueryCompiler(
                (keywordGroup, parentTableSettings) -> null,
                MockDataSourceResolver.getInstance(),
                () -> criteria -> ResultPage.createUnboundedList(List.of(STREAM_ID)),
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

        final SearchRequest result = compiler().compileJoinSide(scan, null, expressionContext());

        assertThat(result.getQuery()).isNotNull();
        assertThat(result.getQuery().getDataSource().getName()).isEqualTo("Events");
        assertThat(result.getResultRequests()).isNotEmpty();
    }

    @Test
    void selectsEveryFieldTheDataSourceExposes() {
        final Scan scan = new Scan("a", "Events", new AstPosition(1, 0));

        final SearchRequest result = compiler().compileJoinSide(scan, null, expressionContext());

        final boolean hasStreamIdColumn = result.getResultRequests().stream()
                .flatMap(rr -> rr.getMappings().stream())
                .flatMap(ts -> ts.getFields().stream())
                .anyMatch(column -> "StreamId".equals(column.getName()));
        assertThat(hasStreamIdColumn).isTrue();
    }

    @Test
    void differentAliasesCompileToTheSameDataSource_aliasIsNotEncodedInTheSubRequest() {
        final Scan scanA = new Scan("a", "Events", new AstPosition(1, 0));
        final Scan scanB = new Scan("b", "Events", new AstPosition(1, 0));

        final SearchRequest resultA = compiler().compileJoinSide(scanA, null, expressionContext());
        final SearchRequest resultB = compiler().compileJoinSide(scanB, null, expressionContext());

        assertThat(resultA.getQuery().getDataSource()).isEqualTo(resultB.getQuery().getDataSource());
    }
}
