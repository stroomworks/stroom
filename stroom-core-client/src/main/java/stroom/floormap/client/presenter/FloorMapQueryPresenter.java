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

import stroom.docref.DocRef;
import stroom.entity.client.presenter.HasToolbar;
import stroom.floormap.client.event.FloorMapDataEvent;
import stroom.floormap.client.event.TimeChangeEvent;
import stroom.floormap.client.presenter.FloorMapQueryPresenter.FloorMapQueryView;
import stroom.floormap.shared.FloorMapDoc;
import stroom.floormap.shared.FloorMapObject;
import stroom.query.api.Column;
import stroom.query.api.Row;
import stroom.query.api.TableResult;
import stroom.query.api.TimeRange;
import stroom.query.client.presenter.QueryEditPresenter;
import stroom.query.shared.QueryTablePreferences;
import stroom.task.client.TaskMonitorFactory;

import com.google.gwt.user.client.ui.Widget;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

public class FloorMapQueryPresenter extends MyPresenterWidget<FloorMapQueryView> implements HasToolbar {

    private final QueryEditPresenter queryEditPresenter;
    private String currentEntityColumn;
    private String currentLocationColumn;

    @Inject
    public FloorMapQueryPresenter(final EventBus eventBus,
                                  final FloorMapQueryView view,
                                  final QueryEditPresenter queryEditPresenter) {
        super(eventBus, view);
        this.queryEditPresenter = queryEditPresenter;
        view.setQueryEditView(queryEditPresenter.getView());
    }

    @Override
    protected void onBind() {
        super.onBind();

        // Listen to column updates inside the table so we can update the dropdown lists dynamically.
        registerHandler(queryEditPresenter.addChangeHandler(this::updateColumnSelections));

        // Listen to table data updates and fire FloorMapDataEvent
        registerHandler(queryEditPresenter.getQueryResultPresenter().getTablePresenter().addUpdateHandler(e -> {
            // Refresh available columns in the dropdowns as soon as the query finishes.
            updateColumnSelections();

            // Refresh map objects.
            final TableResult tableResult = queryEditPresenter.getQueryResultPresenter()
                    .getTablePresenter()
                    .getCurrentTableResult();

            if (tableResult != null) {
                final List<FloorMapObject> objects = parseRows(tableResult);
                FloorMapDataEvent.fire(FloorMapQueryPresenter.this, objects);
            }
        }));

        // Listen to timeline playback changes to automatically update query time and re-run.
        registerHandler(getEventBus().addHandler(TimeChangeEvent.getType(), e -> {
            final TimeRange timeRange = new TimeRange(
                    "CUSTOM", String.valueOf(e.getTime()), String.valueOf(e.getTime()));
            queryEditPresenter.setTimeRange(timeRange);
            queryEditPresenter.start();
        }));
    }

    private void updateColumnSelections() {
        final List<Column> columns = queryEditPresenter.getQueryResultPresenter()
                .getTablePresenter()
                .getCurrentColumns();

        if (columns != null && !columns.isEmpty()) {
            final List<String> colNames = columns
                    .stream()
                    .map(Column::getName)
                    .toList();

            // Save the user's current selection before repopulating.
            final String selectedEntity = getView().getEntityIdColumn();
            final String selectedLocation = getView().getLocationIdColumn();
            getView().setAvailableColumns(colNames);

            // Re-apply selections if they still exist in the updated column list.
            if (colNames.contains(selectedEntity)) {
                getView().setEntityIdColumn(selectedEntity);
                this.currentEntityColumn = selectedEntity;
            } else if (colNames.contains(currentEntityColumn)) {
                getView().setEntityIdColumn(currentEntityColumn);
            }

            if (colNames.contains(selectedLocation)) {
                getView().setLocationIdColumn(selectedLocation);
                this.currentLocationColumn = selectedLocation;
            } else if (colNames.contains(currentLocationColumn)) {
                getView().setLocationIdColumn(currentLocationColumn);
            }
        }
    }

    private List<FloorMapObject> parseRows(final TableResult tableResult) {
        final List<FloorMapObject> list = new ArrayList<>();

        if (tableResult == null || tableResult.getRows() == null || tableResult.getColumns() == null) {
            return list;
        }

        final List<Column> columns = tableResult.getColumns();
        int entityColIndex = -1;
        int locationColIndex = -1;
        int typeColIndex = -1;

        // Find the index of the columns selected by the user in the UI dropdowns.
        for (int i = 0; i < columns.size(); i++) {
            final Column col = columns.get(i);

            if (col.getName().equals(currentEntityColumn)) {
                entityColIndex = i;
            } else if (col.getName().equals(currentLocationColumn)) {
                locationColIndex = i;
            } else if (col.getName().equalsIgnoreCase("type")) {
                typeColIndex = i;
            }
        }

        if (entityColIndex == -1 || locationColIndex == -1) {
            return list; // Columns are not mapped yet.
        }

        for (final Row row : tableResult.getRows()) {
            final List<String> values = row.getValues();
            if (values.size() > entityColIndex && values.size() > locationColIndex) {
                final String entityId = values.get(entityColIndex);
                final String locationStr = values.get(locationColIndex);

                if (entityId != null && locationStr != null) {
                    try {
                        // Location coordinates from lookups are formatted as: mapA, x, y".
                        final String[] parts = locationStr.split(",");

                        if (parts.length >= 3) {
                            final double x = Double.parseDouble(parts[1].trim());
                            final double y = Double.parseDouble(parts[2].trim());

                            String type = "object";
                            if (typeColIndex != -1 && values.size() > typeColIndex) {
                                type = values.get(typeColIndex);
                            }    else if (entityId.contains("@")) {
                                type = "person"; // Fallback: email contains "@" = person.
                            }

                            list.add(new FloorMapObject(entityId, type, x, y));
                        }
                    } catch (final RuntimeException e) {
                        // Skip malformed row.
                    }
                }
            }
        }

        return list;
    }

    public void read(final FloorMapDoc doc) {
        read(doc.asDocRef(), doc.getQuery(), doc.getQueryTimeRange(), doc.getQueryTablePreferences(),
                doc.getEntityIdColumn(), doc.getLocationIdColumn(), true);
    }

    public void read(final DocRef docRef,
                     final String query,
                     final TimeRange timeRange,
                     final QueryTablePreferences queryTablePreferences,
                     final String entityIdColumn,
                     final String locationIdColumn,
                     final boolean showColumnMappings) {
        this.currentEntityColumn = entityIdColumn;
        this.currentLocationColumn = locationIdColumn;

        getView().setEntityIdColumn(currentEntityColumn);
        getView().setLocationIdColumn(currentLocationColumn);
        getView().setColumnMappingsVisible(showColumnMappings);

        // Populate the inner query editor.
        queryEditPresenter.setQuery(docRef, query, false);
        queryEditPresenter.setTimeRange(timeRange);
        queryEditPresenter.read(queryTablePreferences);

        updateColumnSelections();
    }

    public FloorMapDoc write(final FloorMapDoc doc) {
        this.currentEntityColumn = getView().getEntityIdColumn();
        this.currentLocationColumn = getView().getLocationIdColumn();

        return doc.copy()
                .entityIdColumn(currentEntityColumn)
                .locationIdColumn(currentLocationColumn)
                .query(queryEditPresenter.getQuery())
                .queryTimeRange(queryEditPresenter.getTimeRange())
                .queryTablePreferences(queryEditPresenter.write())
                .build();
    }

    public String getQuery() {
        return queryEditPresenter.getQuery();
    }

    public TimeRange getQueryTimeRange() {
        return queryEditPresenter.getTimeRange();
    }

    public QueryTablePreferences getQueryTablePreferences() {
        return queryEditPresenter.write();
    }

    public String getEntityIdColumn() {
        return getView().getEntityIdColumn();
    }

    public String getLocationIdColumn() {
        return getView().getLocationIdColumn();
    }

    public void setTaskMonitorFactory(final TaskMonitorFactory taskMonitorFactory) {
        queryEditPresenter.setTaskMonitorFactory(taskMonitorFactory);
    }

    @Override
    public List<Widget> getToolbars() {
        return queryEditPresenter.getToolbars();
    }

    public interface FloorMapQueryView extends View {
        void setQueryEditView(View view);

        void setAvailableColumns(List<String> columnNames);

        void setEntityIdColumn(String entityId);

        void setLocationIdColumn(String locationId);

        String getEntityIdColumn();

        String getLocationIdColumn();

        void setColumnMappingsVisible(boolean visible);
    }
}
