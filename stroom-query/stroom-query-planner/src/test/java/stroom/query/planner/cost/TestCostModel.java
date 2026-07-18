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

package stroom.query.planner.cost;

import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.grammar.ast.AstPosition;
import stroom.query.planner.logical.Scan;
import stroom.query.planner.port.MetaStats;
import stroom.query.planner.port.RowCountSignal;
import stroom.query.planner.port.StateStoreStats;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 3.2: {@link CostModel} tested entirely against fakes for the three cost ports - no real
 * {@code MetaService}/{@code IndexShardService}/Plan B store needed.
 */
class TestCostModel {

    private static final AstPosition POS = new AstPosition(1, 0);

    private static ExpressionTerm term(final Condition condition) {
        return ExpressionTerm.builder().field("StreamId").condition(condition).value("1").build();
    }

    private static MetaStats noMeta() {
        return (feedName, from, to) -> Optional.empty();
    }

    private static stroom.query.planner.port.IndexShardStats noIndex() {
        return (indexName, from, to) -> Optional.empty();
    }

    private static StateStoreStats noState() {
        return storeName -> Optional.empty();
    }

    @Test
    void onlyMetaAnswers_choosesFullScan() {
        final CostModel costModel = new CostModel(
                new FakeMetaStats("Events", 1_000, 0, 10_000), noIndex(), noState());

        final CostedAccessPath result = costModel.estimate(
                new Scan("s", "Events", POS), null, null, List.of());

        assertThat(result.accessPath()).isInstanceOf(FullScan.class);
        assertThat(result.estimate().rows()).isEqualTo(1_000);
        assertThat(result.estimate().confidence()).isEqualTo(1.0);
    }

    @Test
    void onlyIndexAnswers_withThroughput_computesDuration() {
        final CostModel costModel = new CostModel(
                noMeta(), FakeIndexShardStats.withThroughput("MyIndex", 10_000, 1_000_000, 500.0), noState());

        final CostedAccessPath result = costModel.estimate(
                new Scan("s", "MyIndex", POS), null, null, List.of());

        assertThat(result.accessPath()).isInstanceOf(IndexScan.class);
        assertThat(result.estimate().rows()).isEqualTo(10_000);
        // 10,000 rows / (500 docs/sec / 1000) = 10,000 / 0.5 rows-per-ms = 20,000 ms.
        assertThat(result.estimate().durationMs()).isEqualTo(20_000);
        assertThat(result.estimate().confidence()).isEqualTo(1.0);
    }

    @Test
    void onlyIndexAnswers_withoutThroughput_usesFallbackAndLowersConfidence() {
        final CostModel costModel = new CostModel(
                noMeta(), FakeIndexShardStats.withoutThroughput("MyIndex", 10_000, 1_000_000), noState());

        final CostedAccessPath result = costModel.estimate(
                new Scan("s", "MyIndex", POS), null, null, List.of());

        assertThat(result.estimate().confidence()).isLessThan(1.0);
        assertThat(result.estimate().notes()).anySatisfy(note -> assertThat(note).contains("placeholder"));
    }

    @Test
    void onlyStateAnswers_choosesStateLookup() {
        final CostModel costModel = new CostModel(
                noMeta(), noIndex(), new FakeStateStoreStats(Map.of("MyStore", 500L)));

        final CostedAccessPath result = costModel.estimate(
                new Scan("s", "MyStore", POS), null, null, List.of());

        assertThat(result.accessPath()).isInstanceOf(StateLookup.class);
        assertThat(result.estimate().rows()).isEqualTo(500);
        assertThat(result.estimate().confidence()).isEqualTo(1.0);
    }

    @Test
    void noPortAnswers_fallsBackToZeroConfidenceFullScan() {
        final CostModel costModel = new CostModel(noMeta(), noIndex(), noState());

        final CostedAccessPath result = costModel.estimate(
                new Scan("s", "Unknown", POS), null, null, List.of());

        assertThat(result.accessPath()).isInstanceOf(FullScan.class);
        assertThat(result.estimate().confidence()).isEqualTo(0.0);
    }

    @Test
    void narrowerTimeRange_yieldsLowerOrEqualRowEstimate() {
        final CostModel costModel = new CostModel(
                new FakeMetaStats("Events", 10_000, 0, 100_000), noIndex(), noState());
        final Scan scan = new Scan("s", "Events", POS);

        final long fullRangeRows = costModel.estimate(scan, null, null, List.of()).estimate().rows();
        final long narrowRangeRows = costModel.estimate(scan, 40_000L, 60_000L, List.of()).estimate().rows();
        final long narrowerStillRows = costModel.estimate(scan, 45_000L, 55_000L, List.of()).estimate().rows();

        assertThat(narrowRangeRows).isLessThanOrEqualTo(fullRangeRows);
        assertThat(narrowerStillRows).isLessThanOrEqualTo(narrowRangeRows);
    }

    @Test
    void selectivityOrdering_equalityLessThanRangeLessThanUnindexed() {
        final CostModel costModel = new CostModel(
                new FakeMetaStats("Events", 1_000_000, 0, 1), noIndex(), noState());
        final Scan scan = new Scan("s", "Events", POS);

        final long equalityRows = costModel.estimate(scan, null, null, List.of(term(Condition.EQUALS)))
                .estimate().rows();
        final long rangeRows = costModel.estimate(scan, null, null, List.of(term(Condition.BETWEEN)))
                .estimate().rows();
        final long unindexedRows = costModel.estimate(scan, null, null, List.of(term(Condition.NOT_EQUALS)))
                .estimate().rows();

        assertThat(equalityRows).isLessThan(rangeRows);
        assertThat(rangeRows).isLessThan(unindexedRows);
    }

    @Test
    void multipleTerms_combineMultiplicatively() {
        final CostModel costModel = new CostModel(
                new FakeMetaStats("Events", 1_000_000, 0, 1), noIndex(), noState());
        final Scan scan = new Scan("s", "Events", POS);

        final long oneTermRows = costModel.estimate(scan, null, null, List.of(term(Condition.EQUALS)))
                .estimate().rows();
        final long twoTermRows = costModel.estimate(
                scan, null, null, List.of(term(Condition.EQUALS), term(Condition.EQUALS))).estimate().rows();

        assertThat(twoTermRows).isLessThan(oneTermRows);
    }

    @Test
    void constructorRejectsNullPorts() {
        assertThatThrownBy(() -> new CostModel(null, noIndex(), noState()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CostModel(noMeta(), null, noState()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CostModel(noMeta(), noIndex(), null))
                .isInstanceOf(NullPointerException.class);
    }
}
