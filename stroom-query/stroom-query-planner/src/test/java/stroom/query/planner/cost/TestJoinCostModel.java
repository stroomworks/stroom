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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Task 3.3: {@link JoinCostModel}'s cardinality formula and algorithm selection.
 */
class TestJoinCostModel {

    private static CostedAccessPath tiny(final AccessPath accessPath, final long rows) {
        return new CostedAccessPath(accessPath, new CostEstimate(rows, 0, 0, 1.0, List.of()));
    }

    private static CostedAccessPath unknown() {
        return new CostedAccessPath(new FullScan(), new CostEstimate(0, 0, 0, 0.0, List.of("no signal")));
    }

    @Test
    void estimateCardinality_matchesDesignFormula() {
        // |A join B| ~ |A|*|B| / max(distinct(A.key), distinct(B.key)): 1000 * 500 / max(100, 50) = 5000.
        assertThat(JoinCostModel.estimateCardinality(1_000, 500, 100, 50)).isEqualTo(5_000);
    }

    @Test
    void estimateCardinality_unknownDistinctKeys_fallsBackToFullCrossProduct() {
        assertThat(JoinCostModel.estimateCardinality(100, 200, 0, 0)).isEqualTo(100L * 200L);
    }

    @Test
    void estimateCardinality_rejectsNegativeInputs() {
        assertThatIllegalArgumentException().isThrownBy(() -> JoinCostModel.estimateCardinality(-1, 1, 1, 1));
    }

    @Test
    void estimateCardinality_hugeCrossProduct_saturatesRatherThanOverflowingNegative() {
        // leftRows * rightRows exceeds Long.MAX_VALUE; with the unknown-distinct-keys fallback (maxDistinct=1)
        // the result must stay a pessimistic, non-negative upper bound, not wrap to a negative number.
        final long result = JoinCostModel.estimateCardinality(5_000_000_000L, 5_000_000_000L, 0, 0);
        assertThat(result).isEqualTo(Long.MAX_VALUE);
        assertThat(result).isNotNegative();
    }

    @Test
    void chooseAlgorithm_lookupCapableRightSide_choosesBroadcastLookupOnRight() {
        final CostedAccessPath left = tiny(new IndexScan(), 1_000_000);
        final CostedAccessPath right = tiny(new StateLookup(), 10);

        final JoinPlan plan = JoinCostModel.chooseAlgorithm(left, right);

        assertThat(plan.algorithm()).isEqualTo(JoinAlgorithm.BROADCAST_LOOKUP);
        assertThat(plan.buildSide()).isEqualTo(JoinSide.RIGHT);
    }

    @Test
    void chooseAlgorithm_lookupCapableLeftSide_choosesBroadcastLookupOnLeft() {
        final CostedAccessPath left = tiny(new StateLookup(), 10);
        final CostedAccessPath right = tiny(new IndexScan(), 1_000_000);

        final JoinPlan plan = JoinCostModel.chooseAlgorithm(left, right);

        assertThat(plan.algorithm()).isEqualTo(JoinAlgorithm.BROADCAST_LOOKUP);
        assertThat(plan.buildSide()).isEqualTo(JoinSide.LEFT);
    }

    @Test
    void chooseAlgorithm_twoLargeNonLookupSides_choosesHashJoinWithSmallerBuildSide() {
        final CostedAccessPath left = tiny(new IndexScan(), 1_000_000);
        final CostedAccessPath right = tiny(new FullScan(), 500_000);

        final JoinPlan plan = JoinCostModel.chooseAlgorithm(left, right);

        assertThat(plan.algorithm()).isEqualTo(JoinAlgorithm.HASH_JOIN);
        assertThat(plan.buildSide()).isEqualTo(JoinSide.RIGHT);
    }

    @Test
    void chooseAlgorithm_neitherSideHasAUsableEstimate_fallsBackToNestedLoop() {
        final JoinPlan plan = JoinCostModel.chooseAlgorithm(unknown(), unknown());

        assertThat(plan.algorithm()).isEqualTo(JoinAlgorithm.NESTED_LOOP);
    }

    @Test
    void chooseAlgorithm_rejectsNullSides() {
        assertThatNullPointerException().isThrownBy(() -> JoinCostModel.chooseAlgorithm(null, tiny(new FullScan(), 1)));
        assertThatNullPointerException().isThrownBy(() -> JoinCostModel.chooseAlgorithm(tiny(new FullScan(), 1), null));
    }
}
