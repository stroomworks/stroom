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
import stroom.widget.histogram.client.HistogramDataModel;
import stroom.widget.histogram.client.HistogramQueryHelper;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;

import static stroom.floormap.client.FloorMapJsonKeys.COORDS;
import static stroom.floormap.client.FloorMapJsonKeys.IMG;
import static stroom.floormap.client.FloorMapJsonKeys.TM_MAP_TO_SCREEN;
import static stroom.floormap.client.FloorMapJsonKeys.TM_WORLD_TO_MAP;
import static stroom.floormap.client.FloorMapJsonKeys.TYPE;

/**
 * Presenter for the Floor Map <b>Map</b> tab.
 *
 * <p>Runs independent query models at the current timeline time:</p>
 * <ul>
 *   <li><b>Facts query</b> ({@link #queryModel}) — fetches static floor-plan
 *       objects (desks, rooms, people positions) and passes the parsed result
 *       to {@link FloorMapCanvasPresenter#setObjects}.</li>
 *   <li><b>Histogram query</b> ({@link #histogramQueryHelper}) — runs the
 *       events query over the full timeline range, buckets timestamps via
 *       {@link HistogramDataModel}, and feeds the counts to
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
    private static final int HISTOGRAM_BINS = 100;

    private final FloorMapCanvasPresenter floorMapCanvasPresenter;
    private final FloorMapTimelinePresenter floorMapTimelinePresenter;

    private final QueryModel queryModel;
    private final HistogramQueryHelper histogramQueryHelper;
    private final HistogramQueryHelper factsHistogramQueryHelper;
    private final HistogramDataModel histogramDataModel;

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

        // Histogram data model — buckets timestamps and notifies the timeline.
        this.histogramDataModel = new HistogramDataModel(HISTOGRAM_BINS);
        this.histogramDataModel.setDataHandler(
                floorMapTimelinePresenter::setHistogramData);
        this.histogramDataModel.setDataRangeHandler(
                range -> floorMapTimelinePresenter.setDataRange(range[0], range[1]));

        // Histogram query helpers — one for events, one for facts.
        this.histogramQueryHelper = new HistogramQueryHelper(
                eventBus, restFactory, dateTimeSettingsFactory, resultStoreModel,
                histogramDataModel::process);
        this.factsHistogramQueryHelper = new HistogramQueryHelper(
                eventBus, restFactory, dateTimeSettingsFactory, resultStoreModel,
                histogramDataModel::process);
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
        histogramQueryHelper.init(docRef);
        histogramQueryHelper.reset();
        factsHistogramQueryHelper.init(docRef);
        factsHistogramQueryHelper.reset();

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

        floorMapTimelinePresenter.setTimeRange(start, end);
        floorMapTimelinePresenter.setCurrentTime(selectedTime);

        runHistogramQuery(start, end);
    }

    /**
     * Runs a histogram query over the full [start, end] range.
     * <p>
     * Uses the events query if one is configured, otherwise falls back to the
     * facts query.  Only <em>one</em> query is used to avoid double-counting
     * when both events and facts are sourced from the same data store.
     * <p>
     * The {@link HistogramQueryHelper} passes {@code null} for the TimeRange
     * to bypass temporal-lookup deduplication — see its Javadoc for details.
     */
    private void runHistogramQuery(final long start, final long end) {
        histogramDataModel.setRange(start, end);

        // Prefer the events query — it typically selects from the same store
        // as the facts query and already includes a timestamp column.
        final String eventsHistQuery = buildEventsHistogramQuery();
        if (eventsHistQuery != null && !eventsHistQuery.trim().isEmpty()) {
            histogramQueryHelper.run(eventsHistQuery);
        } else {
            // No events query configured — fall back to the facts query.
            final String factsHistQuery = getFactsQueryToUse();
            if (factsHistQuery != null && !factsHistQuery.trim().isEmpty()) {
                factsHistogramQueryHelper.run(factsHistQuery);
            }
        }
    }

    /**
     * Returns the query text to use for the events histogram.
     * <p>
     * If the user has configured an events query, it is returned as-is.
     * The query's own {@code SELECT} clause is expected to include a recognised
     * timestamp column (e.g. {@code EffectiveTime} or {@code EventTime}) that
     * {@link HistogramDataModel#findTimeColumnIndex} can detect.
     * <p>
     * If no events query is configured, falls back to a minimal query against
     * the configured temporal store selecting {@code EffectiveTime}.
     *
     * @return the histogram query text, or {@code null} if no query can be built
     */
    private String buildEventsHistogramQuery() {
        final String eventsQuery = getEntity() != null ? getEntity().getEventsQuery() : null;
        if (eventsQuery != null && !eventsQuery.trim().isEmpty()) {
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

    public interface FloorMapMapView extends View {
    }
}
