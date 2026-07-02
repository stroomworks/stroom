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
import stroom.entity.client.presenter.DocPresenter;
import stroom.floormap.client.event.FloorMapDataEvent;
import stroom.floormap.client.event.TimeChangeEvent;
import stroom.floormap.client.presenter.FloorMapMapPresenter.FloorMapMapView;
import stroom.floormap.shared.FloorMapDoc;
import stroom.floormap.shared.FloorMapObject;
import stroom.floormap.shared.FloorMapTransformationMatrix;
import stroom.query.api.Column;
import stroom.query.api.DestroyReason;
import stroom.query.api.GroupSelection;
import stroom.query.api.OffsetRange;
import stroom.query.api.Result;
import stroom.query.api.Row;
import stroom.query.api.TableResult;
import stroom.query.api.TimeRange;
import stroom.query.client.presenter.DateTimeSettingsFactory;
import stroom.query.client.presenter.QueryModel;
import stroom.query.client.presenter.ResultComponent;
import stroom.query.client.presenter.ResultStoreModel;
import stroom.query.shared.QueryTablePreferences;
import stroom.widget.datepicker.client.UTCDate;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;

import static stroom.floormap.client.FloorMapJsonKeys.*;

/**
 * Presenter for the Floor Map <b>Map</b> tab.
 *
 * <p>Runs two independent query models at the current timeline time:</p>
 * <ul>
 *   <li><b>Facts query</b> ({@link #queryModel}) — fetches static floor-plan
 *       objects (desks, rooms, people positions) and passes the parsed result
 *       to {@link FloorMapCanvasPresenter#setObjects}.</li>
 *   <li><b>Histogram query</b> ({@link #histogramQueryModel}) — runs the
 *       events query over the full timeline range, buckets timestamps into
 *       {@link #HISTOGRAM_BINS} bins, and feeds the counts to
 *       {@link FloorMapTimelinePresenter#setHistogramData}.</li>
 * </ul>
 *
 * <p>Coordinates with {@link FloorMapCanvasPresenter} for rendering and
 * {@link FloorMapTimelinePresenter} for playback, step, and scrub controls.</p>
 */
public class FloorMapMapPresenter
        extends DocPresenter<FloorMapMapView, FloorMapDoc> {

    public static final Object MAP = new Object();
    public static final Object TIMELINE = new Object();

    private final FloorMapCanvasPresenter floorMapCanvasPresenter;
    private final FloorMapTimelinePresenter floorMapTimelinePresenter;

    private final QueryModel queryModel;
    private final QueryModel histogramQueryModel;
    private final QueryModel factsHistogramQueryModel;

    private long histogramStart;
    private long histogramEnd;
    private static final int HISTOGRAM_BINS = 100;

    /** Per-bin counts from the events histogram query. */
    private int[] eventBins = new int[HISTOGRAM_BINS];
    /** Per-bin counts from the facts histogram query. */
    private int[] factBins = new int[HISTOGRAM_BINS];

    private long selectedTime;
    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;

    @Inject
    public FloorMapMapPresenter(final EventBus eventBus,
                                final FloorMapMapView view,
                                final RestFactory restFactory,
                                final DateTimeSettingsFactory dateTimeSettingsFactory,
                                final ResultStoreModel resultStoreModel,
                                final Provider<FloorMapCanvasPresenter> floorMapCanvasPresenterProvider,
                                final Provider<FloorMapTimelinePresenter> floorMapTimelinePresenterProvider) {
        super(eventBus, view);

        this.floorMapCanvasPresenter = floorMapCanvasPresenterProvider.get();
        this.floorMapTimelinePresenter = floorMapTimelinePresenterProvider.get();

        // Default initial time
        this.selectedTime = System.currentTimeMillis();

        setInSlot(MAP, floorMapCanvasPresenter);
        setInSlot(TIMELINE, floorMapTimelinePresenter);

        // Result component to parse and handle Facts query results
        final ResultComponent resultConsumer = new ResultComponent() {
            @Override
            public OffsetRange getRequestedRange() {
                return new OffsetRange(0, 1000); // Fetch up to 1000 items
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
            public void setData(final Result componentResult) {
                if (componentResult instanceof final TableResult tableResult) {
                    parseFacts(tableResult);
                }
            }

            @Override
            public void setQueryModel(final QueryModel queryModel) {}
        };

        this.queryModel = new QueryModel(
                eventBus,
                restFactory,
                dateTimeSettingsFactory,
                resultStoreModel,
                () -> getEntity() != null && getEntity().getFactsQueryTablePreferences() != null
                        ? getEntity().getFactsQueryTablePreferences()
                        : QueryTablePreferences.builder().build());
        this.queryModel.addResultComponent(QueryModel.TABLE_COMPONENT_ID, resultConsumer);

        // Separate QueryModel for the histogram — runs the events query over the full
        // timeline range to count events per time bucket.
        final ResultComponent histogramResultConsumer = new ResultComponent() {
            @Override
            public OffsetRange getRequestedRange() {
                // Request a large page so we get a meaningful sample for bucketing.
                return new OffsetRange(0, 10000);
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
            public void setData(final Result componentResult) {
                if (componentResult instanceof final TableResult tableResult) {
                    eventBins = bucketTimestamps(tableResult);
                    mergeAndSetHistogram();
                }
            }

            @Override
            public void setQueryModel(final QueryModel queryModel) {}
        };

        this.histogramQueryModel = new QueryModel(
                eventBus,
                restFactory,
                dateTimeSettingsFactory,
                resultStoreModel,
                // Use empty preferences — the histogram query selects its own
                // columns (e.g. EventTime) that differ from the events query tab.
                () -> QueryTablePreferences.builder().build());
        this.histogramQueryModel.addResultComponent(QueryModel.TABLE_COMPONENT_ID, histogramResultConsumer);

        // Third QueryModel: runs the facts query over the full timeline range to count
        // fact timestamps for the histogram (object positions, etc.).
        final ResultComponent factsHistogramResultConsumer = new ResultComponent() {
            @Override
            public OffsetRange getRequestedRange() {
                return new OffsetRange(0, 10000);
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
            public void setData(final Result componentResult) {
                if (componentResult instanceof final TableResult tableResult) {
                    factBins = bucketTimestamps(tableResult);
                    mergeAndSetHistogram();
                }
            }

            @Override
            public void setQueryModel(final QueryModel queryModel) {}
        };

        this.factsHistogramQueryModel = new QueryModel(
                eventBus,
                restFactory,
                dateTimeSettingsFactory,
                resultStoreModel,
                // Use empty preferences — the histogram query selects its own
                // columns that differ from the facts query tab.
                () -> QueryTablePreferences.builder().build());
        this.factsHistogramQueryModel.addResultComponent(
                QueryModel.TABLE_COMPONENT_ID, factsHistogramResultConsumer);
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(getEventBus().addHandler(TimeChangeEvent.getType(), e -> onTimeChange(e.getTime())));
        registerHandler(getEventBus().addHandler(FloorMapDataEvent.getType(), e ->
                floorMapCanvasPresenter.setEventObjects(e.getObjects())));

        // Re-run the histogram whenever the user changes the visible date range via the settings popup.
        floorMapTimelinePresenter.setTimeRangeChangeHandler(() ->
                runHistogramQuery(floorMapTimelinePresenter.getStartTime(),
                        floorMapTimelinePresenter.getEndTime()));

        // Keep the canvas informed of play/pause transitions so it can switch
        // between animate-on-move and teleport behaviour.
        floorMapTimelinePresenter.setPlayStateChangeHandler(
                floorMapCanvasPresenter::setPlaying);

        // Discard stale animation state whenever the timeline jumps non-continuously
        // (scrub, step-back/forward, loop-around, stop-at-end).
        floorMapTimelinePresenter.setClearAnimationStateHandler(
                floorMapCanvasPresenter::clearAnimationState);
    }

    /**
     * Initialises and resets both query models, then loads the timeline range
     * and fires the initial facts query.
     */
    @Override
    protected void onRead(final DocRef docRef, final FloorMapDoc document, final boolean readOnly) {
        // Initialise and reset both query models BEFORE starting any searches, so that the histogram query
        // started inside updateTimelineRange() is not immediately cancelled by the reset() call below.
        queryModel.init(docRef);
        queryModel.reset(DestroyReason.NO_LONGER_NEEDED);
        histogramQueryModel.init(docRef);
        histogramQueryModel.reset(DestroyReason.NO_LONGER_NEEDED);
        factsHistogramQueryModel.init(docRef);
        factsHistogramQueryModel.reset(DestroyReason.NO_LONGER_NEEDED);

        // Start timeline (and histogram query) only after models are ready.
        updateTimelineRange();
        onTimeChange(selectedTime);
    }

    /** No document-level state to write — returns the document unchanged. */
    @Override
    protected FloorMapDoc onWrite(final FloorMapDoc document) {
        return document;
    }

    /** Always returns {@code false} — the Map tab has no associated dirty state. */
    @Override
    public boolean hasAssociatedDirty() {
        return false;
    }

    /**
     * Returns the facts query to execute, falling back to a default template
     * derived from the configured temporal store if no custom query is set.
     *
     * @return the StroomQL query text, or {@code null} if no store is configured
     */
    private String getFactsQueryToUse() {
        String factsQuery = getEntity() != null ? getEntity().getFactsQuery() : null;
        if (factsQuery == null || factsQuery.trim().isEmpty()) {
            final DocRef storeRef = getEntity() != null ? getEntity().getTemporalStoreRef() : null;
            if (storeRef != null && storeRef.getName() != null && !storeRef.getName().isEmpty()) {
                factsQuery = "from \"" + storeRef.getName() + "\"\n"
                             + "select \n"
                             + "  Key, \n"
                             + "  EffectiveTime, \n"
                             + "  jq(Value, \".type\") as type, \n"
                             + "  jq(Value, \".name\") as name, \n"
                             + "  jq(Value, \".maps\") as maps, \n"
                             + "  jq(Value, \".coords\") as coords, \n"
                             + "  jq(Value, \".img\") as img, \n"
                             + "  jq(Value, \"\\\"tm-world-to-map\\\"\") as tm_world_to_map, \n"
                             + "  jq(Value, \"\\\"tm-map-to-screen\\\"\") as tm_map_to_screen";
            }
        }
        return factsQuery;
    }

    /**
     * Handles a timeline time change by re-running the facts query at the
     * specified time.
     *
     * @param time the new timeline position in milliseconds
     */
    private void onTimeChange(final long time) {
        this.selectedTime = time;

        final String factsQuery = getFactsQueryToUse();
        if (factsQuery != null && !factsQuery.trim().isEmpty()) {
            final TimeRange timeRange =
                    new TimeRange("CUSTOM", String.valueOf(selectedTime), String.valueOf(selectedTime));
            queryModel.startNewSearch(
                    QueryModel.TABLE_COMPONENT_ID,
                    "factsTable",
                    factsQuery,
                    null,
                    timeRange,
                    false,
                    false,
                    "Facts Query Playback",
                    null
            );

        }
    }

    /**
     * Parses a facts {@link TableResult} into floor-map objects, extracts the
     * background image and matrix, and updates the canvas.
     *
     * @param tableResult the result from the facts query
     */
    private void parseFacts(final TableResult tableResult) {
        int keyIdx = -1;
        int typeIdx = -1;
        int coordsIdx = -1;
        int imgIdx = -1;
        int worldToMapIdx = -1;
        int mapToScreenIdx = -1;

        final List<Column> columns = tableResult.getColumns();
        if (columns == null) {
            return;
        }

        for (int i = 0; i < columns.size(); i++) {
            final String colName = columns.get(i).getName();
            if (colName.equalsIgnoreCase("Key")) {
                keyIdx = i;
            } else if (colName.equalsIgnoreCase(TYPE)) {
                typeIdx = i;
            } else if (colName.equalsIgnoreCase(COORDS)) {
                coordsIdx = i;
            } else if (colName.equalsIgnoreCase(IMG)) {
                imgIdx = i;
            } else if (colName.equalsIgnoreCase("tm_world_to_map")
                      || colName.equalsIgnoreCase(
                            TM_WORLD_TO_MAP)) {
                worldToMapIdx = i;
            } else if (colName.equalsIgnoreCase("tm_map_to_screen")
                    || colName.equalsIgnoreCase(
                            TM_MAP_TO_SCREEN)) {
                mapToScreenIdx = i;
            }
        }

        String activeBgImage = null;
        FloorMapTransformationMatrix activeBgMatrix = FloorMapTransformationMatrix.identity();
        final List<FloorMapObject> plottedObjects = new ArrayList<>();

        if (tableResult.getRows() != null) {
            for (final Row row : tableResult.getRows()) {
                final List<String> values = row.getValues();
                final String key = keyIdx != -1 && values.size() > keyIdx ? values.get(keyIdx) : null;
                final String type = typeIdx != -1 && values.size() > typeIdx ? values.get(typeIdx) : "";
                final String img = imgIdx != -1 && values.size() > imgIdx ? values.get(imgIdx) : null;

                if ("background".equalsIgnoreCase(type)) {
                    activeBgImage = img;
                    if (mapToScreenIdx != -1 && values.size() > mapToScreenIdx) {
                        final String matrixStr = values.get(mapToScreenIdx);
                        activeBgMatrix = parseMatrix(matrixStr);
                    }
                } else {
                    double worldX = 0;
                    double worldY = 0;
                    if (coordsIdx != -1 && values.size() > coordsIdx) {
                        final String coordsStr = values.get(coordsIdx);
                        final double[] xy = parseCoords(coordsStr);
                        if (xy != null) {
                            worldX = xy[0];
                            worldY = xy[1];
                        }
                    }

                    FloorMapTransformationMatrix worldToMap = FloorMapTransformationMatrix.identity();
                    if (worldToMapIdx != -1 && values.size() > worldToMapIdx) {
                        final String worldToMapStr = values.get(worldToMapIdx);
                        worldToMap = parseMatrix(worldToMapStr);
                    }

                    // Apply coordinates transformation:
                    // mapX = a * worldX + c * worldY + e
                    // mapY = b * worldX + d * worldY + f
                    final double mapX = worldToMap.getA() * worldX
                            + worldToMap.getC() * worldY + worldToMap.getE();
                    final double mapY = worldToMap.getB() * worldX
                            + worldToMap.getD() * worldY + worldToMap.getF();

                    plottedObjects.add(new FloorMapObject(key, type, mapX, mapY));
                }
            }
        }

        floorMapCanvasPresenter.setBackgroundImage(
                activeBgImage != null && !activeBgImage.isEmpty() ? activeBgImage : null);
        floorMapCanvasPresenter.setMatrix(activeBgMatrix);
        floorMapCanvasPresenter.setObjects(plottedObjects);
    }

    /**
     * Parses a comma-separated string of 6 numbers into a transformation
     * matrix, stripping any JSON array brackets.
     *
     * @param str the string to parse; may be {@code null}
     * @return the parsed matrix, or {@link FloorMapTransformationMatrix#identity()}
     *         if parsing fails
     */
    private FloorMapTransformationMatrix parseMatrix(final String str) {
        if (str == null || str.trim().isEmpty()) {
            return FloorMapTransformationMatrix.identity();
        }
        try {
            final String clean = str.replace("[", "").replace("]", "").replace("\"", "");
            final String[] parts = clean.split(",");
            if (parts.length >= 6) {
                final double a = Double.parseDouble(parts[0].trim());
                final double b = Double.parseDouble(parts[1].trim());
                final double c = Double.parseDouble(parts[2].trim());
                final double d = Double.parseDouble(parts[3].trim());
                final double e = Double.parseDouble(parts[4].trim());
                final double f = Double.parseDouble(parts[5].trim());
                return new FloorMapTransformationMatrix(a, b, c, d, e, f);
            }
        } catch (final Exception e) {
            // Ignore
        }
        return FloorMapTransformationMatrix.identity();
    }

    /**
     * Parses a comma-separated string of 2 numbers into {@code [x, y]}
     * coordinates, stripping any JSON array brackets.
     *
     * @param str the string to parse; may be {@code null}
     * @return the coordinates as {@code [x, y]}, or {@code null} if parsing fails
     */
    private double[] parseCoords(final String str) {
        if (str == null || str.trim().isEmpty()) {
            return null;
        }
        try {
            final String clean = str.replace("[", "").replace("]", "").replace("\"", "");
            final String[] parts = clean.split(",");
            if (parts.length >= 2) {
                final double x = Double.parseDouble(parts[0].trim());
                final double y = Double.parseDouble(parts[1].trim());
                return new double[]{x, y};
            }
        } catch (final Exception e) {
            // Ignore
        }
        return null;
    }

    /**
     * Initialises the timeline range to ±1 day around the current time and
     * triggers a histogram query.
     */
    private void updateTimelineRange() {
        // By default, the timeline shows a range 24 hours each side of the current system time.
        final long start = selectedTime - ONE_DAY_MS;
        final long end = selectedTime + ONE_DAY_MS;

        this.histogramStart = start;
        this.histogramEnd = end;

        floorMapTimelinePresenter.setTimeRange(start, end);
        floorMapTimelinePresenter.setCurrentTime(selectedTime);

        runHistogramQuery(start, end);
    }

    /**
     * Runs a histogram query over the full [start, end] range and populates the
     * timeline bins.
     * <p>
     * Uses the events query if one is configured (via
     * {@link #buildEventsHistogramQuery()}), otherwise falls back to the facts
     * query.  Only <em>one</em> query is used to avoid double-counting when both
     * events and facts are sourced from the same data store.
     */
    private void runHistogramQuery(final long start, final long end) {
        // Keep these in sync with the query range so that bucketTimestamps buckets
        // entries against the same window that was actually queried.
        this.histogramStart = start;
        this.histogramEnd = end;

        // Reset both bin arrays so stale data from a previous range is not retained.
        this.eventBins = new int[HISTOGRAM_BINS];
        this.factBins = new int[HISTOGRAM_BINS];

        // IMPORTANT: We deliberately pass a null TimeRange to avoid the temporal
        // store's temporal-lookup semantics.  When a TimeRange is present, the
        // temporal store returns only ONE row per key (the latest entry at or
        // before the range's end time) — correct for point-in-time map display
        // but completely wrong for a histogram that needs ALL entries.
        // Passing null makes the DAO take the "standard path", returning every
        // historical entry.  Client-side filtering in bucketTimestamps() then
        // restricts entries to the visible [histogramStart, histogramEnd] window.

        // Prefer the events query for the histogram — it typically selects from
        // the same store as the facts query and already includes a timestamp column.
        // If no events query is configured, fall back to the facts query.
        final String eventsHistQuery = buildEventsHistogramQuery();
        if (eventsHistQuery != null && !eventsHistQuery.trim().isEmpty()) {
            histogramQueryModel.startNewSearch(
                    QueryModel.TABLE_COMPONENT_ID,
                    "histogramTable",
                    eventsHistQuery,
                    null,
                    null,  // No TimeRange — see comment above
                    false,
                    false,
                    "Events Histogram Query",
                    null
            );
        } else {
            // No events query configured — fall back to the facts query.
            final String factsHistQuery = getFactsQueryToUse();
            if (factsHistQuery != null && !factsHistQuery.trim().isEmpty()) {
                factsHistogramQueryModel.startNewSearch(
                        QueryModel.TABLE_COMPONENT_ID,
                        "factsHistogramTable",
                        factsHistQuery,
                        null,
                        null,  // No TimeRange — see comment above
                        false,
                        false,
                        "Facts Histogram Query",
                        null
                );
            }
        }
    }

    /**
     * Returns the query text to use for the events histogram.
     * <p>
     * If the user has configured an events query, it is returned as-is.
     * The query's own {@code SELECT} clause is expected to include a recognised
     * timestamp column (e.g. {@code EffectiveTime} or {@code EventTime}) that
     * {@link #findTimeColumnIndex} can detect — typically aliased as
     * {@code "Effective Time"} for temporal-store queries.
     * <p>
     * If no events query is configured, falls back to a minimal query against
     * the configured temporal store selecting {@code EffectiveTime}.
     *
     * @return the histogram query text, or {@code null} if no query can be built
     */
    private String buildEventsHistogramQuery() {
        final String eventsQuery = getEntity() != null ? getEntity().getEventsQuery() : null;
        if (eventsQuery != null && !eventsQuery.trim().isEmpty()) {
            // Use the events query directly — it should already contain a
            // timestamp column that findTimeColumnIndex can recognise.
            return eventsQuery;
        }

        // Fallback: derive a histogram query from the configured temporal store.
        final DocRef storeRef = getEntity() != null ? getEntity().getTemporalStoreRef() : null;
        if (storeRef != null && storeRef.getName() != null && !storeRef.getName().isEmpty()) {
            return "from \"" + storeRef.getName() + "\"\n"
                   + "select \n"
                   + "  Key, \n"
                   + "  EffectiveTime";
        }
        return null;
    }

    /**
     * Looks for a well-known timestamp column in the supplied table result.
     * Recognises {@code EffectiveTime} / {@code Effective Time} (used by the
     * SQL Temporal Store facts query) and {@code EventTime} / {@code Event Time}
     * (used by standard Stroom event-source queries).
     *
     * @return the 0-based column index, or {@code -1} if no known time column is found
     */
    private static int findTimeColumnIndex(final List<Column> columns) {
        for (int i = 0; i < columns.size(); i++) {
            final String name = columns.get(i).getName();
            if ("EffectiveTime".equalsIgnoreCase(name)
                    || "Effective Time".equalsIgnoreCase(name)
                    || "EventTime".equalsIgnoreCase(name)
                    || "Event Time".equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Parses a {@link TableResult}, finds the first recognised timestamp column
     * ({@code EffectiveTime}, {@code EventTime}, etc.), buckets the timestamps
     * into {@link #HISTOGRAM_BINS} bins across
     * [{@link #histogramStart}, {@link #histogramEnd}], and returns the per-bin
     * counts. Also updates the timeline data range for "Show All".
     */
    private int[] bucketTimestamps(final TableResult tableResult) {
        final int[] bins = new int[HISTOGRAM_BINS];

        if (tableResult == null || tableResult.getRows() == null || tableResult.getColumns() == null) {
            return bins;
        }

        final int timeColIdx = findTimeColumnIndex(tableResult.getColumns());

        if (timeColIdx == -1 || histogramEnd <= histogramStart) {
            return bins;
        }

        final long range = histogramEnd - histogramStart;
        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;
        for (final Row row : tableResult.getRows()) {
            final List<String> values = row.getValues();
            if (values == null || values.size() <= timeColIdx) {
                continue;
            }
            final String timeStr = values.get(timeColIdx);
            if (timeStr == null || timeStr.trim().isEmpty()) {
                continue;
            }
            try {
                // Parse ISO-8601 timestamp via UTCDate (e.g. "2026-04-01T09:06:46.000Z").
                final UTCDate date = UTCDate.create(timeStr);
                if (date == null) {
                    continue;
                }
                final long t = (long) date.getTime();
                // Track the overall data extent for "Show All".
                if (t < minTime) {
                    minTime = t;
                }
                if (t > maxTime) {
                    maxTime = t;
                }
                // Skip entries that fall outside the visible range — do not clamp them
                // to the edge bins, as that would make out-of-range data appear at the
                // start or end of the histogram.
                if (t < histogramStart || t > histogramEnd) {
                    continue;
                }
                final int bin = (int) Math.min(HISTOGRAM_BINS - 1,
                        (t - histogramStart) * HISTOGRAM_BINS / range);
                bins[bin]++;
            } catch (final Exception e) {
                // Skip unparseable timestamps.
            }
        }

        // Inform the timeline of the actual data extent so Show All can be computed.
        if (minTime <= maxTime) {
            floorMapTimelinePresenter.setDataRange(minTime, maxTime);
        }

        return bins;
    }

    /**
     * Merges the per-bin counts from the events histogram and the facts histogram,
     * then pushes the combined result to the timeline presenter.
     */
    private void mergeAndSetHistogram() {
        final int[] merged = new int[HISTOGRAM_BINS];
        for (int i = 0; i < HISTOGRAM_BINS; i++) {
            merged[i] = eventBins[i] + factBins[i];
        }
        floorMapTimelinePresenter.setHistogramData(merged);
    }

    public interface FloorMapMapView extends View {
    }
}
