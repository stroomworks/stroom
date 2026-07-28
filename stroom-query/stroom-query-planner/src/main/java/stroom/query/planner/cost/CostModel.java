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

import stroom.query.api.ExpressionTerm;
import stroom.query.planner.logical.Scan;
import stroom.query.planner.port.IndexCostSignal;
import stroom.query.planner.port.IndexShardStats;
import stroom.query.planner.port.MetaStats;
import stroom.query.planner.port.RowCountSignal;
import stroom.query.planner.port.StateStoreStats;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Costs a single {@link Scan}'s access path by trying each cost port in turn - {@link MetaStats}, then
 * {@link IndexShardStats}, then {@link StateStoreStats} - and using whichever answers (see
 * Task 3.2).
 *
 * <p><b>Not wired into anything yet.</b> Callers are expected to supply the query's time range and the
 * predicate terms relevant to selectivity directly, rather than this class extracting them from a
 * {@code LogicalPlan} itself - that extraction is a separate concern (arguably what Task 2.3's deferred
 * "time-range extraction" rewrite rule would eventually provide), kept out of this class so its own logic
 * (costing, given known inputs) stays independently testable.</p>
 *
 * <p><b>Node parallelism is not modelled</b> (the design doc's "divide by node parallelism"): there is no
 * existing port for cluster size, and inventing one with no live signal behind it would produce a number that
 * looks calibrated but isn't. All durations here assume a single node; a follow-up can introduce a
 * {@code ClusterSizeProvider} port when something other than a unit test consumes the duration figure.</p>
 */
public final class CostModel {

    /** Placeholder throughput used whenever a real one isn't available (no {@code IndexShardStats} signal, or
     *  for {@link FullScan}, which has no throughput port at all) - documented in {@link CostEstimate#notes}
     *  whenever it's used, never presented as if it were measured. */
    private static final double FALLBACK_ROWS_PER_MS = 1_000.0;

    /** Placeholder fixed cost for a {@link StateLookup} point lookup - no per-lookup latency signal exists yet
     *  (Task 3.1's note on Plan B/State stats). */
    private static final long STATE_LOOKUP_DURATION_MS = 1;

    private final MetaStats metaStats;
    private final IndexShardStats indexShardStats;
    private final StateStoreStats stateStoreStats;

    public CostModel(
            final MetaStats metaStats, final IndexShardStats indexShardStats,
            final StateStoreStats stateStoreStats) {
        this.metaStats = Objects.requireNonNull(metaStats, "metaStats");
        this.indexShardStats = Objects.requireNonNull(indexShardStats, "indexShardStats");
        this.stateStoreStats = Objects.requireNonNull(stateStoreStats, "stateStoreStats");
    }

    /**
     * @param scan             never null.
     * @param fromTimeMs        the query's time range lower bound, or null if unbounded below - passed straight
     *                         through to whichever port answers.
     * @param toTimeMs          the query's time range upper bound, or null if unbounded above.
     * @param selectivityTerms never null; the predicate terms (if any) that apply to {@code scan} and should
     *                         narrow its row-count estimate - see {@link Selectivity}. Not necessarily every
     *                         term of a query's {@code where}/{@code filter} - only those the caller has
     *                         already determined apply to this specific scan.
     * @return never null. Falls back to a zero-confidence {@link FullScan} estimate (never throws) when none of
     *         the three ports answers for {@code scan.dataSourceName}.
     */
    public CostedAccessPath estimate(
            final Scan scan, final @Nullable Long fromTimeMs, final @Nullable Long toTimeMs,
            final List<ExpressionTerm> selectivityTerms) {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(selectivityTerms, "selectivityTerms");

        final Optional<RowCountSignal> metaSignal = metaStats.estimate(scan.dataSourceName(), fromTimeMs, toTimeMs);
        if (metaSignal.isPresent()) {
            return costFullScan(metaSignal.get(), selectivityTerms);
        }

        final Optional<IndexCostSignal> indexSignal =
                indexShardStats.estimate(scan.dataSourceName(), fromTimeMs, toTimeMs);
        if (indexSignal.isPresent()) {
            return costIndexScan(indexSignal.get(), selectivityTerms);
        }

        final Optional<RowCountSignal> stateSignal = stateStoreStats.estimate(scan.dataSourceName());
        if (stateSignal.isPresent()) {
            return costStateLookup(stateSignal.get());
        }

        return new CostedAccessPath(
                new FullScan(),
                new CostEstimate(0, 0, 0, 0.0,
                        List.of("no cost signal available for '" + scan.dataSourceName() + "'")));
    }

    private CostedAccessPath costFullScan(final RowCountSignal signal, final List<ExpressionTerm> terms) {
        final double selectivity = combinedSelectivity(terms);
        final long rows = Math.round(signal.rows() * selectivity);
        final long durationMs = Math.round(rows / FALLBACK_ROWS_PER_MS);
        return new CostedAccessPath(
                new FullScan(),
                new CostEstimate(rows, 0, durationMs, terms.isEmpty() ? 1.0 : 0.5,
                        List.of("full scan; no per-row throughput signal for the stream store - using a "
                                + "placeholder " + FALLBACK_ROWS_PER_MS + " rows/ms")));
    }

    private CostedAccessPath costIndexScan(final IndexCostSignal signal, final List<ExpressionTerm> terms) {
        final double selectivity = combinedSelectivity(terms);
        final long rows = Math.round(signal.documentCount() * selectivity);
        final long bytes = Math.round(signal.byteSize() * selectivity);
        final OptionalDouble rowsPerMs = signal.documentsPerSecond().isPresent()
                ? OptionalDouble.of(signal.documentsPerSecond().getAsDouble() / 1000.0)
                : OptionalDouble.empty();
        final double effectiveRowsPerMs = rowsPerMs.orElse(FALLBACK_ROWS_PER_MS);
        final long durationMs = Math.round(rows / effectiveRowsPerMs);
        final double confidence = (terms.isEmpty() ? 1.0 : 0.5) * (rowsPerMs.isPresent() ? 1.0 : 0.5);
        final List<String> notes = rowsPerMs.isPresent()
                ? List.of("index scan; partition-pruned document count scaled by selectivity")
                : List.of("index scan; no shard had a usable commit-throughput figure - using a placeholder "
                          + FALLBACK_ROWS_PER_MS + " rows/ms");
        return new CostedAccessPath(new IndexScan(), new CostEstimate(rows, bytes, durationMs, confidence, notes));
    }

    private CostedAccessPath costStateLookup(final RowCountSignal signal) {
        return new CostedAccessPath(
                new StateLookup(),
                new CostEstimate(signal.rows(), 0, STATE_LOOKUP_DURATION_MS, 1.0,
                        List.of("state/Plan B point lookup")));
    }

    private static double combinedSelectivity(final List<ExpressionTerm> terms) {
        double selectivity = 1.0;
        for (final ExpressionTerm term : terms) {
            selectivity *= Selectivity.forCondition(term.getCondition());
        }
        return selectivity;
    }
}
