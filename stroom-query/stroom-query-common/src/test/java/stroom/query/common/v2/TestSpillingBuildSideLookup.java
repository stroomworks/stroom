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

import stroom.lmdb2.LmdbEnvDir;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValLong;
import stroom.query.language.functions.ValString;
import stroom.query.planner.join.BuildSideLookup;
import stroom.util.io.ByteSize;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link SpillingBuildSideLookup}'s hybrid behaviour (see
 * {@code docs/join-scalability-implementation-plan.md}, items C1/A6 and the OOM-reduction plan): it stays on the
 * heap below the row <b>and</b> byte thresholds, spills correctly (draining without loss) once either is crossed,
 * and serves probes identically either way.
 */
class TestSpillingBuildSideLookup {

    @TempDir
    private Path tempDir;

    private final AtomicInteger storeCount = new AtomicInteger();

    private static Val[] row(final long id, final String name) {
        return new Val[]{ValLong.create(id), ValString.create(name)};
    }

    /** Drains a lookup's matches for a key into a list via the streaming {@code forEachMatch} primitive - the
     * tests assert on lists for convenience; production never materialises a key-group this way. */
    private static List<Val[]> collect(final BuildSideLookup lookup, final List<String> key) {
        final List<Val[]> out = new ArrayList<>();
        lookup.forEachMatch(key, out::add);
        return out;
    }

    /** Supplies a real disk-backed spill store rooted under the test temp dir, one dedicated dir per store. */
    private LmdbJoinBuildStore newSpillStore() {
        final LmdbEnvDir envDir = new LmdbEnvDir(tempDir.resolve("spill-" + storeCount.incrementAndGet()), true);
        return new LmdbJoinBuildStore(envDir, ResultStoreLmdbConfig.builder()
                .maxStoreSize(ByteSize.ofMebibytes(200))
                .build());
    }

    @Test
    void belowThreshold_staysOnHeap_neverCreatingASpillStore() {
        final boolean[] spilled = {false};
        try (final SpillingBuildSideLookup lookup = new SpillingBuildSideLookup(10, Long.MAX_VALUE, () -> {
            spilled[0] = true;
            return newSpillStore();
        })) {
            lookup.put(List.of("1"), row(1, "a"));
            lookup.put(List.of("2"), row(2, "b"));

            assertThat(spilled[0]).isFalse();
            assertThat(collect(lookup, List.of("1"))).hasSize(1);
            assertThat(collect(lookup, List.of("2")).getFirst())
                    .containsExactly(ValLong.create(2), ValString.create("b"));
            assertThat(lookup.rowCount()).isEqualTo(2);
        }
    }

    @Test
    void crossingThreshold_spills_andRetainsEveryRow() {
        final boolean[] spilled = {false};
        try (final SpillingBuildSideLookup lookup = new SpillingBuildSideLookup(3, Long.MAX_VALUE, () -> {
            spilled[0] = true;
            return newSpillStore();
        })) {
            // Put 5 rows with a heap threshold of 3 - rows 1..3 land on the heap, then row 4 triggers the spill
            // (draining 1..3 to disk), and rows 4..5 go straight to disk.
            for (int i = 1; i <= 5; i++) {
                lookup.put(List.of(Integer.toString(i)), row(i, "n" + i));
            }

            assertThat(spilled[0]).isTrue();
            assertThat(lookup.rowCount()).isEqualTo(5);
            // A row from before the spill and one from after must both be retrievable from disk.
            assertThat(collect(lookup, List.of("1")).getFirst())
                    .containsExactly(ValLong.create(1), ValString.create("n1"));
            assertThat(collect(lookup, List.of("5")).getFirst())
                    .containsExactly(ValLong.create(5), ValString.create("n5"));
            assertThat(collect(lookup, List.of("999"))).isEmpty();
        }
    }

    @Test
    void multipleRowsPerKey_surviveTheSpill() {
        try (final SpillingBuildSideLookup lookup =
                new SpillingBuildSideLookup(1, Long.MAX_VALUE, this::newSpillStore)) {
            lookup.put(List.of("1"), row(1, "a"));
            lookup.put(List.of("1"), row(1, "b")); // triggers spill; both rows must end up on disk under key "1"
            lookup.put(List.of("1"), row(1, "c"));

            assertThat(collect(lookup, List.of("1"))).hasSize(3);
        }
    }

    @Test
    void crossingByteThreshold_spills_evenWhenWellUnderTheRowThreshold() {
        // Row threshold is huge (1e6) so it can never fire; the byte threshold is tiny, so a couple of wide rows
        // trip it. This is the width-blindness fix: few rows, but large, must still spill rather than OOM.
        final boolean[] spilled = {false};
        try (final SpillingBuildSideLookup lookup = new SpillingBuildSideLookup(1_000_000L, 64L, () -> {
            spilled[0] = true;
            return newSpillStore();
        })) {
            final String wide = "x".repeat(1_000); // ~2 KB heap estimate per row, far over the 64-byte cap
            lookup.put(List.of("1"), new Val[]{ValLong.create(1), ValString.create(wide)});
            lookup.put(List.of("2"), new Val[]{ValLong.create(2), ValString.create(wide)});

            assertThat(spilled[0]).isTrue();
            assertThat(lookup.rowCount()).isEqualTo(2);
            assertThat(collect(lookup, List.of("1"))).hasSize(1);
            assertThat(collect(lookup, List.of("2")).getFirst()[1]).isEqualTo(ValString.create(wide));
        }
    }

    @Test
    void narrowRows_underBothThresholds_stayOnHeap() {
        final boolean[] spilled = {false};
        try (final SpillingBuildSideLookup lookup = new SpillingBuildSideLookup(1_000_000L, 256L * 1024L * 1024L,
                () -> {
                    spilled[0] = true;
                    return newSpillStore();
                })) {
            for (int i = 0; i < 100; i++) {
                lookup.put(List.of(Integer.toString(i)), row(i, "n" + i));
            }
            assertThat(spilled[0]).isFalse();
            assertThat(collect(lookup, List.of("50")).getFirst()).containsExactly(ValLong.create(50),
                    ValString.create("n50"));
        }
    }

    @Test
    void zeroThreshold_spillsImmediately() {
        final boolean[] spilled = {false};
        try (final SpillingBuildSideLookup lookup = new SpillingBuildSideLookup(0, Long.MAX_VALUE, () -> {
            spilled[0] = true;
            return newSpillStore();
        })) {
            lookup.put(List.of("1"), row(1, "a"));

            assertThat(spilled[0]).isTrue();
            assertThat(collect(lookup, List.of("1"))).hasSize(1);
        }
    }
}
