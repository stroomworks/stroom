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
import stroom.query.planner.join.JoinExecutor.Side;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The on-heap {@link BuildSideLookup} - a plain {@link HashMap} of key to that key's rows, which is exactly the
 * structure the original {@link JoinExecutor#hashJoin} built inline over the right side. It is the fast default
 * for a build side that fits in memory; {@code stroom-query-common}'s spilling lookup falls back to it below its
 * threshold and swaps to a disk-backed store above it (see {@code docs/join-scalability-implementation-plan.md},
 * items C1/A6).
 *
 * <p>Pure JVM logic, no I/O - so {@link #close()} is a no-op. Not thread-safe: build then probe from one thread,
 * matching the {@link BuildSideLookup} two-phase contract.</p>
 */
public final class HeapBuildSideLookup implements BuildSideLookup {

    private final Map<List<String>, List<Val[]>> rowsByKey = new HashMap<>();
    private long rowCount;

    /**
     * Builds a lookup from an already-materialised {@link Side}, keyed on that side's equi-key positions. Rows
     * whose key is SQL-null ({@link JoinExecutor#keyOf} returns {@code null}) are skipped - they can never match,
     * so they are not probe targets (same rule as the original {@link JoinExecutor#hashJoin}).
     *
     * <p><b>Preconditions:</b> {@code side} must not be null.<br>
     * <b>Postconditions:</b> never null; {@link #rowCount()} equals the number of non-null-keyed rows in
     * {@code side}.</p>
     */
    public static HeapBuildSideLookup of(final Side side) {
        Objects.requireNonNull(side, "side");
        final HeapBuildSideLookup lookup = new HeapBuildSideLookup();
        for (final Val[] row : side.rows()) {
            final List<String> key = JoinExecutor.keyOf(row, side.keyPositions());
            if (key != null) {
                lookup.put(key, row);
            }
        }
        return lookup;
    }

    @Override
    public void put(final List<String> key, final Val[] row) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(row, "row");
        rowsByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        rowCount++;
    }

    @Override
    public boolean forEachMatch(final List<String> key, final Consumer<Val[]> matchConsumer) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(matchConsumer, "matchConsumer");
        final List<Val[]> matches = rowsByKey.get(key);
        if (matches == null || matches.isEmpty()) {
            return false;
        }
        // Already resident on the heap, so iterating adds no allocation - the streaming contract still holds:
        // the consumer sees one row at a time and can stop (via an exception) the moment an output cap is hit.
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
        // No resource to release - the map is plain heap, reclaimed by GC once this lookup is unreachable.
    }
}
