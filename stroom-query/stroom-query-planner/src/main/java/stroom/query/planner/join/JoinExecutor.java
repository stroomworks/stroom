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

package stroom.query.planner.join;

import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValNull;
import stroom.query.planner.cost.JoinAlgorithm;
import stroom.query.planner.logical.JoinType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Combines two already-realised row sets into joined {@code Val[]} rows - pure JVM logic, no I/O, following the
 * same "standalone, thoroughly unit-tested, not wired into anything yet" posture Phase 3's {@code
 * CostModel}/{@code JoinCostModel} used - see {@code docs/query-optimiser-implementation-plan.md}, Task 6.1c.
 *
 * <p>Equi-key matching uses each key {@link Val}'s {@code toString()} as a canonical form, the <b>same</b>
 * semantic for both {@link JoinAlgorithm#HASH_JOIN} and {@link JoinAlgorithm#NESTED_LOOP} - so a different
 * algorithm choice never changes which rows match, only how fast the match is found (the design doc's
 * "algorithm choice never changes the result" invariant).</p>
 *
 * <p><b>Scope note</b>: {@link JoinAlgorithm#HASH_JOIN} here always materialises the <i>right</i> side, regardless
 * of which side {@code JoinCostModel.chooseAlgorithm} would prefer as the build side - correct for both {@code
 * INNER} and {@code LEFT} (materialising the side that must appear unconditionally, i.e. the left side, would
 * need extra bookkeeping to still emit its unmatched rows). Honouring the cost model's build-side choice for
 * performance, when it disagrees with this default, is deferred - never a correctness gap, only a possible
 * missed optimisation. {@link JoinAlgorithm#BROADCAST_LOOKUP} is Task 6.2's job specifically (it needs a {@code
 * StateFetcher}, not two materialised row sets).</p>
 */
public final class JoinExecutor {

    private JoinExecutor() {
    }

    /**
     * One side's realised rows plus which column position(s) hold its equi-key value(s).
     *
     * @param rows         never null; each element's length must be exactly {@code width}.
     * @param keyPositions never null, never empty; positions into each row that make up this side's equi-key
     *                     (composite keys supported - matched as an ordered tuple).
     * @param width        the width of every row in {@code rows} - needed even when {@code rows} is empty, to
     *                     null-pad the *other* side's unmatched rows correctly.
     */
    public record Side(List<Val[]> rows, int[] keyPositions, int width) {

        public Side {
            Objects.requireNonNull(rows, "rows");
            Objects.requireNonNull(keyPositions, "keyPositions");
            if (keyPositions.length == 0) {
                throw new IllegalArgumentException("keyPositions must not be empty");
            }
            if (width < 0) {
                throw new IllegalArgumentException("width must not be negative");
            }
        }
    }

    /**
     * @return one combined {@code Val[]} per matching row pair (left columns then right columns), plus - for
     *         {@link JoinType#LEFT} - one null-padded row per unmatched left row. Never a combined row for an
     *         unmatched right row (not a {@code RIGHT} join - the grammar only has {@code INNER}/{@code LEFT}).
     */
    public static List<Val[]> join(
            final Side left, final Side right, final JoinType joinType, final JoinAlgorithm algorithm) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(joinType, "joinType");
        Objects.requireNonNull(algorithm, "algorithm");
        return switch (algorithm) {
            case HASH_JOIN -> hashJoin(left, right, joinType);
            case NESTED_LOOP -> nestedLoopJoin(left, right, joinType);
            case BROADCAST_LOOKUP -> throw new UnsupportedOperationException(
                    "BROADCAST_LOOKUP needs a StateFetcher, not two materialised row sets - see Task 6.2.");
        };
    }

    private static List<Val[]> hashJoin(final Side left, final Side right, final JoinType joinType) {
        final Map<List<String>, List<Val[]>> rightByKey = new HashMap<>();
        for (final Val[] rightRow : right.rows()) {
            rightByKey.computeIfAbsent(keyOf(rightRow, right.keyPositions()), k -> new ArrayList<>())
                    .add(rightRow);
        }

        final List<Val[]> result = new ArrayList<>();
        for (final Val[] leftRow : left.rows()) {
            final List<Val[]> matches = rightByKey.get(keyOf(leftRow, left.keyPositions()));
            appendMatchesOrPad(result, leftRow, matches, right.width(), joinType);
        }
        return result;
    }

    private static List<Val[]> nestedLoopJoin(final Side left, final Side right, final JoinType joinType) {
        final List<Val[]> result = new ArrayList<>();
        for (final Val[] leftRow : left.rows()) {
            final List<String> leftKey = keyOf(leftRow, left.keyPositions());
            List<Val[]> matches = null;
            for (final Val[] rightRow : right.rows()) {
                if (leftKey.equals(keyOf(rightRow, right.keyPositions()))) {
                    if (matches == null) {
                        matches = new ArrayList<>();
                    }
                    matches.add(rightRow);
                }
            }
            appendMatchesOrPad(result, leftRow, matches, right.width(), joinType);
        }
        return result;
    }

    private static void appendMatchesOrPad(
            final List<Val[]> result,
            final Val[] leftRow,
            final List<Val[]> matches,
            final int rightWidth,
            final JoinType joinType) {
        if (matches != null && !matches.isEmpty()) {
            for (final Val[] rightRow : matches) {
                result.add(combine(leftRow, rightRow));
            }
        } else if (joinType == JoinType.LEFT) {
            result.add(combine(leftRow, nulls(rightWidth)));
        }
    }

    private static List<String> keyOf(final Val[] row, final int[] positions) {
        final List<String> key = new ArrayList<>(positions.length);
        for (final int position : positions) {
            key.add(String.valueOf(row[position]));
        }
        return key;
    }

    private static Val[] combine(final Val[] left, final Val[] right) {
        final Val[] combined = new Val[left.length + right.length];
        System.arraycopy(left, 0, combined, 0, left.length);
        System.arraycopy(right, 0, combined, left.length, right.length);
        return combined;
    }

    private static Val[] nulls(final int width) {
        final Val[] result = new Val[width];
        Arrays.fill(result, ValNull.INSTANCE);
        return result;
    }
}
