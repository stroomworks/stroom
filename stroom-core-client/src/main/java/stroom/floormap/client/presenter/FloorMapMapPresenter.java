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
import stroom.floormap.client.event.MapObjectSelectedEvent;
import stroom.floormap.client.event.TimeChangeEvent;
import stroom.floormap.client.presenter.FloorMapMapPresenter.FloorMapMapView;
import stroom.floormap.shared.Fact;
import stroom.floormap.shared.FloorMapDoc;
import stroom.floormap.shared.FloorMapEntryParser;
import stroom.floormap.shared.FloorMapFieldMapping;
import stroom.floormap.shared.FloorMapFieldMapping.Role;
import stroom.floormap.shared.FloorMapJsonKeys;
import stroom.floormap.shared.FloorMapObject;
import stroom.floormap.shared.FloorMapTransformationMatrix;
import stroom.query.api.Column;
import stroom.query.api.DestroyReason;
import stroom.query.api.GroupSelection;
import stroom.query.api.OffsetRange;
import stroom.query.api.Param;
import stroom.query.api.Result;
import stroom.query.api.Row;
import stroom.query.api.TableResult;
import stroom.query.api.TimeRange;
import stroom.query.client.presenter.DateTimeSettingsFactory;
import stroom.query.client.presenter.QueryModel;
import stroom.query.client.presenter.ResultComponent;
import stroom.query.client.presenter.ResultStoreModel;
import stroom.query.shared.QueryTablePreferences;
import stroom.sqlstore.shared.SqlTemporalStoreResource;
import stroom.util.client.Console;
import stroom.util.shared.TemporalEntry;
import stroom.widget.histogram.client.HistogramDataModel;
import stroom.widget.histogram.client.HistogramQueryHelper;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * Presenter for the Map (visualisation) tab of a {@link FloorMapDoc}.
 *
 * <p>This presenter coordinates the floor-map canvas, timeline scrubber, and object
 * properties editor. Facts are loaded by running a StroomQL query via
 * {@link QueryModel}. Results are parsed by {@link #parseFacts(TableResult)}.</p>
 *
 * <h3>Layout slots</h3>
 * <ul>
 *     <li>{@link #MAP} – the {@link FloorMapCanvasPresenter} (canvas / visualisation)</li>
 *     <li>{@link #TIMELINE} – the {@link FloorMapTimelinePresenter} (timeline scrubber)</li>
 * </ul>
 *
 * <p>Two separate {@link QueryModel} instances are maintained: one for the facts query
 * playback and one for the histogram (events) query that populates the timeline density
 * bars.</p>
 */
public class FloorMapMapPresenter
        extends DocPresenter<FloorMapMapView, FloorMapDoc> {

    public static final Object MAP = new Object();
    public static final Object TIMELINE = new Object();
    private static final int HISTOGRAM_BINS = 100;
    private static final stroom.sqlstore.shared.SqlTemporalStoreResource SQL_TEMPORAL_STORE_RESOURCE =
            GWT.create(stroom.sqlstore.shared.SqlTemporalStoreResource.class);

    private final FloorMapCanvasPresenter floorMapCanvasPresenter;
    private final FloorMapTimelinePresenter floorMapTimelinePresenter;
    private final FloorMapObjectEditPresenter floorMapObjectEditPresenter;
    private final FloorMapFactListPresenter floorMapObjectListPresenter;
    private final RestFactory restFactory;

    private final QueryModel queryModel;
    private final HistogramQueryHelper histogramQueryHelper;
    private final HistogramQueryHelper factsHistogramQueryHelper;
    private final HistogramDataModel histogramDataModel;

    private long selectedTime;

    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;

    /**
     * Returns the document's value schema.
     *
     * @return the active {@link FloorMapFieldMapping} list; may be {@code null}
     *         for legacy documents
     */
    private List<stroom.floormap.shared.FloorMapFieldMapping> valueSchema() {
        return getEntity().getValueSchema();
    }

    /**
     * Resolves the JSON path for a given {@link FloorMapFieldMapping.Role} by looking
     * it up in the current {@link #valueSchema()}.
     *
     * @param role the schema role to look up
     * @return the JSON path string, or {@code null} if the role is not present in the schema
     */
    private String pathForRole(final Role role) {
        return FloorMapEntryParser.findPath(valueSchema(), role);
    }

    @Inject
    public FloorMapMapPresenter(final EventBus eventBus,
                                final FloorMapMapView view,
                                final RestFactory restFactory,
                                final DateTimeSettingsFactory dateTimeSettingsFactory,
                                final ResultStoreModel resultStoreModel,
                                final Provider<FloorMapCanvasPresenter> floorMapCanvasPresenterProvider,
                                final Provider<FloorMapTimelinePresenter> floorMapTimelinePresenterProvider,
                                final Provider<FloorMapObjectEditPresenter> floorMapObjectEditPresenterProvider,
                                final Provider<FloorMapFactListPresenter> floorMapObjectListPresenterProvider) {
        super(eventBus, view);

        this.restFactory = restFactory;
        this.floorMapCanvasPresenter = floorMapCanvasPresenterProvider.get();
        this.floorMapTimelinePresenter = floorMapTimelinePresenterProvider.get();
        this.floorMapObjectEditPresenter = floorMapObjectEditPresenterProvider.get();
        this.floorMapObjectListPresenter = floorMapObjectListPresenterProvider.get();

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
                () -> QueryTablePreferences.builder().build());
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
        // Only react to this tab's own timeline — the Editor tab has its own
        // timeline firing the same event type, and the tabs must not time-sync.
        registerHandler(getEventBus().addHandler(TimeChangeEvent.getType(), e -> {
            if (e.getSource() == floorMapTimelinePresenter) {
                onTimeChange(e.getTime());
            }
        }));
        registerHandler(getEventBus().addHandler(FloorMapDataEvent.getType(), e ->
                floorMapCanvasPresenter.setEventObjects(e.getObjects())));

        // Re-run the histogram whenever the user changes the visible date range via the settings popup.
        floorMapTimelinePresenter.setTimeRangeChangeHandler(() ->
                runHistogramQuery(floorMapTimelinePresenter.getStartTime(),
                        floorMapTimelinePresenter.getEndTime()));

        // Canvas events are fired on the shared event bus by every FloorMap
        // canvas instance (this tab, the Editor tab, and any other open
        // FloorMap document), so each handler must ignore events from
        // canvases other than its own. Without the source guard a drag on
        // the Editor tab's canvas would trigger applyMove() here, silently
        // persisting a new time record at this tab's selectedTime.
        registerHandler(getEventBus().addHandler(MapObjectSelectedEvent.getType(), e -> {
            if (e.getSource() == floorMapCanvasPresenter) {
                floorMapCanvasPresenter.setSelectedObjectId(null);
                getView().setPropertiesVisible(false);
            }
        }));


        // Drag-editing is performed on the Editor tab; the Map tab is view-focused,
        // so no drag handler is installed here.

        this.floorMapObjectListPresenter.setSelectionConsumer(factObj -> {
            if (factObj != null) {
                floorMapObjectEditPresenter.setObject(factObj.getKey());
                final String canvasId = FloorMapJsonKeys.BACKGROUND.equals(factObj.getKey())
                        ? FloorMapJsonKeys.BACKGROUND
                        : factObj.getKey();
                floorMapCanvasPresenter.setSelectedObjectId(canvasId);
                floorMapCanvasPresenter.setIsDraggingEnabled(true);
                getView().setPropertiesVisible(true);
            } else {
                floorMapCanvasPresenter.setSelectedObjectId(null);
                floorMapCanvasPresenter.setIsDraggingEnabled(false);
            }
        });

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
     * {@inheritDoc}
     *
     * <p>Initialises and resets both the facts and histogram {@link QueryModel} instances,
     * configures the object edit presenter with the document's store reference, then starts
     * the timeline and triggers an initial time-change to load facts.</p>
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

        if (document.getFactsStoreRef() != null) {
            floorMapObjectEditPresenter.setMapName(document.getFactsStoreRef().getName());
        }
        floorMapObjectEditPresenter.setFloorMapDoc(document);

        // Start timeline (and histogram query) only after models are ready.
        updateTimelineRange();
        onTimeChange(selectedTime);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the document unchanged. All map edits (object moves, additions) are
     * persisted directly to the temporal store via REST calls rather than through the
     * document save lifecycle.</p>
     */
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
        final java.util.List<stroom.floormap.shared.FloorMapFieldMapping> schema = valueSchema();
        if (schema == null || schema.isEmpty()) {
            return null;
        }
        return FloorMapQueryBuilder.buildFactsQuery(schema, getEntity().getValueFormat());
    }

    /**
     * Responds to a timeline time-change event. Runs the facts StroomQL
     * query via {@link QueryModel} at the selected time.
     *
     * @param time the new selected time in milliseconds since epoch
     */
    private void onTimeChange(final long time) {
        this.selectedTime = time;

        final String factsQuery = getFactsQueryToUse();
        if (factsQuery != null && !factsQuery.trim().isEmpty()) {
            // Resolve param('FactStore') / param('EventStore') references
            // in the query text so the from-clause resolves correctly.
            final Map<String, String> vars =
                    FloorMapQueryPresenter.buildQueryVariables(getEntity());
            String resolvedQuery = factsQuery;
            List<Param> params = null;
            if (!vars.isEmpty()) {
                params = new ArrayList<>();
                for (final Map.Entry<String, String> entry : vars.entrySet()) {
                    params.add(new Param(entry.getKey(), entry.getValue()));
                    resolvedQuery = resolvedQuery.replace(
                            "param('" + entry.getKey() + "')",
                            "\"" + entry.getValue() + "\"");
                }
            }

            final TimeRange timeRange =
                    new TimeRange("CUSTOM", String.valueOf(selectedTime), String.valueOf(selectedTime));
            queryModel.startNewSearch(
                    QueryModel.TABLE_COMPONENT_ID,
                    "factsTable",
                    resolvedQuery,
                    params,
                    timeRange,
                    false,
                    false,
                    "Facts Query Playback",
                    null
            );
        }
    }

    /**
     * Parses a {@link TableResult} from the facts StroomQL query into canvas-renderable
     * objects. Maps column names to schema roles to extract key, type, coordinates,
     * image, and transformation matrices.
     *
     * <p>Background entries set the canvas background image and map-to-screen matrix.
     * Other entries are transformed from world coordinates to map coordinates using
     * their world-to-map matrix and plotted as {@link FloorMapObject} instances.</p>
     *
     * @param tableResult the query result table to parse
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

        final stroom.floormap.shared.ValueFormat vf = getEntity().getValueFormat();
        for (int i = 0; i < columns.size(); i++) {
            final String colName = columns.get(i).getName();
            if (colName.equalsIgnoreCase("Key")) {
                keyIdx = i;
            } else if (colName.equalsIgnoreCase(FloorMapQueryBuilder.buildColumnAlias(
                    pathForRole(Role.TYPE), vf))) {
                typeIdx = i;
            } else if (colName.equalsIgnoreCase(FloorMapQueryBuilder.buildColumnAlias(
                    pathForRole(Role.POSITION), vf))) {
                coordsIdx = i;
            } else if (colName.equalsIgnoreCase(FloorMapQueryBuilder.buildColumnAlias(
                    pathForRole(Role.IMAGE), vf))) {
                imgIdx = i;
            } else if (colName.equalsIgnoreCase(FloorMapQueryBuilder.buildColumnAlias(
                    pathForRole(Role.WORLD_TO_MAP), vf))) {
                worldToMapIdx = i;
            } else if (colName.equalsIgnoreCase(FloorMapQueryBuilder.buildColumnAlias(
                    pathForRole(Role.MAP_TO_SCREEN), vf))) {
                mapToScreenIdx = i;
            }
        }

        String activeBgImage = null;
        FloorMapTransformationMatrix activeBgMatrix = FloorMapTransformationMatrix.identity();
        final List<Fact> facts = new ArrayList<>();

        if (tableResult.getRows() != null) {
            for (final Row row : tableResult.getRows()) {
                final List<String> values = row.getValues();
                final String key = keyIdx != -1 && values.size() > keyIdx ? values.get(keyIdx) : null;
                final String type = typeIdx != -1 && values.size() > typeIdx ? values.get(typeIdx) : "";
                final String img = imgIdx != -1 && values.size() > imgIdx ? values.get(imgIdx) : null;

                if (FloorMapJsonKeys.BACKGROUND.equalsIgnoreCase(type)) {
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

                    facts.add(new Fact(key, type, img, worldToMap, new double[]{worldX, worldY}));
                }
            }
        }

        // The canvas renders the fact list (background painted behind the objects).
        if (activeBgImage != null && !activeBgImage.isEmpty()) {
            facts.add(0, new Fact(FloorMapJsonKeys.BACKGROUND, FloorMapJsonKeys.BACKGROUND,
                    activeBgImage, activeBgMatrix, null));
        }
        floorMapCanvasPresenter.setFacts(facts);
    }

    /**
     * Parses a comma-separated string representation of a 2D affine transformation
     * matrix {@code [a, b, c, d, e, f]} into a {@link FloorMapTransformationMatrix}.
     * Handles optional square brackets and quotes in the input.
     *
     * @param str the matrix string to parse, e.g. {@code "[1,0,0,1,100,200]"}
     * @return the parsed matrix, or {@link FloorMapTransformationMatrix#identity()}
     *         if the string is {@code null}, empty, or unparseable
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
            Console.error("Failed to parse matrix string: " + str, e);
        }
        return FloorMapTransformationMatrix.identity();
    }

    /**
     * Parses a comma-separated string representation of 2D coordinates {@code [x, y]}
     * into a double array. Handles optional square brackets and quotes in the input.
     *
     * @param str the coordinate string to parse, e.g. {@code "[100.5, 200.3]"}
     * @return a two-element array {@code {x, y}}, or {@code null} if the string is
     *         {@code null}, empty, or unparseable
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
            Console.error("Failed to parse coordinates string: " + str, e);
        }
        return null;
    }

    /**
     * Initialises the timeline range to ±24 hours around the currently selected time,
     * then triggers a histogram query to populate the timeline's event density bars.
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
        final DocRef storeRef = getEntity() != null ? getEntity().getFactsStoreRef() : null;
        if (storeRef != null && storeRef.getName() != null && !storeRef.getName().isEmpty()) {
            return "from \"" + storeRef.getName() + "\"\n"
                   + "select \n"
                   + "  Key, \n"
                   + "  EffectiveTime";
        }
        return null;
    }

    /**
     * Pauses the timeline if it is currently playing.
     *
     * <p>Called by {@link FloorMapPresenter} when the user navigates away from
     * the Map tab, so that background queries are not issued while the tab
     * is hidden.</p>
     */
    public void pauseTimeline() {
        floorMapTimelinePresenter.pause();
    }

    public interface FloorMapMapView extends View {

        /**
         * Shows or hides the object properties panel.
         *
         * @param visible {@code true} to show, {@code false} to hide
         */
        void setPropertiesVisible(boolean visible);
    }
}
