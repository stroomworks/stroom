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
import stroom.query.language.functions.Type;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValErr;
import stroom.query.language.functions.ValNull;
import stroom.query.planner.cost.JoinAlgorithm;
import stroom.query.planner.logical.JoinType;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Combines two already-realised row sets into joined {@code Val[]} rows - pure JVM logic, no I/O, following the
 * same "standalone, thoroughly unit-tested, not wired into anything yet" posture Phase 3's {@code
 * CostModel}/{@code JoinCostModel} used - see {@code docs/query-optimiser-implementation-plan.md}, Task 6.1c.
 *
 * <p>Equi-key matching canonicalises each key {@link Val} via {@link #keyOf} - the <b>same</b> semantic for both
 * {@link JoinAlgorithm#HASH_JOIN} and {@link JoinAlgorithm#NESTED_LOOP} - so a different algorithm choice never
 * changes which rows match, only how fast the match is found (the design doc's "algorithm choice never changes
 * the result" invariant). A numeric-typed component (integer/long/short/byte, float/double) keys on its numeric
 * value, so e.g. {@code ValLong 5} and {@code ValDouble 5.0} match; every other type - in particular string and
 * date/duration - keys on its existing {@code toString()} unchanged (see {@link #keyOf}'s Javadoc for the exact
 * rule, and {@code docs/query-graphdb-review-report.md} finding F5 for the divergent-date residual this leaves).
 * </p>
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
 * <p><b>Streaming variant</b>: {@link #streamingHashJoin} is the same hash join with the build side hidden behind
 * a {@link BuildSideLookup} (so it can spill to disk) and the probe side consumed as an {@link Iterator} with
 * results pushed to a {@link Consumer} instead of accumulated - see {@code docs/join-scalability-implementation-
 * plan.md}, items C1/C2. The list-returning {@link #join}/{@code hashJoin} path delegates to it over an on-heap
 * {@link HeapBuildSideLookup}, so both share one join loop.</p>
 *
 * <p>{@link JoinAlgorithm#BROADCAST_LOOKUP} - the enrichment-join fast path against a keyed Plan B/State store
 * (see {@code docs/join-scalability-implementation-plan.md}, decision D8, item B1) - is <b>not</b> reachable
 * through {@link #join(Side, Side, JoinType, JoinAlgorithm, long)}: that method's contract is "combine two
 * already-materialised row sets", and the whole point of broadcast lookup is to <i>never</i> materialise the
 * lookup side. It has its own entry point instead: {@link #broadcastLookupJoin}.</p>
 */
public final class JoinExecutor {

    /**
     * {@code 2^63}, i.e. one past {@link Long#MAX_VALUE} - the exclusive upper bound (and, negated, the
     * exclusive lower bound) a {@code double} must fall within for {@code (long) d} to be a safe, non-saturating
     * narrowing conversion. Used by {@link #canonicalFloatingPoint(Val)} to decide whether an integral
     * {@code FLOAT}/{@code DOUBLE} value can be canonicalised through {@code long} at all.
     */
    private static final double MAX_LONG_AS_DOUBLE_EXCLUSIVE = 9_223_372_036_854_775_808.0;

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
     * <p><b>Lookup failure vs. miss</b>: {@link StateFetcher#getState} returning {@code ValErr} (a failed
     * lookup - e.g. a permission deny, or a key shape mismatched to the store's type) is a real error, never a
     * miss - it is <b>not</b> null-padded/dropped like a genuine {@code ValNull} miss, and is never embedded as
     * the {@code Value} column. It aborts the whole probe by throwing {@link BroadcastLookupFailedException},
     * exactly like {@link JoinLimitExceededException} aborts on a breached cap (see
     * {@code docs/query-graphdb-review-report.md}, findings F1/SEC-1).</p>
     *
     * <p><b>Preconditions:</b> {@code probeRows}, {@code stateFetcher}, {@code mapName}, {@code joinType}, and
     * {@code out} must not be null; {@code probeKeyPosition} must be a valid index into every row {@code
     * probeRows} yields (i.e. {@code 0 <= probeKeyPosition < probeWidth}); {@code probeWidth} must be
     * {@code >= 0}; {@code maxOutputRows} must be {@code >= 0} (as for {@link #join}).<br>
     * <b>Postconditions:</b> {@code out.accept(...)} is called once per surviving row, each of length
     * {@code probeWidth + 2}; never returns a value (streaming - the caller's {@code out} is where results go).
     * {@code probeRows} is fully consumed on normal completion, unless a lookup fails (see above).</p>
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
     * @throws JoinLimitExceededException     if the output would exceed {@code maxOutputRows}.
     * @throws BroadcastLookupFailedException if a lookup returns {@code ValErr} (see above).
     * @throws IllegalArgumentException        if {@code probeKeyPosition}, {@code probeWidth}, or
     *                                          {@code maxOutputRows} is negative, or
     *                                          {@code probeKeyPosition >= probeWidth}.
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
        final Consumer<Val[]> probe = broadcastLookupProbe(
                probeKeyPosition, probeWidth, stateFetcher, mapName, effectiveTimeMs, joinType, maxOutputRows, out);
        probeRows.forEachRemaining(probe);
    }

    /**
     * The push-based counterpart to {@link #broadcastLookupJoin}: returns a stateful {@link Consumer} that does
     * one keyed point-lookup per {@code accept(...)} call, so a caller reading its probe side incrementally
     * (e.g. {@code JoinSearchProvider} streaming rows out of a {@code DataStore.fetch} callback) never has to
     * materialise that side into a list first - the whole point being that neither side of an enrichment join is
     * ever fully resident. Feeding every probe row to the returned consumer is exactly equivalent to a single
     * {@link #broadcastLookupJoin} over the same rows (see its Javadoc for the lookup/null/output-cap semantics).
     *
     * <p><b>Preconditions:</b> {@code stateFetcher}, {@code mapName}, {@code joinType}, {@code out} must be
     * non-null; {@code probeWidth} {@code >= 0}; {@code 0 <= probeKeyPosition < probeWidth};
     * {@code maxOutputRows} {@code >= 0}.<br>
     * <b>Postconditions:</b> never null. The returned consumer is <b>stateful and single-use</b> - it tracks the
     * running output-row count to enforce {@code maxOutputRows}, so it must not be shared across threads or reused
     * for a second probe stream.</p>
     *
     * @throws IllegalArgumentException if {@code probeWidth}, {@code probeKeyPosition}, or {@code maxOutputRows}
     *                                  is out of range.
     */
    public static Consumer<Val[]> broadcastLookupProbe(
            final int probeKeyPosition,
            final int probeWidth,
            final StateFetcher stateFetcher,
            final String mapName,
            final long effectiveTimeMs,
            final JoinType joinType,
            final long maxOutputRows,
            final Consumer<Val[]> out) {
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

        return new Consumer<>() {
            private long emitted;

            @Override
            public void accept(final Val[] probeRow) {
                final Val probeKey = probeRow[probeKeyPosition];
                final boolean hasKey = probeKey != null && !(probeKey instanceof ValNull);
                final Val lookedUp =
                        hasKey ? stateFetcher.getState(mapName, probeKey.toString(), effectiveTimeMs) : null;
                if (lookedUp instanceof ValErr) {
                    // A failed lookup (e.g. a permission deny, or a key shape mismatched to the store's
                    // type) is a real error, never "no match" - it must not be embedded as the joined
                    // Value column, nor counted as a matched row. Fail the whole search the same way a
                    // breached output cap does, rather than silently downgrading the failure to junk data
                    // (see docs/query-graphdb-review-report.md, findings F1/SEC-1). A genuine miss is
                    // ValNull, handled unchanged below.
                    throw BroadcastLookupFailedException.forLookupError(mapName, probeKey.toString(), lookedUp);
                }
                final boolean matched = lookedUp != null && !(lookedUp instanceof ValNull);
                if (matched) {
                    emitted = emitOrThrow(
                            out, combineWithLookup(probeRow, probeKey, lookedUp), maxOutputRows, emitted);
                } else if (joinType == JoinType.LEFT) {
                    emitted = emitOrThrow(
                            out, combineWithLookup(probeRow, ValNull.INSTANCE, ValNull.INSTANCE),
                            maxOutputRows, emitted);
                }
            }
        };
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

    /**
     * The list-returning hash join, now a thin adapter over {@link #streamingHashJoin}: it builds an on-heap
     * {@link HeapBuildSideLookup} over the {@code right} side (the same map the old inline implementation built)
     * and streams the {@code left} side through it, collecting the output into a list. Keeping a single join loop
     * (in {@link #streamingHashJoin}) means the list and streaming paths can never drift in their match/pad/cap
     * semantics.
     */
    private static List<Val[]> hashJoin(
            final Side left, final Side right, final JoinType joinType, final long maxOutputRows) {
        try (final BuildSideLookup buildSide = HeapBuildSideLookup.of(right)) {
            final List<Val[]> result = new ArrayList<>();
            streamingHashJoin(left.rows().iterator(), left.keyPositions(), buildSide, right.width(),
                    joinType, maxOutputRows, result::add);
            return result;
        }
    }

    /**
     * Streams a probe side through an already-built {@link BuildSideLookup}, emitting each joined row to
     * {@code out} as it is produced rather than accumulating a list - the streaming/spilling hash join (see
     * {@code docs/join-scalability-implementation-plan.md}, items C1/C2). Only the build side need be resident
     * (and it may itself spill to disk behind {@link BuildSideLookup}); the probe side is consumed one row at a
     * time and never materialised, so join memory is bounded by the build side alone.
     *
     * <p><b>Orientation:</b> in production the build side is the join's <i>right</i> side and the probe side its
     * <i>left</i> side (the reverse of a naive read order), so unmatched left rows of a {@link JoinType#LEFT} join
     * are emitted inline here - null-padded on the right - exactly as the old {@link #hashJoin} did, with no
     * outer-join bookkeeping. Output row shape is always {@code [probe columns..., build columns...]}
     * (i.e. left then right), matching what {@code JoinSearchProvider} expects. Unmatched build (right) rows are
     * never emitted (the grammar has no {@code RIGHT} join).</p>
     *
     * <p><b>SQL null semantics</b>: a probe row whose key is SQL-null ({@link #keyOf} returns {@code null}) is an
     * automatic miss - never looked up - and is still emitted (null-padded) for a {@link JoinType#LEFT} join, the
     * same rule {@link #hashJoin} and {@link #broadcastLookupJoin} follow.</p>
     *
     * <p><b>Preconditions:</b> {@code probeRows}, {@code probeKeyPositions}, {@code buildSide}, {@code joinType},
     * and {@code out} must be non-null; {@code probeKeyPositions} must be non-empty and each entry a valid index
     * into every probe row; {@code buildWidth} (the build/right side's column count, used to null-pad an unmatched
     * left row) must be {@code >= 0}; {@code maxOutputRows} must be {@code >= 0} (as for {@link #join}).<br>
     * <b>Postconditions:</b> {@code out.accept(...)} is called once per surviving joined row, each of length
     * {@code probeWidth + buildWidth}; never returns a value (results go to {@code out}); {@code probeRows} is
     * fully consumed on normal completion.</p>
     *
     * @throws JoinLimitExceededException if the joined output would exceed {@code maxOutputRows} (checked per
     *                                    emitted row, so a single probe row's large fan-out is still bounded).
     * @throws IllegalArgumentException   if {@code probeKeyPositions} is empty, or {@code buildWidth}/
     *                                    {@code maxOutputRows} is negative.
     */
    public static void streamingHashJoin(
            final Iterator<Val[]> probeRows,
            final int[] probeKeyPositions,
            final BuildSideLookup buildSide,
            final int buildWidth,
            final JoinType joinType,
            final long maxOutputRows,
            final Consumer<Val[]> out) {
        Objects.requireNonNull(probeRows, "probeRows");
        final Consumer<Val[]> probe =
                streamingProbe(probeKeyPositions, buildSide, buildWidth, joinType, maxOutputRows, out);
        probeRows.forEachRemaining(probe);
    }

    /**
     * The push-based counterpart to {@link #streamingHashJoin}: returns a stateful {@link Consumer} that joins one
     * probe row per {@code accept(...)} call, so a caller reading its probe side incrementally (e.g.
     * {@code JoinSearchProvider} streaming rows out of a {@code DataStore.fetch} callback) never has to materialise
     * that side into a list first. Feeding every probe row to the returned consumer is exactly equivalent to a
     * single {@link #streamingHashJoin} over the same rows - the match/pad/null-key/output-cap semantics are
     * single-sourced here (see {@link #streamingHashJoin}'s Javadoc for those semantics).
     *
     * <p><b>Preconditions:</b> {@code probeKeyPositions}, {@code buildSide}, {@code joinType}, {@code out} must be
     * non-null; {@code probeKeyPositions} non-empty; {@code buildWidth} and {@code maxOutputRows} {@code >= 0}.<br>
     * <b>Postconditions:</b> never null. The returned consumer is <b>stateful and single-use</b> - it tracks the
     * running output-row count across calls to enforce {@code maxOutputRows}, so it must not be shared across
     * threads or reused for a second probe stream.</p>
     *
     * @throws IllegalArgumentException if {@code probeKeyPositions} is empty, or {@code buildWidth}/
     *                                  {@code maxOutputRows} is negative.
     */
    public static Consumer<Val[]> streamingProbe(
            final int[] probeKeyPositions,
            final BuildSideLookup buildSide,
            final int buildWidth,
            final JoinType joinType,
            final long maxOutputRows,
            final Consumer<Val[]> out) {
        Objects.requireNonNull(probeKeyPositions, "probeKeyPositions");
        if (probeKeyPositions.length == 0) {
            throw new IllegalArgumentException("probeKeyPositions must not be empty");
        }
        Objects.requireNonNull(buildSide, "buildSide");
        if (buildWidth < 0) {
            throw new IllegalArgumentException("buildWidth must be >= 0, got " + buildWidth);
        }
        Objects.requireNonNull(joinType, "joinType");
        if (maxOutputRows < 0) {
            throw new IllegalArgumentException("maxOutputRows must be >= 0, got " + maxOutputRows);
        }
        Objects.requireNonNull(out, "out");

        return new Consumer<>() {
            private long emitted;

            @Override
            public void accept(final Val[] probeRow) {
                final List<String> key = keyOf(probeRow, probeKeyPositions);
                // Stream the build side's matches one at a time (never materialising a hot key's whole group) and
                // apply the output cap per emitted row - so a skewed key aborts at the cap rather than OOMing.
                final boolean matched = key != null && buildSide.forEachMatch(key, buildRow ->
                        emitted = emitOrThrow(out, combine(probeRow, buildRow), maxOutputRows, emitted));
                if (!matched && joinType == JoinType.LEFT) {
                    emitted = emitOrThrow(out, combine(probeRow, nulls(buildWidth)), maxOutputRows, emitted);
                }
            }
        };
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
     * class Javadoc's SQL-null-semantics note. Null components short-circuit to {@code null} rather than being
     * stringified (which for {@link ValNull} would yield a Java {@code null} element and let null keys collide).
     *
     * <p>Each non-null component is rendered by {@link #canonicalKeyComponent(Val)}: a <b>numeric-typed</b>
     * {@link Val} (integer/long/short/byte, float/double) is canonicalised so numerically-equal values of
     * different numeric types key identically - e.g. {@code ValLong 5} and {@code ValDouble 5.0} both render
     * {@code "5"} - while every other type keeps its existing {@link Val#toString()} exactly, unchanged from
     * before this canonicalisation existed. In particular a {@code ValString "5"} still keys as {@code "5"} (so
     * the already-working string-vs-integer match is preserved) and a {@code ValString "5.0"} keys as the
     * literal {@code "5.0"} - a string is never reinterpreted as a number. Dates and durations are also left
     * un-canonicalised - a divergent date format across two sides is a documented residual, not fixed here (see
     * {@code docs/query-graphdb-review-report.md}, finding F5).</p>
     *
     * <p>Public so every producer of a {@link BuildSideLookup} key derives it identically to how
     * {@link #streamingProbe} derives the probe key - e.g. {@code JoinSearchProvider} populating the build side
     * from a streamed scan. Keeping key derivation single-sourced here is what guarantees the build and probe
     * sides agree on what "the same key" means - including this canonicalisation, applied symmetrically to
     * both sides.</p>
     *
     * <p><b>Preconditions:</b> {@code row} and {@code positions} non-null; every entry of {@code positions} a
     * valid index into {@code row}.<br>
     * <b>Postconditions:</b> null iff any key component is SQL-null; otherwise a non-null, {@code positions.length}
     * -element list.</p>
     */
    public static @Nullable List<String> keyOf(final Val[] row, final int[] positions) {
        final List<String> key = new ArrayList<>(positions.length);
        for (final int position : positions) {
            final Val value = row[position];
            if (value == null || value instanceof ValNull) {
                return null;
            }
            key.add(canonicalKeyComponent(value));
        }
        return key;
    }

    /**
     * Renders one equi-key component: {@link #canonicalNumeric(Val)}'s result for a numeric-typed {@code value},
     * or {@code value.toString()} unchanged for every other type - see {@link #keyOf}'s Javadoc for why
     * non-numeric types (in particular strings and dates) are deliberately left as-is.
     */
    private static String canonicalKeyComponent(final Val value) {
        final String numeric = canonicalNumeric(value);
        return numeric != null
                ? numeric
                : value.toString();
    }

    /**
     * A canonical numeric rendering of {@code value}, or {@code null} if its {@link Type} is not one of the
     * fixed-point/floating-point numeric types this method canonicalises: {@code BYTE}/{@code SHORT}/
     * {@code INTEGER}/{@code LONG} (fixed-point) and {@code FLOAT}/{@code DOUBLE} (floating-point). Every other
     * type - including {@code DATE} and {@code DURATION}, deliberately excluded even though {@link Type#isNumber()}
     * is {@code true} for them - returns {@code null} here and falls back to {@code toString()} in
     * {@link #canonicalKeyComponent(Val)}.
     *
     * <p>A fixed-point type renders via {@link Val#toLong()} directly - <b>never</b> round-tripped through a
     * {@code double} - so a {@code long} outside {@code double}'s exact-integer range still keys on its precise
     * value rather than a lossy approximation. A floating-point type renders in that same long form when the
     * value is integral and within {@code long} range (so {@code ValDouble 5.0} keys identically to
     * {@code ValLong 5}); a genuinely fractional value (or one too large for a {@code long}) falls back to
     * {@link Val#toString()} unchanged (so {@code ValDouble 5.5} keys as {@code "5.5"}, exactly as before).</p>
     */
    private static @Nullable String canonicalNumeric(final Val value) {
        return switch (value.type()) {
            case BYTE, SHORT, INTEGER, LONG -> Long.toString(value.toLong());
            case FLOAT, DOUBLE -> canonicalFloatingPoint(value);
            default -> null;
        };
    }

    /**
     * Canonicalises a {@code FLOAT}/{@code DOUBLE} {@link Val}: the exact {@code long} form if the value is
     * integral (per {@link Val#hasFractionalPart()}) and within {@code long} range, otherwise
     * {@link Val#toString()} unchanged.
     */
    private static String canonicalFloatingPoint(final Val value) {
        final double d = value.toDouble();
        if (!value.hasFractionalPart()
                && !Double.isNaN(d)
                && !Double.isInfinite(d)
                && d > -MAX_LONG_AS_DOUBLE_EXCLUSIVE
                && d < MAX_LONG_AS_DOUBLE_EXCLUSIVE) {
            return Long.toString((long) d);
        }
        return value.toString();
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
