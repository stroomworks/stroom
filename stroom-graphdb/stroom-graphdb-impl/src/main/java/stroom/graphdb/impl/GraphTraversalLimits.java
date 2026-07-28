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

package stroom.graphdb.impl;

import java.time.Duration;
import java.util.Objects;

/**
 * The ceilings a graph traversal fails at.
 *
 * <p>Every one of these exists to make a runaway query fail loudly rather than exhaust the node, so they are
 * deliberately fail-loud rather than truncating: a query that hits one gets an error naming the limit, never a
 * quietly partial result. What they could not previously do is move - each was a {@code private static final} in
 * the engine, so an administrator facing a legitimate but broad query had no recourse.</p>
 *
 * <p>Gathered into one record rather than passed as five parameters because the engine already had a four-deep
 * chain of test-only constructors, each existing solely to override one ceiling. Those constructors are preserved
 * so no existing test needs changing, but new ceilings go here rather than growing the chain further.</p>
 *
 * @param maxVarLengthHops       the widest {@code *n..m} hop range a query may request, rejected before any
 *                               traversal work begins. The grammar makes {@code maxHops} mandatory, so
 *                               {@code -[:T*]->} is a parse error, but places no ceiling on the value - without
 *                               this, {@code -[:T*1..100000]->} was accepted and attempted verbatim.
 * @param maxVarLengthPathStates the most BFS path states a variable-length expansion may explore, across every
 *                               depth of a single hop. This guards what the hop-range cap alone cannot: a modest
 *                               {@code maxHops} of 3 or 4 over a high-fan-out hub still explores exponentially
 *                               many paths, all held in memory at once. <b>The budget is per anchor, not per
 *                               query</b> - a query matching N anchors gets N independent budgets of this size.
 * @param maxTraversalDuration   how long a single traversal may run before it is abandoned. A traversal runs
 *                               synchronously on the calling thread by design, so this is also the backstop
 *                               against a pathological query occupying a request thread indefinitely with no way
 *                               for the caller to cancel it.
 * @param maxAccumulatedRows     the most rows a single query may accumulate in memory, independent of any
 *                               compiled {@code LIMIT} - the essential out-of-memory safety net. A query with
 *                               {@code ORDER BY}, {@code DISTINCT} or aggregation, or with no {@code LIMIT}, has
 *                               no other bound, so without this a broad {@code MATCH} could accumulate
 *                               unboundedly before the wall-clock deadline even fired. The default of a million
 *                               is a tunable rather than an architectural limit: high enough that a
 *                               reasonably-scoped interactive query should never reach it, low enough to leave
 *                               real heap headroom. For a query that trips it, a tighter pattern, a
 *                               {@code LIMIT} or a narrower {@code WHERE} is almost always the better fix than
 *                               raising this.
 * @param wholeGraphNodeCap      the most nodes an unanchored {@code MATCH (n) RETURN GRAPH} preview will draw
 *                               when the query gives no {@code LIMIT}; a query's own {@code LIMIT} overrides it.
 *                               A bare preview walks the store rather than seeking an index, so it must be
 *                               bounded somehow. <b>Alone among these, this one truncates rather than failing</b>,
 *                               because its purpose is to keep a browse of an unknown graph usable.
 */
public record GraphTraversalLimits(int maxVarLengthHops,
                                   long maxVarLengthPathStates,
                                   Duration maxTraversalDuration,
                                   long maxAccumulatedRows,
                                   int wholeGraphNodeCap) {

    public GraphTraversalLimits {
        Objects.requireNonNull(maxTraversalDuration, "maxTraversalDuration must not be null");
    }

    /**
     * The historical hard-coded ceilings, used where no configuration is available - notably the tests, which
     * exercise the ceilings themselves and so must not be at the mercy of a deployment's settings.
     *
     * <p><b>Postconditions:</b> returns the built-in defaults.
     * <b>Null status:</b> the return value is never null.
     *
     * @return the default limits.
     */
    public static GraphTraversalLimits defaults() {
        return new GraphTraversalLimits(
                50,
                200_000L,
                Duration.ofSeconds(30),
                1_000_000L,
                100);
    }

    /**
     * The limits an administrator has configured.
     *
     * <p><b>Preconditions:</b> {@code config} is not null.
     * <b>Postconditions:</b> returns {@code config}'s limits.
     * <b>Null status:</b> {@code config} is not nullable; the return value is never null.
     *
     * @param config the deployment's graph configuration.
     * @return the configured limits.
     */
    public static GraphTraversalLimits from(final GraphDbConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        return new GraphTraversalLimits(
                config.getMaxVarLengthHops(),
                config.getMaxVarLengthPathStates(),
                config.getMaxTraversalDuration().getDuration(),
                config.getMaxAccumulatedRows(),
                config.getWholeGraphNodeCap());
    }

    GraphTraversalLimits withMaxVarLengthPathStates(final long value) {
        return new GraphTraversalLimits(
                maxVarLengthHops, value, maxTraversalDuration, maxAccumulatedRows, wholeGraphNodeCap);
    }

    GraphTraversalLimits withMaxTraversalDuration(final Duration value) {
        return new GraphTraversalLimits(
                maxVarLengthHops, maxVarLengthPathStates, value, maxAccumulatedRows, wholeGraphNodeCap);
    }

    GraphTraversalLimits withMaxAccumulatedRows(final long value) {
        return new GraphTraversalLimits(
                maxVarLengthHops, maxVarLengthPathStates, maxTraversalDuration, value, wholeGraphNodeCap);
    }

    GraphTraversalLimits withWholeGraphNodeCap(final int value) {
        return new GraphTraversalLimits(
                maxVarLengthHops, maxVarLengthPathStates, maxTraversalDuration, maxAccumulatedRows, value);
    }
}
