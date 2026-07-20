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

import stroom.query.language.functions.StateFetcher;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValNull;
import stroom.query.planner.cost.JoinAlgorithm;
import stroom.query.planner.logical.JoinType;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

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
 * <p><b>SQL null semantics</b>: a row whose equi-key value is null ({@link ValNull}, or a {@code null} array
 * slot) never joins - SQL {@code NULL != NULL} - so it is excluded from a hash bucket and never equals another
 * null-keyed row. Without this, {@link ValNull#toString()} returning {@code null} would make every null-keyed
 * left row collide with every null-keyed right row and fabricate a spurious cross-product. A null-keyed left row
 * is still emitted (null-padded) for a {@link JoinType#LEFT} join, exactly like any other unmatched left row.</p>
 *
 * <p><b>Scope note</b>: {@link JoinAlgorithm#HASH_JOIN} here always materialises the <i>right</i> side, regardless
 * of which side {@code JoinCostModel.chooseAlgorithm} would prefer as the build side - correct for both {@code
 * INNER} and {@code LEFT} (materialising the side that must appear unconditionally, i.e. the left side, would
 * need extra bookkeeping to still emit its unmatched rows). Honouring the cost model's build-side choice for
 * performance, when it disagrees with this default, is deferred - never a correctness gap, only a possible
 * missed optimisation.</p>
 *
 * <p>{@link JoinAlgorithm#BROADCAST_LOOKUP} - the enrichment-join fast path against a keyed Plan B/State store
 * (see {@code docs/join-scalability-implementation-plan.md}, decision D8, item B1) - is <b>not</b> reachable
 * through {@link #join(Side, Side, JoinType, JoinAlgorithm, long)}: that method's contract is "combine two
 * already-materialised row sets", and the whole point of broadcast lookup is to <i>never</i> materialise the
 * lookup side. It has its own entry point instead: {@link #broadcastLookupJoin}.</p>
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
     * Same contract as {@link #join(Side, Side, JoinType, JoinAlgorithm, long)}, with no output-row cap (equivalent
     * to passing {@link Long#MAX_VALUE}). Kept as a separate overload, rather than a default parameter, purely so
     * every pre-existing caller/test that doesn't care about the cap is unaffected by its addition (see
     * {@code docs/join-scalability-implementation-plan.md}, decision D1).
     *
     * @return never null; never throws {@link JoinLimitExceededException} (there is no cap to breach).
     */
    public static List<Val[]> join(
            final Side left, final Side right, final JoinType joinType, final JoinAlgorithm algorithm) {
        return join(left, right, joinType, algorithm, Long.MAX_VALUE);
    }

    /**
     * Combines two already-realised row sets into joined rows, aborting once the joined output would exceed
     * {@code maxOutputRows} - see {@code docs/join-scalability-implementation-plan.md}, decision D1 (Phase 0, item
     * C4). The cap is checked as each output row is produced (not just once at the end), so a single left row
     * with a very large fan-out of matches on the right is still bounded rather than fully accumulated before the
     * check runs.
     *
     * <p><b>Preconditions:</b> {@code left}, {@code right}, {@code joinType}, {@code algorithm} must all be
     * non-null (as for the unbounded overload); {@code maxOutputRows} must be {@code >= 0} (a value of {@code 0}
     * means "no output rows allowed at all" - the first row produced breaches it).<br>
     * <b>Postconditions:</b> on normal return, the result has {@code <= maxOutputRows} rows and has the same
     * content as the unbounded overload would produce for the same inputs. The result is never null (an empty
     * join returns an empty, not null, list).</p>
     *
     * @return one combined {@code Val[]} per matching row pair (left columns then right columns), plus - for
     *         {@link JoinType#LEFT} - one null-padded row per unmatched left row. Never a combined row for an
     *         unmatched right row (not a {@code RIGHT} join - the grammar only has {@code INNER}/{@code LEFT}).
     * @throws JoinLimitExceededException if the joined output would exceed {@code maxOutputRows}.
     * @throws IllegalArgumentException   if {@code maxOutputRows} is negative.
     */
    public static List<Val[]> join(
            final Side left, final Side right, final JoinType joinType, final JoinAlgorithm algorithm,
            final long maxOutputRows) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(joinType, "joinType");
        Objects.requireNonNull(algorithm, "algorithm");
        if (maxOutputRows < 0) {
            throw new IllegalArgumentException("maxOutputRows must be >= 0, got " + maxOutputRows);
        }
        return switch (algorithm) {
            case HASH_JOIN -> hashJoin(left, right, joinType, maxOutputRows);
            case NESTED_LOOP -> nestedLoopJoin(left, right, joinType, maxOutputRows);
            case BROADCAST_LOOKUP -> throw new UnsupportedOperationException(
                    "BROADCAST_LOOKUP needs a StateFetcher, not two materialised row sets - see Task 6.2.");
        };
    }

    /**
     * The enrichment-join fast path: streams {@code probeRows}, doing one keyed point-lookup per row against a
     * Plan B/State store via {@code stateFetcher} instead of materialising that store as a join side - see
     * {@code docs/join-scalability-implementation-plan.md}, decisions D5/D7/D8 (item B1). Every combined row is
     * handed to {@code out} as it is produced (never accumulated into a list here), so memory is bounded by the
     * probe side alone, not by the lookup store's size.
     *
     * <p>Per decision D5, the lookup side contributes exactly two synthetic columns to each combined row,
     * appended after the probe row's own columns: the probe's own key value (echoed back, named {@code Key}),
     * then the looked-up value (named {@code Value}), {@link ValNull#INSTANCE} on a miss or for a null-keyed probe
     * row. There is no multi-column enrichment in this version - {@link StateFetcher#getState} itself only ever
     * returns one {@link Val}.</p>
     *
     * <p><b>SQL null semantics</b>: a probe row whose key is {@code null}/{@link ValNull} is never looked up
     * (treated as an automatic miss), matching {@link #join}'s "{@code NULL != NULL}" rule - see this class's
     * Javadoc. It is still emitted (both synthetic columns null) for a {@link JoinType#LEFT} join.</p>
     *
     * <p><b>Preconditions:</b> {@code probeRows}, {@code stateFetcher}, {@code mapName}, {@code joinType}, and
     * {@code out} must not be null; {@code probeKeyPosition} must be a valid index into every row {@code
     * probeRows} yields (i.e. {@code 0 <= probeKeyPosition < probeWidth}); {@code probeWidth} must be
     * {@code >= 0}; {@code maxOutputRows} must be {@code >= 0} (as for {@link #join}).<br>
     * <b>Postconditions:</b> {@code out.accept(...)} is called once per surviving row, each of length
     * {@code probeWidth + 2}; never returns a value (streaming - the caller's {@code out} is where results go).
     * {@code probeRows} is fully consumed on normal completion.</p>
     *
     * @param probeRows        the streaming side's realised rows; never re-iterated, consumed exactly once.
     * @param probeKeyPosition which column of each probe row holds the join key to look up.
     * @param probeWidth       the width (column count) of every row {@code probeRows} yields.
     * @param stateFetcher     performs the point lookup; see {@link StateFetcher#getState}.
     * @param mapName          the Plan B store's name, passed straight through to {@code stateFetcher}.
     * @param effectiveTimeMs  the instant to evaluate state as of (only meaningful for a temporal store; see
     *                         decision D7).
     * @param joinType         {@link JoinType#INNER} drops an unmatched probe row; {@link JoinType#LEFT} keeps it,
     *                         null-padded.
     * @param maxOutputRows    see {@link #join(Side, Side, JoinType, JoinAlgorithm, long)}'s cap semantics.
     * @param out              receives each surviving combined row as it is produced.
     * @throws JoinLimitExceededException if the output would exceed {@code maxOutputRows}.
     * @throws IllegalArgumentException   if {@code probeKeyPosition}, {@code probeWidth}, or {@code maxOutputRows}
     *                                    is negative, or {@code probeKeyPosition >= probeWidth}.
     */
    public static void broadcastLookupJoin(
            final Iterator<Val[]> probeRows,
            final int probeKeyPosition,
            final int probeWidth,
            final StateFetcher stateFetcher,
            final String mapName,
            final long effectiveTimeMs,
            final JoinType joinType,
            final long maxOutputRows,
            final Consumer<Val[]> out) {
        Objects.requireNonNull(probeRows, "probeRows");
        Objects.requireNonNull(stateFetcher, "stateFetcher");
        Objects.requireNonNull(mapName, "mapName");
        Objects.requireNonNull(joinType, "joinType");
        Objects.requireNonNull(out, "out");
        if (probeWidth < 0) {
            throw new IllegalArgumentException("probeWidth must be >= 0, got " + probeWidth);
        }
        if (probeKeyPosition < 0 || probeKeyPosition >= probeWidth) {
            throw new IllegalArgumentException(
                    "probeKeyPosition must be in [0, probeWidth), got " + probeKeyPosition
                    + " for probeWidth " + probeWidth);
        }
        if (maxOutputRows < 0) {
            throw new IllegalArgumentException("maxOutputRows must be >= 0, got " + maxOutputRows);
        }

        long emitted = 0;
        while (probeRows.hasNext()) {
            final Val[] probeRow = probeRows.next();
            final Val probeKey = probeRow[probeKeyPosition];
            final boolean hasKey = probeKey != null && !(probeKey instanceof ValNull);
            final Val lookedUp = hasKey ? stateFetcher.getState(mapName, probeKey.toString(), effectiveTimeMs) : null;
            final boolean matched = lookedUp != null && !(lookedUp instanceof ValNull);
            if (matched) {
                emitted = emitOrThrow(out, combineWithLookup(probeRow, probeKey, lookedUp), maxOutputRows, emitted);
            } else if (joinType == JoinType.LEFT) {
                emitted = emitOrThrow(
                        out, combineWithLookup(probeRow, ValNull.INSTANCE, ValNull.INSTANCE), maxOutputRows, emitted);
            }
        }
    }

    /** Appends the lookup side's two synthetic columns ({@code Key} then {@code Value}) onto {@code probeRow}. */
    private static Val[] combineWithLookup(final Val[] probeRow, final Val key, final Val value) {
        final Val[] combined = new Val[probeRow.length + 2];
        System.arraycopy(probeRow, 0, combined, 0, probeRow.length);
        combined[probeRow.length] = key;
        combined[probeRow.length + 1] = value;
        return combined;
    }

    private static long emitOrThrow(
            final Consumer<Val[]> out, final Val[] row, final long maxOutputRows, final long emittedSoFar) {
        if (emittedSoFar >= maxOutputRows) {
            throw JoinLimitExceededException.forRowCount("join output row count", maxOutputRows, emittedSoFar + 1);
        }
        out.accept(row);
        return emittedSoFar + 1;
    }

    private static List<Val[]> hashJoin(
            final Side left, final Side right, final JoinType joinType, final long maxOutputRows) {
        final Map<List<String>, List<Val[]>> rightByKey = new HashMap<>();
        for (final Val[] rightRow : right.rows()) {
            final List<String> key = keyOf(rightRow, right.keyPositions());
            if (key == null) {
                continue; // SQL null key: never matches, so it is not a probe target.
            }
            rightByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(rightRow);
        }

        final List<Val[]> result = new ArrayList<>();
        for (final Val[] leftRow : left.rows()) {
            final List<String> key = keyOf(leftRow, left.keyPositions());
            final List<Val[]> matches = key == null ? null : rightByKey.get(key);
            appendMatchesOrPad(result, leftRow, matches, right.width(), joinType, maxOutputRows);
        }
        return result;
    }

    private static List<Val[]> nestedLoopJoin(
            final Side left, final Side right, final JoinType joinType, final long maxOutputRows) {
        final List<Val[]> result = new ArrayList<>();
        for (final Val[] leftRow : left.rows()) {
            final List<String> leftKey = keyOf(leftRow, left.keyPositions());
            List<Val[]> matches = null;
            if (leftKey != null) { // SQL null key: never matches, so skip the probe (still padded for LEFT).
                for (final Val[] rightRow : right.rows()) {
                    final List<String> rightKey = keyOf(rightRow, right.keyPositions());
                    if (rightKey != null && leftKey.equals(rightKey)) {
                        if (matches == null) {
                            matches = new ArrayList<>();
                        }
                        matches.add(rightRow);
                    }
                }
            }
            appendMatchesOrPad(result, leftRow, matches, right.width(), joinType, maxOutputRows);
        }
        return result;
    }

    /**
     * Appends {@code leftRow}'s joined output (one combined row per entry in {@code matches}, or one null-padded
     * row if unmatched and {@code joinType} is {@code LEFT}) onto {@code result}, throwing
     * {@link JoinLimitExceededException} the moment a row would take {@code result} over {@code maxOutputRows}
     * rather than after the fact - this is what keeps a single left row's fan-out from overshooting the cap.
     *
     * <p><b>Preconditions:</b> {@code result} and {@code leftRow} must not be null; {@code matches} may be null
     * (meaning "no matches found") or empty; {@code joinType} must not be null; {@code maxOutputRows} must be
     * {@code >= 0} (checked by the caller, {@link #join(Side, Side, JoinType, JoinAlgorithm, long)}).<br>
     * <b>Postconditions:</b> {@code result} has gained zero or more rows; if it would otherwise have exceeded
     * {@code maxOutputRows}, an exception is thrown instead and {@code result} is left with exactly
     * {@code maxOutputRows} rows (the breaching row is never added).</p>
     *
     * @throws JoinLimitExceededException if appending a row would make {@code result.size() > maxOutputRows}.
     */
    private static void appendMatchesOrPad(
            final List<Val[]> result,
            final Val[] leftRow,
            final @Nullable List<Val[]> matches,
            final int rightWidth,
            final JoinType joinType,
            final long maxOutputRows) {
        if (matches != null && !matches.isEmpty()) {
            for (final Val[] rightRow : matches) {
                addOrThrow(result, combine(leftRow, rightRow), maxOutputRows);
            }
        } else if (joinType == JoinType.LEFT) {
            addOrThrow(result, combine(leftRow, nulls(rightWidth)), maxOutputRows);
        }
    }

    private static void addOrThrow(final List<Val[]> result, final Val[] row, final long maxOutputRows) {
        if (result.size() >= maxOutputRows) {
            throw JoinLimitExceededException.forRowCount("join output row count", maxOutputRows, result.size() + 1);
        }
        result.add(row);
    }

    /**
     * The equi-key for {@code row} as a canonical string tuple, or {@code null} when any key component is
     * SQL-null ({@link ValNull} or a {@code null} slot). A null result signals "this row cannot join" - see the
     * class Javadoc's SQL-null-semantics note. Using {@link Val#toString()} directly is safe here precisely
     * because null components short-circuit to {@code null} rather than being stringified (which for
     * {@link ValNull} would yield a Java {@code null} element and let null keys collide).
     */
    private static @Nullable List<String> keyOf(final Val[] row, final int[] positions) {
        final List<String> key = new ArrayList<>(positions.length);
        for (final int position : positions) {
            final Val value = row[position];
            if (value == null || value instanceof ValNull) {
                return null;
            }
            key.add(value.toString());
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
