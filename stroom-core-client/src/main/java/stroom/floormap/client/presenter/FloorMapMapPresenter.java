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
 *     <li>{@link #ENTITY_LIST} – the {@link FloorMapEntityListPresenter} (entity tracking panel)</li>
 *     <li>{@link #TIMELINE} – the {@link FloorMapTimelinePresenter} (timeline scrubber)</li>
 * </ul>
 *
 * <p>Two separate {@link QueryModel} instances are maintained: one for the facts query
 * playback and one for the histogram (events) query that populates the timeline density
 * bars.</p>
 */
public class FloorMapMapPresenter
        extends DocPresenter<FloorMapMapView, FloorMapDoc>
        implements HasToolbar {

    public static final Object MAP = new Object();
    public static final Object ENTITY_LIST = new Object();
    public static final Object TIMELINE = new Object();
    private static final int HISTOGRAM_BINS = 100;

    private final FloorMapCanvasPresenter floorMapCanvasPresenter;
    private final FloorMapTimelinePresenter floorMapTimelinePresenter;
    private final FloorMapObjectEditPresenter floorMapObjectEditPresenter;
    private final FloorMapEntityListPresenter floorMapEntityListPresenter;

    /** Roster of every entity seen on the map, feeding the tracking panel. */
    private final FloorMapEntityList entityList = new FloorMapEntityList();

    private final QueryModel queryModel;
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

    private long selectedTime;

    /**
     * True once the timeline range has been initialised by the first document
     * read. Saving the document triggers a re-read of every tab, and the
     * timeline must not be re-initialised then — it would silently discard
     * the user's chosen range (e.g. after "Show All").
     */
    private boolean timelineInitialised;

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
                                final Provider<FloorMapEntityListPresenter> floorMapEntityListPresenterProvider) {
        super(eventBus, view);

        this.floorMapCanvasPresenter = floorMapCanvasPresenterProvider.get();
        this.floorMapTimelinePresenter = floorMapTimelinePresenterProvider.get();
        this.floorMapObjectEditPresenter = floorMapObjectEditPresenterProvider.get();
        this.floorMapEntityListPresenter = floorMapEntityListPresenterProvider.get();

        // Default initial time
        this.selectedTime = System.currentTimeMillis();

        setInSlot(MAP, floorMapCanvasPresenter);
        setInSlot(ENTITY_LIST, floorMapEntityListPresenter);
        setInSlot(TIMELINE, floorMapTimelinePresenter);

        // Grid on/off toggle, surfaced next to the save buttons (HasToolbar).
        // SvgImage has no dedicated grid glyph; TABLE renders as a grid of cells.
        showGridButton = new InlineSvgToggleButton();
        showGridButton.setSvg(SvgImage.TABLE);
        showGridButton.setTitle("Show Grid");
        showGridButton.setState(false);

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

        // ---- Toolbar ---------------------------------------------------------
        //noinspection unused e
        registerHandler(showGridButton.addClickHandler(e ->
                floorMapCanvasPresenter.setShowGrid(showGridButton.getState())));

        // Only react to this tab's own timeline — the Editor tab has its own
        // timeline firing the same event type, and the tabs must not time-sync.
        registerHandler(getEventBus().addHandler(TimeChangeEvent.getType(), e -> {
            if (e.getSource() == floorMapTimelinePresenter) {
                onTimeChange(e.getTime());
            }
        }));
        registerHandler(getEventBus().addHandler(FloorMapDataEvent.getType(), e -> {
            floorMapCanvasPresenter.setEventObjects(e.getObjects());
            // Keep the tracking panel's roster up to date. Only re-push grid
            // data when membership actually changed so playback refreshes
            // (~300ms apart) don't churn the grid. Restoring the selected id
            // does not re-fire the selection consumer because EntityEntry
            // equality is id-based.
            if (entityList.update(e.getObjects())) {
                final String selectedId = floorMapEntityListPresenter.getSelectedId();
                floorMapEntityListPresenter.setData(entityList.getEntities());
                floorMapEntityListPresenter.setSelected(selectedId);
            }
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
        // panel and starts (or resumes) following it. Ids not in the roster
        // (static facts such as gates) are ignored.
        registerHandler(getEventBus().addHandler(MapObjectSelectedEvent.getType(), e -> {
            if (e.getSource() == floorMapCanvasPresenter
                    && e.getObjectId() != null
                    && entityList.contains(e.getObjectId())) {
                floorMapEntityListPresenter.setSelected(e.getObjectId());
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
        this.floorMapEntityListPresenter.setSelectionConsumer(entry ->
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

        // A (re-)opened document starts with a fresh entity roster.
        entityList.clear();
        floorMapEntityListPresenter.setData(Collections.emptyList());

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

                factsByKey.put(key, new Fact(key, type, img, worldToMap, new double[]{worldX, worldY}));
            }
        }

        final List<Fact> facts = new ArrayList<>(factsByKey.values());

        // Facts paint in the configured type z-order (order backgrounds first on
        // the Settings tab so they sit behind); events draw on top.
        floorMapCanvasPresenter.setTypeStyles(getEntity().getTypeStyles());
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
     * Returns this tab's timeline presenter, used as the time-change source
     * that the events Query tab should follow (so it ignores time-changes from
     * the Editor tab or from other open FloorMap documents).
     *
     * @return the Map tab's timeline presenter
     */
    public FloorMapTimelinePresenter getTimelinePresenter() {
        return floorMapTimelinePresenter;
    }

    public interface FloorMapMapView extends View {

    }
}
