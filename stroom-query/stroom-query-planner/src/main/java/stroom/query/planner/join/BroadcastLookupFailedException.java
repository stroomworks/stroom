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

import java.io.Serial;
import java.util.Objects;

/**
 * Thrown by {@link JoinExecutor#broadcastLookupProbe} - the enrichment-join fast path (see
 * {@code docs/join-scalability-implementation-plan.md}, decision D8, item B1) - when the configured
 * {@code StateFetcher} returns a {@code ValErr} for a probe row's key instead of a real value or
 * {@code ValNull}. A {@code ValErr} means the lookup itself failed - e.g. a permission deny from the doc
 * cache's {@code USE} check, or a key shape mismatched to the store's type (a {@code NumberFormatException}
 * against a ranged store) - which is a genuine error, not "no match". It must never be embedded as the
 * joined row's value or counted as a successful match (see {@code docs/query-graphdb-review-report.md},
 * findings F1/SEC-1). Throwing here lets the caller's usual join-failure handling (e.g.
 * {@code JoinSearchProvider.createResultStore}'s {@code ResultStore#addError(Throwable)}) surface it as a
 * failed search, exactly like {@link JoinLimitExceededException} does for a breached row cap.
 */
public final class BroadcastLookupFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private BroadcastLookupFailedException(final String message) {
        super(message);
    }

    /**
     * Builds the exception for a {@code ValErr} lookup result.
     *
     * <p><b>Preconditions:</b> {@code mapName} and {@code key} must not be null; {@code lookupError} must not
     * be null (its {@link Val#toString()} - {@code "ERR: <message>"} for a {@code ValErr} - is embedded
     * verbatim in the returned exception's message).<br>
     * <b>Postconditions:</b> the returned exception's {@link #getMessage()} is never null and names the
     * store, the key, and the underlying error, so the failure is actionable rather than a bare exception.</p>
     *
     * @param mapName     the Plan B/State store name the lookup was against; never null.
     * @param key         the probe row's key value (as looked up, i.e. already stringified); never null.
     * @param lookupError the {@code ValErr} the lookup returned; never null.
     * @return never null.
     */
    public static BroadcastLookupFailedException forLookupError(
            final String mapName, final String key, final Val lookupError) {
        Objects.requireNonNull(mapName, "mapName");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(lookupError, "lookupError");
        return new BroadcastLookupFailedException(
                "Enrichment lookup against Plan B/State store '" + mapName + "' for key '" + key
                + "' failed: " + lookupError + " - the join has been aborted rather than treating the "
                + "failed lookup as a matched row.");
    }
}
