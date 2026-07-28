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

import java.util.Objects;

/**
 * Join cardinality estimation and algorithm selection (see
 * Task 3.3) - an interface defined and unit-tested here,
 * exercised for real once Phase 6 adds a join executor. Distinct-key counts are supplied by the caller, not
 * estimated here - the design doc's domain-type-sharpened {@code distinct} refinement is out of scope for v1
 * (gated on stats collection, itself a post-v1 non-goal).
 */
public final class JoinCostModel {

    private JoinCostModel() {
        // Static utility - not instantiable.
    }

    /**
     * The design doc's formula: {@code |A join B| ~ |A|*|B| / max(distinct(A.key), distinct(B.key))}.
     *
     * @param leftRows          never negative.
     * @param rightRows         never negative.
     * @param leftDistinctKeys  never negative; an unknown/zero count is treated as {@code 1} (see below).
     * @param rightDistinctKeys never negative; same treatment.
     * @return never negative. When both distinct-key counts are unknown (zero), this degrades to the full
     *         cross-product {@code leftRows * rightRows} - a deliberately pessimistic (over-, never under-)
     *         estimate rather than risking a division by zero or an invented small number. The
     *         {@code leftRows * rightRows} product is computed with saturating arithmetic: if it would overflow
     *         a 64-bit {@code long} it is clamped to {@link Long#MAX_VALUE} (an even more pessimistic estimate)
     *         rather than wrapping to a negative number and violating this "never negative" postcondition.
     */
    public static long estimateCardinality(
            final long leftRows, final long rightRows, final long leftDistinctKeys,
            final long rightDistinctKeys) {
        if (leftRows < 0 || rightRows < 0 || leftDistinctKeys < 0 || rightDistinctKeys < 0) {
            throw new IllegalArgumentException("rows/distinct-key counts must not be negative");
        }
        final long maxDistinct = Math.max(1, Math.max(leftDistinctKeys, rightDistinctKeys));
        long product;
        try {
            product = Math.multiplyExact(leftRows, rightRows);
        } catch (final ArithmeticException overflow) {
            // The cross-product exceeds Long.MAX_VALUE; clamp rather than wrap negative (the estimate is a
            // pessimistic upper bound anyway, so a saturated value is still directionally correct).
            product = Long.MAX_VALUE;
        }
        return product / maxDistinct;
    }

    /**
     * @param left  never null; the left side's chosen access path and cost.
     * @param right never null; the right side's chosen access path and cost.
     * @return never null. {@link JoinAlgorithm#BROADCAST_LOOKUP} whenever either side's access path is a
     *         {@link StateLookup} (matching the design doc's framing of broadcast-lookup as a State/Plan-B
     *         capability, not a size threshold - preferring the right side if, unusually, both are lookup-
     *         capable); otherwise {@link JoinAlgorithm#HASH_JOIN} with the smaller-by-{@code rows} side as the
     *         build side; {@link JoinAlgorithm#NESTED_LOOP} only when neither side has a usable cost estimate
     *         (both {@code confidence == 0.0}) to compare.
     */
    public static JoinPlan chooseAlgorithm(final CostedAccessPath left, final CostedAccessPath right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");

        if (right.accessPath() instanceof StateLookup) {
            return new JoinPlan(JoinAlgorithm.BROADCAST_LOOKUP, JoinSide.RIGHT);
        }
        if (left.accessPath() instanceof StateLookup) {
            return new JoinPlan(JoinAlgorithm.BROADCAST_LOOKUP, JoinSide.LEFT);
        }
        if (left.estimate().confidence() == 0.0 && right.estimate().confidence() == 0.0) {
            return new JoinPlan(JoinAlgorithm.NESTED_LOOP, JoinSide.LEFT);
        }
        final JoinSide buildSide = left.estimate().rows() <= right.estimate().rows() ? JoinSide.LEFT : JoinSide.RIGHT;
        return new JoinPlan(JoinAlgorithm.HASH_JOIN, buildSide);
    }
}
