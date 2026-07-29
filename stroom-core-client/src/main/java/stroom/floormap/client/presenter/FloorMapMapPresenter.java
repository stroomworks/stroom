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
import stroom.entity.client.presenter.HasToolbar;
import stroom.floormap.client.event.FloorMapDataEvent;
import stroom.floormap.client.event.MapObjectSelectedEvent;
import stroom.floormap.client.event.TimeChangeEvent;
import stroom.floormap.client.presenter.FloorMapMapPresenter.FloorMapMapView;
import stroom.floormap.shared.Fact;
import stroom.floormap.shared.FloorMapAreaMembership;
import stroom.floormap.shared.FloorMapDoc;
import stroom.floormap.shared.FloorMapEntityList;
import stroom.floormap.shared.FloorMapEntryParser;
import stroom.floormap.shared.FloorMapFieldMapping;
import stroom.floormap.shared.FloorMapFieldMapping.Role;
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
import stroom.svg.shared.SvgImage;
import stroom.util.client.Console;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.InlineSvgToggleButton;
import stroom.widget.histogram.client.HistogramDataModel;
import stroom.widget.histogram.client.HistogramQueryHelper;

import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
 *     <li>{@link #DOCK} – the {@link FloorMapDockPresenter} (right-hand dock; hosts the
 *     {@link FloorMapTrackingPresenter} tracking panel as its first tab)</li>
 *     <li>{@link #TIMELINE} – the {@link FloorMapTimelinePresenter} (timeline scrubber)</li>
 * </ul>
 *
 * <p>Three {@link QueryModel}-based searches are maintained: the facts query playback,
 * the events query playback (the entity overlay the canvas animates), and the histogram
 * query that populates the timeline density bars.</p>
 */
public class FloorMapMapPresenter
        extends DocPresenter<FloorMapMapView, FloorMapDoc>
        implements HasToolbar {

    public static final Object MAP = new Object();
    public static final Object DOCK = new Object();
    public static final Object TIMELINE = new Object();
    private static final int HISTOGRAM_BINS = 100;

    private final FloorMapCanvasPresenter floorMapCanvasPresenter;
    private final FloorMapTimelinePresenter floorMapTimelinePresenter;
    private final FloorMapObjectEditPresenter floorMapObjectEditPresenter;
    private final FloorMapTrackingPresenter floorMapTrackingPresenter;
    private final FloorMapLayersPresenter floorMapLayersPresenter;

    /** Roster of every entity seen on the map, feeding the tracking panel. */
    private final FloorMapEntityList entityList = new FloorMapEntityList();

    /**
     * The latest facts and event entities, kept so area containment can be
     * recomputed when either side refreshes (the two queries refresh
     * independently). See {@link #updateAreaMembership()}.
     */
    private List<Fact> lastFacts;
    private List<FloorMapObject> lastEventObjects;

    private final QueryModel queryModel;

    /**
     * Runs the document's events query at the selected time, producing the
     * entity overlay that {@link FloorMapCanvasPresenter} animates.
     *
     * <p>The Map tab owns this query rather than {@link FloorMapQueryPresenter}
     * (the Events Query tab): that presenter is created lazily, the first time
     * its tab is opened, so a Map tab depending on it showed no live entities at
     * all — and therefore no movement animation — until the user happened to
     * visit another tab.</p>
     */
    private final QueryModel eventsQueryModel;

    private final HistogramQueryHelper histogramQueryHelper;
    private final HistogramQueryHelper factsHistogramQueryHelper;
    private final HistogramDataModel histogramDataModel;

    /**
     * Toolbar toggle controlling the canvas grid overlay. Shown next to the
     * document save buttons via {@link HasToolbar} whenever the Map tab is
     * active. Off by default — the Map tab is view-focused, so the grid is
     * opt-in (unlike the Editor tab, where it defaults on).
     */
    private final InlineSvgToggleButton showGridButton;

    /**
     * Toolbar toggle that shows/hides the right-hand dock. On by default on the
     * Map tab, which always has the Tracking panel to show.
     */
    private final InlineSvgToggleButton dockToggleButton;

    private long selectedTime;

    /**
     * True once the timeline range has been initialised by the first document
     * read. Saving the document triggers a re-read of every tab, and the
     * timeline must not be re-initialised then — it would silently discard
     * the user's chosen range (e.g. after "Show All").
     */
    private boolean timelineInitialised;

    /**
     * UUID of the document this Map is showing, used to ignore
     * {@link FloorMapDataEvent}s fired by other open FloorMap documents on the
     * shared event bus (which would otherwise render another doc's entities here).
     */
    private String docUuid;

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
                                final Provider<FloorMapTrackingPresenter> floorMapEntityListPresenterProvider,
                                final Provider<FloorMapLayersPresenter> floorMapLayersPresenterProvider,
                                final Provider<FloorMapDockPresenter> floorMapDockPresenterProvider) {
        super(eventBus, view);

        this.floorMapCanvasPresenter = floorMapCanvasPresenterProvider.get();
        this.floorMapTimelinePresenter = floorMapTimelinePresenterProvider.get();
        this.floorMapObjectEditPresenter = floorMapObjectEditPresenterProvider.get();
        this.floorMapTrackingPresenter = floorMapEntityListPresenterProvider.get();
        this.floorMapLayersPresenter = floorMapLayersPresenterProvider.get();
        final FloorMapDockPresenter floorMapDockPresenter = floorMapDockPresenterProvider.get();

        // Default initial time
        this.selectedTime = System.currentTimeMillis();

        // The Tracking and Layers panels live as tabs of the right-hand dock.
        floorMapDockPresenter.addTab("Tracking", floorMapTrackingPresenter);
        floorMapDockPresenter.addTab("Layers", floorMapLayersPresenter);
        // Layer visibility is a transient view control on the Map tab; push
        // changes to the canvas as hidden / dimmed type sets.
        floorMapLayersPresenter.setChangeHandler(() ->
                floorMapCanvasPresenter.setLayerVisibility(
                        floorMapLayersPresenter.getHiddenTypes(),
                        floorMapLayersPresenter.getDimmedTypes()));

        setInSlot(MAP, floorMapCanvasPresenter);
        setInSlot(DOCK, floorMapDockPresenter);
        setInSlot(TIMELINE, floorMapTimelinePresenter);

        // Grid on/off toggle, surfaced next to the save buttons (HasToolbar).
        // SvgImage has no dedicated grid glyph; TABLE renders as a grid of cells.
        showGridButton = new InlineSvgToggleButton();
        showGridButton.setSvg(SvgImage.TABLE);
        showGridButton.setTitle("Toggle Grid");
        showGridButton.setState(false);

        // Show/hide the right-hand dock
        dockToggleButton = new InlineSvgToggleButton();
        dockToggleButton.setSvg(SvgImage.SHOW_MENU);
        dockToggleButton.setTitle("Toggle Controls");
        dockToggleButton.setState(true);

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

        // Result component to parse and handle Events query results — the entity
        // overlay that gets animated during playback.
        final ResultComponent eventsResultConsumer = new ResultComponent() {
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
                    publishEventEntities(tableResult);
                }
            }

            @Override
            public void setQueryModel(final QueryModel queryModel) {}
        };

        this.eventsQueryModel = new QueryModel(
                eventBus,
                restFactory,
                dateTimeSettingsFactory,
                resultStoreModel,
                () -> QueryTablePreferences.builder().build());
        this.eventsQueryModel.addResultComponent(
                QueryModel.TABLE_COMPONENT_ID, eventsResultConsumer);

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

        // ---- Toolbar ---------------------------------------------------------
        //noinspection unused e
        registerHandler(showGridButton.addClickHandler(e ->
                floorMapCanvasPresenter.setShowGrid(showGridButton.getState())));
        //noinspection unused e
        registerHandler(dockToggleButton.addClickHandler(e ->
                getView().setDockVisible(dockToggleButton.getState())));

        // Only react to this tab's own timeline — the Editor tab has its own
        // timeline firing the same event type, and the tabs must not time-sync.
        registerHandler(getEventBus().addHandler(TimeChangeEvent.getType(), e -> {
            if (e.getSource() == floorMapTimelinePresenter) {
                onTimeChange(e.getTime());
            }
        }));
        registerHandler(getEventBus().addHandler(FloorMapDataEvent.getType(), e -> {
            // Fired by this tab's own events query (see publishEventEntities) and,
            // while it is open, by the Events Query tab as the user edits/runs the
            // query. Ignore events from other open FloorMap documents (shared bus).
            if (!java.util.Objects.equals(docUuid, e.getDocUuid())) {
                return;
            }
            floorMapCanvasPresenter.setEventObjects(e.getObjects());
            // Keep the tracking panel's roster up to date. Only re-push grid
            // data when membership actually changed so playback refreshes
            // (~300ms apart) don't churn the grid.
            if (entityList.update(e.getObjects())) {
                refreshEntityGrid();
            }
            // Entities have moved, so which areas they are in may have changed.
            lastEventObjects = e.getObjects();
            updateAreaMembership();
        }));

        // Re-run the histogram whenever the user changes the visible date range via the settings popup.
        floorMapTimelinePresenter.setTimeRangeChangeHandler(() ->
                runHistogramQuery(floorMapTimelinePresenter.getStartTime(),
                        floorMapTimelinePresenter.getEndTime()));

        // Canvas events are fired on the shared event bus by every FloorMap
        // canvas instance (this tab, the Editor tab, and any other open
        // FloorMap document), so each handler must ignore events from
        // canvases other than its own — without the source guard a selection
        // on the Editor tab's canvas would change this tab's tracking state.
        //
        // Clicking an entity on this tab's canvas selects it in the tracking
        // panel and starts (or resumes) following it. The roster holds both
        // event entities and static facts; ids not (yet) in it are ignored.
        // The canvas never fires this for backgrounds or areas — their
        // clickable surface can cover most of the map, so a press over them
        // must stay a pan — but they can still be tracked from the panel.
        registerHandler(getEventBus().addHandler(MapObjectSelectedEvent.getType(), e -> {
            if (e.getSource() == floorMapCanvasPresenter
                    && e.getObjectId() != null
                    && entityList.contains(e.getObjectId())) {
                floorMapTrackingPresenter.setSelected(e.getObjectId());
                floorMapCanvasPresenter.setTrackedObjectId(e.getObjectId());
            }
        }));


        // Drag-editing is performed on the Editor tab; the Map tab is view-focused,
        // so no drag handler is installed here.

        // Selecting an entity in the tracking panel highlights it on the
        // canvas, centres the camera on it, and follows it as it moves;
        // selecting nothing stops tracking. Re-clicking the selected row
        // re-invokes this consumer, which re-centres and resumes following
        // after a manual pan paused it.
        this.floorMapTrackingPresenter.setSelectionConsumer(entry ->
                floorMapCanvasPresenter.setTrackedObjectId(entry != null
                        ? entry.getId()
                        : null));

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
     * Returns the Map tab's toolbar widgets. {@link FloorMapPresenter}'s
     * base class ({@code DocTabPresenter}) appends these after the document
     * save buttons whenever this tab is selected.
     */
    @Override
    public List<Widget> getToolbars() {
        final ButtonPanel toolbar = new ButtonPanel();
        toolbar.addButton(showGridButton);
        toolbar.addButton(dockToggleButton);
        return Collections.singletonList(toolbar);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Initialises and resets both the facts and histogram {@link QueryModel} instances,
     * configures the object edit presenter with the document's store reference, then starts
     * the timeline and triggers an initial time-change to load facts. The timeline range is
     * only initialised on the first read; save-triggered re-reads preserve it.</p>
     */
    @Override
    protected void onRead(final DocRef docRef, final FloorMapDoc document, final boolean readOnly) {
        this.docUuid = docRef != null ? docRef.getUuid() : null;
        // Initialise and reset every query model BEFORE starting any searches, so that the histogram query
        // started inside updateTimelineRange() is not immediately cancelled by the reset() calls below.
        queryModel.init(docRef);
        queryModel.reset(DestroyReason.NO_LONGER_NEEDED);
        eventsQueryModel.init(docRef);
        eventsQueryModel.reset(DestroyReason.NO_LONGER_NEEDED);
        histogramQueryHelper.init(docRef);
        histogramQueryHelper.reset();
        factsHistogramQueryHelper.init(docRef);
        factsHistogramQueryHelper.reset();

        if (document.getFactsStoreRef() != null) {
            floorMapObjectEditPresenter.setMapName(document.getFactsStoreRef().getName());
        }
        floorMapObjectEditPresenter.setFloorMapDoc(document);

        // A (re-)opened document starts with a fresh entity roster and no
        // inherited area containment.
        entityList.clear();
        floorMapTrackingPresenter.setData(Collections.emptyList());
        lastFacts = null;
        lastEventObjects = null;
        floorMapTrackingPresenter.clearAreaState();
        floorMapCanvasPresenter.setAreaMembership(FloorMapAreaMembership.EMPTY);

        // Populate the Layers panel from the document's type styles and sync the
        // canvas with the current (transient) layer visibility.
        floorMapLayersPresenter.setLayers(document.getTypeStyles());
        floorMapCanvasPresenter.setLayerVisibility(
                floorMapLayersPresenter.getHiddenTypes(),
                floorMapLayersPresenter.getDimmedTypes());

        // Start timeline (and histogram query) only after models are ready.
        // Initialise the range on the first read only; on a save-triggered
        // re-read, keep the user's range and current position but still
        // re-run the histogram query in case the settings change altered
        // the underlying queries or stores.
        if (!timelineInitialised) {
            timelineInitialised = true;
            updateTimelineRange();
        } else {
            runHistogramQuery(floorMapTimelinePresenter.getStartTime(),
                    floorMapTimelinePresenter.getEndTime());
        }
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
     * Responds to a timeline time-change event. Runs both StroomQL queries at
     * the selected time via their {@link QueryModel}s: the facts query (static
     * floor-plan content) and the events query (the entity overlay that the
     * canvas animates between positions).
     *
     * @param time the new selected time in milliseconds since epoch
     */
    private void onTimeChange(final long time) {
        this.selectedTime = time;
        runQueryAtSelectedTime(queryModel, getFactsQueryToUse(),
                "factsTable", "Facts Query Playback");
        runQueryAtSelectedTime(eventsQueryModel, getEventsQueryToUse(),
                "eventsTable", "Events Query Playback");
    }

    /**
     * Starts a search for the given query text at {@link #selectedTime}, as an
     * instant (start == end) custom time range. A {@code null}/blank query is a
     * no-op.
     *
     * @param model         the query model to run the search on
     * @param query         the StroomQL query text; may be {@code null} or blank
     * @param componentName the table component name for the search
     * @param taskName      the task name shown in the task monitor
     */
    private void runQueryAtSelectedTime(final QueryModel model,
                                        final String query,
                                        final String componentName,
                                        final String taskName) {
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        final TimeRange timeRange =
                new TimeRange("CUSTOM", String.valueOf(selectedTime), String.valueOf(selectedTime));
        model.startNewSearch(
                QueryModel.TABLE_COMPONENT_ID,
                componentName,
                // Resolve param('FactStore') / param('EventStore') references in
                // the query text so the from-clause resolves correctly.
                resolveQueryParams(query),
                queryParams(),
                timeRange,
                false,
                false,
                taskName,
                null
        );
    }

    /**
     * The document's store references as query {@link Param}s, matching the
     * substitutions {@link #resolveQueryParams(String)} makes in the text.
     *
     * @return the params, or {@code null} when the document declares none
     */
    private List<Param> queryParams() {
        final Map<String, String> vars =
                FloorMapQueryPresenter.buildQueryVariables(getEntity());
        if (vars.isEmpty()) {
            return null;
        }
        final List<Param> params = new ArrayList<>(vars.size());
        for (final Map.Entry<String, String> entry : vars.entrySet()) {
            params.add(new Param(entry.getKey(), entry.getValue()));
        }
        return params;
    }

    /**
     * Returns the document's events query — the entity locations over time that
     * become the animated overlay.
     *
     * @return the StroomQL query text, or {@code null} if none is configured
     */
    private String getEventsQueryToUse() {
        return getEntity() != null ? getEntity().getEventsQuery() : null;
    }

    /**
     * Parses an events query result into entities and publishes them on the
     * shared event bus — the single channel into the canvas overlay, the tracking
     * roster and the Editor's layer discovery (see the
     * {@link FloorMapDataEvent} handler in {@link #onBind()}).
     *
     * @param tableResult the events query result to parse
     */
    private void publishEventEntities(final TableResult tableResult) {
        if (getEntity() == null) {
            return;
        }
        FloorMapDataEvent.fire(this, docUuid, FloorMapQueryPresenter.parseRows(
                tableResult,
                getEntity().getEntityIdColumn(),
                getEntity().getLocationIdColumn()));
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
        int labelIdx = -1;
        int coordsIdx = -1;
        int imgIdx = -1;
        int worldToMapIdx = -1;
        int geometryIdx = -1;
        int fillIdx = -1;
        int opacityIdx = -1;

        final List<Column> columns = tableResult.getColumns();
        if (columns == null) {
            return;
        }

        final stroom.floormap.shared.ValueFormat vf = getEntity().getValueFormat();
        // Area roles are absent from pre-area schemas, so their aliases may be
        // null — matched columns simply stay at -1.
        // All aliases are resolved once, before the loop. Each one costs a linear
        // scan of the schema, so resolving them per column made this O(columns ×
        // schema) for no benefit — the aliases do not vary by column.
        final String typeAlias = columnAliasForRole(Role.TYPE, vf);
        final String positionAlias = columnAliasForRole(Role.POSITION, vf);
        final String imageAlias = columnAliasForRole(Role.IMAGE, vf);
        final String worldToMapAlias = columnAliasForRole(Role.WORLD_TO_MAP, vf);
        final String geometryAlias = columnAliasForRole(Role.GEOMETRY, vf);
        final String fillAlias = columnAliasForRole(Role.FILL, vf);
        final String opacityAlias = columnAliasForRole(Role.OPACITY, vf);
        // LABEL supplies the user-facing area name in the tracking panel. Like
        // the area roles it may be unmapped, so the alias can be null.
        final String labelAlias = columnAliasForRole(Role.LABEL, vf);
        for (int i = 0; i < columns.size(); i++) {
            final String colName = columns.get(i).getName();
            if (colName.equalsIgnoreCase("Key")) {
                keyIdx = i;
            } else if (colName.equalsIgnoreCase(typeAlias)) {
                typeIdx = i;
            } else if (colName.equalsIgnoreCase(positionAlias)) {
                coordsIdx = i;
            } else if (colName.equalsIgnoreCase(imageAlias)) {
                imgIdx = i;
            } else if (colName.equalsIgnoreCase(worldToMapAlias)) {
                worldToMapIdx = i;
            } else if (colName.equalsIgnoreCase(labelAlias)) {
                labelIdx = i;
            } else if (colName.equalsIgnoreCase(geometryAlias)) {
                geometryIdx = i;
            } else if (colName.equalsIgnoreCase(fillAlias)) {
                fillIdx = i;
            } else if (colName.equalsIgnoreCase(opacityAlias)) {
                opacityIdx = i;
            }
        }

        // The query returns every effective-time shard of every key (rows are in
        // ascending effective-time order), so collapse to one fact per key — a
        // later shard overwrites the earlier one. This shows a single current
        // instance per object rather than every time version at once; distinct
        // keys (e.g. several backgrounds) are preserved.
        final Map<String, Fact> factsByKey = new LinkedHashMap<>();

        if (tableResult.getRows() != null) {
            for (final Row row : tableResult.getRows()) {
                final List<String> values = row.getValues();
                final String key = keyIdx != -1 && values.size() > keyIdx ? values.get(keyIdx) : null;
                final String type = typeIdx != -1 && values.size() > typeIdx ? values.get(typeIdx) : "";
                final String img = imgIdx != -1 && values.size() > imgIdx ? values.get(imgIdx) : null;

                // Every row is a fact placed by its WORLD_TO_MAP matrix — a
                // background is simply an image fact, not a special case.
                double worldX = 0;
                double worldY = 0;
                if (coordsIdx != -1 && values.size() > coordsIdx) {
                    final double[] xy = parseCoords(values.get(coordsIdx));
                    if (xy != null) {
                        worldX = xy[0];
                        worldY = xy[1];
                    }
                }

                FloorMapTransformationMatrix worldToMap = FloorMapTransformationMatrix.identity();
                if (worldToMapIdx != -1 && values.size() > worldToMapIdx) {
                    worldToMap = parseMatrix(values.get(worldToMapIdx));
                }

                final double[][] vertices = geometryIdx != -1 && values.size() > geometryIdx
                        ? parseVertices(values.get(geometryIdx))
                        : null;
                final String fill = fillIdx != -1 && values.size() > fillIdx
                        ? values.get(fillIdx)
                        : null;
                final Double opacity = opacityIdx != -1 && values.size() > opacityIdx
                        ? parseNullableDouble(values.get(opacityIdx))
                        : null;

                final String label = labelIdx != -1 && values.size() > labelIdx
                        ? values.get(labelIdx)
                        : null;

                factsByKey.put(key, new Fact(key, type, img, worldToMap,
                        new double[]{worldX, worldY}, vertices, fill, opacity, label));
            }
        }

        final List<Fact> facts = new ArrayList<>(factsByKey.values());

        // Facts paint in the configured type z-order (order backgrounds first on
        // the Settings tab so they sit behind); events draw on top.
        floorMapCanvasPresenter.setTypeStyles(getEntity().getTypeStyles());
        floorMapCanvasPresenter.setFacts(facts);

        // Static facts (objects, backgrounds, areas) belong in the tracking
        // panel alongside the event entities, so merge them into the roster.
        if (entityList.updateFacts(facts)) {
            refreshEntityGrid();
        }

        // Areas and static placements may have changed (a new timeline shard),
        // so recompute containment.
        lastFacts = facts;
        updateAreaMembership();
    }

    /**
     * Recomputes which entities are inside which areas at the current timeline
     * instant, then pushes the snapshot to the canvas (containment highlight and
     * occupant badges) and the tracking panel (Area column).
     *
     * <p>Driven by query refreshes — a facts reload or an events refresh — not by
     * animation frames, so the cost is bounded by how often the data changes
     * rather than by the frame rate.</p>
     */
    private void updateAreaMembership() {
        final FloorMapAreaMembership membership =
                FloorMapAreaMembership.compute(lastFacts, lastEventObjects);
        floorMapCanvasPresenter.setAreaMembership(membership);
        floorMapTrackingPresenter.setAreaMembership(membership, this::entityDisplayName);
    }

    /**
     * Resolves any entity id to the name shown in the tracking panel — for an
     * area, its user-facing {@code LABEL} name where it has one.
     *
     * <p>Reads it back out of the roster rather than re-deriving it, so an area
     * named in the Area column always matches the name on its own row. Falls
     * back to the id-derived name for an id the roster has not seen yet (a facts
     * refresh admits every fact, so this is only the very first frame).</p>
     */
    private String entityDisplayName(final String id) {
        final String name = entityList.getDisplayName(id);
        return name != null
                ? name
                : FloorMapEntityList.displayName(id);
    }

    /**
     * Re-pushes the roster into the tracking panel's grid, preserving the
     * current selection. Restoring the selected id does not re-fire the
     * selection consumer because {@code EntityEntry} equality is id-based.
     */
    private void refreshEntityGrid() {
        final String selectedId = floorMapTrackingPresenter.getSelectedId();
        floorMapTrackingPresenter.setData(entityList.getEntities());
        floorMapTrackingPresenter.setSelected(selectedId);
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
     * The query column alias for a role, or {@code null} when the schema does
     * not map the role (e.g. the area roles on a pre-area document).
     */
    private String columnAliasForRole(final Role role,
                                      final stroom.floormap.shared.ValueFormat vf) {
        final String path = pathForRole(role);
        return path != null ? FloorMapQueryBuilder.buildColumnAlias(path, vf) : null;
    }

    /**
     * Parses a flat comma-separated area geometry string
     * {@code "[x0, y0, x1, y1, ...]"} into vertex pairs. Returns {@code null}
     * for a missing/short array (fewer than 3 vertices); a trailing odd value
     * is ignored — matching {@code FloorMapEntryParser}'s behaviour.
     */
    private double[][] parseVertices(final String str) {
        if (str == null || str.trim().isEmpty()) {
            return null;
        }
        try {
            final String clean = str.replace("[", "").replace("]", "").replace("\"", "");
            final String[] parts = clean.split(",");
            final int count = parts.length / 2;
            if (count < 3) {
                return null;
            }
            final double[][] vertices = new double[count][];
            for (int i = 0; i < count; i++) {
                vertices[i] = new double[]{
                        Double.parseDouble(parts[i * 2].trim()),
                        Double.parseDouble(parts[i * 2 + 1].trim())};
            }
            return vertices;
        } catch (final Exception e) {
            Console.error("Failed to parse geometry string: " + str, e);
            return null;
        }
    }

    /**
     * Parses a double, returning {@code null} for blank or unparseable input.
     */
    private Double parseNullableDouble(final String str) {
        if (str == null || str.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(str.trim());
        } catch (final NumberFormatException e) {
            return null;
        }
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
        // Resolve param('EventStore')/param('FactStore') in the text: the
        // histogram helpers run with no params, so an unresolved from-clause
        // would make the query fail and leave the density bars empty.
        final String eventsHistQuery = resolveQueryParams(buildEventsHistogramQuery());
        if (eventsHistQuery != null && !eventsHistQuery.trim().isEmpty()) {
            histogramQueryHelper.run(eventsHistQuery);
        } else {
            // No events query configured — fall back to the facts query.
            final String factsHistQuery = resolveQueryParams(getFactsQueryToUse());
            if (factsHistQuery != null && !factsHistQuery.trim().isEmpty()) {
                factsHistogramQueryHelper.run(factsHistQuery);
            }
        }
    }

    /**
     * Resolves {@code param('X')} references (e.g. {@code param('EventStore')},
     * {@code param('FactStore')}) against the configured store names, mirroring
     * the substitution done for the playback query in {@link #onTimeChange}.
     *
     * @param query the raw query text; may be {@code null}
     * @return the query with param references substituted, or the original
     *         value if it (or the entity) is {@code null}
     */
    private String resolveQueryParams(final String query) {
        if (query == null || getEntity() == null) {
            return query;
        }
        final Map<String, String> vars =
                FloorMapQueryPresenter.buildQueryVariables(getEntity());
        String resolved = query;
        for (final Map.Entry<String, String> entry : vars.entrySet()) {
            resolved = resolved.replace(
                    "param('" + entry.getKey() + "')",
                    "\"" + entry.getValue() + "\"");
        }
        return resolved;
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

    /**
     * Re-runs the facts query at the current time so the canvas reflects the
     * latest persisted state.
     *
     * <p>Called by {@link FloorMapPresenter} when the user selects the Map tab
     * and after the Editor tab's staged changes are flushed on save — the Map
     * tab otherwise only re-queries on its own timeline changes, so edits made
     * on the Editor tab (moved objects, new icons/backgrounds) would not appear
     * here until the timeline was next moved.</p>
     */
    public void refresh() {
        if (getEntity() != null) {
            onTimeChange(selectedTime);
        }
    }

    /**
     * Registers a listener notified once with this tab's computed initial view
     * {@code {scale, offsetX, offsetY}}, so the Editor tab can adopt the same
     * initial zoom + translation and nothing jumps on the first tab switch.
     *
     * @param listener the callback, or {@code null} to remove
     */
    public void setInitialViewListener(final java.util.function.Consumer<double[]> listener) {
        floorMapCanvasPresenter.setInitialViewListener(listener);
    }

    public interface FloorMapMapView extends View {

        /**
         * Shows or hides the right-hand dock, preserving its dragged width.
         *
         * @param visible {@code true} to show the dock, {@code false} to hide it
         */
        void setDockVisible(boolean visible);
    }
}
