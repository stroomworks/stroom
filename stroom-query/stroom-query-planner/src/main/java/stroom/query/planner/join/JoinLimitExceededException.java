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

import java.io.Serial;
import java.util.Objects;

/**
 * Thrown when a join breaches one of the {@code stroom.query.join} memory guardrails - see
 * {@code docs/join-scalability-implementation-plan.md}, decision D1 (Phase 0, item C4). Today's join executor
 * realises each side and the joined output fully in memory (see {@code JoinExecutor}'s class Javadoc and
 * {@code JoinSearchProvider}), so on a datasource with a very large row count these caps are the only defence
 * against exhausting heap; this exception is how a breach is reported so the search fails with a clear message
 * instead of an {@code OutOfMemoryError}.
 *
 * <p>Two breach sites throw this, both under {@code stroom-searchable-impl}'s {@code JoinSearchProvider} and
 * {@code stroom-query-planner}'s {@code JoinExecutor}: a single side realising more than
 * {@code JoinConfig.getMaxSideRows()} rows, and the joined output accumulating more than
 * {@code JoinConfig.getMaxOutputRows()} rows. Both call the same {@link #forRowCount(String, long, long)} factory
 * so the message shape is consistent regardless of which limit was breached.</p>
 */
public final class JoinLimitExceededException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private JoinLimitExceededException(final String message) {
        super(message);
    }

    /**
     * Builds the exception for a breach of a row-count guardrail.
     *
     * <p><b>Preconditions:</b> {@code limitDescription} must not be null; {@code limit} must be {@code >= 0}
     * (matching {@code JoinConfig}'s own non-negative constraint on both caps).<br>
     * <b>Postconditions:</b> the returned exception's {@link #getMessage()} is never null and names both the
     * breached limit and a concrete remedy (narrow the query, or use an enrichment join once available), so the
     * error is actionable rather than a bare number.</p>
     *
     * @param limitDescription a short, human-readable name for which cap was breached, e.g.
     *                          {@code "join side row count"} or {@code "join output row count"}.
     * @param limit             the configured cap that was reached, in rows.
     * @param observedRowCount  the row count observed at the moment of breach; must be {@code >= limit} (the
     *                          check is always "would this row take us over the limit", so the observed count
     *                          reported here is always at least the limit itself).
     * @return never null.
     */
    public static JoinLimitExceededException forRowCount(final String limitDescription, final long limit,
                                                          final long observedRowCount) {
        Objects.requireNonNull(limitDescription, "limitDescription");
        return new JoinLimitExceededException(
                "Join exceeded the configured " + limitDescription + " limit of " + limit + " rows (reached "
                + observedRowCount + " rows) - add a filter to reduce the offending side, narrow the time range, "
                + "or (once available) use an enrichment join against a keyed Plan B/State store instead of a "
                + "general join.");
    }
}
