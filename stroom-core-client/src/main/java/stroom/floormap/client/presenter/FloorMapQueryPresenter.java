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
import stroom.entity.client.presenter.HasClose;
import stroom.entity.client.presenter.HasToolbar;
import stroom.floormap.client.event.FloorMapDataEvent;
import stroom.floormap.client.presenter.FloorMapQueryPresenter.FloorMapQueryView;
import stroom.floormap.shared.FloorMapDoc;
import stroom.floormap.shared.FloorMapJsonKeys;
import stroom.floormap.shared.FloorMapLocationResolver;
import stroom.floormap.shared.FloorMapObject;
import stroom.query.api.Column;
import stroom.query.api.Row;
import stroom.query.api.TableResult;
import stroom.query.api.TimeRange;
import stroom.query.client.presenter.QueryEditPresenter;
import stroom.query.client.presenter.QueryResultTablePresenter;
import stroom.query.shared.QueryTablePreferences;
import stroom.task.client.TaskMonitorFactory;

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
public class FloorMapQueryPresenter
        extends MyPresenterWidget<FloorMapQueryView>
        implements HasToolbar, HasClose {

    private final QueryEditPresenter queryEditPresenter;
    private String currentEntityColumn;
    private String currentLocationColumn;
    /** UUID of the document being queried, stamped onto {@link FloorMapDataEvent}. */
    private String docUuid;
    /** {@code true} while this tab's query is running — see {@link #onBind()}. */
    private boolean searching;

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

        // Listen to table data updates and fire FloorMapDataEvent.
        //
        // TableUpdateEvent is fired on the shared event bus by every query result
        // table in the application, so the source guard is what keeps an unrelated
        // table's update (a Dashboard, another Query document) from republishing
        // this tab's last result set as the map overlay.
        final QueryResultTablePresenter tablePresenter =
                queryEditPresenter.getQueryResultPresenter().getTablePresenter();
        registerHandler(tablePresenter.addUpdateHandler(e -> {
            if (e.getSource() != tablePresenter) {
                return;
            }
            // The dropdowns track whatever columns the table currently has, so
            // they follow every update, partial result set or not.
            updateColumnSelections();

            // The overlay does not — see the search-state listener below.
            if (!searching) {
                publishMapObjects();
            }
        }));

        // Queries here run incrementally: the result table is updated on every
        // poll of a still-filling store, and at high row counts those partial
        // result sets differ from one poll to the next. Publishing each of them
        // walked the map's entities across the floor for as long as the search
        // ran — visibly so with the timeline paused, which places entities
        // instantly. The searching-to-idle transition is the only point at which
        // the rows are the answer to the query rather than a snapshot of
        // progress, so that is when the overlay is published.
        registerHandler(queryEditPresenter.addSearchStateListener(searching -> {
            // Only the running-to-idle transition, so that the reset at the start
            // of the next run — which also reports "not searching" — does not
            // republish the result set the previous run left behind.
            final boolean finished = this.searching && !searching;
            this.searching = searching;
            if (finished) {
                updateColumnSelections();
                publishMapObjects();
            }
        }));

        // This tab does NOT follow the timeline. The playback events query is
        // owned by FloorMapMapPresenter, because this presenter is created
        // lazily (only when its tab is first opened) and the Map tab's animated
        // entity overlay must not depend on that. Running it here as well would
        // fire a second, identical query per playback tick.
    }

    /**
     * Stops this tab's query when the document is closed.
     *
     * <p>Without this the search outlives the document: the result store is left
     * on the server, the client keeps polling it, and each response still fires a
     * {@link FloorMapDataEvent} stamped with this document's UUID — which a
     * <em>reopened</em> copy of the same document accepts as live entity data.
     * Reachable only because this presenter declares {@link HasClose}; {@code
     * AbstractTabProvider} forwards the close hook to nothing else.</p>
     */
    @Override
    public void onClose() {
        queryEditPresenter.onClose();
    }

    /**
     * Parses the current result table into map objects and publishes them as the
     * canvas entity overlay.
     *
     * <p>Only ever called for a finished result set (see {@link #onBind()}).</p>
     */
    private void publishMapObjects() {
        final TableResult tableResult = queryEditPresenter.getQueryResultPresenter()
                .getTablePresenter()
                .getCurrentTableResult();

        if (tableResult != null) {
            final List<FloorMapObject> objects = parseRows(
                    tableResult, currentEntityColumn, currentLocationColumn);
            FloorMapDataEvent.fire(FloorMapQueryPresenter.this, docUuid, objects);
        }
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
     * {@link FloorMapObject} instances using the given entity and location
     * column mappings.
     *
     * <p>Static and package-visible because two callers run the same events
     * query: this editor tab, and {@link FloorMapMapPresenter}, which owns the
     * timeline-driven playback query feeding the animated entity overlay.</p>
     *
     * <p>The location column may hold either literal {@code map, x, y}
     * coordinates or a reference to the fact the event happened at; the
     * returned objects are only <em>positioned</em> in the first case. The
     * referencing ones carry a {@link FloorMapObject#getLocationRef()} and must
     * be run through {@link FloorMapLocationResolver#resolve} against the
     * current facts before they are drawn.</p>
     *
     * @param tableResult    the query result to parse
     * @param entityColumn   the column name holding the entity id
     * @param locationColumn the column name holding the location — {@code map,
     *                       x, y} coordinates or a fact key
     * @return a list of map objects; never {@code null}
     */
    static List<FloorMapObject> parseRows(final TableResult tableResult,
                                          final String entityColumn,
                                          final String locationColumn) {
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

            if (col.getName().equals(entityColumn)) {
                entityColIndex = i;
            } else if (col.getName().equals(locationColumn)) {
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
                    String type = "object";
                    if (typeColIndex != -1 && values.size() > typeColIndex) {
                        type = values.get(typeColIndex);
                    } else if (entityId.contains("@")) {
                        type = FloorMapJsonKeys.PERSON; // Fallback: email contains "@" = person.
                    }

                    // The location is either coordinates baked into the event at
                    // ingest ("mapA, x, y") or a reference to the fact the event
                    // happened at. A reference is left for
                    // FloorMapLocationResolver to place against the current
                    // facts, which is what lets a moved object take its visitors
                    // with it — baked coordinates cannot.
                    final double[] coords = FloorMapLocationResolver.parseCoordinates(locationStr);
                    if (coords != null) {
                        list.add(new FloorMapObject(entityId, type, coords[0], coords[1]));
                    } else {
                        final String ref = FloorMapLocationResolver.parseReference(locationStr);
                        if (ref != null) {
                            final FloorMapObject object = new FloorMapObject(entityId, type, 0, 0);
                            object.setLocationRef(ref);
                            list.add(object);
                        }
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
        this.docUuid = docRef != null ? docRef.getUuid() : null;
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
