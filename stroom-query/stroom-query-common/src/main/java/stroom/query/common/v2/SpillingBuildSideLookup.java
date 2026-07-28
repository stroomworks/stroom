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

import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValString;
import stroom.query.language.functions.ValXml;
import stroom.query.planner.join.BuildSideLookup;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The adaptive {@link BuildSideLookup} used by {@code JoinSearchProvider}: fast on-heap while the build side is
 * small, spilling to a disk-backed {@link LmdbJoinBuildStore} once it grows past a threshold (see
 * items C1/A6). This is the classic hybrid hash join - a
 * join that fits in memory pays no disk cost, while one that would previously have exhausted heap (or been
 * aborted by the row guardrail) instead spills and completes.
 *
 * <p>The heap is bounded on <b>two</b> axes, spilling on whichever is hit first (see the OOM-reduction plan):
 * a <b>row count</b> ({@code maxHeapRows}) and an approximate <b>heap byte size</b> ({@code maxHeapBytes}). The
 * byte axis matters because a build side of relatively few but very <i>wide</i> rows (large strings/XML) can
 * exhaust heap while still under the row count - so a row-count-only trigger was width-blind. The byte figure is
 * a deliberately coarse, over-estimating heuristic ({@link #estimateHeapBytes}); it only decides <i>when</i> to
 * spill and is never a correctness input, so erring high (spill a little sooner) is always safe.</p>
 *
 * <p>Both figures are measured cheaply as rows are inserted (no up-front sizing pass - a cheap up-front byte size
 * does not exist: {@code MapDataStore.getByteSize} serialises the whole dataset, and no store exposes an O(1)
 * row count). Once a threshold is crossed, the next {@link #put} creates the spill store, drains the heap into it
 * once, releases the heap, and every later {@link #put}/{@link #forEachMatch} goes to disk.</p>
 *
 * <p><b>Not thread-safe</b>, and keys must already be non-null/non-empty (SQL-null-keyed rows are dropped by the
 * caller) - the {@link BuildSideLookup} contract. {@link #close} must always be called; it closes the spill store
 * (deleting its temporary directory) if one was created.</p>
 */
public final class SpillingBuildSideLookup implements BuildSideLookup {

    /** Coarse (over-estimating) constants for {@link #estimateHeapBytes} - a rough object header + the array of
     * value references, and a per-value object overhead. Intentionally generous so the byte trigger errs towards
     * spilling early rather than late. */
    private static final long ROW_OVERHEAD_BYTES = 32L;
    private static final long REFERENCE_BYTES = 8L;
    private static final long VALUE_OVERHEAD_BYTES = 24L;

    private final long maxHeapRows;
    private final long maxHeapBytes;
    private final Supplier<? extends BuildSideLookup> spillStoreFactory;

    /** Non-null while on-heap; nulled once spilled. */
    private @Nullable Map<List<String>, List<Val[]>> heap = new HashMap<>();
    /** Null until the heap spills - then a disk-backed {@link BuildSideLookup} (an {@link LmdbJoinBuildStore} in
     * production; the interface keeps this class decoupled from that concrete store). */
    private @Nullable BuildSideLookup spilled;
    private long rowCount;
    private long estimatedHeapBytes;
    private boolean closed;

    /**
     * @param maxHeapRows       the number of rows kept on the heap before spilling; must be {@code >= 0} (a value
     *                          of {@code 0} spills on the first row). See {@code JoinConfig.getMaxHeapBuildRows}.
     * @param maxHeapBytes      the approximate heap byte footprint kept before spilling; must be {@code >= 0} (a
     *                          value of {@code 0} spills on the first row). See
     *                          {@code JoinConfig.getMaxHeapBuildBytes}.
     * @param spillStoreFactory creates the disk-backed store on demand (only called if a threshold is crossed);
     *                          must not be null. Deferring creation this way means a small join never touches disk.
     */
    public SpillingBuildSideLookup(final long maxHeapRows,
                                   final long maxHeapBytes,
                                   final Supplier<? extends BuildSideLookup> spillStoreFactory) {
        if (maxHeapRows < 0) {
            throw new IllegalArgumentException("maxHeapRows must be >= 0, got " + maxHeapRows);
        }
        if (maxHeapBytes < 0) {
            throw new IllegalArgumentException("maxHeapBytes must be >= 0, got " + maxHeapBytes);
        }
        this.maxHeapRows = maxHeapRows;
        this.maxHeapBytes = maxHeapBytes;
        this.spillStoreFactory = Objects.requireNonNull(spillStoreFactory, "spillStoreFactory");
    }

    @Override
    public void put(final List<String> key, final Val[] row) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(row, "row");
        if (closed) {
            throw new IllegalStateException("put(...) called on a closed lookup");
        }
        // Spill just before the row that would take the heap over either threshold - so at most maxHeapRows rows
        // (and ~maxHeapBytes) are ever resident on the heap at once. The byte axis catches a few very wide rows
        // that the row count alone would miss.
        if (spilled == null && (rowCount >= maxHeapRows || estimatedHeapBytes >= maxHeapBytes)) {
            spill();
        }
        if (spilled != null) {
            spilled.put(key, row);
        } else {
            heap.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            estimatedHeapBytes += estimateHeapBytes(row);
        }
        rowCount++;
    }

    @Override
    public boolean forEachMatch(final List<String> key, final Consumer<Val[]> matchConsumer) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(matchConsumer, "matchConsumer");
        if (closed) {
            throw new IllegalStateException("forEachMatch(...) called on a closed lookup");
        }
        if (spilled != null) {
            return spilled.forEachMatch(key, matchConsumer);
        }
        final List<Val[]> matches = heap.get(key);
        if (matches == null || matches.isEmpty()) {
            return false;
        }
        for (final Val[] row : matches) {
            matchConsumer.accept(row);
        }
        return true;
    }

    @Override
    public long rowCount() {
        return rowCount;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        heap = null;
        if (spilled != null) {
            spilled.close();
        }
    }

    /**
     * Creates the spill store and drains the current heap into it. The store is adopted into {@link #spilled}
     * <i>before</i> draining, so a failure mid-drain still leaves it reachable for {@link #close} to clean up; the
     * heap is released only after a fully successful drain.
     */
    private void spill() {
        final BuildSideLookup store = spillStoreFactory.get();
        spilled = store;
        for (final Map.Entry<List<String>, List<Val[]>> entry : heap.entrySet()) {
            for (final Val[] row : entry.getValue()) {
                store.put(entry.getKey(), row);
            }
        }
        heap = null;
    }

    /**
     * A deliberately coarse, over-estimating approximation of one row's on-heap footprint, used only to decide
     * when to spill (never a correctness input) - so erring high is safe (spill a little sooner). The variable
     * cost is in the string-like values (the width risk); numeric/date/boolean/null values are covered by the
     * flat per-value overhead without touching them (no {@code toString} allocation on the common path).
     *
     * @param row must not be null.
     * @return an approximate byte count, always {@code >= 0}.
     */
    private static long estimateHeapBytes(final Val[] row) {
        long bytes = ROW_OVERHEAD_BYTES + (long) row.length * REFERENCE_BYTES;
        for (final Val value : row) {
            bytes += VALUE_OVERHEAD_BYTES;
            if (value instanceof final ValString s) {
                // ValString.toString returns its backing String (no copy); 2 bytes/char for a UTF-16 char array.
                bytes += 2L * s.toString().length();
            } else if (value instanceof final ValXml x) {
                bytes += x.getBytes() == null ? 0L : x.getBytes().length;
            }
            // else numeric/date/boolean/null - the flat VALUE_OVERHEAD_BYTES already accounts for it.
        }
        return bytes;
    }
}
