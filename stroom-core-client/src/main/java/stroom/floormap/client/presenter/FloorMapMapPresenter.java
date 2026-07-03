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

import stroom.alert.client.event.PromptEvent;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.shared.ExpressionCriteria;
import stroom.floormap.client.FloorMapJsonKeys;
import stroom.floormap.client.ValuePathAccessor;
import stroom.floormap.client.event.FloorMapDataEvent;
import stroom.floormap.client.event.MapObjectMovedEvent;
import stroom.floormap.client.event.MapObjectSelectedEvent;
import stroom.floormap.client.event.TimeChangeEvent;
import stroom.floormap.client.presenter.FloorMapMapPresenter.FloorMapMapView;
import stroom.floormap.shared.FloorMapDoc;
import stroom.floormap.shared.FloorMapFieldMapping;
import stroom.floormap.shared.FloorMapFieldMapping.Role;
import stroom.floormap.shared.FloorMapObject;
import stroom.floormap.shared.FloorMapTransformationMatrix;
import stroom.query.api.Column;
import stroom.query.api.DestroyReason;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
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
import stroom.sqlstore.shared.SqlTemporalStoreResource;
import stroom.util.client.JSONUtil;
import stroom.util.shared.TemporalEntry;
import stroom.widget.datepicker.client.UTCDate;

import com.google.gwt.core.client.GWT;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Presenter for the Map (visualisation) tab of a {@link FloorMapDoc}.
 *
 * <p>This presenter coordinates the floor-map canvas, timeline scrubber, and object
 * properties editor. It operates in two modes:</p>
 * <ul>
 *     <li><b>View mode</b> – facts are loaded by running a StroomQL query via
 *         {@link QueryModel}. Results are parsed by {@link #parseFacts(TableResult)}.</li>
 *     <li><b>Edit mode</b> – facts are loaded directly from the temporal store via
 *         {@link SqlTemporalStoreResource} REST calls. Results are parsed by
 *         {@link FloorMapEntryParser} in {@link #parseTemporalEntries(List)}.</li>
 * </ul>
 *
 * <h3>Layout slots</h3>
 * <ul>
 *     <li>{@link #MAP} – the {@link FloorMapCanvasPresenter} (canvas / visualisation)</li>
 *     <li>{@link #TIMELINE} – the {@link FloorMapTimelinePresenter} (timeline scrubber)</li>
 *     <li>{@link #PROPERTIES} – the {@link FloorMapObjectEditPresenter} (object property editor)</li>
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
    public static final Object PROPERTIES = new Object();

    private static final SqlTemporalStoreResource SQL_TEMPORAL_STORE_RESOURCE =
            GWT.create(SqlTemporalStoreResource.class);

    private final FloorMapCanvasPresenter floorMapCanvasPresenter;
    private final FloorMapTimelinePresenter floorMapTimelinePresenter;
    private final FloorMapObjectEditPresenter floorMapObjectEditPresenter;
    private final FloorMapFactListPresenter floorMapObjectListPresenter;

    private final RestFactory restFactory;
    private final QueryModel queryModel;
    private final QueryModel histogramQueryModel;

    private long histogramStart;
    private long histogramEnd;
    private static final int HISTOGRAM_BINS = 100;

    private long selectedTime;
    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;
    private String activeBgKey;
    private boolean editMode = false;

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
                                final Provider<FloorMapObjectEditPresenter> floorMapObjectEditPresenterProvider,
                                final Provider<FloorMapCanvasPresenter> floorMapCanvasPresenterProvider,
                                final Provider<FloorMapTimelinePresenter> floorMapTimelinePresenterProvider,
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
        setInSlot(PROPERTIES, floorMapObjectEditPresenter);

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
                    parseHistogram(tableResult);
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
                () -> getEntity() != null && getEntity().getEventsQueryTablePreferences() != null
                        ? getEntity().getEventsQueryTablePreferences()
                        : QueryTablePreferences.builder().build());
        this.histogramQueryModel.addResultComponent(QueryModel.TABLE_COMPONENT_ID, histogramResultConsumer);
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

        registerHandler(getEventBus().addHandler(MapObjectSelectedEvent.getType(), e -> {
            if (e.getObjectId() != null && editMode) {
                final String key = FloorMapJsonKeys.BACKGROUND.equals(e.getObjectId())
                        ? (activeBgKey != null ? activeBgKey : FloorMapJsonKeys.BACKGROUND)
                        : e.getObjectId();
                if (key != null) {
                    floorMapObjectListPresenter.setSelected(key);
                    floorMapObjectEditPresenter.setObject(key);
                    floorMapCanvasPresenter.setSelectedObjectId(e.getObjectId());
                    getView().setPropertiesVisible(true);
                }
            } else {
                floorMapCanvasPresenter.setSelectedObjectId(null);
                getView().setPropertiesVisible(false);
            }
        }));

        registerHandler(getEventBus().addHandler(MapObjectMovedEvent.getType(), e -> {
            final String mapName = getEntity() != null && getEntity().getFactsStoreRef() != null
                    ? getEntity().getFactsStoreRef().getName()
                    : null;
            if (mapName == null) {
                return;
            }

            final String key = FloorMapJsonKeys.BACKGROUND.equals(e.getObjectId())
                    ? (activeBgKey != null ? activeBgKey : FloorMapJsonKeys.BACKGROUND)
                    : e.getObjectId();
            if (key == null) {
                return;
            }

            final TemporalEntry selectedEntry = findEntry(key);
            final long targetTime = selectedEntry != null ? selectedEntry.getEffectiveTimeMs() : selectedTime;

            applyMove(selectedEntry, key, mapName, e.getX(), e.getY(), targetTime);
        }));

        this.floorMapCanvasPresenter.setDragHandler((objectId, x, y, bgMatrix) -> {
            if (FloorMapJsonKeys.BACKGROUND.equals(objectId)) {
                if (bgMatrix != null) {
                    floorMapObjectEditPresenter.getView().setMapToScreenMatrix(new double[]{
                            bgMatrix.getA(), bgMatrix.getB(),
                            bgMatrix.getC(), bgMatrix.getD(),
                            bgMatrix.getE(), bgMatrix.getF()
                    });
                }
            } else {
                final TemporalEntry entry = findEntry(objectId);
                final EntryCoordsAndMatrix info = getEntryCoordsAndMatrix(entry);

                // e = mapX - (a * worldX + c * worldY)
                // f = mapY - (b * worldX + d * worldY)
                final double newE = x - (info.a * info.worldX + info.c * info.worldY);
                final double newF = y - (info.b * info.worldX + info.d * info.worldY);

                final double[] newW2m = new double[]{info.a, info.b, info.c, info.d, newE, newF};
                floorMapObjectEditPresenter.getView().setWorldToMapMatrix(newW2m);
            }
        });



        this.floorMapObjectEditPresenter.addAssetSelectionHandler(e -> {
            if (e.getSelectedItem() != null) {
                final String type = floorMapObjectEditPresenter.getView().getType();
                if (FloorMapJsonKeys.BACKGROUND.equalsIgnoreCase(type)) {
                    floorMapCanvasPresenter.setBackgroundImage(e.getSelectedItem());
                }
            }
        });

        this.floorMapObjectListPresenter.setSelectionConsumer(factObj -> {
            if (factObj != null) {
                floorMapObjectEditPresenter.setObject(factObj.getKey());
                final String canvasId = (factObj.getKey().equals(activeBgKey)
                        || FloorMapJsonKeys.BACKGROUND.equals(factObj.getKey()))
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

    }

    /**
     * Handles a drag-move operation on a map object or the background.
     *
     * <p>Builds the updated JSON value with a recalculated transformation matrix, then
     * writes the entry directly to the server via
     * {@link SqlTemporalStoreResource#update(TemporalEntry)}. On success, refreshes the
     * canvas by re-running {@link #onTimeChange(long)}.</p>
     *
     * <p>For background objects the map-to-screen matrix from the canvas is persisted.
     * For regular objects the world-to-map matrix translation components (e, f) are
     * recomputed so the object appears at the new map position while preserving its
     * scale and rotation.</p>
     *
     * @param selectedEntry the existing temporal entry for the moved object,
     *                      or {@code null} if a new entry should be created
     * @param key           the object's unique key in the temporal store
     * @param mapName       the name of the facts store (map name)
     * @param mapX          the new X coordinate in map space
     * @param mapY          the new Y coordinate in map space
     * @param effectiveTime the effective timestamp to use for the updated entry
     */
    private void applyMove(final TemporalEntry selectedEntry,
                           final String key,
                           final String mapName,
                           final double mapX,
                           final double mapY,
                           final long effectiveTime) {
        setDirty(true);

        JSONObject json = null;
        if (selectedEntry != null
            && selectedEntry.getValue() != null
            && selectedEntry.getValue().trim().startsWith("{")) {
            json = JSONUtil.getObject(JSONUtil.parse(selectedEntry.getValue()));
        }

        // TODO MB Check this
        if (FloorMapJsonKeys.BACKGROUND.equals(key)
            || (selectedEntry != null && json != null
                && FloorMapJsonKeys.BACKGROUND.equalsIgnoreCase(
                JSONUtil.getString(ValuePathAccessor.get(json, pathForRole(Role.TYPE)))))) {
            if (json == null) {
                json = new JSONObject();
                json.put(FloorMapJsonKeys.TYPE, new JSONString(FloorMapJsonKeys.BACKGROUND));
                json.put(FloorMapJsonKeys.NAME, new JSONString(FloorMapJsonKeys.BACKGROUND_DISPLAY_NAME));
            }
            final FloorMapTransformationMatrix bgMatrix = floorMapCanvasPresenter.getMatrix();
            final JSONArray matrixArr = new JSONArray();
            matrixArr.set(0, new JSONNumber(bgMatrix.getA()));
            matrixArr.set(1, new JSONNumber(bgMatrix.getB()));
            matrixArr.set(2, new JSONNumber(bgMatrix.getC()));
            matrixArr.set(3, new JSONNumber(bgMatrix.getD()));
            matrixArr.set(4, new JSONNumber(bgMatrix.getE()));
            matrixArr.set(5, new JSONNumber(bgMatrix.getF()));
            ValuePathAccessor.set(json, pathForRole(Role.MAP_TO_SCREEN), matrixArr);
        } else {
            if (json == null) {
                json = new JSONObject();
                ValuePathAccessor.set(json, pathForRole(Role.TYPE), new JSONString("gates"));
                ValuePathAccessor.set(json, pathForRole(Role.LABEL), new JSONString(key));
            }

            double worldX = 0.0;
            double worldY = 0.0;
            double a = 1.0;
            double b = 0.0;
            double c = 0.0;
            double d = 1.0;

            final JSONArray coordsArr =
                    JSONUtil.getArray(ValuePathAccessor.get(json, pathForRole(Role.POSITION)));
            if (coordsArr != null && coordsArr.size() >= 2) {
                worldX = JSONUtil.getDouble(coordsArr.get(0));
                worldY = JSONUtil.getDouble(coordsArr.get(1));
            } else {
                final JSONArray newCoordsArr = new JSONArray();
                newCoordsArr.set(0, new JSONNumber(0.0));
                newCoordsArr.set(1, new JSONNumber(0.0));
                ValuePathAccessor.set(json, pathForRole(Role.POSITION), newCoordsArr);
            }

            final JSONArray matrixArr =
                    JSONUtil.getArray(ValuePathAccessor.get(json, pathForRole(Role.WORLD_TO_MAP)));
            if (matrixArr != null && matrixArr.size() >= 6) {
                a = JSONUtil.getDouble(matrixArr.get(0));
                b = JSONUtil.getDouble(matrixArr.get(1));
                c = JSONUtil.getDouble(matrixArr.get(2));
                d = JSONUtil.getDouble(matrixArr.get(3));
            }

            // e = mapX - (a * worldX + c * worldY)
            // f = mapY - (b * worldX + d * worldY)
            final double newE = mapX - (a * worldX + c * worldY);
            final double newF = mapY - (b * worldX + d * worldY);

            final JSONArray newMatrixArr = new JSONArray();
            newMatrixArr.set(0, new JSONNumber(a));
            newMatrixArr.set(1, new JSONNumber(b));
            newMatrixArr.set(2, new JSONNumber(c));
            newMatrixArr.set(3, new JSONNumber(d));
            newMatrixArr.set(4, new JSONNumber(newE));
            newMatrixArr.set(5, new JSONNumber(newF));
            ValuePathAccessor.set(json, pathForRole(Role.WORLD_TO_MAP), newMatrixArr);

            // Update details panel coordinates
            floorMapObjectEditPresenter.updateCoords(worldX, worldY);
        }

        final TemporalEntry entry = new TemporalEntry(
                mapName,
                key,
                effectiveTime,
                json.toString()
        );

        //noinspection unused result
        restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                .method(res -> res.update(entry))
                .onSuccess(result -> {
                    onTimeChange(selectedTime);
                    floorMapObjectEditPresenter.setObject(key);
                    floorMapObjectListPresenter.setSelected(key);
                })
                .exec();
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
        histogramQueryModel.init(docRef);
        histogramQueryModel.reset(DestroyReason.NO_LONGER_NEEDED);

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

    /**
     * Overridden to make public.
     */
    @Override
    public boolean hasAssociatedDirty() {
        return false;
    }

    private String getFactsQueryToUse() {
        final java.util.List<stroom.floormap.shared.FloorMapFieldMapping> schema = valueSchema();
        if (schema == null || schema.isEmpty()) {
            return null;
        }
        return FloorMapQueryBuilder.buildFactsQuery(schema, getEntity().getValueFormat());
    }

    /**
     * Responds to a timeline time-change event. In edit mode, fetches facts via the
     * REST API ({@link #fetchFactsViaRest()}). In view mode, runs the facts StroomQL
     * query via {@link QueryModel} at the selected time.
     *
     * @param time the new selected time in milliseconds since epoch
     */
    private void onTimeChange(final long time) {
        this.selectedTime = time;

        if (editMode) {
            fetchFactsViaRest();
        } else {
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
    }

    /**
     * Fetches facts from the temporal store via REST for the current map and selected
     * time (edit mode only). Results are filtered to entries whose effective time is
     * less than or equal to the selected time. On success, delegates to
     * {@link #parseTemporalEntries(List)}.
     */
    private void fetchFactsViaRest() {
        final String mapName = getEntity() != null && getEntity().getFactsStoreRef() != null
                ? getEntity().getFactsStoreRef().getName()
                : "location_plan_b";

        final ExpressionOperator expression = ExpressionOperator.builder()
                .addTerm(ExpressionTerm.builder()
                        .field("Map")
                        .condition(Condition.EQUALS)
                        .value(mapName)
                        .build())
                .addTerm(ExpressionTerm.builder()
                        .field("EffectiveTime")
                        .condition(Condition.LESS_THAN_OR_EQUAL_TO)
                        .value(String.valueOf(selectedTime))
                        .build())
                .build();

        final ExpressionCriteria criteria = new ExpressionCriteria(expression);

        restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                .method(res -> res.find(criteria))
                .onSuccess(result -> {
                    if (result != null && result.getValues() != null) {
                        parseTemporalEntries(result.getValues());
                    } else {
                        parseTemporalEntries(new ArrayList<>());
                    }
                })
                .exec();
    }

    /**
     * Parses a list of {@link TemporalEntry} results from the REST API into
     * canvas-renderable objects using {@link FloorMapEntryParser}. Updates the canvas
     * background image, transformation matrix, and plotted objects.
     *
     * @param entries the temporal entries to parse; must not be {@code null}
     */
    private void parseTemporalEntries(final List<TemporalEntry> entries) {
        this.currentEntries = entries;
        final FloorMapEntryParser.ParseResult result = FloorMapEntryParser.parse(
                entries, valueSchema());

        activeBgKey = result.getBackgroundKey();

        if (result.getBackgroundImage() != null && !result.getBackgroundImage().isEmpty()) {
            floorMapCanvasPresenter.setBackgroundImage(result.getBackgroundImage());
        } else {
            floorMapCanvasPresenter.setBackgroundImage(null);
        }
        floorMapCanvasPresenter.setMatrix(result.getBackgroundMatrix());
        floorMapCanvasPresenter.setObjects(result.getObjects());
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
        final List<FloorMapObject> plottedObjects = new ArrayList<>();

        if (tableResult.getRows() != null) {
            for (final Row row : tableResult.getRows()) {
                final List<String> values = row.getValues();
                final String key = keyIdx != -1 && values.size() > keyIdx ? values.get(keyIdx) : null;
                final String type = typeIdx != -1 && values.size() > typeIdx ? values.get(typeIdx) : "";
                final String img = imgIdx != -1 && values.size() > imgIdx ? values.get(imgIdx) : null;

                if (FloorMapJsonKeys.BACKGROUND.equalsIgnoreCase(type)) {
                    activeBgImage = img;
                    activeBgKey = key;
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

        if (activeBgImage != null && !activeBgImage.isEmpty()) {
            floorMapCanvasPresenter.setBackgroundImage(activeBgImage);
        } else {
            floorMapCanvasPresenter.setBackgroundImage(null);
        }
        floorMapCanvasPresenter.setMatrix(activeBgMatrix);
        floorMapCanvasPresenter.setObjects(plottedObjects);
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
            // Ignore
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
            // Ignore
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

        this.histogramStart = start;
        this.histogramEnd = end;

        floorMapTimelinePresenter.setTimeRange(start, end);
        floorMapTimelinePresenter.setCurrentTime(selectedTime);

        runHistogramQuery(start, end);
    }

    /**
     * Runs the events StroomQL query over the specified time range to populate the
     * timeline histogram. Stores the query range for bucket calculations in
     * {@link #parseHistogram(TableResult)}.
     *
     * @param start the start of the time range in milliseconds since epoch
     * @param end   the end of the time range in milliseconds since epoch
     */
    private void runHistogramQuery(final long start, final long end) {
        final String eventsQuery = getEntity() != null ? getEntity().getEventsQuery() : null;
        if (eventsQuery == null || eventsQuery.trim().isEmpty()) {
            return;
        }

        // Keep these in sync with the query range so that parseHistogram buckets
        // events against the same window that was actually queried.
        this.histogramStart = start;
        this.histogramEnd = end;

        final TimeRange fullRange = new TimeRange("CUSTOM",
                String.valueOf(start), String.valueOf(end));
        histogramQueryModel.startNewSearch(
                QueryModel.TABLE_COMPONENT_ID,
                "histogramTable",
                eventsQuery,
                null,
                fullRange,
                false,
                false,
                "Histogram Query",
                null
        );
    }

    /**
     * Parses the histogram {@link TableResult}: reads the 'Effective Time' column
     * (ISO-8601 string), buckets events into {@link #HISTOGRAM_BINS} bins across
     * [{@link #histogramStart}, {@link #histogramEnd}], and sends the bin counts to the
     * timeline presenter for rendering. Events outside the visible range are skipped
     * (not clamped to edge bins). Also tracks the overall min/max data extent to support
     * the "Show All" feature.
     *
     * @param tableResult the histogram query result to parse
     */
    private void parseHistogram(final TableResult tableResult) {
        final int[] bins = new int[HISTOGRAM_BINS];

        if (tableResult == null || tableResult.getRows() == null || tableResult.getColumns() == null) {
            floorMapTimelinePresenter.setHistogramData(bins);
            return;
        }

        // Find the "Effective Time" column index.
        int timeColIdx = -1;
        final List<Column> columns = tableResult.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            final String name = columns.get(i).getName();
            if ("Effective Time".equalsIgnoreCase(name) || "EffectiveTime".equalsIgnoreCase(name)) {
                timeColIdx = i;
                break;
            }
        }

        if (timeColIdx == -1 || histogramEnd <= histogramStart) {
            floorMapTimelinePresenter.setHistogramData(bins);
            return;
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
                // Skip events that fall outside the visible range — do not clamp them
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

        floorMapTimelinePresenter.setHistogramData(bins);
        // Inform the timeline of the actual data extent so Show All can be computed.
        if (minTime <= maxTime) {
            floorMapTimelinePresenter.setDataRange(minTime, maxTime);
        }
    }

    /**
     * Displays a prompt dialog asking the user to enter an object ID/key, then
     * delegates to {@link #addNewObject(String)} to create the entry in the temporal
     * store.
     */
    public void promptAndAddObject() {
        PromptEvent.fire(this,
                "Enter Object ID/Key to add:",
                "",
                key -> {
                    if (key != null && !key.trim().isEmpty()) {
                        addNewObject(key.trim());
                    }
                });
    }

    /**
     * Creates a new object entry in the temporal store with the given key. The entry
     * is initialised with default coordinates (500, 500), an identity world-to-map
     * matrix, and the "gates" type. On success, refreshes the canvas and updates the
     * fact list in edit mode.
     *
     * @param key the unique object key/ID for the new entry
     */
    public void addNewObject(final String key) {
        final String mapName = getEntity() != null && getEntity().getFactsStoreRef() != null
                ? getEntity().getFactsStoreRef().getName()
                : "location_plan_b";

        final JSONObject json = new JSONObject();
        ValuePathAccessor.set(json, pathForRole(Role.TYPE), new JSONString("gates"));
        ValuePathAccessor.set(json, pathForRole(Role.LABEL), new JSONString(key));

        final JSONArray coordsArr = new JSONArray();
        coordsArr.set(0, new JSONNumber(500.0));
        coordsArr.set(1, new JSONNumber(500.0));
        ValuePathAccessor.set(json, pathForRole(Role.POSITION), coordsArr);

        if (activeBgKey != null) {
            final JSONArray mapsArr = new JSONArray();
            mapsArr.set(0, new JSONString(activeBgKey));
            json.put("maps", mapsArr);
        }

        final JSONArray matrixArr = new JSONArray();
        matrixArr.set(0, new JSONNumber(1.0));
        matrixArr.set(1, new JSONNumber(0.0));
        matrixArr.set(2, new JSONNumber(0.0));
        matrixArr.set(3, new JSONNumber(1.0));
        matrixArr.set(4, new JSONNumber(0.0));
        matrixArr.set(5, new JSONNumber(0.0));
        ValuePathAccessor.set(json, pathForRole(Role.WORLD_TO_MAP), matrixArr);

        final TemporalEntry entry = new TemporalEntry(
                mapName,
                key,
                selectedTime,
                json.toString()
        );

        //noinspection unused result
        restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                .method(res -> res.create(entry))
                .onSuccess(result -> {
                    onTimeChange(selectedTime);
                    if (editMode) {
                        fetchObjectsForList(factObjects -> {
                            floorMapObjectListPresenter.setData(factObjects);
                            floorMapObjectListPresenter.setSelected(key);
                        });
                    }
                })
                .exec();
    }

    /**
     * Switches between view mode and edit mode. In edit mode, the fact list is populated
     * and dragging is enabled; in view mode, the properties panel is hidden and facts
     * are loaded via query. Triggers a time-change to refresh the canvas data source.
     *
     * @param editMode {@code true} to enable edit mode, {@code false} for view mode
     */
    public void toggleEditMode(final boolean editMode) {
        this.editMode = editMode;
        floorMapCanvasPresenter.setEditMode(editMode);
        if (!editMode) {
            getView().setPropertiesVisible(false);
        } else {
            fetchObjectsForList(factObjects -> {
                floorMapObjectListPresenter.setData(factObjects);
                floorMapObjectListPresenter.selectLast();
            });
        }
        onTimeChange(selectedTime);
    }

    /**
     * Fetches all objects from the facts temporal store via REST (without time filtering)
     * and deduplicates by key, keeping the latest entry per key. Ensures a background
     * entry is always present in the result set. Results are sorted alphabetically by
     * name and passed to the consumer.
     *
     * @param consumer the callback to receive the list of
     *                 {@link FloorMapFactListPresenter.FactObject} instances
     */
    private void fetchObjectsForList(final Consumer<List<FloorMapFactListPresenter.FactObject>> consumer) {
        if (getEntity() == null || getEntity().getFactsStoreRef() == null) {
            consumer.accept(new ArrayList<>());
            return;
        }
        final String mapName = getEntity().getFactsStoreRef().getName();

        final ExpressionOperator expression = ExpressionOperator.builder()
                .addTerm(ExpressionTerm.builder()
                        .field("Map")
                        .condition(Condition.EQUALS)
                        .value(mapName)
                        .build())
                .build();

        final ExpressionCriteria criteria = new ExpressionCriteria(expression);
        criteria.setPageRequest(new stroom.util.shared.PageRequest(0, 10000));

        restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                .method(res -> res.find(criteria))
                .onSuccess(result -> {
                    final List<FloorMapFieldMapping> schema = getEntity().getValueSchema();
                    final List<FloorMapFactListPresenter.FactObject> factObjects = new ArrayList<>();
                    if (result != null && result.getValues() != null) {
                        final java.util.Map<String, TemporalEntry> latestByKey = new java.util.HashMap<>();
                        for (final TemporalEntry entry : result.getValues()) {
                            final String key = entry.getKey();
                            if (key == null) {
                                continue;
                            }
                            final TemporalEntry existing = latestByKey.get(key);
                            if (existing == null || entry.getEffectiveTimeMs() > existing.getEffectiveTimeMs()) {
                                latestByKey.put(key, entry);
                            }
                        }

                        for (final java.util.Map.Entry<String, TemporalEntry> entry : latestByKey.entrySet()) {
                            factObjects.add(FloorMapFactListPresenter.FactObject.fromEntry(
                                    entry.getValue(), schema));
                        }
                    }
                    // Ensure background is always in the list of items
                    final String bgKey = activeBgKey != null ? activeBgKey : FloorMapJsonKeys.BACKGROUND;
                    boolean hasBg = false;
                    for (final FloorMapFactListPresenter.FactObject obj : factObjects) {
                        if (obj.getKey().equals(bgKey) || FloorMapJsonKeys.BACKGROUND.equalsIgnoreCase(obj.getType())) {
                            hasBg = true;
                            break;
                        }
                    }
                    if (!hasBg) {
                        factObjects.add(new FloorMapFactListPresenter.FactObject(
                                bgKey, FloorMapJsonKeys.BACKGROUND_DISPLAY_NAME, FloorMapJsonKeys.BACKGROUND));
                    }

                    factObjects.sort(java.util.Comparator.comparing(
                            FloorMapFactListPresenter.FactObject::getName,
                            String.CASE_INSENSITIVE_ORDER));
                    consumer.accept(factObjects);
                })
                .exec();
    }

    private List<TemporalEntry> currentEntries = new ArrayList<>();

    /**
     * Finds a {@link TemporalEntry} in the current entry cache by key.
     *
     * @param key the object key to search for
     * @return the matching entry, or {@code null} if not found
     */
    private TemporalEntry findEntry(final String key) {
        if (currentEntries != null && key != null) {
            for (final TemporalEntry entry : currentEntries) {
                if (key.equals(entry.getKey())) {
                    return entry;
                }
            }
        }
        return null;
    }

    /**
     * Holds extracted world coordinates and world-to-map affine transformation matrix
     * components from a temporal entry. Fields default to the origin position
     * {@code (0, 0)} and the identity matrix {@code [1, 0, 0, 1, 0, 0]}.
     */
    private static class EntryCoordsAndMatrix {
        double worldX = 0.0;
        double worldY = 0.0;
        double a = 1.0;
        double b = 0.0;
        double c = 0.0;
        double d = 1.0;
        double e = 0.0;
        double f = 0.0;
    }

    /**
     * Extracts the world coordinates (position) and world-to-map transformation matrix
     * components from a {@link TemporalEntry}'s JSON value. If the entry is {@code null}
     * or its JSON cannot be parsed, returns default values (origin and identity matrix).
     *
     * @param entry the temporal entry to extract data from; may be {@code null}
     * @return an {@link EntryCoordsAndMatrix} containing the extracted (or default) values
     */
    private EntryCoordsAndMatrix getEntryCoordsAndMatrix(final TemporalEntry entry) {
        final EntryCoordsAndMatrix result = new EntryCoordsAndMatrix();
        if (entry != null && entry.getValue() != null && entry.getValue().trim().startsWith("{")) {
            try {
                final JSONObject json = JSONUtil.getObject(JSONUtil.parse(entry.getValue()));
                if (json != null) {
                    final JSONArray coordsArr =
                            JSONUtil.getArray(ValuePathAccessor.get(json, pathForRole(Role.POSITION)));
                    if (coordsArr != null && coordsArr.size() >= 2) {
                        result.worldX = JSONUtil.getDouble(coordsArr.get(0));
                        result.worldY = JSONUtil.getDouble(coordsArr.get(1));
                    }
                    final JSONArray matrixArr =
                            JSONUtil.getArray(ValuePathAccessor.get(json, pathForRole(Role.WORLD_TO_MAP)));
                    if (matrixArr != null && matrixArr.size() >= 6) {
                        result.a = JSONUtil.getDouble(matrixArr.get(0));
                        result.b = JSONUtil.getDouble(matrixArr.get(1));
                        result.c = JSONUtil.getDouble(matrixArr.get(2));
                        result.d = JSONUtil.getDouble(matrixArr.get(3));
                        result.e = JSONUtil.getDouble(matrixArr.get(4));
                        result.f = JSONUtil.getDouble(matrixArr.get(5));
                    }
                }
            } catch (final Exception ex) {
                // Ignore
            }
        }
        return result;
    }

    public interface FloorMapMapView extends View {
        void setPropertiesVisible(boolean visible);
    }
}
