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
 * {@code docs/join-scalability-implementation-plan.md}, decision D1. Resolves to the property path
 * {@code stroom.query.join}.
 *
 * <p>Today a join realises both sides fully in memory and builds an unbounded in-memory hash table and output
 * list - see the same plan doc, §0 and §2 (Lever C). Until that executor is replaced with a streaming/spilling
 * one (Phase 2 of the plan), these two row-count caps are the only defence against an out-of-memory failure on a
 * datasource with a very large row count (the plan's stated scale is ~10^11 rows per datasource): a breach aborts
 * the search with a clear error instead of exhausting heap.</p>
 *
 * <p>This class is immutable: every field is set once, either by {@link #JoinConfig()} (JSON-absent defaults) or
 * by the {@link JsonCreator} constructor (explicit YAML/JSON values, with a JSON {@code null} for either property
 * falling back to that same default - see the {@link #JoinConfig(Long, Long)} Javadoc).</p>
 */
@JsonPropertyOrder(alphabetic = true)
public class JoinConfig extends AbstractConfig implements IsStroomConfig {

    /**
     * Default for {@link #getMaxSideRows()} when no value is configured: one million rows.
     */
    static final long DEFAULT_MAX_SIDE_ROWS = 1_000_000L;

    /**
     * Default for {@link #getMaxOutputRows()} when no value is configured: one million rows.
     */
    static final long DEFAULT_MAX_OUTPUT_ROWS = 1_000_000L;

    private final long maxSideRows;
    private final long maxOutputRows;

    /**
     * Builds a config carrying both defaults ({@link #DEFAULT_MAX_SIDE_ROWS}, {@link #DEFAULT_MAX_OUTPUT_ROWS}).
     * Used when no {@code stroom.query.join} section is present in configuration at all.
     *
     * <p><b>Preconditions:</b> none.<br>
     * <b>Postconditions:</b> {@link #getMaxSideRows()} and {@link #getMaxOutputRows()} both return their
     * documented defaults.</p>
     */
    public JoinConfig() {
        maxSideRows = DEFAULT_MAX_SIDE_ROWS;
        maxOutputRows = DEFAULT_MAX_OUTPUT_ROWS;
    }

    /**
     * Builds a config from explicit configuration values, each independently falling back to its default when
     * absent from the source JSON/YAML (a JSON {@code null} deserialises to a Java {@code null} here, which this
     * constructor treats identically to "absent").
     *
     * <p><b>Preconditions:</b> none - both parameters may be null.<br>
     * <b>Postconditions:</b> {@link #getMaxSideRows()} returns {@code maxSideRows} if non-null, else
     * {@link #DEFAULT_MAX_SIDE_ROWS}; {@link #getMaxOutputRows()} returns {@code maxOutputRows} if non-null, else
     * {@link #DEFAULT_MAX_OUTPUT_ROWS}. Both returned values are always {@code &gt;= 0}; a negative configured
     * value is rejected (see below) rather than silently accepted, since a negative cap has no sensible meaning
     * and would otherwise make every join fail confusingly at the first row.</p>
     *
     * @param maxSideRows   nullable; if non-null, must be {@code >= 0}.
     * @param maxOutputRows nullable; if non-null, must be {@code >= 0}.
     * @throws IllegalArgumentException if a non-null argument is negative.
     */
    @JsonCreator
    public JoinConfig(
            @JsonProperty("maxSideRows") final Long maxSideRows,
            @JsonProperty("maxOutputRows") final Long maxOutputRows) {
        this.maxSideRows = requireNonNegativeOrDefault(maxSideRows, DEFAULT_MAX_SIDE_ROWS, "maxSideRows");
        this.maxOutputRows = requireNonNegativeOrDefault(maxOutputRows, DEFAULT_MAX_OUTPUT_ROWS, "maxOutputRows");
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
     * The maximum number of rows {@code JoinSearchProvider} will realise from one join side before aborting the
     * search - see {@code docs/join-scalability-implementation-plan.md}, decision D1.
     *
     * <p><b>Postconditions:</b> the return value is always {@code >= 0}; a value of {@code 0} disables joins
     * entirely (every side breaches immediately) rather than meaning "unbounded" - there is deliberately no
     * "unbounded" sentinel for this v1 guardrail.</p>
     */
    @JsonProperty("maxSideRows")
    @JsonPropertyDescription("The maximum number of rows a join is allowed to realise from a single side before " +
                             "the search is aborted with an error. Guards against exhausting heap while today's " +
                             "join executor still materialises each side fully in memory. Default: " +
                             DEFAULT_MAX_SIDE_ROWS + ".")
    public long getMaxSideRows() {
        return maxSideRows;
    }

    /**
     * The maximum number of joined rows {@code JoinSearchProvider} will produce before aborting the search - see
     * {@code docs/join-scalability-implementation-plan.md}, decision D1.
     *
     * <p><b>Postconditions:</b> the return value is always {@code >= 0}; a value of {@code 0} disables joins
     * entirely, as for {@link #getMaxSideRows()}.</p>
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
