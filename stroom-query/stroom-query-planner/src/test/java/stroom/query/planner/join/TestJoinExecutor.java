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
import stroom.query.language.functions.ValLong;
import stroom.query.language.functions.ValNull;
import stroom.query.language.functions.ValString;
import stroom.query.planner.cost.JoinAlgorithm;
import stroom.query.planner.join.JoinExecutor.Side;
import stroom.query.planner.logical.JoinType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.List;

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
}
