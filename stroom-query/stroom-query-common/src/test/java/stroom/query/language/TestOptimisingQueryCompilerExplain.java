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
import stroom.query.api.ExplainPlan;
import stroom.query.api.datasource.FieldType;
import stroom.query.api.datasource.QueryField;
import stroom.query.language.functions.ExpressionContext;
import stroom.query.planner.port.FieldInfoSource;
import stroom.query.planner.port.RowCountSignal;
import stroom.security.mock.MockSecurityContext;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 4.1: proves {@link OptimisingQueryCompiler#explain} is really wired to the Phase 2/3 pipeline (Binder,
 * RewritePipeline, CostModel) - previously exercised only by their own standalone unit tests, this is their
 * first real caller.
 */
class TestOptimisingQueryCompilerExplain {

    private static final QueryField STREAM_ID = QueryField.builder()
            .fldName("StreamId").fldType(FieldType.LONG).build();

    private static final FieldInfoSource FIELD_INFO_SOURCE = new FieldInfoSource() {
        @Override
        public List<QueryField> getFields(final String dataSourceName) {
            return "Events".equals(dataSourceName) ? List.of(STREAM_ID) : List.of();
        }

        @Override
        public Optional<QueryField> getTimeField(final String dataSourceName) {
            return Optional.empty();
        }
    };

    private OptimisingQueryCompiler compiler(
            final java.util.function.Function<String, Optional<RowCountSignal>> metaStatsFunction) {
        return new OptimisingQueryCompiler(
                (keywordGroup, parentTableSettings) -> null,
                MockDataSourceResolver.getInstance(),
                () -> criteria -> null,
                MockSecurityContext.getInstance(),
                FIELD_INFO_SOURCE,
                (feedName, from, to) -> metaStatsFunction.apply(feedName),
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
    void metaStatsAnswers_producesRealCostOnTheScanNode() {
        final OptimisingQueryCompiler compiler = compiler(
                feedName -> "Events".equals(feedName) ? Optional.of(new RowCountSignal(500)) : Optional.empty());

        final ExplainPlan plan = compiler.explain(
                "from \"Events\" where StreamId = 1 select StreamId", expressionContext());

        final ExplainPlan scanNode = findScanNode(plan);
        assertThat(scanNode.getDescription()).contains("Events");
        // CostModel.costFullScan halves confidence whenever a selectivity heuristic was applied (not a direct
        // port answer) - see Task 3.2's CostModel; this query's equality term triggers that.
        assertThat(scanNode.getConfidence()).isEqualTo(0.5);
        // 500 rows * 0.01 (equality selectivity) = 5.
        assertThat(scanNode.getEstimatedRows()).isEqualTo(5L);
    }

    @Test
    void noPortAnswers_producesZeroConfidenceScanNode() {
        final OptimisingQueryCompiler compiler = compiler(feedName -> Optional.empty());

        final ExplainPlan plan = compiler.explain("from \"Events\" select StreamId", expressionContext());

        final ExplainPlan scanNode = findScanNode(plan);
        assertThat(scanNode.getConfidence()).isEqualTo(0.0);
        assertThat(scanNode.getNotes()).anySatisfy(note -> assertThat(note).contains("no cost signal available"));
    }

    @Test
    void planTreeShapeReflectsClausesPresent() {
        final OptimisingQueryCompiler compiler = compiler(feedName -> Optional.empty());

        final ExplainPlan plan = compiler.explain(
                "from \"Events\" where StreamId = 1 sort by StreamId limit 10 select StreamId",
                expressionContext());

        assertThat(plan.getDescription()).contains("Limit");
        assertThat(plan.getChildren()).hasSize(1);
        assertThat(plan.getChildren().getFirst().getDescription()).contains("Sort");
    }

    private static ExplainPlan findScanNode(final ExplainPlan plan) {
        if (plan.getDescription().startsWith("Scan ")) {
            return plan;
        }
        assertThat(plan.getChildren()).as("expected a descendant Scan node").isNotEmpty();
        return findScanNode(plan.getChildren().getFirst());
    }
}
