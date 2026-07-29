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
     * Advisory algorithm/build-side selection - consumed only by {@code EXPLAIN} output
     * ({@code LogicalPlanExplainer}); the executor picks its own build side from measured
     * {@code DataStore.getSize()} values once both sides have materialised.
     *
     * <p>Deliberately mirrors {@code JoinSearchProvider}'s A6 build-side selection so the advisory and the
     * execution-time rule cannot drift apart: an {@code INNER} join builds the smaller side; any other join type
     * preserves its left side's unmatched rows, so the left side is never the build side - the build side is
     * always the right. A side with no cost signal ({@code confidence() == 0.0}) is treated as unbounded, never
     * as the smaller side: if either side is unknown, no build side is nominated at all.</p>
     *
     * @param joinType never null; the join's type. For anything other than {@link JoinType#INNER}, the left
     *                 (preserved) side is never nominated as the build side of a
     *                 {@link JoinAlgorithm#HASH_JOIN} or {@link JoinAlgorithm#BROADCAST_LOOKUP}.
     * @param left     never null; the left side's chosen access path and cost.
     * @param right    never null; the right side's chosen access path and cost.
     * @return never null. {@link JoinAlgorithm#BROADCAST_LOOKUP} whenever a side's access path is a
     *         {@link StateLookup} that the join type permits as the build side (matching the design doc's
     *         framing of broadcast-lookup as a State/Plan-B capability, not a size threshold): the right side
     *         for any join type (preferred if, unusually, both are lookup-capable), the left side only for an
     *         {@code INNER} join. Otherwise {@link JoinAlgorithm#NESTED_LOOP} when either side has no usable
     *         cost estimate ({@code confidence == 0.0}) - the returned {@link JoinPlan#buildSide()} then
     *         carries no meaning (see {@link JoinPlan}). Otherwise {@link JoinAlgorithm#HASH_JOIN}: with the
     *         smaller-by-{@code rows} side as the build side for an {@code INNER} join, and always the right
     *         side for any other join type.
     */
    public static JoinPlan chooseAlgorithm(
            final JoinType joinType, final CostedAccessPath left, final CostedAccessPath right) {
        Objects.requireNonNull(joinType, "joinType");
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");

        if (right.accessPath() instanceof StateLookup) {
            // The right side is never the preserved side, so a right-side lookup is safe for every join type -
            // for a LEFT join this is the common enrichment case, and matches what the executor does.
            return new JoinPlan(JoinAlgorithm.BROADCAST_LOOKUP, JoinSide.RIGHT);
        }
        if (left.accessPath() instanceof StateLookup && joinType == JoinType.INNER) {
            // A left-side lookup may only be built for an INNER join: a non-INNER join must emit the left
            // side's unmatched rows, and building the left executes right-preserving semantics instead (the
            // broadcast-lookup defect confirmed on the execution path). A non-INNER left-side lookup falls
            // through to the selection below, where the build side is forced to the right.
            return new JoinPlan(JoinAlgorithm.BROADCAST_LOOKUP, JoinSide.LEFT);
        }
        if (left.estimate().confidence() == 0.0 || right.estimate().confidence() == 0.0) {
            // A side with no cost signal is treated as unbounded, never as the smaller side, so with either
            // side unknown there is no basis to nominate a build side at all. NESTED_LOOP is the existing
            // spelling for "no preference"; JoinPlan requires a side, but the one returned here carries no
            // meaning (see JoinPlan's Javadoc).
            return new JoinPlan(JoinAlgorithm.NESTED_LOOP, JoinSide.LEFT);
        }
        if (joinType != JoinType.INNER) {
            // Mirror of JoinSearchProvider's A6 rule: a LEFT join keeps probe = left (its unmatched rows emit
            // inline) so it never swaps - the build side is always the right, regardless of row counts.
            return new JoinPlan(JoinAlgorithm.HASH_JOIN, JoinSide.RIGHT);
        }
        final JoinSide buildSide = left.estimate().rows() <= right.estimate().rows() ? JoinSide.LEFT : JoinSide.RIGHT;
        return new JoinPlan(JoinAlgorithm.HASH_JOIN, buildSide);
    }
}
