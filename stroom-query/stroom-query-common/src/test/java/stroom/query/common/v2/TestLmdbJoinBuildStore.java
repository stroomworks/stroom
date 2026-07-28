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

package stroom.query.common.v2;

import stroom.lmdb.LmdbConfig;
import stroom.lmdb2.LmdbEnvDir;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValLong;
import stroom.query.language.functions.ValString;
import stroom.query.planner.join.BuildSideLookup;
import stroom.util.io.ByteSize;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@link LmdbJoinBuildStore} - the disk-backed join build side (see
 * item C1): keyed multi-value retrieval, duplicate-row
 * preservation, prefix-free key encoding, the build-to-probe commit boundary, and temp-dir cleanup on close.
 */
class TestLmdbJoinBuildStore {

    @TempDir
    private Path tempDir;

    private final AtomicInteger storeCount = new AtomicInteger();

    private static Val[] row(final Object... cells) {
        final Val[] values = new Val[cells.length];
        for (int i = 0; i < cells.length; i++) {
            values[i] = cells[i] instanceof final Number n
                    ? ValLong.create(n.longValue())
                    : ValString.create(String.valueOf(cells[i]));
        }
        return values;
    }

    /** A small-map-size test config - only the store-size/reader knobs matter; the store is handed its dir
     * directly, so {@code localDir} is irrelevant. A modest map size avoids a large sparse file per test store. */
    private static LmdbConfig testConfig() {
        return ResultStoreLmdbConfig.builder().maxStoreSize(ByteSize.ofMebibytes(200)).build();
    }

    private LmdbEnvDir newEnvDir() {
        return new LmdbEnvDir(tempDir.resolve("join-build-" + storeCount.incrementAndGet()), true);
    }

    private LmdbJoinBuildStore newStore() {
        return new LmdbJoinBuildStore(newEnvDir(), testConfig());
    }

    /** Drains a key's matches via the streaming {@code forEachMatch} primitive - tests assert on lists for
     * convenience; production streams (never materialising a key-group). */
    private static List<Val[]> collect(final BuildSideLookup store, final List<String> key) {
        final List<Val[]> out = new ArrayList<>();
        store.forEachMatch(key, out::add);
        return out;
    }

    @Test
    void putThenGet_roundTripsASingleRow() {
        try (final LmdbJoinBuildStore store = newStore()) {
            store.put(List.of("1"), row(1L, "Alice"));

            final List<Val[]> got = collect(store, List.of("1"));
            assertThat(got).hasSize(1);
            assertThat(got.getFirst()).containsExactly(ValLong.create(1), ValString.create("Alice"));
        }
    }

    @Test
    void multipleRowsUnderOneKey_areAllReturned() {
        try (final LmdbJoinBuildStore store = newStore()) {
            store.put(List.of("1"), row(1L, "Alice"));
            store.put(List.of("1"), row(1L, "Annie"));

            assertThat(collect(store, List.of("1"))).hasSize(2);
        }
    }

    @Test
    void byteIdenticalDuplicateRows_areBothRetained() {
        // A join emits one output row per build row, so identical duplicates must NOT be collapsed.
        try (final LmdbJoinBuildStore store = newStore()) {
            store.put(List.of("1"), row(1L, "Alice"));
            store.put(List.of("1"), row(1L, "Alice"));

            assertThat(collect(store, List.of("1"))).hasSize(2);
            assertThat(store.rowCount()).isEqualTo(2);
        }
    }

    @Test
    void compositeKey_isMatchedAsAWholeTuple() {
        try (final LmdbJoinBuildStore store = newStore()) {
            store.put(List.of("1", "x"), row(10L));
            store.put(List.of("1", "y"), row(20L));

            assertThat(collect(store, List.of("1", "x"))).hasSize(1);
            assertThat(collect(store, List.of("1", "x")).getFirst()).containsExactly(ValLong.create(10));
            assertThat(collect(store, List.of("1", "y"))).hasSize(1);
        }
    }

    @Test
    void forEachMatch_streamsRowsOneAtATimeInInsertionOrder() {
        // The key OOM-safety property: matches are handed over one at a time (never collected into a list here),
        // in insertion order, and forEachMatch reports whether anything matched.
        try (final LmdbJoinBuildStore store = newStore()) {
            store.put(List.of("1"), row(1L, "first"));
            store.put(List.of("1"), row(1L, "second"));
            store.put(List.of("1"), row(1L, "third"));

            final List<String> seen = new ArrayList<>();
            final boolean matched = store.forEachMatch(List.of("1"), r -> seen.add(r[1].toString()));

            assertThat(matched).isTrue();
            assertThat(seen).containsExactly("first", "second", "third");
            assertThat(store.forEachMatch(List.of("nope"), r -> {
                throw new AssertionError("should not be called for a miss");
            })).isFalse();
        }
    }

    @Test
    void missingKey_returnsEmpty() {
        try (final LmdbJoinBuildStore store = newStore()) {
            store.put(List.of("1"), row(1L, "Alice"));

            assertThat(collect(store, List.of("999"))).isEmpty();
        }
    }

    @Test
    void keysWhereOneStringPrefixesAnother_doNotCrossMatch() {
        // Length-prefixed encoding must keep "1" and "11" distinct even though "1" is a text prefix of "11".
        try (final LmdbJoinBuildStore store = newStore()) {
            store.put(List.of("1"), row(1L));
            store.put(List.of("11"), row(11L));

            assertThat(collect(store, List.of("1"))).hasSize(1);
            assertThat(collect(store, List.of("1")).getFirst()).containsExactly(ValLong.create(1));
            assertThat(collect(store, List.of("11"))).hasSize(1);
            assertThat(collect(store, List.of("11")).getFirst()).containsExactly(ValLong.create(11));
        }
    }

    @Test
    void survivesTheBuildPhaseCommitBoundary() {
        // COMMIT_INTERVAL_ROWS is 50,000; put enough to cross it so the write txn commits and reopens mid-build,
        // then confirm both an early row and a post-commit row are still retrievable.
        try (final LmdbJoinBuildStore store = newStore()) {
            final int rows = 60_000;
            for (int i = 0; i < rows; i++) {
                store.put(List.of(Integer.toString(i)), row((long) i));
            }
            assertThat(store.rowCount()).isEqualTo(rows);
            assertThat(collect(store, List.of("0")).getFirst()).containsExactly(ValLong.create(0));
            assertThat(collect(store, List.of("55000")).getFirst()).containsExactly(ValLong.create(55000));
            assertThat(collect(store, List.of("59999")).getFirst()).containsExactly(ValLong.create(59999));
        }
    }

    @Test
    void put_afterFirstGet_isRejected() {
        try (final LmdbJoinBuildStore store = newStore()) {
            store.put(List.of("1"), row(1L));
            collect(store, List.of("1"));

            assertThatThrownBy(() -> store.put(List.of("2"), row(2L)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void overLongKey_failsWithAClearError() {
        try (final LmdbJoinBuildStore store = newStore()) {
            final String hugeKey = "x".repeat(1_000);
            assertThatThrownBy(() -> store.put(List.of(hugeKey), row(1L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("too large to spill");
        }
    }

    @Test
    void overLongProbeKey_returnsNoMatchCleanly() {
        // A probe-side equi-key whose encoded form is too large to ever have been stored (see
        // overLongKey_failsWithAClearError - put rejects a key this size) must be treated as a guaranteed miss,
        // not overflow the fixed-size keyBuffer with a BufferOverflowException.
        try (final LmdbJoinBuildStore store = newStore()) {
            store.put(List.of("1"), row(1L, "Alice"));

            final String hugeKey = "x".repeat(1_000);

            assertThat(collect(store, List.of(hugeKey))).isEmpty();
            assertThat(store.forEachMatch(List.of(hugeKey), r -> {
                throw new AssertionError("should not be called for a miss");
            })).isFalse();
        }
    }

    @Test
    void close_deletesTheTemporaryDirectory() {
        final LmdbEnvDir envDir = newEnvDir();
        final LmdbJoinBuildStore store = new LmdbJoinBuildStore(envDir, testConfig());
        store.put(List.of("1"), row(1L));
        assertThat(Files.exists(envDir.getEnvDir())).isTrue();

        store.close();

        assertThat(Files.exists(envDir.getEnvDir())).isFalse();
    }

    @Test
    void close_isIdempotent() {
        final LmdbJoinBuildStore store = newStore();
        store.put(List.of("1"), row(1L));
        store.close();
        store.close(); // must not throw
    }
}
