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

import java.util.List;
import java.util.Objects;

/**
 * The design doc's cost signal shape exactly (Task
 * 3.2) - what {@link CostModel} emits for a single access path.
 *
 * @param rows        never negative; the estimated matching row count.
 * @param bytes       never negative; the estimated data volume.
 * @param durationMs  never negative; the estimated wall-clock time to scan/probe.
 * @param confidence  in {@code [0,1]} - {@code 1.0} when a port answered directly with no heuristic applied on
 *                    top, lower when a selectivity heuristic or a missing-throughput fallback was used, and
 *                    {@code 0.0} when no port answered at all (a "cost" that exists only so callers have
 *                    something to compare, not a real estimate).
 * @param notes       never null; human-readable provenance (e.g. which heuristic/fallback was applied) - meant
 *                    to be surfaced verbatim by Phase 4's EXPLAIN output.
 */
public record CostEstimate(long rows, long bytes, long durationMs, double confidence, List<String> notes) {

    public CostEstimate {
        if (rows < 0) {
            throw new IllegalArgumentException("rows must not be negative: " + rows);
        }
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must not be negative: " + bytes);
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative: " + durationMs);
        }
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            // NaN must be rejected explicitly: NaN < 0.0 and NaN > 1.0 are both false, so a bare range check
            // would let a NaN confidence through and later corrupt comparisons like chooseAlgorithm's
            // confidence == 0.0 (NaN equals nothing, including itself).
            throw new IllegalArgumentException("confidence must be in [0,1]: " + confidence);
        }
        Objects.requireNonNull(notes, "notes");
        notes = List.copyOf(notes);
    }
}
