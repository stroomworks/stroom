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

import stroom.alert.client.event.AlertEvent;
import stroom.alert.client.event.ConfirmEvent;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.shared.ExpressionCriteria;
import stroom.floormap.client.ValueAccessorFactory;
import stroom.floormap.client.event.MapContextMenuEvent;
import stroom.floormap.client.event.MapObjectSelectedEvent;
import stroom.floormap.client.event.TimeChangeEvent;
import stroom.floormap.client.presenter.FloorMapEditorPresenter.FloorMapEditorView;
import stroom.floormap.shared.FloorMapDoc;
import stroom.floormap.shared.FloorMapEditorModel;
import stroom.floormap.shared.FloorMapEntryParser;
import stroom.floormap.shared.FloorMapFieldMapping;
import stroom.floormap.shared.FloorMapFieldMapping.Role;
import stroom.floormap.shared.FloorMapJsonKeys;
import stroom.floormap.shared.FloorMapPendingChanges;
import stroom.floormap.shared.FloorMapTransformationMatrix;
import stroom.floormap.shared.ParsedValue;
import stroom.floormap.shared.ValueAccessor;
import stroom.floormap.shared.ValueFormat;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.sqlstore.shared.ApplyChangesRequest;
import stroom.sqlstore.shared.ApplyChangesResult;
import stroom.sqlstore.shared.ChangeOperation;
import stroom.sqlstore.shared.FetchAtTimeRequest;
import stroom.sqlstore.shared.SqlTemporalStoreResource;
import stroom.sqlstore.shared.TemporalStoreTimeRange;
import stroom.svg.shared.SvgImage;
import stroom.util.client.Console;
import stroom.util.shared.TemporalEntry;
import stroom.util.shared.TemporalEntryId;
import stroom.widget.menu.client.presenter.IconMenuItem;
import stroom.widget.menu.client.presenter.Item;
import stroom.widget.menu.client.presenter.ShowMenuEvent;
import stroom.widget.popup.client.presenter.PopupPosition;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javax.inject.Provider;



/**
 * Presenter for the FloorMap <b>Editor</b> tab.
 *
 * <p>The Editor tab provides a dedicated authoring environment for configuring a
 * {@link FloorMapDoc}. It is <em>always</em> in edit mode and exposes all
 * editing panels simultaneously.</p>
 *
 * <h3>Layout</h3>
 * <pre>
 * ┌──────────────────────────────────────────────────────┐
 * │                  Map Canvas  (MAIN)                  │
 * ├──────────────────────────────────────────────────────┤
 * │              Timeline control (TIMELINE)             │  fixed height
 * ├────────────────────┬────────────────┬────────────────┤
 * │    Fact List       │   Time List    │   Properties   │
 * │   (FACT_LIST)      │  (TIME_LIST)   │  (PROPERTIES)  │
 * └────────────────────┴────────────────┴────────────────┘
 * </pre>
 *
 * <h3>Shared selection model (single source of truth)</h3>
 * <p>All inter-panel state lives here. Child panels signal changes to this
 * presenter only; they never call each other directly.</p>
 *
 * <ul>
 *   <li>{@code selectedFactKey} — key of the selected fact, or {@code null}</li>
 *   <li>{@code selectedTime} — current timeline position in ms</li>
 *   <li>{@code showAllFacts} — whether "show all" mode is active</li>
 *   <li>{@code pendingChanges} — staged edits awaiting flush</li>
 * </ul>
 *
 * <h3>Staged saves</h3>
 * <p>All edits are buffered in {@link FloorMapPendingChanges}. They are flushed
 * via {@link #onSave(FloorMapDoc, Consumer)} as part of the standard
 * Stroom document save chain. On success the buffer is cleared and all panels
 * are reloaded. On failure a top-level error is shown and all panels reload
 * from the server.</p>
 */
public class FloorMapEditorPresenter
        extends DocPresenter<FloorMapEditorView, FloorMapDoc> {

    /** REST endpoint */
    private static final SqlTemporalStoreResource SQL_TEMPORAL_STORE_RESOURCE =
            GWT.create(SqlTemporalStoreResource.class);

    /** One day in ms */
    private static final long ONE_DAY_MS = 24L * 60L * 60L * 1000L;

    // -----------------------------------------------------------------------
    // View slots
    // -----------------------------------------------------------------------

    /** Slot for the interactive map canvas. */
    public static final Object MAIN = new Object();

    /**
     * Slot for the timeline scrubber.
     * Fixed-height — stored in {@code FloorMapEditorViewImpl.TIMELINE_HEIGHT}.
     */
    public static final Object TIMELINE = new Object();

    /** Slot for the Fact List panel (leftmost bottom column). */
    public static final Object FACT_LIST = new Object();

    /** Slot for the Time List panel (centre bottom column). */
    public static final Object TIME_LIST = new Object();

    // -----------------------------------------------------------------------
    // Child presenters
    // -----------------------------------------------------------------------

    private final RestFactory restFactory;
    private final FloorMapCanvasPresenter floorMapCanvasPresenter;
    private final FloorMapTimelinePresenter floorMapTimelinePresenter;
    private final FloorMapFactListPresenter floorMapFactListPresenter;
    private final FloorMapTimeListPresenter floorMapTimeListPresenter;
    private final FloorMapObjectEditPresenter floorMapObjectEditPresenter;

    /** The GWT-free model containing all shared state and pure logic. */
    private final FloorMapEditorModel model;

    // -----------------------------------------------------------------------

    @Inject
    public FloorMapEditorPresenter(final EventBus eventBus,
                                   final FloorMapEditorView view,
                                   final RestFactory restFactory,
                                   final Provider<FloorMapCanvasPresenter> canvasProvider,
                                   final Provider<FloorMapTimelinePresenter> timelineProvider,
                                   final Provider<FloorMapFactListPresenter> factListProvider,
                                   final Provider<FloorMapTimeListPresenter> timeListProvider,
                                   final Provider<FloorMapObjectEditPresenter> propertiesProvider) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.model = new FloorMapEditorModel(
                new java.util.Random(), Console::warn);

        // Each child is obtained via Provider so GIN creates a fresh instance
        // for this presenter rather than sharing singletons used elsewhere.
        this.floorMapCanvasPresenter = canvasProvider.get();
        this.floorMapTimelinePresenter = timelineProvider.get();
        this.floorMapFactListPresenter = factListProvider.get();
        this.floorMapTimeListPresenter = timeListProvider.get();
        this.floorMapObjectEditPresenter = propertiesProvider.get();

        // Always in edit mode, with the grid overlay shown as an editing aid.
        floorMapCanvasPresenter.setEditMode(true);
        floorMapCanvasPresenter.setShowGrid(true);
        // Persist a drag as a single translate of the whole selection.
        floorMapCanvasPresenter.setDragHandler(this::onFactsTranslated);

        setInSlot(MAIN, floorMapCanvasPresenter);
        setInSlot(TIMELINE, floorMapTimelinePresenter);
        setInSlot(FACT_LIST, floorMapFactListPresenter);
        setInSlot(TIME_LIST, floorMapTimeListPresenter);
        // Properties are shown as a modal dialog — no slot needed.
    }

    @Override
    protected void onBind() {
        super.onBind();

        // ---- Timeline events ------------------------------------------------
        registerHandler(getEventBus().addHandler(TimeChangeEvent.getType(), event -> {
            if (event.getSource() == floorMapTimelinePresenter) {
                onTimeChange(event.getTime());
            }
        }));

        // ---- Canvas events --------------------------------------------------
        registerHandler(getEventBus().addHandler(MapObjectSelectedEvent.getType(), event -> {
            if (event.getSource() == floorMapCanvasPresenter) {
                onObjectSelectedOnCanvas(event.getObjectId());
            }
        }));

        // ---- Canvas context menu --------------------------------------------
        registerHandler(getEventBus().addHandler(MapContextMenuEvent.getType(), event -> {
            if (event.getSource() == floorMapCanvasPresenter) {
                onCanvasContextMenu(event);
            }
        }));

        // ---- Fact List selection + Show All toggle --------------------------
        floorMapFactListPresenter.setSelectionConsumer(this::onFactSelectedInFactList);
        floorMapFactListPresenter.setShowAllConsumer(() -> onShowAllFactsToggled(true));
        floorMapFactListPresenter.setShowTimeFilteredConsumer(() -> onShowAllFactsToggled(false));
        floorMapFactListPresenter.setAddConsumer(this::onAddFactToFactList);
        floorMapFactListPresenter.setDeleteConsumer(this::onDeleteFactFromFactList);

        // ---- Time List selection / toolbar ----------------------------------
        floorMapTimeListPresenter.setSelectionConsumer(this::onTimeSelectedInTimeList);
        floorMapTimeListPresenter.setEditConsumer(this::onEditTimeInTimeList);
        floorMapTimeListPresenter.setAddConsumer(this::onAddTimeInTimeList);
        floorMapTimeListPresenter.setDeleteConsumer(this::onDeleteTimeFromTimeList);
    }

    // -----------------------------------------------------------------------
    // DocPresenter lifecycle
    // -----------------------------------------------------------------------

    /**
     * Called by the framework when the document is opened or refreshed.
     *
     * <p>Loads the time range from the server and initialises the timeline,
     * then fetches facts at the initial time and populates all panels.</p>
     */
    @Override
    protected void onRead(final DocRef docRef, final FloorMapDoc document, final boolean readOnly) {
        final String mapName = getMapName();
        if (mapName == null) {
            floorMapFactListPresenter.setData(new ArrayList<>());
            floorMapTimeListPresenter.setData(new ArrayList<>());
            return;
        }

        // Pass mapName + doc to Properties panel so it can resolve asset paths.
        floorMapObjectEditPresenter.setMapName(mapName);
        floorMapObjectEditPresenter.setFloorMapDoc(document);

        // Load time range → initialise slider → load canvas + Fact List
        restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                .method(res -> res.getTimeRange(mapName))
                .onSuccess(range -> {
                    initTimeline(range);
                    loadAtTime(model.getSelectedTime());
                })
                .exec();
    }

    /**
     * Called by the framework when the document is saved.
     *
     * <p>The Editor tab does not write state into the {@link FloorMapDoc} itself
     * (temporal store edits are flushed separately via {@link #onSave}). This
     * method returns the document unchanged.</p>
     */
    @Override
    protected FloorMapDoc onWrite(final FloorMapDoc document) {
        return document;
    }

    // -----------------------------------------------------------------------
    // Save chain hook (called by FloorMapPresenter)
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when there are staged edits awaiting flush.
     *
     * <p>Used by {@link FloorMapPresenter#hasAssociatedDirty()} to drive the
     * dirty indicator on the document tab.</p>
     *
     * @return {@code true} when pending changes exist
     */
    public boolean hasPendingChanges() {
        return model.hasPendingChanges();
    }

    /**
     * Pauses the timeline if it is currently playing.
     *
     * <p>Called by {@link FloorMapPresenter} when the user navigates away from
     * the Editor tab, so that background queries are not issued while the tab
     * is hidden.</p>
     */
    public void pauseTimeline() {
        floorMapTimelinePresenter.pause();
    }

    /**
     * Flushes pending changes to the server as part of the Stroom save chain.
     *
     * <p>Called by {@link FloorMapPresenter} via {@code getPostSaveCallback()}
     * after the {@link FloorMapDoc} has been saved. Sends all staged operations
     * in a single {@code applyChanges} call.</p>
     *
     * <ul>
     *   <li><b>Success</b>: the buffer is cleared, all panels reload from the
     *       server, and {@code callback} is invoked with the document.</li>
     *   <li><b>Failure</b> (server-side error or HTTP error): the buffer is
     *       cleared (the server rolled back), a top-level alert is shown, and
     *       all panels reload. The {@code callback} is <em>not</em> invoked,
     *       so the save chain stops here.</li>
     * </ul>
     *
     * @param document the saved document; passed through to the callback on
     *                 success
     * @param callback invoked with the document only when the flush succeeds
     */
    public void onSave(final FloorMapDoc document, final Consumer<FloorMapDoc> callback) {
        if (!model.hasPendingChanges()) {
            callback.accept(document);
            return;
        }

        restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                .method(res -> res.applyChanges(buildApplyChangesRequest()))
                .onSuccess(result -> {
                    model.clearPendingChanges();
                    if (result.isSuccess()) {
                        // Reload all panels so they reflect the newly-persisted data
                        reloadAllPanels();
                        callback.accept(document);
                    } else {
                        onFlushError(result);
                    }
                })
                .onFailure(error -> {
                    model.clearPendingChanges();
                    AlertEvent.fireError(this,
                            "Error saving floor map editor changes: " + error.getMessage(),
                            this::reloadAllPanels);
                })
                .taskMonitorFactory(this)
                .exec();
    }

    /**
     * Builds an {@link ApplyChangesRequest} from the model's pending changes.
     * This method lives in the presenter (not the model) because the
     * {@code sqlstore.shared} types are not available in {@code stroom-core-shared}.
     */
    private ApplyChangesRequest buildApplyChangesRequest() {
        final List<ChangeOperation> ops = new ArrayList<>();
        for (final FloorMapPendingChanges.PendingChange change : model.getPendingChanges().getChanges()) {
            if (change instanceof FloorMapPendingChanges.Creation) {
                ops.add(ChangeOperation.upsert(
                        ((FloorMapPendingChanges.Creation) change).getEntry()));
            } else if (change instanceof FloorMapPendingChanges.Update) {
                ops.add(ChangeOperation.upsert(
                        ((FloorMapPendingChanges.Update) change).getEntry()));
            } else if (change instanceof FloorMapPendingChanges.Deletion) {
                ops.add(ChangeOperation.delete(
                        ((FloorMapPendingChanges.Deletion) change).getId()));
            }
        }
        return new ApplyChangesRequest(ops);
    }

    // -----------------------------------------------------------------------
    // Timeline
    // -----------------------------------------------------------------------

    /**
     * Initialises the timeline slider from the server-supplied time range.
     *
     * <ul>
     *   <li>If the store is empty the slider covers [now − 1 day, now + 1 day]
     *       and the initial selected time is now.</li>
     *   <li>Otherwise the slider range is [min, max] and the initial selected
     *       time is max (the most recent entry).</li>
     * </ul>
     *
     * @param range the time range returned by the server; never {@code null}
     */
    private void initTimeline(final TemporalStoreTimeRange range) {
        final long now = System.currentTimeMillis();

        if (range.getMinEffectiveTimeMs() == null || range.getMaxEffectiveTimeMs() == null) {
            floorMapTimelinePresenter.setTimeRange(now - ONE_DAY_MS, now + ONE_DAY_MS);
            floorMapTimelinePresenter.setCurrentTime(now);
            model.setSelectedTime(now);
        } else {
            final long min = range.getMinEffectiveTimeMs();
            final long max = range.getMaxEffectiveTimeMs();
            floorMapTimelinePresenter.setTimeRange(min, max);
            floorMapTimelinePresenter.setCurrentTime(max);
            model.setSelectedTime(max);
        }
    }

    /**
     * Called when the user moves the timeline scrubber.
     *
     * <p>Updates the model's selected time and reloads the canvas and Fact List
     * at the new time. If a fact is selected, also refreshes the Time List
     * from the model's server entries for the selected fact overlaid with pending changes
     * (no extra server call).</p>
     *
     * @param timeMs the new timeline position in milliseconds
     */
    private void onTimeChange(final long timeMs) {
        model.setSelectedTime(timeMs);
        loadAtTime(timeMs);
        if (model.getSelectedFactKey() != null) {
            refreshTimeListAtTime(timeMs);
        }
    }

    // -----------------------------------------------------------------------
    // Data loading
    // -----------------------------------------------------------------------

    /**
     * Fetches facts at the given time (or all facts if {@code showAllFacts} is
     * active) and reloads the canvas and Fact List.
     *
     * @param timeMs the point in time to query
     */
    private void loadAtTime(final long timeMs) {
        final String mapName = getMapName();
        if (mapName == null) {
            return;
        }

        if (model.isShowAllFacts()) {
            fetchAll(mapName);
        } else {
            fetchAtTime(mapName, timeMs);
        }
    }

    /**
     * Fetches the most recent entry per key at or before {@code timeMs}
     * from the server, then calls {@link #onEntriesFetched} to update
     * the canvas and fact list.
     *
     * @param mapName the temporal store name
     * @param timeMs  the upper bound for effective_time
     */
    private void fetchAtTime(final String mapName, final long timeMs) {
        final FetchAtTimeRequest request = new FetchAtTimeRequest(mapName, timeMs);
        restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                .method(res -> res.fetchAtTime(request))
                .onSuccess(this::onEntriesFetched)
                .exec();
    }

    /**
     * Fetches all entries (server-side timeTo = now + ONE_DAY_MS).
     *
     * @param mapName the temporal store name
     */
    private void fetchAll(final String mapName) {
        restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                .method(res -> res.fetchAll(mapName))
                .onSuccess(this::onEntriesFetched)
                .exec();
    }

    /**
     * Shared callback for {@link #fetchAtTime} and {@link #fetchAll}.
     * Stores the server entries, merges pending changes, then refreshes
     * the canvas and Fact List.
     */
    private void onEntriesFetched(final List<TemporalEntry> entries) {
        final List<TemporalEntry> merged = model.onEntriesFetched(entries);
        updateCanvasAndFactList(merged);
    }

    /**
     * Fetches all temporal entries (every effective time) for the given key.
     * Uses {@code find(Map=name, Key=key)} with no time term.
     *
     * @param mapName the temporal store name
     * @param key     the fact key
     */
    private void fetchTimeList(final String mapName, final String key) {
        final ExpressionOperator expression = ExpressionOperator.builder()
                .addTerm(ExpressionTerm.builder()
                        .field("Map").condition(Condition.EQUALS).value(mapName)
                        .build())
                .addTerm(ExpressionTerm.builder()
                        .field("Key").condition(Condition.EQUALS).value(key)
                        .build())
                .build();

        final ExpressionCriteria criteria = new ExpressionCriteria(expression);
        restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                .method(res -> res.find(criteria))
                .onSuccess(result -> {
                    model.onTimeListFetched(
                            result != null ? result.getValues() : null);
                    refreshTimeListAtTime(model.getSelectedTime());
                })
                .exec();
    }

    // -----------------------------------------------------------------------
    // Canvas + Fact List rendering
    // -----------------------------------------------------------------------

    /**
     * Updates the canvas and Fact List from a merged entry list.
     * Parses the name and type out of the entries and creates a list of FactObjects.
     * Also ensures the currently selected fact remains highlighted on the canvas.
     *
     * @param entries merged entries (server data + pending changes)
     */
    private void updateCanvasAndFactList(final List<TemporalEntry> entries) {
        // Update canvas using shared parser (applies world-to-map transform)
        final FloorMapEntryParser.ParseResult result = model.parseForCanvas(
                entries, getEntity().getValueSchema(),
                ValueAccessorFactory.forFormat(getEntity().getValueFormat()));
        floorMapCanvasPresenter.setTypeStyles(getEntity().getTypeStyles());
        floorMapCanvasPresenter.setFacts(result.getFacts());
        if (model.getSelectedFactKey() != null) {
            floorMapCanvasPresenter.setSelectedObjectId(model.getSelectedFactKey());
        }

        // Update Fact List — one row per unique key.
        // The merged entry list may contain multiple entries for the same key
        // (e.g. when "Show All" mode is on, or when a pending creation overlaps
        // with a server-returned entry). We deduplicate by key so the fact list
        // shows each object exactly once.
        final List<FloorMapFieldMapping> schema = getEntity().getValueSchema();
        final List<FloorMapFactListPresenter.FactObject> factObjects = new ArrayList<>();
        final Set<String> seenKeys = new HashSet<>();
        for (final TemporalEntry entry : entries) {
            if (seenKeys.add(entry.getKey())) {
                factObjects.add(FloorMapFactListPresenter.FactObject.fromEntry(entry, schema));
            }
        }

        floorMapFactListPresenter.setData(factObjects);

        // Restore selection highlight without re-firing selection event
        if (model.getSelectedFactKey() != null) {
            floorMapFactListPresenter.setSelected(model.getSelectedFactKey());
        }
    }

    // -----------------------------------------------------------------------
    // Event handlers
    // -----------------------------------------------------------------------

    /**
     * Called when a canvas object is clicked.
     *
     * @param objectId the ID of the clicked object (= fact key)
     */
    private void onObjectSelectedOnCanvas(final String objectId) {
        model.setSelectedFactKey(objectId);
        // Highlight in Fact List without re-firing the consumer
        floorMapFactListPresenter.setSelected(objectId);
        // Load Time List
        loadTimeListForSelectedFact();
    }

    /**
     * Persists a completed drag as a single map-space translation applied to the
     * whole selection (see {@link FloorMapCanvasPresenter.DragHandler}). Each
     * fact's world-to-map matrix is shifted by {@code (dxMap, dyMap)}.
     */
    private void onFactsTranslated(final java.util.Collection<String> keys,
                                   final double dxMap,
                                   final double dyMap) {
        if (getMapName() == null || keys == null || keys.isEmpty()) {
            return;
        }
        try {
            final int moved = model.translateFacts(keys, dxMap, dyMap,
                    getEntity().getValueSchema(),
                    ValueAccessorFactory.forFormat(getEntity().getValueFormat()));
            if (moved > 0) {
                setDirty(true);
            }
        } catch (final Exception ex) {
            AlertEvent.fireError(this,
                    "Cannot move selection: " + ex.getMessage(), null);
        }
        refreshCanvasOnly();
    }


    /**
     * Refreshes the canvas by re-applying pending changes and re-parsing,
     * without reloading the Fact List (which would clear its selection
     * and cascade into the Time List).
     */
    private void refreshCanvasOnly() {
        final List<TemporalEntry> canvasEntries = model.buildMergedCanvasEntries();
        final FloorMapEntryParser.ParseResult result = model.parseForCanvas(
                canvasEntries, getEntity().getValueSchema(),
                ValueAccessorFactory.forFormat(getEntity().getValueFormat()));
        floorMapCanvasPresenter.setTypeStyles(getEntity().getTypeStyles());
        floorMapCanvasPresenter.setFacts(result.getFacts());
        if (model.getSelectedFactKey() != null) {
            floorMapCanvasPresenter.setSelectedObjectId(model.getSelectedFactKey());
        }
    }

    /**
     * Called when a row in the Fact List is selected.
     *
     * @param factObject the selected fact, or {@code null}
     */
    private void onFactSelectedInFactList(final FloorMapFactListPresenter.FactObject factObject) {
        if (factObject == null) {
            model.setSelectedFactKey(null);
            floorMapCanvasPresenter.setSelectedObjectId(null);
            floorMapTimeListPresenter.setData(new ArrayList<>());
            return;
        }
        model.setSelectedFactKey(factObject.getKey());
        floorMapCanvasPresenter.setSelectedObjectId(model.getSelectedFactKey());

        // Pass object info to Properties panel
        floorMapObjectEditPresenter.setMapName(getMapName());
        floorMapObjectEditPresenter.setObject(model.getSelectedFactKey());

        loadTimeListForSelectedFact();
    }

    /**
     * Called when a row in the Time List is selected.
     *
     * <p>Moves the timeline scrubber to the entry's effective time and reloads
     * the canvas so all panels stay in sync. {@link FloorMapTimelinePresenter#setCurrentTime}
     * only repositions the scrubber — it does <em>not</em> fire a
     * {@link stroom.floormap.client.event.TimeChangeEvent} — so there is no
     * feedback loop back into {@link #onTimeChange}.</p>
     *
     * @param entry the selected entry, or {@code null}
     */
    private void onTimeSelectedInTimeList(final TemporalEntry entry) {
        floorMapCanvasPresenter.setIsDraggingEnabled(entry != null);
        if (entry != null) {
            model.setSelectedTime(entry.getEffectiveTimeMs());
            floorMapTimelinePresenter.setCurrentTime(model.getSelectedTime());
            loadAtTime(model.getSelectedTime());
        }
    }

    /**
     * Called when the Time List's Edit button is clicked.
     * Opens the Properties dialog for the currently selected entry.
     *
     * @param entry the selected entry to edit
     */
    private void onEditTimeInTimeList(final TemporalEntry entry) {
        if (entry == null) {
            return;
        }
        floorMapObjectEditPresenter.showForEdit(
                "Edit Time Properties",
                entry,
                (saved, clone) -> {
                    // When the effective time changed, a "move" deletes the
                    // original version; a "clone" keeps it alongside the new one.
                    if (!clone
                            && !Objects.equals(saved.getEffectiveTimeMs(), entry.getEffectiveTimeMs())) {
                        model.getPendingChanges().recordDeletion(new TemporalEntryId(
                                saved.getMap(), saved.getKey(),
                                entry.getEffectiveTimeMs()));
                    }
                    model.getPendingChanges().recordUpdate(saved);
                    setDirty(true);
                    refreshTimeListAtTime(saved.getEffectiveTimeMs());
                    refreshCanvas();
                });
    }

    /**
     * Called when the Time List's Add button is clicked.
     * Creates a new entry cloned from the currently selected one (or defaults),
     * staged in the pending-changes buffer.
     */
    private void onAddTimeInTimeList() {
        final String mapName = getMapName();
        if (mapName == null || model.getSelectedFactKey() == null) {
            return;
        }

        final long newTime = System.currentTimeMillis();
        final TemporalEntry selected = floorMapTimeListPresenter.getSelectedEntry();
        final TemporalEntry newEntry = FloorMapEditorModel.cloneEntryAtTime(
                selected, mapName, model.getSelectedFactKey(), newTime);

        floorMapObjectEditPresenter.show(
                "Add Time Properties",
                newEntry,
                saved -> {
                    model.getPendingChanges().recordCreation(saved);
                    setDirty(true);
                    loadAtTime(model.getSelectedTime());
                    refreshTimeListAtTime(model.getSelectedTime());
                });
    }

    /**
     * Called when the Fact List's Add button is clicked.
     *
     * <p>Delegates to {@link #onAddObjectAtPosition} using the centre of the
     * visible canvas area as the initial position, giving the same
     * properties-editor experience as the right-click "Add Object Here" action.</p>
     */
    private void onAddFactToFactList() {
        final double[] centre = floorMapCanvasPresenter.getVisibleCentreMapCoords();
        onAddObjectAtPosition(centre[0], centre[1]);
    }

    /**
     * Called when the Fact List's Delete button is clicked.
     * Confirms with the user then stages deletions for all time-entries of the selected fact.
     *
     * @param key the fact key to delete
     */
    private void onDeleteFactFromFactList(final String key) {
        if (key == null) {
            return;
        }
        ConfirmEvent.fire(this,
                "Delete all entries for '" + key + "'? This cannot be undone.",
                ok -> {
                    if (ok) {
                        // Was this key the current selection? Capture before staging,
                        // since the model clears the selection as part of the deletion.
                        final boolean wasSelected = key.equals(model.getSelectedFactKey());
                        if (model.stageFactDeletion(key)) {
                            setDirty(true);
                        }
                        // Clear selection and refresh
                        if (wasSelected) {
                            floorMapTimeListPresenter.setData(new ArrayList<>());
                            floorMapObjectEditPresenter.loadEntry(null);
                        }
                        loadAtTime(model.getSelectedTime());
                    }
                });
    }

    /**
     * Called when the Time List's Delete button is clicked on an entry.
     * Stages a deletion in the pending-changes buffer; the entry disappears
     * immediately from the Time List (optimistic hide).
     *
     * @param entry the entry to delete
     */
    private void onDeleteTimeFromTimeList(final TemporalEntry entry) {
        // Stage the deletion and compute the row to select afterwards (the item
        // above the deleted one) so the user stays in context.
        final int selectIndex = model.stageTimeEntryDeletion(entry);
        setDirty(true);

        // Rebuild the Time List optimistically from the same merged view.
        final List<TemporalEntry> merged = model.buildMergedTimeList();
        floorMapTimeListPresenter.setData(merged);
        floorMapTimeListPresenter.selectAtIndex(selectIndex);

        refreshCanvasOnly();
    }

    // -----------------------------------------------------------------------
    // Canvas context menu
    // -----------------------------------------------------------------------

    /**
     * Called when the user right-clicks on the floor map canvas.
     *
     * <p>Builds and shows a context menu whose items depend on whether the
     * click landed on an existing map object or on empty canvas space.
     * All actions use the current timeline scrubber position as the
     * effective time.</p>
     *
     * @param event the context menu event from the canvas
     */
    private void onCanvasContextMenu(final MapContextMenuEvent event) {
        showCanvasContextMenu(
                event.getObjectId(),
                event.getMapX(),
                event.getMapY(),
                event.getClientX(),
                event.getClientY());
    }

    /**
     * Builds and displays the canvas context menu at the given screen position.
     *
     * <p>Menu structure:</p>
     * <ul>
     *   <li><b>Empty canvas:</b>
     *       <ul>
     *         <li>"Add Object Here" — creates a new object at the clicked map position</li>
     *       </ul>
     *   </li>
     *   <li><b>On an object:</b>
     *       <ul>
     *         <li>"Edit Properties" — selects the object and opens the property editor</li>
     *         <li>"Add Time Version" — creates a new effective time entry at the scrubber
     *             position, cloned from the current version</li>
     *         <li>"Duplicate Object" — clones the object with a new key, offset slightly</li>
     *         <li>"Delete Object" — confirms and stages deletion of all time entries</li>
     *       </ul>
     *   </li>
     * </ul>
     *
     * @param objectId the right-clicked object's key, or {@code null} for empty canvas
     * @param mapX     map-space X coordinate of the click
     * @param mapY     map-space Y coordinate of the click
     * @param clientX  screen X coordinate for popup positioning
     * @param clientY  screen Y coordinate for popup positioning
     */
    private void showCanvasContextMenu(final String objectId,
                                       final double mapX,
                                       final double mapY,
                                       final int clientX,
                                       final int clientY) {
        final String mapName = getMapName();
        if (mapName == null) {
            return;
        }

        final List<Item> menuItems = new ArrayList<>();

        if (objectId == null) {
            // ---- Right-clicked on empty canvas ----
            menuItems.add(new IconMenuItem.Builder()
                    .priority(1)
                    .icon(SvgImage.ADD)
                    .text("Add Object Here")
                    .command(() -> onAddObjectAtPosition(mapX, mapY))
                    .build());
        } else {
            // ---- Right-clicked on an object ----

            // Edit Properties
            menuItems.add(new IconMenuItem.Builder()
                    .priority(1)
                    .icon(SvgImage.EDIT)
                    .text("Edit Properties")
                    .command(() -> {
                        // Select the object and open the properties editor
                        model.setSelectedFactKey(objectId);
                        floorMapCanvasPresenter.setSelectedObjectId(objectId);
                        floorMapFactListPresenter.setSelected(objectId);
                        loadTimeListForSelectedFact();

                        // Find the active entry for this object at the current time
                        // and open the edit dialog
                        final List<TemporalEntry> all = model.buildMergedCanvasEntries();
                        for (final TemporalEntry e : all) {
                            if (objectId.equals(e.getKey())) {
                                onEditTimeInTimeList(e);
                                break;
                            }
                        }
                    })
                    .build());

            // Add Time Version at scrubber position
            if (!FloorMapJsonKeys.BACKGROUND.equals(objectId)) {
                menuItems.add(new IconMenuItem.Builder()
                        .priority(2)
                        .icon(SvgImage.HISTORY)
                        .text("Add Time Version")
                        .command(() -> {
                            // Select the object first so the time list loads
                            model.setSelectedFactKey(objectId);
                            floorMapCanvasPresenter.setSelectedObjectId(objectId);
                            floorMapFactListPresenter.setSelected(objectId);
                            // Trigger the same flow as "Add Time" on the Time List
                            onAddTimeInTimeList();
                        })
                        .build());
            }

            // Duplicate Object
            if (!FloorMapJsonKeys.BACKGROUND.equals(objectId)) {
                menuItems.add(new IconMenuItem.Builder()
                        .priority(3)
                        .icon(SvgImage.COPY)
                        .text("Duplicate Object")
                        .command(() -> onDuplicateObject(objectId, mapX, mapY))
                        .build());
            }

            // Delete Object
            menuItems.add(new IconMenuItem.Builder()
                    .priority(4)
                    .icon(SvgImage.DELETE)
                    .text("Delete Object")
                    .command(() -> onDeleteFactFromFactList(objectId))
                    .build());
        }

        final PopupPosition popupPosition = new PopupPosition(clientX, clientY);
        ShowMenuEvent
                .builder()
                .items(menuItems)
                .popupPosition(popupPosition)
                .fire(this);
    }

    /**
     * Creates a new object at the given map-space position.
     *
     * <p>Called from the canvas context menu's "Add Object Here" action.
     * Uses the current timeline scrubber position as the effective time,
     * and opens the properties editor dialog so the user can set the type,
     * name, and image before confirming.</p>
     *
     * @param mapX the X coordinate in map space
     * @param mapY the Y coordinate in map space
     */
    private void onAddObjectAtPosition(final double mapX, final double mapY) {
        final String mapName = getMapName();
        if (mapName == null) {
            return;
        }

        // Generate a unique key for the new object
        final String newKey = generateObjectKey("new");

        try {
            final ValueFormat format = getEntity().getValueFormat();
            final ValueAccessor accessor = ValueAccessorFactory.forFormat(format);
            final ParsedValue newValue = accessor.createEmpty("entry");
            accessor.setString(newValue, pathForRole(Role.TYPE), "");
            accessor.setString(newValue, pathForRole(Role.LABEL), newKey);
            accessor.setArray(newValue, pathForRole(Role.POSITION),
                    new double[]{mapX, mapY});
            final FloorMapTransformationMatrix identity = FloorMapTransformationMatrix.identity();
            accessor.setArray(newValue, pathForRole(Role.WORLD_TO_MAP),
                    new double[]{identity.getA(), identity.getB(), identity.getC(),
                            identity.getD(), identity.getE(), identity.getF()});
            final String valueStr = accessor.serialize(newValue);

            final TemporalEntry entry = new TemporalEntry(
                    mapName, newKey, model.getSelectedTime(), valueStr);

            // Open the properties editor so the user can customise before committing
            floorMapObjectEditPresenter.setMapName(mapName);
            floorMapObjectEditPresenter.setObject(newKey);
            floorMapObjectEditPresenter.setFloorMapDoc(getEntity());

            floorMapObjectEditPresenter.show(
                    "Add Object",
                    entry,
                    saved -> {
                        model.getPendingChanges().recordCreation(saved);
                        setDirty(true);
                        model.setSelectedFactKey(saved.getKey());

                        // Select the new object in the Fact List and canvas
                        floorMapCanvasPresenter.setSelectedObjectId(model.getSelectedFactKey());
                        floorMapFactListPresenter.setSelected(model.getSelectedFactKey());

                        // Populate the Time List optimistically from pending
                        // changes (the server doesn't know about this entry yet)
                        model.setServerEntriesForSelectedFact(new ArrayList<>());
                        refreshTimeListAtTime(model.getSelectedTime());

                        // Enable dragging so the user can immediately reposition
                        floorMapCanvasPresenter.setIsDraggingEnabled(true);

                        loadAtTime(model.getSelectedTime());
                    });
        } catch (final IllegalStateException ex) {
            AlertEvent.fireError(
                    this,
                    "Cannot add object: " + ex.getMessage(),
                    null);
        }
    }

    /**
     * Duplicates an existing object with a new key, offset slightly from
     * the original position.
     *
     * <p>The new object is a clone of the original's current state at the
     * timeline scrubber position, with coordinates shifted by a small offset
     * to avoid overlapping the original. Uses the scrubber time as the
     * effective time for the new entry.</p>
     *
     * @param originalKey the key of the object to duplicate
     * @param mapX        the original object's map-space X coordinate
     * @param mapY        the original object's map-space Y coordinate
     */
    private void onDuplicateObject(final String originalKey,
                                    final double mapX,
                                    final double mapY) {
        final String mapName = getMapName();
        if (mapName == null) {
            return;
        }

        // Find the current entry for this object
        final List<TemporalEntry> all = model.buildMergedCanvasEntries();
        TemporalEntry sourceEntry = null;
        for (final TemporalEntry e : all) {
            if (originalKey.equals(e.getKey())) {
                sourceEntry = e;
                break;
            }
        }

        if (sourceEntry == null) {
            return;
        }

        try {
            final String newKey = generateObjectKey(originalKey + "-copy");
            final ValueFormat format = getEntity().getValueFormat();
            final ValueAccessor accessor = ValueAccessorFactory.forFormat(format);
            final ParsedValue parsed = accessor.parse(sourceEntry.getValue());
            if (parsed != null) {
                // Offset the position slightly so the duplicate doesn't sit on top
                accessor.setArray(parsed, pathForRole(Role.POSITION),
                        new double[]{mapX + 50, mapY + 50});
                accessor.setString(parsed, pathForRole(Role.LABEL), newKey);
            }

            final String valueStr = parsed != null
                    ? accessor.serialize(parsed)
                    : sourceEntry.getValue();

            final TemporalEntry newEntry = new TemporalEntry(
                    mapName, newKey, model.getSelectedTime(), valueStr);

            model.getPendingChanges().recordCreation(newEntry);
            setDirty(true);
            model.setSelectedFactKey(newKey);
            loadAtTime(model.getSelectedTime());
        } catch (final Exception ex) {
            AlertEvent.fireError(
                    this,
                    "Cannot duplicate object: " + ex.getMessage(),
                    null);
        }
    }

    // -----------------------------------------------------------------------
    // Key generation
    // -----------------------------------------------------------------------

    /**
     * Generates a unique object key with the given prefix, guaranteed not to
     * clash with any key currently known to the editor.
     *
     * <p>The returned key has the form {@code prefix-NNNNN} where {@code NNNNN}
     * is a random integer. If the generated key already exists, a new random
     * suffix is tried until a unique key is found (up to a safety limit of
     * 1000 attempts).</p>
     *
     * <p>The prefix must not start with
     * {@link FloorMapJsonKeys#SVG_GROUP_PREFIX} as that would make the object
     * unselectable on the canvas.</p>
     *
     * @param prefix a human-readable prefix (e.g. {@code "new"}, {@code "gate-1-copy"})
     * @return a key string suitable for use as a temporal-store fact key
     */
    private String generateObjectKey(final String prefix) {
        return model.generateObjectKey(prefix);
    }

    // -----------------------------------------------------------------------
    // "Show all" toggle
    // -----------------------------------------------------------------------

    /**
     * Called when the Fact List's "Show all" button is toggled.
     *
     * @param showAll {@code true} to ignore the time filter
     */
    public void onShowAllFactsToggled(final boolean showAll) {
        model.setShowAllFacts(showAll);
        loadAtTime(model.getSelectedTime());
    }

    // -----------------------------------------------------------------------
    // Optimistic refresh helpers
    // -----------------------------------------------------------------------

    /**
     * Refreshes the Time List from the model's server entries for the selected fact,
     * overlaid with pending changes, then selects the entry active at
     * the model's selected time (i.e. the most recent entry ≤ the current
     * timeline position).
     *
     * <p>Called on timeline scrubber moves so the highlighted row tracks the
     * time position rather than always jumping to the newest entry.</p>
     *
     * @param timeMs the timeline position to select against
     */
    private void refreshTimeListAtTime(final long timeMs) {
        final List<TemporalEntry> merged = model.buildMergedTimeList();
        floorMapTimeListPresenter.setData(merged);
        floorMapTimeListPresenter.selectAtTime(timeMs);
    }

    /** Refreshes the canvas using the latest Fact List data at the current time. */
    private void refreshCanvas() {
        loadAtTime(model.getSelectedTime());
    }

    // -----------------------------------------------------------------------
    // Error handling
    // -----------------------------------------------------------------------

    /**
     * Handles a server-side flush failure.
     * Shows a top-level error and reloads all panels from the server.
     *
     * @param result the failed {@link ApplyChangesResult}
     */
    private void onFlushError(final ApplyChangesResult result) {
        model.getPendingChanges().clear();
        final String message = result.getErrorMessage() != null
                ? result.getErrorMessage()
                : "Unknown error";
        AlertEvent.fireError(this,
                "Error saving floor map editor changes: " + message,
                this::reloadAllPanels);
    }

    /**
     * Reloads all panels by re-reading from the server using the current time.
     */
    private void reloadAllPanels() {
        final String mapName = getMapName();
        if (mapName != null) {
            loadAtTime(model.getSelectedTime());
            if (model.getSelectedFactKey() != null) {
                fetchTimeList(mapName, model.getSelectedFactKey());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Loads the Time List for the currently selected fact, then auto-selects
     * the last entry and scrolls to it.
     */
    private void loadTimeListForSelectedFact() {
        final String mapName = getMapName();
        if (mapName == null || model.getSelectedFactKey() == null) {
            floorMapTimeListPresenter.setData(new ArrayList<>());
            return;
        }
        fetchTimeList(mapName, model.getSelectedFactKey());
    }

    /**
     * Returns the temporal store map name from the document, or {@code null}
     * if the document has no store reference configured.
     *
     * @return the map name, or {@code null}
     */
    private String getMapName() {
        final FloorMapDoc doc = getEntity();
        if (doc == null
                || doc.getFactsStoreRef() == null
                || doc.getFactsStoreRef().getName() == null
                || doc.getFactsStoreRef().getName().isEmpty()) {
            return null;
        }
        return doc.getFactsStoreRef().getName();
    }

    /**
     * Returns the JSON path for the given {@link Role} using the document's
     * value schema, falling back to the default schema if the document is
     * unavailable.
     *
     * @param role the field role to look up
     * @return the JSON path string for the role; never {@code null}
     * @throws IllegalStateException if the schema does not contain the requested role
     */
    private String pathForRole(final Role role) {
        final String path = FloorMapEntryParser.findPath(getEntity().getValueSchema(), role);
        if (path == null) {
            throw new IllegalStateException(
                    "The Value Schema for this Floor Map does not define a mapping "
                    + "for the '" + role + "' role. Please add a '" + role
                    + "' mapping in the Settings tab under Value Schema.");
        }
        return path;
    }

    // -----------------------------------------------------------------------
    // View interface
    // -----------------------------------------------------------------------

    /**
     * View interface for the Editor tab.
     *
     * <p>No custom methods are needed here — all child content is routed
     * through GWTP's standard {@link com.gwtplatform.mvp.client.View#setInSlot}
     * mechanism, overridden in {@link stroom.floormap.client.view.FloorMapEditorViewImpl}.</p>
     */
    public interface FloorMapEditorView extends View {

    }
}
