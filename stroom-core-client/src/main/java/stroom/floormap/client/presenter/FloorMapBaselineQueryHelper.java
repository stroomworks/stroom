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

package stroom.floormap.client.presenter;

import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.query.api.DestroyReason;
import stroom.query.api.GroupSelection;
import stroom.query.api.OffsetRange;
import stroom.query.api.Param;
import stroom.query.api.Result;
import stroom.query.api.TableResult;
import stroom.query.api.TimeRange;
import stroom.query.client.presenter.DateTimeSettingsFactory;
import stroom.query.client.presenter.QueryModel;
import stroom.query.client.presenter.ResultComponent;
import stroom.query.client.presenter.ResultStoreModel;
import stroom.query.shared.QueryTablePreferences;
import stroom.util.shared.ErrorMessage;
import stroom.util.shared.Severity;

import com.google.web.bindery.event.shared.EventBus;

import java.util.List;
import java.util.function.Consumer;

/**
 * Runs the Map tab's periodic <b>baseline</b> read: every entity active within the horizon, which
 * replaces what the tab knows rather than adding to it.
 *
 * <h3>Why this needs its own {@link QueryModel}</h3>
 * <p>{@code QueryModel}'s state is single-valued — one {@code currentSearch}, one
 * {@code currentQueryKey}, one searching flag — and {@code startNewSearch} destroys the previous
 * result store. A baseline sharing the playback model would be destroyed by the next 300 ms tick,
 * every time. Same reason {@code HistogramQueryHelper} owns one, and this follows its shape.</p>
 *
 * <h3>Why the result is delivered on the searching&rarr;idle edge, not from setData</h3>
 * <p>A baseline replaces state wholesale, so applying a <em>partial</em> one drops entities that
 * are still there. Two things make that reachable:</p>
 * <ul>
 *   <li>{@code StateSearchProvider} catches a scan failure, calls {@code addError}, and then
 *       <b>still</b> calls {@code signalComplete()} — so a broken or never-written Plan B store
 *       presents as a legitimately empty horizon, and a mid-scan failure presents as a small
 *       complete result.</li>
 *   <li>{@code QueryModel.update} calls {@code setData} <em>before</em> {@code setErrors}, so at
 *       {@code setData} time the error state is not yet known. {@code setSearching(false)} fires
 *       after both.</li>
 * </ul>
 * <p>So rows are held as they arrive and handed over only on the running&rarr;idle edge, together
 * with whether the search reported an error. This is the pattern {@link FloorMapQueryPresenter}
 * already uses and documents, including its guard against acting on the "not searching" that the
 * <em>start</em> of the next run also reports.</p>
 */
class FloorMapBaselineQueryHelper {

    /**
     * The row cap for a baseline.
     *
     * <p>Server-side {@code DataStoreSettings.maxResults} defaults far above this, so this is the
     * binding limit, and it <b>is</b> reached in practice — see {@link Outcome#truncated()}.
     * 20 000 rows over a six-hour horizon is 0.93 events per second sustained, which a hundred
     * entities emitting once a minute already exceeds. The remedy is {@code condense} on the Plan B
     * store, which collapses the repeated identical positions that make up most of that volume;
     * raising this number just moves the threshold.</p>
     */
    static final int MAX_ROWS = 20_000;

    /**
     * How long the server may spend on a baseline before responding.
     *
     * <p>{@code QuerySearchRequest} defaults to one second, and a whole-store Plan B scan routinely
     * exceeds that. Overrunning is not data loss — polling continues and the rows arrive — but it
     * costs a request per second per overrun and the timeout message is stripped before the client
     * sees it, so the lateness is silent. Thirty seconds is long enough that an ordinary baseline
     * answers in one round trip.</p>
     */
    private static final long TIMEOUT_MS = 30_000L;

    private final QueryModel queryModel;
    private final Consumer<Outcome> outcomeHandler;

    /**
     * Whether a baseline is in flight.
     *
     * <p><b>Deliberately not {@code queryModel.isSearching()}.</b> That flag is cleared only by
     * {@code stop}, {@code reset}, a null response and completion — <em>not</em> by the REST
     * failure path, which sets errors and stops polling with the flag still set. Delegating to it
     * would let one network blip suppress every future baseline, silently, until the document was
     * re-read.</p>
     */
    private boolean running;

    /** Rows from the current search, replaced on each poll. */
    private TableResult latestResult;

    /** Whether any result has arrived since {@link #run} — see {@link Outcome#failed()}. */
    private boolean resultSeen;

    /** Whether the current search has reported an error. Cleared when a search starts. */
    private boolean errored;

    /**
     * The upper bound the in-flight baseline was issued for.
     *
     * <p>Reported back with the outcome rather than the caller reading its own clock on arrival: a
     * baseline takes long enough that the timeline has usually moved on, and stamping the cursor
     * with a later time than was actually read would silently skip everything in between.</p>
     */
    private long pendingTo;

    private boolean searching;

    FloorMapBaselineQueryHelper(final EventBus eventBus,
                                final RestFactory restFactory,
                                final DateTimeSettingsFactory dateTimeSettingsFactory,
                                final ResultStoreModel resultStoreModel,
                                final Consumer<Outcome> outcomeHandler) {
        this.outcomeHandler = outcomeHandler;
        this.queryModel = new QueryModel(
                eventBus,
                restFactory,
                dateTimeSettingsFactory,
                resultStoreModel,
                () -> QueryTablePreferences.builder().build());
        this.queryModel.setTimeout(TIMEOUT_MS);

        this.queryModel.addResultComponent(QueryModel.TABLE_COMPONENT_ID, new ResultComponent() {
            @Override
            public OffsetRange getRequestedRange() {
                return new OffsetRange(0, MAX_ROWS);
            }

            @Override
            public GroupSelection getGroupSelection() {
                return null;
            }

            @Override
            public void reset() {}

            @Override
            public void startSearch() {}

            @Override
            public void endSearch() {}

            @Override
            public void setData(final Result result) {
                if (result instanceof final TableResult tableResult) {
                    latestResult = tableResult;
                    resultSeen = true;
                }
            }

            @Override
            public void setQueryModel(final QueryModel model) {}
        });

        // Errors arrive after setData and before the idle edge, so recording them here is enough
        // to have the answer by the time the outcome is delivered.
        //
        // Only ERROR and above count. Refusing a baseline keeps stale positions on the map until
        // the next one, so a WARNING — a value that would not format, say — must not be able to
        // freeze the map for as long as it keeps recurring. The list is rebuilt on every poll and
        // may be null.
        this.queryModel.addSearchErrorListener(errors -> {
            if (errors != null) {
                for (final ErrorMessage error : errors) {
                    if (error != null
                        && error.getSeverity() != null
                        && error.getSeverity().greaterThanOrEqual(Severity.ERROR)) {
                        errored = true;
                    }
                }
            }
        });

        this.queryModel.addSearchStateListener(isSearching -> {
            if (isSearching) {
                // Attribute errors to the search that is starting, not the one that finished.
                errored = false;
                resultSeen = false;
                latestResult = null;
                searching = true;
                return;
            }
            // Only the running-to-idle edge. The reset at the start of the next run reports "not
            // searching" too, and a deliberate reset() clears `running` first so its edge is not
            // mistaken for a completion.
            final boolean finished = searching && running;
            searching = false;
            if (finished) {
                running = false;
                // The null-response path fires this edge with no setErrors at all, so "idle having
                // never delivered a result" counts as a failure rather than an empty store.
                outcomeHandler.accept(new Outcome(latestResult, errored || !resultSeen, pendingTo));
            }
        });
    }

    /**
     * Whether a baseline is in flight.
     *
     * <p>For deciding whether abandoning it is <em>wanted</em>, not whether it is safe —
     * {@link #run} handles the safety itself. A routine baseline asks the same question the one in
     * flight is already answering, so replacing it would be a self-destroying loop; one following a
     * user's jump asks about a position the in-flight read has already left, so it should
     * replace it.</p>
     */
    boolean isRunning() {
        return running;
    }

    void init(final DocRef docRef) {
        queryModel.init(docRef);
    }

    /**
     * Abandons any baseline in flight and destroys its result store.
     *
     * <p>Clears the in-flight state <b>before</b> resetting the model, so the
     * searching&rarr;false the reset emits is not read as a completed baseline carrying whatever
     * rows had arrived.</p>
     */
    void reset() {
        clearInFlight();
        queryModel.reset(DestroyReason.NO_LONGER_NEEDED);
    }

    /**
     * Forgets the in-flight search without touching the model.
     *
     * <p>Ordering matters more than the assignments do. {@code startNewSearch} destroys the
     * previous search, which emits searching&rarr;false — and if {@link #running} and
     * {@link #searching} were still set, that destruction would present as the completion edge and
     * deliver the abandoned search's rows stamped with the new cursor.</p>
     */
    private void clearInFlight() {
        running = false;
        searching = false;
        latestResult = null;
        resultSeen = false;
        errored = false;
    }

    /**
     * Starts a baseline over {@code [from, to]}.
     *
     * @param query  the resolved query text; a blank one is a no-op
     * @param params the store references, matching the substitutions already made in {@code query}
     * @param from   inclusive lower bound of the horizon
     * @param to     inclusive upper bound — 1 ms is added here because the generated term is
     *               {@code LESS_THAN}
     */
    void run(final String query, final List<Param> params, final long from, final long to) {
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        // Abandon anything in flight here rather than obliging the caller to do it first. See
        // clearInFlight() for what goes wrong otherwise.
        clearInFlight();
        running = true;
        pendingTo = to;
        queryModel.startNewSearch(
                QueryModel.TABLE_COMPONENT_ID,
                "eventsBaselineTable",
                query,
                params,
                new TimeRange("CUSTOM", String.valueOf(from), String.valueOf(to + 1)),
                false,  // incremental
                false,  // storeHistory
                "Events Query Baseline",
                null);  // additionalQueryExpression
    }

    /**
     * A finished baseline: the rows, and whether to trust them.
     */
    static final class Outcome {

        private final TableResult result;
        private final boolean failed;
        private final long to;

        private Outcome(final TableResult result, final boolean failed, final long to) {
            this.result = result;
            this.failed = failed;
            this.to = to;
        }

        /** The upper bound this baseline covered — the cursor the caller should stamp. */
        long to() {
            return to;
        }

        /**
         * Whether the search reported an error, or ended without ever delivering a result.
         *
         * <p>A failed baseline must not be applied: it cannot be distinguished from an empty
         * horizon by its rows, and applying it would drop every entity.</p>
         */
        boolean failed() {
            return failed;
        }

        /**
         * Whether the row cap bound.
         *
         * <p>{@code TableResult.getTotalResults()} is populated independently of the rows returned,
         * so a larger total means the result was cut. A truncated baseline is per-key complete in
         * key order except possibly at the boundary key, so it is worth upserting — but it is no
         * evidence that an absent entity has gone, so it must not prune.</p>
         */
        boolean truncated() {
            return result != null
                   && result.getTotalResults() != null
                   && result.getRows() != null
                   && result.getTotalResults() > result.getRows().size();
        }

        TableResult result() {
            return result;
        }
    }
}
