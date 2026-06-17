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

import stroom.data.client.presenter.MetaPresenter;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.shared.ExpressionCriteria;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.floormap.client.event.FloorMapDataEvent;
import stroom.floormap.client.event.MapObjectMovedEvent;
import stroom.floormap.client.event.MapObjectSelectedEvent;
import stroom.floormap.client.event.TimeChangeEvent;
import stroom.floormap.client.presenter.FloorMapMapPresenter.FloorMapMapView;
import stroom.floormap.shared.FloorMapBackground;
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
import stroom.query.shared.QueryTablePreferences;
import stroom.query.client.presenter.DateTimeSettingsFactory;
import stroom.query.client.presenter.QueryModel;
import stroom.query.client.presenter.ResultComponent;
import stroom.query.client.presenter.ResultStoreModel;
import stroom.sqlstore.shared.SqlTemporalStoreResource;
import stroom.util.shared.TemporalEntry;
import stroom.widget.tab.client.presenter.LinkTabsPresenter;
import stroom.widget.tab.client.presenter.TabData;
import stroom.alert.client.event.PromptEvent;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import stroom.util.client.JSONUtil;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Main presenter for the Floor Map visualization.
 * Coordinates the map canvas, the timeline, and the log data view.
 */
public class FloorMapMapPresenter
        extends DocPresenter<FloorMapMapView, FloorMapDoc> {

    public static final Object MAP = new Object();
    public static final Object TIMELINE = new Object();
    public static final Object LOG_DATA = new Object();
    public static final Object PROPERTIES = new Object();

    private static final SqlTemporalStoreResource SQL_TEMPORAL_STORE_RESOURCE =
            GWT.create(SqlTemporalStoreResource.class);

    private final FloorMapCanvasPresenter floorMapCanvasPresenter;
    private final FloorMapTimelinePresenter floorMapTimelinePresenter;
    private final FloorMapObjectEditPresenter floorMapObjectEditPresenter;
    private final FloorMapObjectListPresenter floorMapObjectListPresenter;

    private final RestFactory restFactory;
    private final LinkTabsPresenter linkTabsPresenter;
    private final QueryModel queryModel;

    // Local state for batch saving
    // TODO MB FIXME IntelliJ warning
    private final List<TemporalEntry> pendingUpdates = new ArrayList<>();

    private long selectedTime;
    private static final long ONE_DAY_MS = 24 * 60 * 60 * 1000;
    private String activeBgKey;
    private boolean editMode = false;

    @Inject
    public FloorMapMapPresenter(final EventBus eventBus,
                                final FloorMapMapView view,
                                final RestFactory restFactory,
                                final LinkTabsPresenter linkTabsPresenter,
                                final MetaPresenter metaPresenter,
                                final FloorMapTempPresenter floorMapTempPresenter,
                                final DateTimeSettingsFactory dateTimeSettingsFactory,
                                final ResultStoreModel resultStoreModel,
                                final Provider<FloorMapObjectEditPresenter> floorMapObjectEditPresenterProvider,
                                final Provider<FloorMapCanvasPresenter> floorMapCanvasPresenterProvider,
                                final Provider<FloorMapTimelinePresenter> floorMapTimelinePresenterProvider,
                                final Provider<FloorMapObjectListPresenter> floorMapObjectListPresenterProvider) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.linkTabsPresenter = linkTabsPresenter;

        this.floorMapCanvasPresenter = floorMapCanvasPresenterProvider.get();
        this.floorMapTimelinePresenter = floorMapTimelinePresenterProvider.get();
        this.floorMapObjectEditPresenter = floorMapObjectEditPresenterProvider.get();
        this.floorMapObjectListPresenter = floorMapObjectListPresenterProvider.get();

        // Default initial time
        this.selectedTime = System.currentTimeMillis();

        final TabData dataTab = linkTabsPresenter.addTab("Data", metaPresenter);
        linkTabsPresenter.addTab("Temp", floorMapTempPresenter);
        linkTabsPresenter.changeSelectedTab(dataTab);

        setInSlot(LOG_DATA, linkTabsPresenter);
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
                () -> getEntity() != null && getEntity().getFactsQueryTablePreferences() != null
                        ? getEntity().getFactsQueryTablePreferences()
                        : QueryTablePreferences.builder().build());
        this.queryModel.addResultComponent(QueryModel.TABLE_COMPONENT_ID, resultConsumer);
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(getEventBus().addHandler(TimeChangeEvent.getType(), e -> onTimeChange(e.getTime())));
        registerHandler(getEventBus().addHandler(FloorMapDataEvent.getType(), e ->
                floorMapCanvasPresenter.setObjects(e.getObjects())));

        registerHandler(getEventBus().addHandler(MapObjectSelectedEvent.getType(), e -> {
            if (e.getObjectId() != null && editMode) {
                final String key = "background".equals(e.getObjectId()) ? activeBgKey : e.getObjectId();
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
            final String mapName = getEntity() != null && getEntity().getTemporalStoreRef() != null 
                    ? getEntity().getTemporalStoreRef().getName() 
                    : "location_plan_b";

            final String key = "background".equals(e.getObjectId()) ? activeBgKey : e.getObjectId();
            if (key == null) {
                return;
            }

            final TemporalEntry selectedEntry = floorMapObjectEditPresenter.getSelectedEntry();
            final long targetTime = selectedEntry != null ? selectedEntry.getEffectiveTimeMs() : selectedTime;

            applyMove(selectedEntry, key, mapName, e.getX(), e.getY(), targetTime);
        }));

        this.floorMapCanvasPresenter.setDragHandler((objectId, x, y, bgMatrix) -> {
            if ("background".equals(objectId)) {
                if (bgMatrix != null) {
                    floorMapObjectEditPresenter.getView().setMapToScreenMatrix(new double[]{
                            bgMatrix.getA(), bgMatrix.getB(),
                            bgMatrix.getC(), bgMatrix.getD(),
                            bgMatrix.getE(), bgMatrix.getF()
                    });
                }
            } else {
                final double worldX = floorMapObjectEditPresenter.getView().getX();
                final double worldY = floorMapObjectEditPresenter.getView().getY();
                final double[] w2m = floorMapObjectEditPresenter.getView().getWorldToMapMatrix();
                if (w2m != null && w2m.length >= 6) {
                    final double a = w2m[0];
                    final double b = w2m[1];
                    final double c = w2m[2];
                    final double d = w2m[3];

                    // e = mapX - (a * worldX + c * worldY)
                    // f = mapY - (b * worldX + d * worldY)
                    final double newE = x - (a * worldX + c * worldY);
                    final double newF = y - (b * worldX + d * worldY);

                    final double[] newW2m = new double[]{a, b, c, d, newE, newF};
                    floorMapObjectEditPresenter.getView().setWorldToMapMatrix(newW2m);
                }
            }
        });

        floorMapObjectEditPresenter.setEditStateConsumer(floorMapCanvasPresenter::setIsDraggingEnabled);

        this.floorMapObjectListPresenter.setSelectionConsumer(factObj -> {
            if (factObj != null) {
                floorMapObjectEditPresenter.setObject(factObj.getKey());
                final String canvasId = factObj.getKey().equals(activeBgKey) ? "background" : factObj.getKey();
                floorMapCanvasPresenter.setSelectedObjectId(canvasId);
                getView().setPropertiesVisible(true);
            } else {
                floorMapCanvasPresenter.setSelectedObjectId(null);
            }
        });

        this.floorMapObjectEditPresenter.setChangeEventConsumer(() -> {
            onTimeChange(selectedTime);
            if (editMode) {
                final FloorMapObjectListPresenter.FactObject selected = floorMapObjectListPresenter.getSelectedObject();
                final String selectedKey = selected != null ? selected.getKey() : null;
                fetchObjectsForList(factObjects -> {
                    floorMapObjectListPresenter.setData(factObjects);
                    if (selectedKey != null) {
                        floorMapObjectListPresenter.setSelected(selectedKey);
                    }
                });
            }
        });
    }

    private void applyMove(final TemporalEntry selectedEntry, final String key, final String mapName, final double mapX, final double mapY, final long effectiveTime) {
        setDirty(true);

        JSONObject json = null;
        if (selectedEntry != null && selectedEntry.getValue() != null && selectedEntry.getValue().trim().startsWith("{")) {
            json = JSONUtil.getObject(JSONUtil.parse(selectedEntry.getValue()));
        }

        // TODO MB Check this
        if ("background".equals(key)
            || (selectedEntry != null && json != null
                && "background".equalsIgnoreCase(JSONUtil.getString(json.get(FloorMapObjectEditPresenter.JSON_KEY_TYPE))))) {
            if (json == null) {
                json = new JSONObject();
                json.put(FloorMapObjectEditPresenter.JSON_KEY_TYPE, new JSONString("background"));
                json.put(FloorMapObjectEditPresenter.JSON_KEY_NAME, new JSONString("Background"));
            }
            final FloorMapTransformationMatrix bgMatrix = floorMapCanvasPresenter.getMatrix();
            final JSONArray matrixArr = new JSONArray();
            matrixArr.set(0, new JSONNumber(bgMatrix.getA()));
            matrixArr.set(1, new JSONNumber(bgMatrix.getB()));
            matrixArr.set(2, new JSONNumber(bgMatrix.getC()));
            matrixArr.set(3, new JSONNumber(bgMatrix.getD()));
            matrixArr.set(4, new JSONNumber(bgMatrix.getE()));
            matrixArr.set(5, new JSONNumber(bgMatrix.getF()));
            json.put(FloorMapObjectEditPresenter.JSON_KEY_TM_MAP_TO_SCREEN, matrixArr);
        } else {
            if (json == null) {
                json = new JSONObject();
                json.put(FloorMapObjectEditPresenter.JSON_KEY_TYPE, new JSONString("gates"));
                json.put(FloorMapObjectEditPresenter.JSON_KEY_NAME, new JSONString(key));
            }

            double worldX = 0.0;
            double worldY = 0.0;
            double a = 1.0;
            double b = 0.0;
            double c = 0.0;
            double d = 1.0;

            final JSONArray coordsArr = JSONUtil.getArray(json.get(FloorMapObjectEditPresenter.JSON_KEY_COORDS));
            if (coordsArr != null && coordsArr.size() >= 2) {
                worldX = JSONUtil.getDouble(coordsArr.get(0));
                worldY = JSONUtil.getDouble(coordsArr.get(1));
            } else {
                final JSONArray newCoordsArr = new JSONArray();
                newCoordsArr.set(0, new JSONNumber(0.0));
                newCoordsArr.set(1, new JSONNumber(0.0));
                json.put(FloorMapObjectEditPresenter.JSON_KEY_COORDS, newCoordsArr);
            }

            final JSONArray matrixArr = JSONUtil.getArray(json.get(FloorMapObjectEditPresenter.JSON_KEY_TM_WORLD_TO_MAP));
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
            json.put(FloorMapObjectEditPresenter.JSON_KEY_TM_WORLD_TO_MAP, newMatrixArr);

            // Update details panel coordinates
            floorMapObjectEditPresenter.updateCoords(worldX, worldY);
        }

        final TemporalEntry entry = new TemporalEntry(
                mapName,
                key,
                effectiveTime,
                json.toString()
        );

        restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                .method(res -> res.update(entry))
                .onSuccess(result -> {
                    onTimeChange(selectedTime);
                    floorMapObjectEditPresenter.setObject(key);
                    floorMapObjectListPresenter.setSelected(key);
                })
                .exec();
    }

    @Override
    protected void onRead(final DocRef docRef, final FloorMapDoc document, final boolean readOnly) {
        updateTimelineRange();
        queryModel.init(docRef);
        queryModel.reset(DestroyReason.NO_LONGER_NEEDED);

        final String mapName = document.getTemporalStoreRef() != null ? document.getTemporalStoreRef().getName() : "location_plan_b";
        floorMapObjectEditPresenter.setMapName(mapName);

        onTimeChange(selectedTime);
    }

    @Override
    protected FloorMapDoc onWrite(final FloorMapDoc document) {
        // Batch flush all pending updates to the REST API before returning the document.
        if (!pendingUpdates.isEmpty()) {
            for (final TemporalEntry entry : pendingUpdates) {
                // Example REST call
                restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                        .method(res -> res.update(entry))
                        .exec();
            }

            pendingUpdates.clear();
        }
        return document;
    }

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

    private void onTimeChange(final long time) {
        this.selectedTime = time;

        if (editMode) {
            fetchFactsViaRest();
        } else {
            final String factsQuery = getFactsQueryToUse();
            if (factsQuery != null && !factsQuery.trim().isEmpty()) {
                final TimeRange timeRange = new TimeRange("CUSTOM", String.valueOf(selectedTime), String.valueOf(selectedTime));
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
            } else {
                // Fallback to inline background images
                final FloorMapBackground activeBg = getEntity() != null ? getEntity().getActiveBackground(time) : null;
                if (activeBg != null) {
                    floorMapCanvasPresenter.setBackgroundImage(activeBg.getImage());
                    floorMapCanvasPresenter.setMatrix(activeBg.getMatrix());
                } else {
                    floorMapCanvasPresenter.setBackgroundImage(null);
                    floorMapCanvasPresenter.setMatrix(FloorMapTransformationMatrix.identity());
                }
                floorMapCanvasPresenter.setObjects(new ArrayList<>());
            }
        }
    }

    private void fetchFactsViaRest() {
        final String mapName = getEntity() != null && getEntity().getTemporalStoreRef() != null 
                ? getEntity().getTemporalStoreRef().getName() 
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

    private void parseTemporalEntries(final List<TemporalEntry> entries) {
        String activeBgImage = null;
        FloorMapTransformationMatrix activeBgMatrix = FloorMapTransformationMatrix.identity();
        final List<FloorMapObject> plottedObjects = new ArrayList<>();

        for (final TemporalEntry entry : entries) {
            final String key = entry.getKey();
            final String valueStr = entry.getValue();
            if (valueStr == null || valueStr.trim().isEmpty()) {
                continue;
            }

            try {
                if (valueStr.trim().startsWith("{")) {
                    final JSONObject json = JSONUtil.getObject(JSONUtil.parse(valueStr));
                    if (json != null) {
                        final String type = JSONUtil.getString(json.get(FloorMapObjectEditPresenter.JSON_KEY_TYPE));
                        final String img = JSONUtil.getString(json.get(FloorMapObjectEditPresenter.JSON_KEY_IMG));

                        if ("background".equalsIgnoreCase(type)) {
                            activeBgImage = img;
                            activeBgKey = key;
                            final JSONArray mapToScreenArr = JSONUtil.getArray(json.get(FloorMapObjectEditPresenter.JSON_KEY_TM_MAP_TO_SCREEN));
                            if (mapToScreenArr != null) {
                                activeBgMatrix = parseMatrix(mapToScreenArr.toString());
                            }
                        } else {
                            double worldX = 0;
                            double worldY = 0;
                            final JSONArray coordsArr = JSONUtil.getArray(json.get(FloorMapObjectEditPresenter.JSON_KEY_COORDS));
                            if (coordsArr != null && coordsArr.size() >= 2) {
                                worldX = JSONUtil.getDouble(coordsArr.get(0));
                                worldY = JSONUtil.getDouble(coordsArr.get(1));
                            }

                            FloorMapTransformationMatrix worldToMap = FloorMapTransformationMatrix.identity();
                            final JSONArray worldToMapArr = JSONUtil.getArray(json.get(FloorMapObjectEditPresenter.JSON_KEY_TM_WORLD_TO_MAP));
                            if (worldToMapArr != null) {
                                worldToMap = parseMatrix(worldToMapArr.toString());
                            }

                            // Apply coordinates transformation:
                            final double mapX = worldToMap.getA() * worldX + worldToMap.getC() * worldY + worldToMap.getE();
                            final double mapY = worldToMap.getB() * worldX + worldToMap.getD() * worldY + worldToMap.getF();

                            plottedObjects.add(new FloorMapObject(key, type, mapX, mapY));
                        }
                    }
                }
            } catch (final Exception e) {
                // Ignore malformed entry
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

    private void parseFacts(final TableResult tableResult) {
        int keyIdx = -1;
        int typeIdx = -1;
        int nameIdx = -1;
        int mapsIdx = -1;
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
            if (colName.equalsIgnoreCase("Key")) keyIdx = i;
            else if (colName.equalsIgnoreCase(FloorMapObjectEditPresenter.JSON_KEY_TYPE)) typeIdx = i;
            else if (colName.equalsIgnoreCase(FloorMapObjectEditPresenter.JSON_KEY_NAME)) nameIdx = i;
            else if (colName.equalsIgnoreCase("maps")) mapsIdx = i;
            else if (colName.equalsIgnoreCase(FloorMapObjectEditPresenter.JSON_KEY_COORDS)) coordsIdx = i;
            else if (colName.equalsIgnoreCase(FloorMapObjectEditPresenter.JSON_KEY_IMG)) imgIdx = i;
            else if (colName.equalsIgnoreCase("tm_world_to_map") || colName.equalsIgnoreCase(FloorMapObjectEditPresenter.JSON_KEY_TM_WORLD_TO_MAP)) worldToMapIdx = i;
            else if (colName.equalsIgnoreCase("tm_map_to_screen") || colName.equalsIgnoreCase(FloorMapObjectEditPresenter.JSON_KEY_TM_MAP_TO_SCREEN)) mapToScreenIdx = i;
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
                    final double mapX = worldToMap.getA() * worldX + worldToMap.getC() * worldY + worldToMap.getE();
                    final double mapY = worldToMap.getB() * worldX + worldToMap.getD() * worldY + worldToMap.getF();

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

    @Override
    protected boolean hasAssociatedDirty() {
        return !pendingUpdates.isEmpty();
    }

    private void updateTimelineRange() {
        // By default, the timeline shows a range 24 hours each side of the current system time.
        final long start = selectedTime - ONE_DAY_MS;
        final long end = selectedTime + ONE_DAY_MS;

        floorMapTimelinePresenter.setTimeRange(start, end);
        floorMapTimelinePresenter.setCurrentTime(selectedTime);
    }

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

    public void addNewObject(final String key) {
        final String mapName = getEntity() != null && getEntity().getTemporalStoreRef() != null
                ? getEntity().getTemporalStoreRef().getName()
                : "location_plan_b";

        final JSONObject json = new JSONObject();
        json.put(FloorMapObjectEditPresenter.JSON_KEY_TYPE, new JSONString("gates"));
        json.put(FloorMapObjectEditPresenter.JSON_KEY_NAME, new JSONString(key));

        final JSONArray coordsArr = new JSONArray();
        coordsArr.set(0, new JSONNumber(500.0));
        coordsArr.set(1, new JSONNumber(500.0));
        json.put(FloorMapObjectEditPresenter.JSON_KEY_COORDS, coordsArr);

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
        json.put(FloorMapObjectEditPresenter.JSON_KEY_TM_WORLD_TO_MAP, matrixArr);

        final TemporalEntry entry = new TemporalEntry(
                mapName,
                key,
                selectedTime,
                json.toString()
        );

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

    public void toggleEditMode(final boolean editMode) {
        this.editMode = editMode;
        floorMapCanvasPresenter.setEditMode(editMode);
        if (!editMode) {
            // Revert bottom panel to normal timeline + tabs
            setInSlot(LOG_DATA, linkTabsPresenter);
            getView().setPropertiesVisible(false);
        } else {
            // Show objects list
            setInSlot(LOG_DATA, floorMapObjectListPresenter);
            fetchObjectsForList(factObjects -> {
                floorMapObjectListPresenter.setData(factObjects);
                floorMapObjectListPresenter.selectLast();
            });
        }
        onTimeChange(selectedTime);
    }

    private void fetchObjectsForList(final Consumer<List<FloorMapObjectListPresenter.FactObject>> consumer) {
        final String mapName = getEntity() != null && getEntity().getTemporalStoreRef() != null 
                ? getEntity().getTemporalStoreRef().getName() 
                : "location_plan_b";

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
                    final List<FloorMapObjectListPresenter.FactObject> factObjects = new ArrayList<>();
                    if (result != null && result.getValues() != null) {
                        final java.util.Map<String, TemporalEntry> latestByKey = new java.util.HashMap<>();
                        for (final TemporalEntry entry : result.getValues()) {
                            final String key = entry.getKey();
                            if (key == null) continue;
                            final TemporalEntry existing = latestByKey.get(key);
                            if (existing == null || entry.getEffectiveTimeMs() > existing.getEffectiveTimeMs()) {
                                latestByKey.put(key, entry);
                            }
                        }

                        for (final java.util.Map.Entry<String, TemporalEntry> entry : latestByKey.entrySet()) {
                            final String key = entry.getKey();
                            final TemporalEntry latestEntry = entry.getValue();
                            String name = key;
                            String type = "";
                            try {
                                if (latestEntry.getValue() != null && latestEntry.getValue().trim().startsWith("{")) {
                                    final JSONObject json = JSONUtil.getObject(JSONUtil.parse(latestEntry.getValue()));
                                    if (json != null) {
                                        name = JSONUtil.getString(json.get(FloorMapObjectEditPresenter.JSON_KEY_NAME));
                                        type = JSONUtil.getString(json.get(FloorMapObjectEditPresenter.JSON_KEY_TYPE));
                                    }
                                }
                            } catch (final Exception ex) {
                                // ignore
                            }
                            if (name == null || name.isEmpty()) {
                                name = key;
                            }
                            factObjects.add(new FloorMapObjectListPresenter.FactObject(key, name, type));
                        }
                    }
                    factObjects.sort(java.util.Comparator.comparing(
                            FloorMapObjectListPresenter.FactObject::getName,
                            String.CASE_INSENSITIVE_ORDER));
                    consumer.accept(factObjects);
                })
                .exec();
    }

    public interface FloorMapMapView extends View {
        void setPropertiesVisible(boolean visible);
    }
}
