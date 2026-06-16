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
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;

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
import java.util.function.Consumer;
import javax.inject.Inject;

public class FloorMapObjectEditPresenter extends MyPresenterWidget<FloorMapObjectEditView> {

    private static final SqlTemporalStoreResource SQL_TEMPORAL_STORE_RESOURCE =
            GWT.create(SqlTemporalStoreResource.class);

    private Consumer<Boolean> editStateConsumer;

    private final RestFactory restFactory;
    private final MyDataGrid<TemporalEntry> dataGrid;
    private final ListDataProvider<TemporalEntry> dataProvider = new ListDataProvider<>();
    private final SingleSelectionModel<TemporalEntry> selectionModel = new SingleSelectionModel<>();

    private final ButtonView addButton;
    private final ButtonView deleteButton;
    private String objectId;
    private boolean isAdding = false;

    @Inject
    public FloorMapObjectEditPresenter(final EventBus eventBus,
                                       final FloorMapObjectEditView view,
                                       final RestFactory restFactory) {
        super(eventBus, view);
        this.restFactory = restFactory;

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

        // Row selection updates the input form
        registerHandler(selectionModel.addSelectionChangeHandler(e -> {
            final TemporalEntry selected = selectionModel.getSelectedObject();

            if (selected != null) {
                isAdding = false;
                getView().setEnabled(true);
                deleteButton.setEnabled(true);
                getView().setEffectiveTime(selected.getEffectiveTimeMs());

                try {
                    final String[] coords = selected.getValue().split(",");
                    getView().setX(Double.parseDouble(coords[1].trim()));
                    getView().setY(Double.parseDouble(coords[2].trim()));
                } catch (final Exception ex) {
                    getView().setX(0.0);
                    getView().setY(0.0);
                }

                if (editStateConsumer != null) {
                    editStateConsumer.accept(true);
                }
            } else {
                deleteButton.setEnabled(false);
                if (!isAdding) {
                    getView().setEnabled(false);
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
        registerHandler(addButton.addClickHandler(e -> {
            isAdding = true;
            selectionModel.clear();
            getView().setEnabled(true);
            getView().setEffectiveTime(System.currentTimeMillis());
            getView().setX(0.0);
            getView().setY(0.0);

            if (editStateConsumer != null) {
                editStateConsumer.accept(true);
            }
        }));

        // Toolbar: Click Delete to remove from database and refresh table
        registerHandler(deleteButton.addClickHandler(e -> {
            final TemporalEntry selected = selectionModel.getSelectedObject();

            if (selected != null) {
                deleteEntry(selected.getEffectiveTimeMs(), this::refresh);
            }
        }));

        // Action Form: Save/Update temporal record
        registerHandler(getView().addSaveHandler(e -> {
            final long time = getView().getEffectiveTime();
            final double x = getView().getX();
            final double y = getView().getY();
            updateEntry(time, x, y, this::refresh);
        }));
    }

    public void setObject(final String objectId) {
        this.objectId = objectId;
        refresh();
    }

    public void setEditStateConsumer(final Consumer<Boolean> editStateConsumer) {
        this.editStateConsumer = editStateConsumer;
    }

    private void refresh() {
        fetchHistory(entries -> {
            dataProvider.setList(entries);
            dataGrid.setRowData(0, entries);
            selectionModel.clear();
        });
    }

    private void initGridColumns() {
        // Date/Time Column
        final Column<TemporalEntry, String> timeColumn = new TextColumn<TemporalEntry>() {
            @Override
            public String getValue(final TemporalEntry entry) {
                return new Date(entry.getEffectiveTimeMs()).toString();
            }
        };

        dataGrid.addColumn(timeColumn, "Effective Time");

        // Coordinates Column
        final Column<TemporalEntry, String> valueColumn = new TextColumn<TemporalEntry>() {
            @Override
            public String getValue(final TemporalEntry entry) {
                return entry.getValue();
            }
        };

        dataGrid.addColumn(valueColumn, "Location State");
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
                        .value("location_plan_b")
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

        final String valueStr = "map3, " + x + ", " + y;
        final TemporalEntry entry = new TemporalEntry(
                "location_plan_b",
                objectId,
                effectiveTimeMs,
                valueStr
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

        final String valueStr = "map3, " + x + ", " + y;
        final TemporalEntry entry = new TemporalEntry(
                "location_plan_b",
                objectId,
                effectiveTimeMs,
                valueStr
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
                "location_plan_b",
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

        HandlerRegistration addSaveHandler(ClickHandler handler);

        void setEnabled(final boolean enabled);
    }
}
