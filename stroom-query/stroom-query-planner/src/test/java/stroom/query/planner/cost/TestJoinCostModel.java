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

import stroom.query.planner.logical.JoinType;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Task 3.3: {@link JoinCostModel}'s cardinality formula and algorithm selection. Tasks 7.1/7.2: a
 * zero-confidence side is never the build side, and a non-{@code INNER} join never builds its preserved (left)
 * side.
 */
class TestJoinCostModel {

    private static CostedAccessPath tiny(final AccessPath accessPath, final long rows) {
        return new CostedAccessPath(accessPath, new CostEstimate(rows, 0, 0, 1.0, List.of()));
    }

    private static CostedAccessPath unknown() {
        return unknown(new FullScan());
    }

    private static CostedAccessPath unknown(final AccessPath accessPath) {
        return new CostedAccessPath(accessPath, new CostEstimate(0, 0, 0, 0.0, List.of("no signal")));
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

        final JoinPlan plan = JoinCostModel.chooseAlgorithm(JoinType.INNER, left, right);

        assertThat(plan.algorithm()).isEqualTo(JoinAlgorithm.BROADCAST_LOOKUP);
        assertThat(plan.buildSide()).isEqualTo(JoinSide.RIGHT);
    }

    @Test
    void chooseAlgorithm_lookupCapableLeftSide_choosesBroadcastLookupOnLeftForInnerJoin() {
        final CostedAccessPath left = tiny(new StateLookup(), 10);
        final CostedAccessPath right = tiny(new IndexScan(), 1_000_000);

        final JoinPlan plan = JoinCostModel.chooseAlgorithm(JoinType.INNER, left, right);

        assertThat(plan.algorithm()).isEqualTo(JoinAlgorithm.BROADCAST_LOOKUP);
        assertThat(plan.buildSide()).isEqualTo(JoinSide.LEFT);
    }

    @Test
    void chooseAlgorithm_innerJoin_stateLookupSideWinsRegardlessOfConfidence() {
        // The StateLookup branches are structural and precede the confidence guard - a lookup-capable side is
        // still nominated for an INNER join even when the other side has no cost signal at all.
        final JoinPlan rightLookup = JoinCostModel.chooseAlgorithm(
                JoinType.INNER, unknown(), tiny(new StateLookup(), 10));
        assertThat(rightLookup.algorithm()).isEqualTo(JoinAlgorithm.BROADCAST_LOOKUP);
        assertThat(rightLookup.buildSide()).isEqualTo(JoinSide.RIGHT);

        final JoinPlan leftLookup = JoinCostModel.chooseAlgorithm(
                JoinType.INNER, tiny(new StateLookup(), 10), unknown());
        assertThat(leftLookup.algorithm()).isEqualTo(JoinAlgorithm.BROADCAST_LOOKUP);
        assertThat(leftLookup.buildSide()).isEqualTo(JoinSide.LEFT);
    }

    @Test
    void chooseAlgorithm_innerJoin_leftSmaller_choosesHashJoinBuildingLeft() {
        final CostedAccessPath left = tiny(new IndexScan(), 500_000);
        final CostedAccessPath right = tiny(new FullScan(), 1_000_000);

        final JoinPlan plan = JoinCostModel.chooseAlgorithm(JoinType.INNER, left, right);

        assertThat(plan.algorithm()).isEqualTo(JoinAlgorithm.HASH_JOIN);
        assertThat(plan.buildSide()).isEqualTo(JoinSide.LEFT);
    }

    @Test
    void chooseAlgorithm_innerJoin_rightSmaller_choosesHashJoinBuildingRight() {
        final CostedAccessPath left = tiny(new IndexScan(), 1_000_000);
        final CostedAccessPath right = tiny(new FullScan(), 500_000);

        final JoinPlan plan = JoinCostModel.chooseAlgorithm(JoinType.INNER, left, right);

        assertThat(plan.algorithm()).isEqualTo(JoinAlgorithm.HASH_JOIN);
        assertThat(plan.buildSide()).isEqualTo(JoinSide.RIGHT);
    }

    @Test
    void chooseAlgorithm_leftJoin_leftSmaller_stillBuildsTheRightSide() {
        // Mirrors JoinSearchProvider's A6 rule: a LEFT join keeps probe = left (its unmatched rows emit
        // inline) so it never swaps - the smaller-but-preserved left side must not be the build side.
        final CostedAccessPath left = tiny(new IndexScan(), 10);
        final CostedAccessPath right = tiny(new FullScan(), 1_000_000);

        final JoinPlan plan = JoinCostModel.chooseAlgorithm(JoinType.LEFT, left, right);

        assertThat(plan.algorithm()).isEqualTo(JoinAlgorithm.HASH_JOIN);
        assertThat(plan.buildSide()).isEqualTo(JoinSide.RIGHT);
    }

    @Test
    void chooseAlgorithm_leftJoin_rightSmaller_buildsTheRightSide() {
        final CostedAccessPath left = tiny(new IndexScan(), 1_000_000);
        final CostedAccessPath right = tiny(new FullScan(), 10);

        final JoinPlan plan = JoinCostModel.chooseAlgorithm(JoinType.LEFT, left, right);

        assertThat(plan.algorithm()).isEqualTo(JoinAlgorithm.HASH_JOIN);
        assertThat(plan.buildSide()).isEqualTo(JoinSide.RIGHT);
    }

    @Test
    void chooseAlgorithm_leftJoin_lookupCapableRightSide_choosesBroadcastLookupOnRight() {
        // The good enrichment case: the right side of a LEFT join is not the preserved side, so a right-side
        // lookup is safe - and it is what the executor actually does.
        final CostedAccessPath left = tiny(new IndexScan(), 1_000_000);
        final CostedAccessPath right = tiny(new StateLookup(), 10);

        final JoinPlan plan = JoinCostModel.chooseAlgorithm(JoinType.LEFT, left, right);

        assertThat(plan.algorithm()).isEqualTo(JoinAlgorithm.BROADCAST_LOOKUP);
        assertThat(plan.buildSide()).isEqualTo(JoinSide.RIGHT);
    }

    @Test
    void chooseAlgorithm_leftJoin_lookupCapableLeftSide_neverBuildsThePreservedLeftSide() {
        // BROADCAST_LOOKUP with the lookup side on the left of a LEFT join is the combination confirmed broken
        // on the execution path (right-preserving semantics for a left-outer join) - it must not be nominated.
        final CostedAccessPath left = tiny(new StateLookup(), 10);
        final CostedAccessPath right = tiny(new IndexScan(), 1_000_000);

        final JoinPlan plan = JoinCostModel.chooseAlgorithm(JoinType.LEFT, left, right);

        assertThat(plan.algorithm()).isEqualTo(JoinAlgorithm.HASH_JOIN);
        assertThat(plan.buildSide()).isEqualTo(JoinSide.RIGHT);
    }

    @Test
    void chooseAlgorithm_leftKnownRightUnknown_expressesNoBuildSidePreference() {
        final JoinPlan plan = JoinCostModel.chooseAlgorithm(
                JoinType.INNER, tiny(new IndexScan(), 1_000), unknown());

        assertThat(plan.algorithm()).isEqualTo(JoinAlgorithm.NESTED_LOOP);
    }

    @Test
    void chooseAlgorithm_leftUnknownRightKnown_expressesNoBuildSidePreference() {
        // Task 7.1: before the fix the unknown side's rows() == 0 won the <= comparison and the side nothing
        // is known about became the build side. Unknown must mean "assume large", never "smallest possible".
        final JoinPlan plan = JoinCostModel.chooseAlgorithm(
                JoinType.INNER, unknown(), tiny(new IndexScan(), 1_000));

        assertThat(plan.algorithm()).isEqualTo(JoinAlgorithm.NESTED_LOOP);
    }

    @Test
    void chooseAlgorithm_neitherSideHasAUsableEstimate_fallsBackToNestedLoop() {
        final JoinPlan plan = JoinCostModel.chooseAlgorithm(JoinType.INNER, unknown(), unknown());

        assertThat(plan.algorithm()).isEqualTo(JoinAlgorithm.NESTED_LOOP);
    }

    @Test
    void chooseAlgorithm_rejectsNullArguments() {
        assertThatNullPointerException().isThrownBy(
                () -> JoinCostModel.chooseAlgorithm(null, tiny(new FullScan(), 1), tiny(new FullScan(), 1)));
        assertThatNullPointerException().isThrownBy(
                () -> JoinCostModel.chooseAlgorithm(JoinType.INNER, null, tiny(new FullScan(), 1)));
        assertThatNullPointerException().isThrownBy(
                () -> JoinCostModel.chooseAlgorithm(JoinType.INNER, tiny(new FullScan(), 1), null));
    }
}
