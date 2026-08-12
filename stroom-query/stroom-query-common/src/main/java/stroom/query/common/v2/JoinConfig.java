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

import stroom.util.shared.AbstractConfig;
import stroom.util.shared.IsStroomConfig;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Memory guardrails for {@code stroom.query.join} (a {@code join}-clause search, executed by
 * {@code JoinSearchProvider} in {@code stroom-searchable-impl}) - see
 * decision D1. Resolves to the property path
 * {@code stroom.query.join}.
 *
 * <p>A join now streams its probe (left) side and spills its build (right) side to disk past
 * {@link #getMaxHeapBuildRows} rows (the streaming/spilling executor - plan §12 items C1/C2), so heap is no
 * longer the hard limit it once was. The remaining caps are: {@link #getMaxSideRows}, an absolute ceiling on
 * how large the build side may grow (heap <i>and</i> spilled - a disk/time sanity guard); and
 * {@link #getMaxOutputRows}, a cap on joined output rows (guarding a runaway cross-match). A breach of either
 * aborts the search with a clear error rather than filling disk or exhausting heap. The streaming probe side is
 * deliberately not row-capped - it never accumulates, so it is memory-safe by construction.</p>
 *
 * <p>This class is immutable: every field is set once, either by {@link #JoinConfig} (JSON-absent defaults) or
 * by the {@link JsonCreator} constructor (explicit YAML/JSON values, with a JSON {@code null} for any property
 * falling back to that same default - see the {@link #JoinConfig(Long, Long, Long, Long)} Javadoc).</p>
 */
@JsonPropertyOrder(alphabetic = true)
public class JoinConfig extends AbstractConfig implements IsStroomConfig {

    /**
     * Default for {@link #getMaxSideRows} when no value is configured: ten million rows. Raised from the earlier
     * one million now that the build side spills to disk past {@link #getMaxHeapBuildRows} rather than being held
     * wholly in heap - so a larger ceiling is safe, and lets big joins actually benefit from spilling out of the
     * box rather than aborting just above the old heap limit.
     */
    static final long DEFAULT_MAX_SIDE_ROWS = 10_000_000L;

    /**
     * Default for {@link #getMaxOutputRows} when no value is configured: one million rows.
     */
    static final long DEFAULT_MAX_OUTPUT_ROWS = 1_000_000L;

    /**
     * Default for {@link #getMaxHeapBuildRows} when no value is configured: five hundred thousand rows - the
     * point at which the build side stops being held on the heap and spills to a disk-backed store.
     */
    static final long DEFAULT_MAX_HEAP_BUILD_ROWS = 500_000L;

    /**
     * Default for {@link #getMaxHeapBuildBytes} when no value is configured: 256 MiB - the approximate on-heap
     * footprint at which the build side spills, independently of the row count, so a few very wide rows can't
     * exhaust heap while still under {@link #DEFAULT_MAX_HEAP_BUILD_ROWS}.
     */
    static final long DEFAULT_MAX_HEAP_BUILD_BYTES = 256L * 1024L * 1024L;

    private final long maxSideRows;
    private final long maxOutputRows;
    private final long maxHeapBuildRows;
    private final long maxHeapBuildBytes;

    /**
     * Builds a config carrying both defaults ({@link #DEFAULT_MAX_SIDE_ROWS}, {@link #DEFAULT_MAX_OUTPUT_ROWS}).
     * Used when no {@code stroom.query.join} section is present in configuration at all.
     *
     * <p><b>Preconditions:</b> none.<br>
     * <b>Postconditions:</b> {@link #getMaxSideRows} and {@link #getMaxOutputRows} both return their
     * documented defaults.</p>
     */
    public JoinConfig() {
        maxSideRows = DEFAULT_MAX_SIDE_ROWS;
        maxOutputRows = DEFAULT_MAX_OUTPUT_ROWS;
        maxHeapBuildRows = DEFAULT_MAX_HEAP_BUILD_ROWS;
        maxHeapBuildBytes = DEFAULT_MAX_HEAP_BUILD_BYTES;
    }

    /**
     * Builds a config from explicit configuration values, each independently falling back to its default when
     * absent from the source JSON/YAML (a JSON {@code null} deserialises to a Java {@code null} here, which this
     * constructor treats identically to "absent").
     *
     * <p><b>Preconditions:</b> none - both parameters may be null.<br>
     * <b>Postconditions:</b> {@link #getMaxSideRows} returns {@code maxSideRows} if non-null, else
     * {@link #DEFAULT_MAX_SIDE_ROWS}; {@link #getMaxOutputRows} returns {@code maxOutputRows} if non-null, else
     * {@link #DEFAULT_MAX_OUTPUT_ROWS}. Both returned values are always {@code &gt;= 0}; a negative configured
     * value is rejected (see below) rather than silently accepted, since a negative cap has no sensible meaning
     * and would otherwise make every join fail confusingly at the first row.</p>
     *
     * @param maxSideRows       nullable; if non-null, must be {@code >= 0}.
     * @param maxOutputRows     nullable; if non-null, must be {@code >= 0}.
     * @param maxHeapBuildRows  nullable; if non-null, must be {@code >= 0}.
     * @param maxHeapBuildBytes nullable; if non-null, must be {@code >= 0}.
     * @throws IllegalArgumentException if a non-null argument is negative.
     */
    @JsonCreator
    public JoinConfig(
            @JsonProperty("maxSideRows") final Long maxSideRows,
            @JsonProperty("maxOutputRows") final Long maxOutputRows,
            @JsonProperty("maxHeapBuildRows") final Long maxHeapBuildRows,
            @JsonProperty("maxHeapBuildBytes") final Long maxHeapBuildBytes) {
        this.maxSideRows = requireNonNegativeOrDefault(maxSideRows, DEFAULT_MAX_SIDE_ROWS, "maxSideRows");
        this.maxOutputRows = requireNonNegativeOrDefault(maxOutputRows, DEFAULT_MAX_OUTPUT_ROWS, "maxOutputRows");
        this.maxHeapBuildRows =
                requireNonNegativeOrDefault(maxHeapBuildRows, DEFAULT_MAX_HEAP_BUILD_ROWS, "maxHeapBuildRows");
        this.maxHeapBuildBytes =
                requireNonNegativeOrDefault(maxHeapBuildBytes, DEFAULT_MAX_HEAP_BUILD_BYTES, "maxHeapBuildBytes");
    }

    private static long requireNonNegativeOrDefault(
            final Long configuredValue, final long defaultValue, final String propertyName) {
        if (configuredValue == null) {
            return defaultValue;
        }
        if (configuredValue < 0) {
            throw new IllegalArgumentException(
                    "stroom.query.join." + propertyName + " must be >= 0, got " + configuredValue);
        }
        return configuredValue;
    }

    /**
     * The absolute maximum number of rows a join's build (right) side may grow to - on the heap <i>and</i> spilled
     * to disk - before the search is aborted. Now that the build side spills past {@link #getMaxHeapBuildRows}
     * this is a disk/time sanity ceiling rather than the heap guard it originally was (see
     * §12 items C1/C2). The streaming probe (left) side is
     * not bounded by this - it never accumulates.
     *
     * <p><b>Postconditions:</b> the return value is always {@code >= 0}; a value of {@code 0} disables joins
     * entirely (the build side breaches immediately) rather than meaning "unbounded" - there is deliberately no
     * "unbounded" sentinel for this guardrail.</p>
     */
    @JsonProperty("maxSideRows")
    @JsonPropertyDescription("The absolute maximum number of rows a join's build (right) side may grow to - on " +
                             "heap and spilled to disk - before the search is aborted with an error. A disk/time " +
                             "sanity ceiling (the build side spills to disk past maxHeapBuildRows rather than " +
                             "exhausting heap). Default: " + DEFAULT_MAX_SIDE_ROWS + ".")
    public long getMaxSideRows() {
        return maxSideRows;
    }

    /**
     * The number of rows a join's build (right) side is kept on the heap before it spills to a disk-backed store -
     * §12 items C1/A6. Below this, the build side is a
     * fast in-heap hash map; above it, it spills so the join completes on bounded memory (slower, but it finishes
     * rather than failing). Should be {@code <= }{@link #getMaxSideRows} to have any effect.
     *
     * <p><b>Postconditions:</b> the return value is always {@code >= 0}; a value of {@code 0} spills on the very
     * first build row (never uses the heap at all).</p>
     */
    @JsonProperty("maxHeapBuildRows")
    @JsonPropertyDescription("The number of rows a join's build (right) side is held on the heap before spilling " +
                             "to a disk-backed store. Below this the build side is a fast in-heap hash map; above " +
                             "it the join spills so it completes on bounded memory instead of exhausting heap. " +
                             "Default: " + DEFAULT_MAX_HEAP_BUILD_ROWS + ".")
    public long getMaxHeapBuildRows() {
        return maxHeapBuildRows;
    }

    /**
     * The approximate on-heap byte footprint a join's build (right) side may reach before it spills to disk,
     * independently of {@link #getMaxHeapBuildRows} - the build side spills on whichever limit is hit first.
     * This guards against a build side of relatively few but very <i>wide</i> rows (large strings/XML) exhausting
     * heap while still under the row count. The figure is compared against a coarse, over-estimating heuristic, so
     * it need not be exact.
     *
     * <p><b>Note on output:</b> the join's <i>output</i> is streamed straight to the coprocessors and bounded by
     * {@link #getMaxOutputRows}; with the default {@code stroom.search.resultStore.offHeapResults=true} that
     * output store is itself off-heap. If an operator both disables off-heap results and raises
     * {@code maxOutputRows} substantially, a very large join output could pressure heap - that is a deliberate
     * global-config choice, outside these join guardrails.</p>
     *
     * <p><b>Postconditions:</b> the return value is always {@code >= 0}; a value of {@code 0} spills on the very
     * first build row (never uses the heap at all).</p>
     */
    @JsonProperty("maxHeapBuildBytes")
    @JsonPropertyDescription("The approximate on-heap byte footprint a join's build (right) side may reach before " +
                             "spilling to a disk-backed store, independently of maxHeapBuildRows (it spills on " +
                             "whichever is hit first). Guards against a few very wide rows exhausting heap while " +
                             "under the row count. Default: " + DEFAULT_MAX_HEAP_BUILD_BYTES + " bytes (256 MiB).")
    public long getMaxHeapBuildBytes() {
        return maxHeapBuildBytes;
    }

    /**
     * The maximum number of joined rows {@code JoinSearchProvider} will produce before aborting the search - see
     * decision D1.
     *
     * <p><b>Postconditions:</b> the return value is always {@code >= 0}; a value of {@code 0} disables joins
     * entirely, as for {@link #getMaxSideRows}.</p>
     */
    @JsonProperty("maxOutputRows")
    @JsonPropertyDescription("The maximum number of joined output rows a join is allowed to produce before the " +
                             "search is aborted with an error. Guards against an unexpectedly large cross-match " +
                             "(e.g. a near-cross-product on a poorly-selective join key) exhausting heap. " +
                             "Default: " + DEFAULT_MAX_OUTPUT_ROWS + ".")
    public long getMaxOutputRows() {
        return maxOutputRows;
    }
}
