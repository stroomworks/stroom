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
import stroom.floormap.shared.FloorMapJsonKeys;
import stroom.floormap.shared.FloorMapObject;
import stroom.query.api.Column;
import stroom.query.api.Row;
import stroom.query.api.TableResult;
import stroom.query.api.TimeRange;
import stroom.query.client.presenter.QueryEditPresenter;
import stroom.query.shared.QueryTablePreferences;
import stroom.task.client.TaskMonitorFactory;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Widget;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;

/**
 * Presenter for the Floor Map query tab.
 *
 * <p>Embeds a {@link QueryEditPresenter} for authoring and executing StroomQL
 * queries, parses the resulting {@link TableResult} rows into
 * {@link FloorMapObject} lists, and fires {@link FloorMapDataEvent} so the
 * canvas can display the matched entities.  Also provides column-mapping
 * dropdowns that let the user choose which result columns contain the entity
 * ID, location, and type.</p>
 */
public class FloorMapQueryPresenter extends MyPresenterWidget<FloorMapQueryView> implements HasToolbar {

    private final QueryEditPresenter queryEditPresenter;
    private String currentEntityColumn;
    private String currentLocationColumn;

    /**
     * The time-change source (this document's Map-tab timeline) that this query
     * should follow. The event bus is shared across tabs and across any other
     * open FloorMap document, so time-changes from a different source must be
     * ignored. {@code null} until wired — see {@link #setTimeSource}.
     */
    private Object timeSource;

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
        //noinspection unused e
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
            // Only react to this document's own time source. The bus is shared
            // across tabs and across any other open FloorMap document, so an
            // unguarded handler would re-run this query for the Editor tab's
            // timeline or a second document. Until a source is wired we fall
            // back to reacting to all, preserving the previous behaviour.
            if (timeSource != null && e.getSource() != timeSource) {
                return;
            }
            final TimeRange timeRange = new TimeRange(
                    "CUSTOM",
                    String.valueOf(e.getTime()),
                    String.valueOf(e.getTime()));
            queryEditPresenter.setTimeRange(timeRange);
            queryEditPresenter.start();
        }));
    }

    /**
     * Sets the time-change source this query should follow — the owning
     * document's Map-tab timeline. Time-change events from any other source
     * (the Editor tab's timeline, or another open FloorMap document) are
     * ignored.
     *
     * @param timeSource the timeline presenter to follow; may be {@code null}
     */
    public void setTimeSource(final Object timeSource) {
        this.timeSource = timeSource;
    }

    /**
     * Refreshes the entity/location column dropdowns from the latest table
     * columns, preserving the user's current selections where possible.
     */
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

    /**
     * Parses all rows of the supplied {@link TableResult} into
     * {@link FloorMapObject} instances using the currently selected entity and
     * location column mappings.
     *
     * @param tableResult the query result to parse
     * @return a list of map objects; never {@code null}
     */
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
                            } else if (entityId.contains("@")) {
                                type = FloorMapJsonKeys.PERSON; // Fallback: email contains "@" = person.
                            }

                            list.add(new FloorMapObject(entityId, type, x, y));
                        }
                    } catch (final NumberFormatException e) {
                        GWT.log("Skipping malformed floor-map row for entity '"
                                + entityId + "': " + e.getMessage());
                    }
                }
            }
        }

        return list;
    }

    /**
     * Convenience overload that reads all query state from a {@link FloorMapDoc}.
     *
     * @param doc the floor map document to read from
     */
    public void read(final FloorMapDoc doc) {
        read(doc.asDocRef(), doc.getEventsQuery(), doc.getEventsQueryTimeRange(),
                doc.getEventsQueryTablePreferences(),
                doc.getEntityIdColumn(), doc.getLocationIdColumn(), true,
                buildQueryVariables(doc));
    }

    /**
     * Populates the query editor and column-mapping dropdowns from the supplied
     * parameters.
     *
     * @param docRef               the document reference for the query context
     * @param query                the StroomQL query text
     * @param timeRange            the time range filter; may be {@code null}
     * @param queryTablePreferences table column preferences; may be {@code null}
     * @param entityIdColumn       the column name for entity IDs; may be {@code null}
     * @param locationIdColumn     the column name for locations; may be {@code null}
     * @param showColumnMappings   {@code true} to show the column-mapping dropdowns
     */
    public void read(final DocRef docRef,
                     final String query,
                     final TimeRange timeRange,
                     final QueryTablePreferences queryTablePreferences,
                     final String entityIdColumn,
                     final String locationIdColumn,
                     final boolean showColumnMappings,
                     final Map<String, String> queryVariables) {
        this.currentEntityColumn = entityIdColumn;
        this.currentLocationColumn = locationIdColumn;

        getView().setEntityIdColumn(currentEntityColumn);
        getView().setLocationIdColumn(currentLocationColumn);
        getView().setColumnMappingsVisible(showColumnMappings);

        // Populate the inner query editor.
        queryEditPresenter.setQueryVariables(queryVariables);
        queryEditPresenter.setQuery(docRef, query, false);
        queryEditPresenter.setTimeRange(timeRange);
        queryEditPresenter.read(queryTablePreferences);

        updateColumnSelections();
    }

    /**
     * Writes the current query editor state and column selections back into a
     * copy of the supplied document.
     *
     * @param doc the document to update
     * @return a new document copy with the query state applied
     */
    public FloorMapDoc write(final FloorMapDoc doc) {
        this.currentEntityColumn = getView().getEntityIdColumn();
        this.currentLocationColumn = getView().getLocationIdColumn();

        return doc.copy()
                .entityIdColumn(currentEntityColumn)
                .locationIdColumn(currentLocationColumn)
                .eventsQuery(queryEditPresenter.getQuery())
                .eventsQueryTimeRange(queryEditPresenter.getTimeRange())
                .eventsQueryTablePreferences(queryEditPresenter.write())
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

    /**
     * Builds the query parameter map from a {@link FloorMapDoc}'s store references.
     * Parameters {@code FactStore} and {@code EventStore} are mapped to the
     * store names so that {@code param('FactStore')} and {@code param('EventStore')}
     * references in queries resolve correctly.
     *
     * @param doc the floor map document; never null
     * @return a parameter map, possibly empty but never null
     */
    public static Map<String, String> buildQueryVariables(final FloorMapDoc doc) {
        final Map<String, String> vars = new HashMap<>();
        if (doc.getFactsStoreRef() != null && doc.getFactsStoreRef().getName() != null) {
            vars.put("FactStore", doc.getFactsStoreRef().getName());
        }
        if (doc.getEventsStoreRef() != null && doc.getEventsStoreRef().getName() != null) {
            vars.put("EventStore", doc.getEventsStoreRef().getName());
        }
        return vars;
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
