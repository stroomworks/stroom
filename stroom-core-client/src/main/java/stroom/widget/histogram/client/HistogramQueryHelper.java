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

package stroom.widget.histogram.client;

import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.query.api.DestroyReason;
import stroom.query.api.GroupSelection;
import stroom.query.api.OffsetRange;
import stroom.query.api.Result;
import stroom.query.api.TableResult;
import stroom.query.client.presenter.DateTimeSettingsFactory;
import stroom.query.client.presenter.QueryModel;
import stroom.query.client.presenter.ResultComponent;
import stroom.query.client.presenter.ResultStoreModel;
import stroom.query.shared.QueryTablePreferences;
import stroom.util.client.Console;
import stroom.util.shared.ErrorMessage;
import stroom.util.shared.Severity;

import com.google.web.bindery.event.shared.EventBus;

import java.util.function.Consumer;

/**
 * Encapsulates a {@link QueryModel} for histogram queries against temporal stores.
 *
 * <p>This helper creates a lightweight {@link ResultComponent} that receives {@link TableResult}
 * data and forwards it to the provided result handler.</p>
 *
 * <p><b>Failures are reported rather than drawn as an empty histogram.</b> Every way this query can
 * fail produces no bars, which is also what a store with nothing in it produces — so without the
 * error listener registered in the constructor the two are indistinguishable to the user, and the
 * more likely of the two (a query that will not resolve) looks like the harmless one. The result
 * handler cannot cover this, because a failure of that kind never reaches {@code setData}.</p>
 */
public class HistogramQueryHelper {

    private final QueryModel queryModel;

    /**
     * Whether a failure has already been reported for this helper.
     *
     * <p>The histogram re-runs on every timeline range change, so a persistent fault — a query that
     * cannot resolve, a store that is unreachable — would otherwise repeat its message for as long
     * as the user keeps moving the timeline.</p>
     */
    private boolean errorReported;

    public HistogramQueryHelper(final EventBus eventBus,
                                final RestFactory restFactory,
                                final DateTimeSettingsFactory dateTimeSettingsFactory,
                                final ResultStoreModel resultStoreModel,
                                final Consumer<TableResult> resultHandler) {
        this.queryModel = new QueryModel(
                eventBus,
                restFactory,
                dateTimeSettingsFactory,
                resultStoreModel,
                () -> QueryTablePreferences.builder().build());

        final ResultComponent resultComponent = new ResultComponent() {
            @Override
            public OffsetRange getRequestedRange() {
                return new OffsetRange(0, 10000);
            }

            @Override
            public GroupSelection getGroupSelection() {
                return null;
            }

            @Override
            public void reset() {
            }

            @Override
            public void startSearch() {
            }

            @Override
            public void endSearch() {
            }

            @Override
            public void setQueryModel(final QueryModel queryModel) {
            }

            @Override
            public void setData(final Result result) {
                if (result instanceof TableResult) {
                    final TableResult tableResult = (TableResult) result;
                    resultHandler.accept(tableResult);
                }
            }
        };

        queryModel.addResultComponent(QueryModel.TABLE_COMPONENT_ID, resultComponent);

        // Without this a failed histogram is indistinguishable from a store with nothing in it:
        // both draw no bars. The failures that actually happen here are quiet ones — a query whose
        // `from` clause did not resolve, or an unbound parameter, either of which produces zero
        // rows and an error message that nothing was listening for. Neither reaches setData, so the
        // result handler cannot report them.
        //
        // Only ERROR and above. A WARNING does not empty the bars, and the histogram has no state
        // to protect, so there is nothing to be gained by reporting one.
        queryModel.addSearchErrorListener(errors -> {
            if (errors == null || errorReported) {
                return;
            }
            for (final ErrorMessage error : errors) {
                if (error != null
                    && error.getSeverity() != null
                    && error.getSeverity().greaterThanOrEqual(Severity.ERROR)) {
                    errorReported = true;
                    Console.error("Histogram: the query failed, so the timeline shows no density"
                                  + " bars. This is not the same as there being no data."
                                  + " Cause: " + error.getMessage()
                                  + " Further failures are not reported.");
                    return;
                }
            }
        });
    }

    /**
     * Initialises the underlying query model with the given data source.
     *
     * @param docRef the data source to query against
     */
    public void init(final DocRef docRef) {
        queryModel.init(docRef);
    }

    /**
     * Resets the underlying query model, destroying the current result store.
     *
     * <p>Also re-arms failure reporting. This is called when a document is (re-)read, so a fault
     * that was reported for the previous document is reported again for the next one rather than
     * being suppressed for the life of the session.</p>
     */
    public void reset() {
        errorReported = false;
        queryModel.reset(DestroyReason.NO_LONGER_NEEDED);
    }

    /**
     * Starts a new histogram search with the given StroomQL query text.
     * <p>
     * <b>Important:</b> The {@code timeRange} parameter is deliberately set to
     * {@code null}.  When a TimeRange is present, the temporal store's DAO
     * activates temporal-lookup semantics — it returns only <em>one</em> row
     * per key (the latest entry at or before the range's end time).  This is
     * correct for point-in-time map display but completely wrong for a
     * histogram that needs <em>all</em> entries across a time window.  Passing
     * {@code null} makes the DAO use the "standard path", returning every
     * historical entry.  Client-side filtering in {@link HistogramDataModel}
     * then restricts entries to the visible range.
     *
     * @param query the StroomQL query text to execute
     */
    public void run(final String query) {
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        queryModel.startNewSearch(
                QueryModel.TABLE_COMPONENT_ID,
                "histogramTable",
                query,
                null,   // params
                null,   // timeRange — deliberately null, see Javadoc above
                false,  // incremental
                false,  // storeHistory
                "Histogram Query",  // queryInfo
                null);  // additionalQueryExpression
    }
}
