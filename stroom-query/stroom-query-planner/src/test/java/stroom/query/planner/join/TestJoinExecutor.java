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
import stroom.query.language.functions.ValLong;
import stroom.query.language.functions.ValNull;
import stroom.query.language.functions.ValString;
import stroom.query.planner.cost.JoinAlgorithm;
import stroom.query.planner.join.JoinExecutor.Side;
import stroom.query.planner.logical.JoinType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 6.1c: proves {@link JoinExecutor} - INNER/LEFT correctness, matching identically across both algorithms
 * (the design doc's "algorithm choice never changes the result" invariant), and unmatched-left-row null-padding
 * for LEFT joins.
 */
class TestJoinExecutor {

    // Left rows: [id, name]. Right rows: [id, amount]. Joined on left[0] = right[0].
    private static Val[] leftRow(final long id, final String name) {
        return new Val[]{ValLong.create(id), ValString.create(name)};
    }

    private static Val[] rightRow(final long id, final long amount) {
        return new Val[]{ValLong.create(id), ValLong.create(amount)};
    }

    @ParameterizedTest
    @EnumSource(value = JoinAlgorithm.class, names = {"HASH_JOIN", "NESTED_LOOP"})
    void innerJoin_onlyEmitsMatchingRows(final JoinAlgorithm algorithm) {
        final Side left = new Side(List.of(leftRow(1, "a"), leftRow(2, "b")), new int[]{0}, 2);
        final Side right = new Side(List.of(rightRow(2, 200), rightRow(3, 300)), new int[]{0}, 2);

        final List<Val[]> result = JoinExecutor.join(left, right, JoinType.INNER, algorithm);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).containsExactly(ValLong.create(2), ValString.create("b"),
                ValLong.create(2), ValLong.create(200));
    }

    @ParameterizedTest
    @EnumSource(value = JoinAlgorithm.class, names = {"HASH_JOIN", "NESTED_LOOP"})
    void leftJoin_padsUnmatchedLeftRowsWithNulls_ratherThanDroppingThem(final JoinAlgorithm algorithm) {
        final Side left = new Side(List.of(leftRow(1, "a"), leftRow(2, "b")), new int[]{0}, 2);
        final Side right = new Side(List.<Val[]>of(rightRow(2, 200)), new int[]{0}, 2);

        final List<Val[]> result = JoinExecutor.join(left, right, JoinType.LEFT, algorithm);

        assertThat(result).hasSize(2);
        assertThat(result).anySatisfy(row -> assertThat(row).containsExactly(
                ValLong.create(1), ValString.create("a"), ValNull.INSTANCE, ValNull.INSTANCE));
        assertThat(result).anySatisfy(row -> assertThat(row).containsExactly(
                ValLong.create(2), ValString.create("b"), ValLong.create(2), ValLong.create(200)));
    }

    @ParameterizedTest
    @EnumSource(value = JoinAlgorithm.class, names = {"HASH_JOIN", "NESTED_LOOP"})
    void oneToManyMatch_fansOutOneRowPerMatch(final JoinAlgorithm algorithm) {
        final Side left = new Side(List.<Val[]>of(leftRow(1, "a")), new int[]{0}, 2);
        final Side right = new Side(List.of(rightRow(1, 100), rightRow(1, 101)), new int[]{0}, 2);

        final List<Val[]> result = JoinExecutor.join(left, right, JoinType.INNER, algorithm);

        assertThat(result).hasSize(2);
    }

    @Test
    void bothAlgorithms_produceTheSameResultSet() {
        final Side left = new Side(
                List.of(leftRow(1, "a"), leftRow(2, "b"), leftRow(3, "c")), new int[]{0}, 2);
        final Side right = new Side(
                List.of(rightRow(2, 200), rightRow(3, 300), rightRow(3, 301)), new int[]{0}, 2);

        final List<Val[]> hashJoinResult = JoinExecutor.join(left, right, JoinType.LEFT, JoinAlgorithm.HASH_JOIN);
        final List<Val[]> nestedLoopResult = JoinExecutor.join(left, right, JoinType.LEFT, JoinAlgorithm.NESTED_LOOP);

        assertThat(hashJoinResult).hasSameSizeAs(nestedLoopResult);
        assertThat(hashJoinResult.stream().map(Arrays::asList).toList())
                .containsExactlyInAnyOrderElementsOf(
                        nestedLoopResult.stream().map(Arrays::asList).toList());
    }

    @Test
    void broadcastLookup_isNotThisTasksJob() {
        final Side left = new Side(List.<Val[]>of(leftRow(1, "a")), new int[]{0}, 2);
        final Side right = new Side(List.<Val[]>of(rightRow(1, 100)), new int[]{0}, 2);

        assertThatThrownBy(() -> JoinExecutor.join(left, right, JoinType.INNER, JoinAlgorithm.BROADCAST_LOOKUP))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sideRejectsEmptyKeyPositions() {
        assertThatThrownBy(() -> new Side(List.of(), new int[0], 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = JoinAlgorithm.class, names = {"HASH_JOIN", "NESTED_LOOP"})
    void nullKeyRows_neverMatchEachOther_forInnerJoin(final JoinAlgorithm algorithm) {
        // SQL NULL != NULL: two rows whose join key is null must NOT join, even though ValNull.toString() is null
        // (which previously made every null-keyed row collide into one bucket and cross-product with each other).
        final Val[] leftNull = new Val[]{ValNull.INSTANCE, ValString.create("a")};
        final Val[] rightNull = new Val[]{ValNull.INSTANCE, ValLong.create(200)};
        final Side left = new Side(List.of(leftNull, leftRow(1, "b")), new int[]{0}, 2);
        final Side right = new Side(List.of(rightNull, rightRow(1, 100)), new int[]{0}, 2);

        final List<Val[]> result = JoinExecutor.join(left, right, JoinType.INNER, algorithm);

        // Only the non-null key (1) matches; the two null-keyed rows produce no joined row at all.
        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).containsExactly(
                ValLong.create(1), ValString.create("b"), ValLong.create(1), ValLong.create(100));
    }

    @ParameterizedTest
    @EnumSource(value = JoinAlgorithm.class, names = {"HASH_JOIN", "NESTED_LOOP"})
    void nullKeyLeftRow_isStillPaddedForLeftJoin_butNeverMatchesANullKeyRightRow(final JoinAlgorithm algorithm) {
        final Val[] leftNull = new Val[]{ValNull.INSTANCE, ValString.create("a")};
        final Side left = new Side(List.<Val[]>of(leftNull), new int[]{0}, 2);
        final Side right = new Side(List.<Val[]>of(new Val[]{ValNull.INSTANCE, ValLong.create(200)}), new int[]{0}, 2);

        final List<Val[]> result = JoinExecutor.join(left, right, JoinType.LEFT, algorithm);

        // The null-keyed left row is kept (LEFT join) but null-padded - it does NOT match the null-keyed right row.
        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).containsExactly(
                ValNull.INSTANCE, ValString.create("a"), ValNull.INSTANCE, ValNull.INSTANCE);
    }

    @ParameterizedTest
    @EnumSource(value = JoinAlgorithm.class, names = {"HASH_JOIN", "NESTED_LOOP"})
    void maxOutputRows_underTheCap_isUnaffected(final JoinAlgorithm algorithm) {
        final Side left = new Side(List.of(leftRow(1, "a"), leftRow(2, "b")), new int[]{0}, 2);
        final Side right = new Side(List.of(rightRow(1, 100), rightRow(2, 200)), new int[]{0}, 2);

        final List<Val[]> result = JoinExecutor.join(left, right, JoinType.INNER, algorithm, 2);

        assertThat(result).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(value = JoinAlgorithm.class, names = {"HASH_JOIN", "NESTED_LOOP"})
    void maxOutputRows_exceeded_throwsJoinLimitExceededException(final JoinAlgorithm algorithm) {
        final Side left = new Side(List.of(leftRow(1, "a"), leftRow(2, "b")), new int[]{0}, 2);
        final Side right = new Side(List.of(rightRow(1, 100), rightRow(2, 200)), new int[]{0}, 2);

        assertThatThrownBy(() -> JoinExecutor.join(left, right, JoinType.INNER, algorithm, 1))
                .isInstanceOf(JoinLimitExceededException.class)
                .hasMessageContaining("1 rows");
    }

    @ParameterizedTest
    @EnumSource(value = JoinAlgorithm.class, names = {"HASH_JOIN", "NESTED_LOOP"})
    void maxOutputRows_zero_rejectsAnyOutputAtAll(final JoinAlgorithm algorithm) {
        final Side left = new Side(List.<Val[]>of(leftRow(1, "a")), new int[]{0}, 2);
        final Side right = new Side(List.<Val[]>of(rightRow(1, 100)), new int[]{0}, 2);

        assertThatThrownBy(() -> JoinExecutor.join(left, right, JoinType.INNER, algorithm, 0))
                .isInstanceOf(JoinLimitExceededException.class);
    }

    @ParameterizedTest
    @EnumSource(value = JoinAlgorithm.class, names = {"HASH_JOIN", "NESTED_LOOP"})
    void maxOutputRows_onlyCountsEmittedRows_notMatchesConsidered(final JoinAlgorithm algorithm) {
        // A single left row fanning out to many right matches must still be bounded mid-fan-out, not just
        // checked once at the end - see appendMatchesOrPad's Javadoc.
        final Side left = new Side(List.<Val[]>of(leftRow(1, "a")), new int[]{0}, 2);
        final Side right = new Side(
                List.of(rightRow(1, 100), rightRow(1, 101), rightRow(1, 102)), new int[]{0}, 2);

        assertThatThrownBy(() -> JoinExecutor.join(left, right, JoinType.INNER, algorithm, 2))
                .isInstanceOf(JoinLimitExceededException.class);
    }

    @Test
    void unboundedOverload_isEquivalentToMaxLongCap() {
        final Side left = new Side(List.of(leftRow(1, "a"), leftRow(2, "b")), new int[]{0}, 2);
        final Side right = new Side(List.of(rightRow(2, 200), rightRow(3, 300)), new int[]{0}, 2);

        final List<Val[]> unbounded = JoinExecutor.join(left, right, JoinType.INNER, JoinAlgorithm.HASH_JOIN);
        final List<Val[]> explicitMax =
                JoinExecutor.join(left, right, JoinType.INNER, JoinAlgorithm.HASH_JOIN, Long.MAX_VALUE);

        assertThat(unbounded).hasSameSizeAs(explicitMax);
    }

    @Test
    void negativeMaxOutputRows_throwsIllegalArgumentException() {
        final Side left = new Side(List.<Val[]>of(leftRow(1, "a")), new int[]{0}, 2);
        final Side right = new Side(List.<Val[]>of(rightRow(1, 100)), new int[]{0}, 2);

        assertThatThrownBy(() -> JoinExecutor.join(left, right, JoinType.INNER, JoinAlgorithm.HASH_JOIN, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = JoinAlgorithm.class, names = {"HASH_JOIN", "NESTED_LOOP"})
    void compositeEquiKey_matchesOnAllKeyPositionsAsAnOrderedTuple(final JoinAlgorithm algorithm) {
        // Left rows [k1, k2, name]; right rows [k1, k2, amount]; joined on positions {0,1}.
        final Val[] l1 = new Val[]{ValLong.create(1), ValString.create("x"), ValString.create("a")};
        final Val[] l2 = new Val[]{ValLong.create(1), ValString.create("y"), ValString.create("b")};
        final Val[] r1 = new Val[]{ValLong.create(1), ValString.create("x"), ValLong.create(10)};
        final Val[] r2 = new Val[]{ValLong.create(1), ValString.create("z"), ValLong.create(20)};
        final Side left = new Side(List.of(l1, l2), new int[]{0, 1}, 3);
        final Side right = new Side(List.of(r1, r2), new int[]{0, 1}, 3);

        final List<Val[]> result = JoinExecutor.join(left, right, JoinType.INNER, algorithm);

        // Only (1,x) matches; (1,y) and (1,z) share k1 but differ on k2, so the composite key must not match them.
        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).containsExactly(
                ValLong.create(1), ValString.create("x"), ValString.create("a"),
                ValLong.create(1), ValString.create("x"), ValLong.create(10));
    }

    // ------------------------------------------------------------------------------------------------------
    // broadcastLookupJoin (Task B1 - see docs/join-scalability-implementation-plan.md, decisions D5/D7/D8):
    // the enrichment-join fast path, streaming a probe side against a keyed StateFetcher lookup instead of
    // materialising the lookup side.
    // ------------------------------------------------------------------------------------------------------

    /** A fake {@link stroom.query.language.functions.StateFetcher} backed by a plain map - misses return
     * {@link ValNull#INSTANCE}, matching {@code StateFetcherImpl}'s real "unknown key" behaviour. */
    private static StateFetcher fakeStore(final Map<String, Val> values) {
        return (map, key, effectiveTimeMs) -> values.getOrDefault(key, ValNull.INSTANCE);
    }

    private static Val[] probeRow(final long userId, final String name) {
        return new Val[]{ValLong.create(userId), ValString.create(name)};
    }

    @Test
    void matchingKey_emitsProbeRowPlusKeyAndValueColumns() {
        final StateFetcher store =
                fakeStore(Map.of("1", ValString.create("Alice")));
        final List<Val[]> result = new ArrayList<>();

        JoinExecutor.broadcastLookupJoin(
                List.<Val[]>of(probeRow(1, "a")).iterator(), 0, 2, store, "users", 0L, JoinType.INNER, Long.MAX_VALUE,
                result::add);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).containsExactly(
                ValLong.create(1), ValString.create("a"), ValLong.create(1), ValString.create("Alice"));
    }

    @Test
    void missingKey_innerJoin_dropsTheProbeRow() {
        final StateFetcher store = fakeStore(Map.of());
        final List<Val[]> result = new ArrayList<>();

        JoinExecutor.broadcastLookupJoin(
                List.<Val[]>of(probeRow(1, "a")).iterator(), 0, 2, store, "users", 0L, JoinType.INNER, Long.MAX_VALUE,
                result::add);

        assertThat(result).isEmpty();
    }

    @Test
    void missingKey_leftJoin_keepsTheProbeRow_nullPadded() {
        final StateFetcher store = fakeStore(Map.of());
        final List<Val[]> result = new ArrayList<>();

        JoinExecutor.broadcastLookupJoin(
                List.<Val[]>of(probeRow(1, "a")).iterator(), 0, 2, store, "users", 0L, JoinType.LEFT, Long.MAX_VALUE,
                result::add);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).containsExactly(
                ValLong.create(1), ValString.create("a"), ValNull.INSTANCE, ValNull.INSTANCE);
    }

    @Test
    void nullKeyedProbeRow_neverLookedUp_innerJoinDrops() {
        final StateFetcher store =
                fakeStore(Map.of("1", ValString.create("Alice")));
        final List<Val[]> result = new ArrayList<>();
        final Val[] nullKeyRow = new Val[]{ValNull.INSTANCE, ValString.create("a")};

        JoinExecutor.broadcastLookupJoin(
                List.<Val[]>of(nullKeyRow).iterator(), 0, 2, store, "users", 0L, JoinType.INNER, Long.MAX_VALUE,
                result::add);

        assertThat(result).isEmpty();
    }

    @Test
    void nullKeyedProbeRow_leftJoin_keptNullPadded() {
        final StateFetcher store =
                fakeStore(Map.of("1", ValString.create("Alice")));
        final List<Val[]> result = new ArrayList<>();
        final Val[] nullKeyRow = new Val[]{ValNull.INSTANCE, ValString.create("a")};

        JoinExecutor.broadcastLookupJoin(
                List.<Val[]>of(nullKeyRow).iterator(), 0, 2, store, "users", 0L, JoinType.LEFT, Long.MAX_VALUE,
                result::add);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).containsExactly(
                ValNull.INSTANCE, ValString.create("a"), ValNull.INSTANCE, ValNull.INSTANCE);
    }

    @Test
    void multipleProbeRows_mixedHitsAndMisses() {
        final StateFetcher store =
                fakeStore(Map.of("1", ValString.create("Alice"), "3", ValString.create("Carol")));
        final List<Val[]> result = new ArrayList<>();

        JoinExecutor.broadcastLookupJoin(
                List.of(probeRow(1, "a"), probeRow(2, "b"), probeRow(3, "c")).iterator(),
                0, 2, store, "users", 0L, JoinType.INNER, Long.MAX_VALUE, result::add);

        assertThat(result).hasSize(2);
        assertThat(result).anySatisfy(row -> assertThat(row).containsExactly(
                ValLong.create(1), ValString.create("a"), ValLong.create(1), ValString.create("Alice")));
        assertThat(result).anySatisfy(row -> assertThat(row).containsExactly(
                ValLong.create(3), ValString.create("c"), ValLong.create(3), ValString.create("Carol")));
    }

    @Test
    void maxOutputRowsExceeded_throwsJoinLimitExceededException() {
        final StateFetcher store =
                fakeStore(Map.of("1", ValString.create("Alice"), "2", ValString.create("Bob")));

        assertThatThrownBy(() -> JoinExecutor.broadcastLookupJoin(
                List.of(probeRow(1, "a"), probeRow(2, "b")).iterator(),
                0, 2, store, "users", 0L, JoinType.INNER, 1, row -> {
                }))
                .isInstanceOf(JoinLimitExceededException.class)
                .hasMessageContaining("join output row count");
    }

    @Test
    void negativeProbeWidth_throwsIllegalArgumentException() {
        final StateFetcher store = fakeStore(Map.of());

        assertThatThrownBy(() -> JoinExecutor.broadcastLookupJoin(
                List.<Val[]>of().iterator(), 0, -1, store, "users", 0L, JoinType.INNER, Long.MAX_VALUE, row -> {
                }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void probeKeyPositionOutOfRange_throwsIllegalArgumentException() {
        final StateFetcher store = fakeStore(Map.of());

        assertThatThrownBy(() -> JoinExecutor.broadcastLookupJoin(
                List.<Val[]>of().iterator(), 2, 2, store, "users", 0L, JoinType.INNER, Long.MAX_VALUE, row -> {
                }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void broadcastLookupJoin_negativeMaxOutputRows_throwsIllegalArgumentException() {
        final StateFetcher store = fakeStore(Map.of());

        assertThatThrownBy(() -> JoinExecutor.broadcastLookupJoin(
                List.<Val[]>of().iterator(), 0, 2, store, "users", 0L, JoinType.INNER, -1, row -> {
                }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------------------
    // streamingHashJoin + HeapBuildSideLookup (Task C1/C2 - see docs/join-scalability-implementation-plan.md):
    // the streaming hash join with the build side behind a BuildSideLookup and the probe side consumed as an
    // Iterator with results pushed to a Consumer. Build side = right, probe side = left.
    // ------------------------------------------------------------------------------------------------------

    /** Builds an on-heap lookup over {@code rightRows} keyed on position(s) {@code keyPositions}. */
    private static BuildSideLookup heapLookup(final List<Val[]> rightRows, final int[] keyPositions,
                                              final int width) {
        return HeapBuildSideLookup.of(new Side(rightRows, keyPositions, width));
    }

    /** Drains a key's matches via the streaming {@code forEachMatch} primitive (tests assert on a list). */
    private static List<Val[]> collect(final BuildSideLookup lookup, final List<String> key) {
        final List<Val[]> out = new ArrayList<>();
        lookup.forEachMatch(key, out::add);
        return out;
    }

    @Test
    void streamingHashJoin_innerJoin_onlyEmitsMatchingRows() {
        final BuildSideLookup build = heapLookup(List.of(rightRow(2, 200), rightRow(3, 300)), new int[]{0}, 2);
        final List<Val[]> result = new ArrayList<>();

        JoinExecutor.streamingHashJoin(
                List.of(leftRow(1, "a"), leftRow(2, "b")).iterator(), new int[]{0}, build, 2,
                JoinType.INNER, Long.MAX_VALUE, result::add);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).containsExactly(
                ValLong.create(2), ValString.create("b"), ValLong.create(2), ValLong.create(200));
    }

    @Test
    void streamingHashJoin_leftJoin_padsUnmatchedProbeRowsInline() {
        final BuildSideLookup build = heapLookup(List.<Val[]>of(rightRow(2, 200)), new int[]{0}, 2);
        final List<Val[]> result = new ArrayList<>();

        JoinExecutor.streamingHashJoin(
                List.of(leftRow(1, "a"), leftRow(2, "b")).iterator(), new int[]{0}, build, 2,
                JoinType.LEFT, Long.MAX_VALUE, result::add);

        assertThat(result).hasSize(2);
        assertThat(result).anySatisfy(row -> assertThat(row).containsExactly(
                ValLong.create(1), ValString.create("a"), ValNull.INSTANCE, ValNull.INSTANCE));
        assertThat(result).anySatisfy(row -> assertThat(row).containsExactly(
                ValLong.create(2), ValString.create("b"), ValLong.create(2), ValLong.create(200)));
    }

    @Test
    void streamingHashJoin_emptyBuildSide_innerDropsAll_leftPadsAll() {
        final BuildSideLookup emptyBuild = heapLookup(List.of(), new int[]{0}, 2);
        final List<Val[]> inner = new ArrayList<>();
        JoinExecutor.streamingHashJoin(
                List.of(leftRow(1, "a"), leftRow(2, "b")).iterator(), new int[]{0}, emptyBuild, 2,
                JoinType.INNER, Long.MAX_VALUE, inner::add);
        assertThat(inner).isEmpty();

        final BuildSideLookup emptyBuild2 = heapLookup(List.of(), new int[]{0}, 2);
        final List<Val[]> left = new ArrayList<>();
        JoinExecutor.streamingHashJoin(
                List.of(leftRow(1, "a"), leftRow(2, "b")).iterator(), new int[]{0}, emptyBuild2, 2,
                JoinType.LEFT, Long.MAX_VALUE, left::add);
        assertThat(left).hasSize(2);
        assertThat(left).allSatisfy(row -> assertThat(row).endsWith(ValNull.INSTANCE, ValNull.INSTANCE));
    }

    @Test
    void streamingHashJoin_compositeKey_matchesOnAllPositions() {
        final Val[] r1 = new Val[]{ValLong.create(1), ValString.create("x"), ValLong.create(10)};
        final Val[] r2 = new Val[]{ValLong.create(1), ValString.create("z"), ValLong.create(20)};
        final BuildSideLookup build = heapLookup(List.of(r1, r2), new int[]{0, 1}, 3);
        final Val[] l1 = new Val[]{ValLong.create(1), ValString.create("x"), ValString.create("a")};
        final Val[] l2 = new Val[]{ValLong.create(1), ValString.create("y"), ValString.create("b")};
        final List<Val[]> result = new ArrayList<>();

        JoinExecutor.streamingHashJoin(
                List.of(l1, l2).iterator(), new int[]{0, 1}, build, 3, JoinType.INNER, Long.MAX_VALUE, result::add);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).containsExactly(
                ValLong.create(1), ValString.create("x"), ValString.create("a"),
                ValLong.create(1), ValString.create("x"), ValLong.create(10));
    }

    @Test
    void streamingHashJoin_maxOutputRows_boundsMidFanOut() {
        // One probe row fanning out to three build matches must throw once the cap is hit, not after accumulating.
        final BuildSideLookup build = heapLookup(
                List.of(rightRow(1, 100), rightRow(1, 101), rightRow(1, 102)), new int[]{0}, 2);

        assertThatThrownBy(() -> JoinExecutor.streamingHashJoin(
                List.<Val[]>of(leftRow(1, "a")).iterator(), new int[]{0}, build, 2, JoinType.INNER, 2, row -> {
                }))
                .isInstanceOf(JoinLimitExceededException.class)
                .hasMessageContaining("join output row count");
    }

    @Test
    void streamingHashJoin_nullKeyedProbeRow_isMiss_butPaddedForLeft() {
        final BuildSideLookup build = heapLookup(List.<Val[]>of(rightRow(1, 100)), new int[]{0}, 2);
        final Val[] nullKeyLeft = new Val[]{ValNull.INSTANCE, ValString.create("a")};
        final List<Val[]> result = new ArrayList<>();

        JoinExecutor.streamingHashJoin(
                List.<Val[]>of(nullKeyLeft).iterator(), new int[]{0}, build, 2, JoinType.LEFT, Long.MAX_VALUE,
                result::add);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).containsExactly(
                ValNull.INSTANCE, ValString.create("a"), ValNull.INSTANCE, ValNull.INSTANCE);
    }

    @Test
    void streamingHashJoin_rejectsEmptyProbeKeyPositions() {
        final BuildSideLookup build = heapLookup(List.of(), new int[]{0}, 2);
        assertThatThrownBy(() -> JoinExecutor.streamingHashJoin(
                List.<Val[]>of().iterator(), new int[0], build, 2, JoinType.INNER, Long.MAX_VALUE, row -> {
                }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void streamingHashJoin_rejectsNegativeBuildWidth() {
        final BuildSideLookup build = heapLookup(List.of(), new int[]{0}, 2);
        assertThatThrownBy(() -> JoinExecutor.streamingHashJoin(
                List.<Val[]>of().iterator(), new int[]{0}, build, -1, JoinType.INNER, Long.MAX_VALUE, row -> {
                }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void streamingHashJoin_hotBuildKey_abortsAtOutputCap_withoutConsumingTheWholeGroup() {
        // OOM-safety guarantee: a single probe row matching a huge build-side key group must abort at the output
        // cap DURING the fan-out - it must not first stream the whole (potentially enormous) group. A counting
        // BuildSideLookup proves streamingProbe stopped calling forEachMatch's consumer at the cap, not after the
        // full group was handed over.
        final int hotKeyRows = 1_000;
        final int[] handedOver = {0};
        final BuildSideLookup countingBuild = new BuildSideLookup() {
            @Override
            public void put(final List<String> key, final Val[] row) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean forEachMatch(final List<String> key, final Consumer<Val[]> matchConsumer) {
                for (int i = 0; i < hotKeyRows; i++) {
                    handedOver[0]++;
                    matchConsumer.accept(rightRow(1, i)); // throws out of here once the cap is hit
                }
                return true;
            }

            @Override
            public long rowCount() {
                return hotKeyRows;
            }

            @Override
            public void close() {
            }
        };

        assertThatThrownBy(() -> JoinExecutor.streamingHashJoin(
                List.<Val[]>of(leftRow(1, "a")).iterator(), new int[]{0}, countingBuild, 2,
                JoinType.INNER, 3, row -> {
                }))
                .isInstanceOf(JoinLimitExceededException.class);
        // Stopped at the cap (a handful of rows), NOT after materialising all 1,000.
        assertThat(handedOver[0]).isLessThan(hotKeyRows);
    }

    @Test
    void heapBuildSideLookup_skipsNullKeyedRows_andCountsTheRest() {
        final Val[] nullKeyRight = new Val[]{ValNull.INSTANCE, ValLong.create(200)};
        final BuildSideLookup build = heapLookup(List.of(nullKeyRight, rightRow(1, 100), rightRow(1, 101)),
                new int[]{0}, 2);

        // Null-keyed row skipped: only the two id=1 rows are retained and counted.
        assertThat(build.rowCount()).isEqualTo(2);
        assertThat(collect(build, List.of("1"))).hasSize(2);
        assertThat(collect(build, List.of("999"))).isEmpty();
    }
}
