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

import stroom.data.grid.client.MyDataGrid;
import stroom.dispatch.client.RestFactory;
import stroom.entity.shared.ExpressionCriteria;
import stroom.floormap.client.presenter.FloorMapObjectEditPresenter.FloorMapObjectEditView;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.sqlstore.shared.SqlTemporalStoreResource;
import stroom.svg.client.SvgPresets;
import stroom.util.shared.TemporalEntry;
import stroom.util.shared.TemporalEntryId;
import stroom.alert.client.event.ConfirmEvent;
import stroom.alert.client.event.PromptEvent;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import stroom.util.client.JSONUtil;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.cellview.client.TextColumn;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.view.client.ListDataProvider;
import com.google.gwt.view.client.SingleSelectionModel;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.HandlerRegistration;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.inject.Inject;

import stroom.document.asset.client.presenter.DocumentAssetDropDownPresenter;
import stroom.floormap.shared.FloorMapDoc;

public class FloorMapObjectEditPresenter extends MyPresenterWidget<FloorMapObjectEditView> {

    private static final SqlTemporalStoreResource SQL_TEMPORAL_STORE_RESOURCE =
            GWT.create(SqlTemporalStoreResource.class);

    public static final String JSON_KEY_COORDS = "coords";
    public static final String JSON_KEY_TYPE = "type";
    public static final String JSON_KEY_NAME = "name";
    public static final String JSON_KEY_IMG = "img";
    public static final String JSON_KEY_TM_WORLD_TO_MAP = "tm-world-to-map";
    public static final String JSON_KEY_TM_MAP_TO_SCREEN = "tm-map-to-screen";

    private Consumer<Boolean> editStateConsumer;
    private Runnable changeEventConsumer;

    public void setChangeEventConsumer(final Runnable changeEventConsumer) {
        this.changeEventConsumer = changeEventConsumer;
    }

    private final RestFactory restFactory;
    private final MyDataGrid<TemporalEntry> dataGrid;
    private final ListDataProvider<TemporalEntry> dataProvider = new ListDataProvider<>();
    private final SingleSelectionModel<TemporalEntry> selectionModel = new SingleSelectionModel<>();

    private final ButtonView addButton;
    private final ButtonView deleteButton;
    private final DocumentAssetDropDownPresenter documentAssetDropDownPresenter;
    private String objectId;
    private boolean isAdding = false;
    // TODO MB FIX THIS
    private String mapName = "location_plan_b";

    public void setMapName(final String mapName) {
        // TODO MB FIX THIS
        this.mapName = mapName != null ? mapName : "location_plan_b";
    }

    public void setFloorMapDoc(final FloorMapDoc floorMapDoc) {
        documentAssetDropDownPresenter.setDocument(floorMapDoc);
    }

    public TemporalEntry getSelectedEntry() {
        return selectionModel.getSelectedObject();
    }

    public void updateCoords(final double x, final double y) {
        getView().setX(x);
        getView().setY(y);
    }

    @Inject
    public FloorMapObjectEditPresenter(final EventBus eventBus,
                                       final FloorMapObjectEditView view,
                                       final RestFactory restFactory,
                                       final DocumentAssetDropDownPresenter documentAssetDropDownPresenter) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.documentAssetDropDownPresenter = documentAssetDropDownPresenter;

        // Initialise the Table grid
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setSelectionModel(selectionModel);
        view.setGridView(dataGrid);
        initGridColumns();
        dataProvider.addDataDisplay(dataGrid);

        // Initialise the Toolbar Buttons
        final ButtonPanel buttonPanel = new ButtonPanel();
        addButton = buttonPanel.addButton(SvgPresets.ADD);
        deleteButton = buttonPanel.addButton(SvgPresets.DELETE);
        view.setToolbar(buttonPanel);
    }

    @Override
    protected void onBind() {
        super.onBind();

        getView().setChooseImgView(documentAssetDropDownPresenter.getView().asWidget());

        // Row selection updates the input form
        //noinspection unused
        registerHandler(selectionModel.addSelectionChangeHandler(e -> {
            final TemporalEntry selected = selectionModel.getSelectedObject();

            if (selected != null) {
                isAdding = false;
                getView().setEnabled(true);
                documentAssetDropDownPresenter.setEnabled(true);
                deleteButton.setEnabled(true);
                resetInputs(selected);

                if (editStateConsumer != null) {
                    editStateConsumer.accept(true);
                }

                final List<TemporalEntry> list = dataProvider.getList();
                if (list != null) {
                    final int index = list.indexOf(selected);
                    if (index >= 0) {
                        com.google.gwt.core.client.Scheduler.get().scheduleDeferred(() -> {
                            if (index < dataGrid.getVisibleItemCount()) {
                                try {
                                    final com.google.gwt.dom.client.TableRowElement rowEl = dataGrid.getRowElement(index);
                                    if (rowEl != null) {
                                        stroom.widget.util.client.ElementUtil.scrollIntoViewNearest(rowEl);
                                    }
                                } catch (final Exception ex) {
                                    // Ignore
                                }
                            }
                        });
                    }
                }
            } else {
                deleteButton.setEnabled(false);
                if (!isAdding) {
                    getView().setEnabled(false);
                    documentAssetDropDownPresenter.setEnabled(false);
                    getView().setEffectiveTime(0L);
                    getView().setX(0.0);
                    getView().setY(0.0);

                    if (editStateConsumer != null) {
                        editStateConsumer.accept(false);
                    }
                }
            }
        }));

        // Toolbar: Click Add to clear input form for a new row
        //noinspection unused
        registerHandler(addButton.addClickHandler(e -> {
            final TemporalEntry activeVersion = selectionModel.getSelectedObject();
            
            PromptEvent.fire(this, 
                    "Enter the new Effective Time (in milliseconds):", 
                    String.valueOf(System.currentTimeMillis()), 
                    result -> {
                        if (result != null && !result.trim().isEmpty()) {
                            try {
                                final long newTime = Long.parseLong(result.trim());
                                
                                // Clone coordinate values from active selection
                                double x = 0.0;
                                double y = 0.0;
                                if (activeVersion != null) {
                                    final String originalValue = activeVersion.getValue();
                                    if (originalValue != null && originalValue.trim().startsWith("{")) {
                                        final JSONObject json = JSONUtil.getObject(JSONUtil.parse(originalValue));
                                        if (json != null) {
                                            final JSONArray coordsArr = JSONUtil.getArray(json.get(JSON_KEY_COORDS));
                                            if (coordsArr != null && coordsArr.size() >= 2) {
                                                x = JSONUtil.getDouble(coordsArr.get(0));
                                                y = JSONUtil.getDouble(coordsArr.get(1));
                                            }
                                        }
                                    } else if (originalValue != null) {
                                        final String[] coords = originalValue.split(",");
                                        x = Double.parseDouble(coords[1].trim());
                                        y = Double.parseDouble(coords[2].trim());
                                    }
                                }
                                
                                final double finalX = x;
                                final double finalY = y;
                                addEntry(newTime, finalX, finalY, this::onWriteComplete);
                            } catch (final NumberFormatException ex) {
                                // Ignore
                            }
                        }
                    });
        }));

        // Toolbar: Click Delete to remove from database and refresh table
        //noinspection unused
        registerHandler(deleteButton.addClickHandler(e -> {
            final TemporalEntry selected = selectionModel.getSelectedObject();

            if (selected != null) {
                ConfirmEvent.fire(this, "Are you sure you want to delete this temporal version?", ok -> {
                    if (ok) {
                        deleteEntry(selected.getEffectiveTimeMs(), this::onWriteComplete);
                    }
                });
            }
        }));

        // Action Form: Save/Update temporal record
        //noinspection unused
        registerHandler(getView().addSaveHandler(e -> {
            final long time = getView().getEffectiveTime();
            final double x = getView().getX();
            final double y = getView().getY();
            
            final TemporalEntry selected = selectionModel.getSelectedObject();
            if (selected != null && selected.getEffectiveTimeMs() != time) {
                ConfirmEvent.fire(this, 
                        "You have changed the effective time. Do you want to move the version to the new time? (Click OK to move, or Cancel to create a new cloned version instead)", 
                        move -> {
                            if (move) {
                                deleteEntry(selected.getEffectiveTimeMs(),
                                        () -> updateEntry(time, x, y, this::onWriteComplete));
                            } else {
                                updateEntry(time, x, y, this::onWriteComplete);
                            }
                        });
            } else {
                updateEntry(time, x, y, this::onWriteComplete);
            }
        }));

        // Action Form: Revert/Cancel changes
        //noinspection unused
        registerHandler(getView().addCancelHandler(e -> {
            final TemporalEntry selected = selectionModel.getSelectedObject();
            resetInputs(selected);
        }));
    }

    public void setObject(final String objectId) {
        this.objectId = objectId;
        refresh();
    }

    public void setEditStateConsumer(final Consumer<Boolean> editStateConsumer) {
        this.editStateConsumer = editStateConsumer;
    }

    private void onWriteComplete() {
        refresh();
        if (changeEventConsumer != null) {
            changeEventConsumer.run();
        }
    }

    private void refresh() {
        fetchHistory(entries -> {
            if (entries != null) {
                entries.sort(java.util.Comparator.comparing(TemporalEntry::getEffectiveTimeMs));
            }
            dataProvider.setList(entries);
            dataGrid.setRowData(0, entries);
            // Make sure something is selected if possible
            if (entries != null && !entries.isEmpty()) {
                final TemporalEntry currentlySelected = selectionModel.getSelectedObject();
                TemporalEntry matchingEntry = null;
                if (currentlySelected != null && Objects.equals(objectId, currentlySelected.getKey())) {
                    for (final TemporalEntry entry : entries) {
                        if (Objects.equals(entry.getEffectiveTimeMs(), currentlySelected.getEffectiveTimeMs())) {
                            matchingEntry = entry;
                            break;
                        }
                    }
                }
                if (matchingEntry != null) {
                    selectionModel.setSelected(matchingEntry, true);
                } else {
                    // Select the last item if nothing is already selected
                    //noinspection SequencedCollectionMethodCanBeUsed
                    selectionModel.setSelected(entries.get(entries.size() - 1), true);
                }
            } else {
                selectionModel.clear();
            }
        });
    }

    private void initGridColumns() {
        // Date/Time Column
        final Column<TemporalEntry, String> timeColumn = new TextColumn<>() {
            @Override
            public String getValue(final TemporalEntry entry) {
                return new Date(entry.getEffectiveTimeMs()).toString();
            }
        };

        dataGrid.addColumn(timeColumn, "Effective Time");
    }

    /**
     * Fetches all temporal/historical locations for the selected object from the store.
     */
    public void fetchHistory(final Consumer<List<TemporalEntry>> consumer) {
        if (objectId == null) {
            consumer.accept(new ArrayList<>());
            return;
        }

        final ExpressionOperator expression = ExpressionOperator.builder()
                .addTerm(ExpressionTerm.builder()
                        .field("Map")
                        .condition(Condition.EQUALS)
                        .value(mapName)
                        .build())
                .addTerm(ExpressionTerm.builder()
                        .field("Key")
                        .condition(Condition.EQUALS)
                        .value(objectId)
                        .build())
                .build();

        final ExpressionCriteria criteria = new ExpressionCriteria(expression);

        restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                .method(res -> res.find(criteria))
                .onSuccess(result -> {
                    if (result != null && result.getValues() != null) {
                        consumer.accept(result.getValues());
                    } else {
                        consumer.accept(new ArrayList<>());
                    }
                })
                .exec();
    }

    /**
     * Adds a new temporal entry for the current object.
     */
    public void addEntry(final long effectiveTimeMs, final double x, final double y, final Runnable onSuccess) {
        if (objectId == null) {
            return;
        }

        // TODO MB FIX THIS - type = "gates"

        final JSONObject json = new JSONObject();
        json.put(JSON_KEY_TYPE, new JSONString("gates"));
        json.put(JSON_KEY_NAME, new JSONString(objectId));
        final JSONArray coordsArr = new JSONArray();
        coordsArr.set(0, new JSONNumber(x));
        coordsArr.set(1, new JSONNumber(y));
        json.put(JSON_KEY_COORDS, coordsArr);
        
        final JSONArray matrixArr = new JSONArray();
        matrixArr.set(0, new JSONNumber(1.0));
        matrixArr.set(1, new JSONNumber(0.0));
        matrixArr.set(2, new JSONNumber(0.0));
        matrixArr.set(3, new JSONNumber(1.0));
        matrixArr.set(4, new JSONNumber(0.0));
        matrixArr.set(5, new JSONNumber(0.0));
        json.put(JSON_KEY_TM_WORLD_TO_MAP, matrixArr);

        final TemporalEntry entry = new TemporalEntry(
                mapName,
                objectId,
                effectiveTimeMs,
                json.toString()
        );

        restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                .method(res -> res.create(entry))
                .onSuccess(result -> onSuccess.run())
                .exec();
    }

    /**
     * Updates an existing temporal entry (or inserts if not exists).
     */
    public void updateEntry(final long effectiveTimeMs, final double x, final double y, final Runnable onSuccess) {
        if (objectId == null) {
            return;
        }

        final JSONObject json = new JSONObject();
        json.put(JSON_KEY_TYPE, new JSONString(getView().getType()));
        json.put(JSON_KEY_NAME, new JSONString(getView().getName()));
        json.put(JSON_KEY_IMG, new JSONString(documentAssetDropDownPresenter.getSelectedAssetPath() == null ? "" : documentAssetDropDownPresenter.getSelectedAssetPath()));

        final JSONArray coordsArr = new JSONArray();
        coordsArr.set(0, new JSONNumber(getView().getX()));
        coordsArr.set(1, new JSONNumber(getView().getY()));
        json.put(JSON_KEY_COORDS, coordsArr);

        final double[] w2m = getView().getWorldToMapMatrix();
        final JSONArray w2mArr = new JSONArray();
        for (int i = 0; i < 6; i++) {
            w2mArr.set(i, new JSONNumber(w2m[i]));
        }
        json.put(JSON_KEY_TM_WORLD_TO_MAP, w2mArr);

        if ("background".equalsIgnoreCase(getView().getType())) {
            final double[] m2s = getView().getMapToScreenMatrix();
            final JSONArray m2sArr = new JSONArray();
            for (int i = 0; i < 6; i++) {
                m2sArr.set(i, new JSONNumber(m2s[i]));
            }
            json.put(JSON_KEY_TM_MAP_TO_SCREEN, m2sArr);
        }

        final TemporalEntry entry = new TemporalEntry(
                mapName,
                objectId,
                effectiveTimeMs,
                json.toString()
        );

        restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                .method(res -> res.update(entry))
                .onSuccess(result -> onSuccess.run())
                .exec();
    }

    /**
     * Deletes a specific temporal entry by its effective time key.
     */
    public void deleteEntry(final long effectiveTimeMs, final Runnable onSuccess) {
        if (objectId == null) {
            return;
        }

        final TemporalEntryId id = new TemporalEntryId(
                mapName,
                objectId,
                effectiveTimeMs
        );

        restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                .method(res -> res.delete(id))
                .onSuccess(result -> {
                    if (result) {
                        onSuccess.run();
                    }
                })
                .exec();
    }

    private void resetInputs(final TemporalEntry selected) {
        if (selected != null) {
            getView().setEffectiveTime(selected.getEffectiveTimeMs());
            double x = 0.0;
            double y = 0.0;
            String name = "";
            String type = "";
            String img = "";
            final double[] w2m = new double[]{1.0, 0.0, 0.0, 1.0, 0.0, 0.0};
            final double[] m2s = new double[]{1.0, 0.0, 0.0, 1.0, 0.0, 0.0};

            try {
                if (selected.getValue() != null && selected.getValue().trim().startsWith("{")) {
                    final JSONObject json = JSONUtil.getObject(JSONUtil.parse(selected.getValue()));
                    if (json != null) {
                        name = JSONUtil.getString(json.get(JSON_KEY_NAME));
                        type = JSONUtil.getString(json.get(JSON_KEY_TYPE));
                        img = JSONUtil.getString(json.get(JSON_KEY_IMG));

                        final JSONArray coordsArr = JSONUtil.getArray(json.get(JSON_KEY_COORDS));
                        if (coordsArr != null && coordsArr.size() >= 2) {
                            x = JSONUtil.getDouble(coordsArr.get(0));
                            y = JSONUtil.getDouble(coordsArr.get(1));
                        }

                        final JSONArray w2mArr = JSONUtil.getArray(json.get(JSON_KEY_TM_WORLD_TO_MAP));
                        if (w2mArr != null && w2mArr.size() >= 6) {
                            for (int i = 0; i < 6; i++) {
                                w2m[i] = JSONUtil.getDouble(w2mArr.get(i));
                            }
                        }

                        final JSONArray m2sArr = JSONUtil.getArray(json.get(JSON_KEY_TM_MAP_TO_SCREEN));
                        if (m2sArr != null && m2sArr.size() >= 6) {
                            for (int i = 0; i < 6; i++) {
                                m2s[i] = JSONUtil.getDouble(m2sArr.get(i));
                            }
                        }
                    }
                } else if (selected.getValue() != null) {
                    final String[] coords = selected.getValue().split(",");
                    x = Double.parseDouble(coords[1].trim());
                    y = Double.parseDouble(coords[2].trim());
                }
            } catch (final Exception ex) {
                // Ignore
            }
            getView().setX(x);
            getView().setY(y);
            getView().setName(name);
            getView().setType(type);
            documentAssetDropDownPresenter.setSelectedAssetPath(img);
            getView().setWorldToMapMatrix(w2m);
            getView().setMapToScreenMatrix(m2s);
        } else {
            getView().setEffectiveTime(0L);
            getView().setX(0.0);
            getView().setY(0.0);
            getView().setName("");
            getView().setType("");
            documentAssetDropDownPresenter.setSelectedAssetPath("");
            getView().setWorldToMapMatrix(new double[]{1.0, 0.0, 0.0, 1.0, 0.0, 0.0});
            getView().setMapToScreenMatrix(new double[]{1.0, 0.0, 0.0, 1.0, 0.0, 0.0});
        }
    }

    // --------------------------------------------------------------------------------

    public interface FloorMapObjectEditView extends View {
        void setToolbar(Widget toolbarWidget);
        void setGridView(Widget gridWidget);

        long getEffectiveTime();
        void setEffectiveTime(long timeMS);

        double getX();
        void setX(double x);

        double getY();
        void setY(double y);

        String getName();
        void setName(String name);

        String getType();
        void setType(String type);

        void setChooseImgView(Widget widget);

        double[] getWorldToMapMatrix();
        void setWorldToMapMatrix(double[] matrix);

        double[] getMapToScreenMatrix();
        void setMapToScreenMatrix(double[] matrix);

        HandlerRegistration addSaveHandler(ClickHandler handler);
        HandlerRegistration addCancelHandler(ClickHandler handler);

        void setEnabled(final boolean enabled);
    }
}
