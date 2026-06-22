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
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.shared.ExpressionCriteria;
import stroom.floormap.client.event.MapObjectMovedEvent;
import stroom.floormap.client.event.MapObjectSelectedEvent;
import stroom.floormap.client.event.TimeChangeEvent;
import stroom.floormap.client.presenter.FloorMapEditorPresenter.FloorMapEditorView;
import stroom.floormap.shared.FloorMapDoc;
import stroom.floormap.shared.FloorMapObject;
import stroom.floormap.shared.FloorMapTransformationMatrix;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.sqlstore.shared.ApplyChangesResult;
import stroom.sqlstore.shared.FetchAtTimeRequest;
import stroom.sqlstore.shared.SqlTemporalStoreResource;
import stroom.sqlstore.shared.TemporalStoreTimeRange;
import stroom.util.client.JSONUtil;
import stroom.util.shared.TemporalEntry;
import stroom.util.shared.TemporalEntryId;

import com.google.gwt.core.client.GWT;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONObject;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 *   <li>{@link #selectedFactKey} — key of the selected fact, or {@code null}</li>
 *   <li>{@link #selectedTime} — current timeline position in ms</li>
 *   <li>{@link #showAllFacts} — whether "show all" mode is active</li>
 *   <li>{@link #pendingChanges} — staged edits awaiting flush</li>
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

    // -----------------------------------------------------------------------
    // REST resource
    // -----------------------------------------------------------------------

    private static final SqlTemporalStoreResource SQL_TEMPORAL_STORE_RESOURCE =
            GWT.create(SqlTemporalStoreResource.class);

    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;

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

    /** Slot for the Properties panel (rightmost bottom column). */
    public static final Object PROPERTIES = new Object();

    // -----------------------------------------------------------------------
    // Child presenters
    // -----------------------------------------------------------------------

    private final RestFactory restFactory;
    private final FloorMapCanvasPresenter floorMapCanvasPresenter;
    private final FloorMapTimelinePresenter floorMapTimelinePresenter;
    private final FloorMapFactListPresenter floorMapFactListPresenter;
    private final FloorMapTimeListPresenter floorMapTimeListPresenter;
    private final FloorMapObjectEditPresenter floorMapObjectEditPresenter;

    // -----------------------------------------------------------------------
    // Shared selection model
    // -----------------------------------------------------------------------

    /** Currently selected fact key, or {@code null}. */
    private String selectedFactKey;

    /** Current timeline position in milliseconds. */
    private long selectedTime;

    /** When {@code true}, the Fact List ignores the time filter and shows everything. */
    private boolean showAllFacts;

    /**
     * Server-sourced entry list for the currently selected fact.
     * {@link #pendingChanges} is merged on top of this for display.
     */
    private List<TemporalEntry> serverEntriesForSelectedFact = new ArrayList<>();

    /**
     * Server-sourced snapshot of all keys visible on the canvas at the current time.
     * Populated whenever {@link #fetchAtTime} or {@link #fetchAll} succeeds.
     * Used by {@link #onObjectMovedOnCanvas} to locate an entry by key regardless
     * of which key is currently selected in the Time List.
     */
    private List<TemporalEntry> serverEntriesAtCurrentTime = new ArrayList<>();

    /** Buffer of staged edits awaiting the next flush. */
    private final FloorMapPendingChanges pendingChanges = new FloorMapPendingChanges();

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

        // Each child is obtained via Provider so GIN creates a fresh instance
        // for this presenter rather than sharing singletons used elsewhere.
        this.floorMapCanvasPresenter = canvasProvider.get();
        this.floorMapTimelinePresenter = timelineProvider.get();
        this.floorMapFactListPresenter = factListProvider.get();
        this.floorMapTimeListPresenter = timeListProvider.get();
        this.floorMapObjectEditPresenter = propertiesProvider.get();

        // Always in edit mode
        floorMapCanvasPresenter.setEditMode(true);

        setInSlot(MAIN, floorMapCanvasPresenter);
        setInSlot(TIMELINE, floorMapTimelinePresenter);
        setInSlot(FACT_LIST, floorMapFactListPresenter);
        setInSlot(TIME_LIST, floorMapTimeListPresenter);
        setInSlot(PROPERTIES, floorMapObjectEditPresenter);
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

        registerHandler(getEventBus().addHandler(MapObjectMovedEvent.getType(), event -> {
            if (event.getSource() == floorMapCanvasPresenter) {
                onObjectMovedOnCanvas(event.getObjectId(), event.getX(), event.getY());
            }
        }));

        // ---- Canvas drag (live coords → Properties update) ------------------
        floorMapCanvasPresenter.setDragHandler(this::onCanvasDrag);

        // ---- Fact List selection + Show All toggle --------------------------
        floorMapFactListPresenter.setSelectionConsumer(this::onFactSelected);
        floorMapFactListPresenter.setShowAllConsumer(() -> onShowAllToggled(true));
        floorMapFactListPresenter.setShowTimeFilteredConsumer(() -> onShowAllToggled(false));

        // ---- Time List selection / toolbar ----------------------------------
        floorMapTimeListPresenter.setSelectionConsumer(this::onEntrySelected);
        floorMapTimeListPresenter.setAddConsumer(this::onAddEntry);
        floorMapTimeListPresenter.setDeleteConsumer(this::onDeleteEntry);

        // ---- Properties (stage updates through pending-changes buffer) ------
        floorMapObjectEditPresenter.setSaveConsumer(entry -> {
            pendingChanges.recordUpdate(entry);
            setDirty(true);
            refreshTimeListOptimistic();
            refreshCanvas();
        });
        floorMapObjectEditPresenter.setDeleteConsumer(id -> {
            pendingChanges.recordDeletion(id);
            setDirty(true);
            refreshTimeListOptimistic();
        });

        // Live drag → Properties coordinates update
        floorMapObjectEditPresenter.setChangeEventConsumer(() -> {
            // no-op: saves are routed via setSaveConsumer above
        });
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
                    loadAtTime(selectedTime);
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
        return pendingChanges.isDirty();
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
        if (!pendingChanges.isDirty()) {
            callback.accept(document);
            return;
        }

        restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                .method(res -> res.applyChanges(pendingChanges.toRequest()))
                .onSuccess(result -> {
                    pendingChanges.clear();
                    if (result.isSuccess()) {
                        // Reload all panels so they reflect the newly-persisted data
                        reloadAllPanels();
                        callback.accept(document);
                    } else {
                        onFlushError(result);
                    }
                })
                .onFailure(error -> {
                    pendingChanges.clear();
                    AlertEvent.fireError(this,
                            "Error saving floor map editor changes: " + error.getMessage(),
                            this::reloadAllPanels);
                })
                .taskMonitorFactory(this)
                .exec();
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
            // Empty store — use a default range centred on now
            floorMapTimelinePresenter.setTimeRange(now - ONE_DAY_MS, now + ONE_DAY_MS);
            floorMapTimelinePresenter.setCurrentTime(now);
            selectedTime = now;
        } else {
            final long min = range.getMinEffectiveTimeMs();
            final long max = range.getMaxEffectiveTimeMs();
            floorMapTimelinePresenter.setTimeRange(min, max);
            floorMapTimelinePresenter.setCurrentTime(max);
            selectedTime = max;
        }
    }

    /**
     * Called when the user moves the timeline scrubber.
     *
     * <p>Updates {@link #selectedTime} and reloads the canvas and Fact List
     * from the server at the new time. If a fact is already selected, also
     * refreshes the Time List display using the cached
     * {@link #serverEntriesForSelectedFact} overlaid with pending changes
     * (no extra server call).</p>
     *
     * @param timeMs the new timeline position in milliseconds
     */
    private void onTimeChange(final long timeMs) {
        selectedTime = timeMs;
        loadAtTime(timeMs);
        if (selectedFactKey != null) {
            refreshTimeListOptimistic();
        }
    }

    // -----------------------------------------------------------------------
    // Data loading
    // -----------------------------------------------------------------------

    /**
     * Fetches facts at the given time (or all facts if {@link #showAllFacts} is
     * active) and reloads the canvas and Fact List.
     *
     * @param timeMs the point in time to query
     */
    private void loadAtTime(final long timeMs) {
        final String mapName = getMapName();
        if (mapName == null) {
            return;
        }

        if (showAllFacts) {
            fetchAll(mapName);
        } else {
            fetchAtTime(mapName, timeMs);
        }
    }

    /**
     * Fetches the most recent entry per key at or before {@code timeMs}.
     *
     * @param mapName the temporal store name
     * @param timeMs  the upper bound for effective_time
     */
    private void fetchAtTime(final String mapName, final long timeMs) {
        final FetchAtTimeRequest request = new FetchAtTimeRequest(mapName, timeMs);
        restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                .method(res -> res.fetchAtTime(request))
                .onSuccess(entries -> {
                    serverEntriesAtCurrentTime = entries != null ? entries : new ArrayList<>();
                    final List<TemporalEntry> merged = pendingChanges.applyTo(entries);
                    updateCanvasAndFactList(merged);
                })
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
                .onSuccess(entries -> {
                    serverEntriesAtCurrentTime = entries != null ? entries : new ArrayList<>();
                    final List<TemporalEntry> merged = pendingChanges.applyTo(entries);
                    updateCanvasAndFactList(merged);
                })
                .exec();
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
                    if (result != null && result.getValues() != null) {
                        serverEntriesForSelectedFact = new ArrayList<>(result.getValues());
                    } else {
                        serverEntriesForSelectedFact = new ArrayList<>();
                    }
                    serverEntriesForSelectedFact.sort(
                            Comparator.comparingLong(TemporalEntry::getEffectiveTimeMs));
                    refreshTimeListOptimistic();
                })
                .exec();
    }

    // -----------------------------------------------------------------------
    // Canvas + Fact List rendering
    // -----------------------------------------------------------------------

    /**
     * Updates the canvas and Fact List from a merged entry list.
     * Also ensures the currently selected fact remains highlighted on the canvas.
     *
     * @param entries merged entries (server data + pending changes)
     */
    private void updateCanvasAndFactList(final List<TemporalEntry> entries) {
        // Update canvas
        parseForCanvas(entries);

        // Update Fact List
        final List<FloorMapFactListPresenter.FactObject> factObjects = new ArrayList<>();
        for (final TemporalEntry entry : entries) {
            String name = entry.getKey();
            String type = "";
            try {
                if (entry.getValue() != null && entry.getValue().trim().startsWith("{")) {
                    final JSONObject json = JSONUtil.getObject(JSONUtil.parse(entry.getValue()));
                    if (json != null) {
                        final String parsedName = JSONUtil.getString(json.get("name"));
                        final String parsedType = JSONUtil.getString(json.get("type"));
                        if (parsedName != null && !parsedName.isEmpty()) {
                            name = parsedName;
                        }
                        if (parsedType != null) {
                            type = parsedType;
                        }
                    }
                }
            } catch (final Exception ex) {
                // Use key as display name
            }
            factObjects.add(new FloorMapFactListPresenter.FactObject(entry.getKey(), name, type));
        }

        // Guarantee background entry is always present
        final boolean hasBg = factObjects.stream()
                .anyMatch(o -> "background".equalsIgnoreCase(o.getType())
                        || "background".equalsIgnoreCase(o.getKey()));
        if (!hasBg) {
            factObjects.add(new FloorMapFactListPresenter.FactObject(
                    "background", "Background", "background"));
        }

        floorMapFactListPresenter.setData(factObjects);

        // Restore selection highlight without re-firing selection event
        if (selectedFactKey != null) {
            floorMapFactListPresenter.setSelected(selectedFactKey);
        }
    }

    /**
     * Parses the entry list and sends canvas objects to the canvas presenter.
     *
     * <p>This is the Editor tab's own canvas data feed; it does not share code
     * with the Map tab (which will be radically reworked later).</p>
     *
     * @param entries the merged entry list to render
     */
    private void parseForCanvas(final List<TemporalEntry> entries) {
        final List<FloorMapObject> objects = new ArrayList<>();
        String backgroundImage = null;
        FloorMapTransformationMatrix bgMatrix = null;

        for (final TemporalEntry entry : entries) {
            try {
                if (entry.getValue() == null || !entry.getValue().trim().startsWith("{")) {
                    continue;
                }
                final JSONObject json = JSONUtil.getObject(JSONUtil.parse(entry.getValue()));
                if (json == null) {
                    continue;
                }

                final String type = JSONUtil.getString(json.get("type"));

                if ("background".equalsIgnoreCase(type)
                        || "background".equalsIgnoreCase(entry.getKey())) {
                    // Background
                    backgroundImage = JSONUtil.getString(json.get("img"));

                    final JSONArray m2sArr = JSONUtil.getArray(json.get("tm-map-to-screen"));
                    if (m2sArr != null && m2sArr.size() >= 6) {
                        bgMatrix = new FloorMapTransformationMatrix(
                                JSONUtil.getDouble(m2sArr.get(0)),
                                JSONUtil.getDouble(m2sArr.get(1)),
                                JSONUtil.getDouble(m2sArr.get(2)),
                                JSONUtil.getDouble(m2sArr.get(3)),
                                JSONUtil.getDouble(m2sArr.get(4)),
                                JSONUtil.getDouble(m2sArr.get(5)));
                    }
                } else {
                    // Regular object
                    double x = 0;
                    double y = 0;
                    final JSONArray coords = JSONUtil.getArray(json.get("coords"));
                    if (coords != null && coords.size() >= 2) {
                        x = JSONUtil.getDouble(coords.get(0));
                        y = JSONUtil.getDouble(coords.get(1));
                    }
                    final FloorMapObject obj = new FloorMapObject(entry.getKey(), type != null ? type : "", x, y);
                    objects.add(obj);
                }
            } catch (final Exception ex) {
                // Skip malformed entries
            }
        }

        floorMapCanvasPresenter.setBackgroundImage(backgroundImage);
        if (bgMatrix != null) {
            floorMapCanvasPresenter.setMatrix(bgMatrix);
        }
        floorMapCanvasPresenter.setObjects(objects);
        if (selectedFactKey != null) {
            floorMapCanvasPresenter.setSelectedObjectId(selectedFactKey);
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
        selectedFactKey = objectId;
        // Highlight in Fact List without re-firing the consumer
        floorMapFactListPresenter.setSelected(objectId);
        // Load Time List
        loadTimeListForSelectedFact();
    }

    /**
     * Called when the user finishes dragging an object on the canvas.
     * Stages an update in the pending-changes buffer.
     *
     * @param objectId the moved object's fact key
     * @param x        new X coordinate in map space
     * @param y        new Y coordinate in map space
     */
    private void onObjectMovedOnCanvas(final String objectId, final double x, final double y) {
        // Search the full canvas snapshot (all keys at current time) so that dragging
        // any object works regardless of which key is selected in the Time List.
        final List<TemporalEntry> all = pendingChanges.applyTo(serverEntriesAtCurrentTime);
        for (final TemporalEntry e : all) {
            if (objectId.equals(e.getKey())) {
                // Build an updated entry with the new coordinates
                final TemporalEntry updated = buildUpdatedEntryWithCoords(e, x, y);
                if (updated != null) {
                    pendingChanges.recordUpdate(updated);
                    setDirty(true);
                }
                break;
            }
        }
        // Optimistic canvas + Time List refresh
        refreshCanvas();
        refreshTimeListOptimistic();
    }

    /**
     * Called every time the canvas fires a live-drag notification (before
     * mouse-up). Immediately mirrors the dragged object's coordinates into the
     * Properties panel so the user sees live feedback, without staging a change
     * in the pending-changes buffer (that happens in
     * {@link #onObjectMovedOnCanvas} on mouse-up).
     *
     * @param objectId the dragged object's ID
     * @param x        current X in map space
     * @param y        current Y in map space
     * @param bgMatrix current background transformation matrix (reserved for
     *                 future use; not used by this method)
     */
    private void onCanvasDrag(final String objectId,
                               final double x,
                               final double y,
                               final FloorMapTransformationMatrix bgMatrix) {
        floorMapObjectEditPresenter.updateCoords(x, y);
    }

    /**
     * Called when a row in the Fact List is selected.
     *
     * @param factObject the selected fact, or {@code null}
     */
    private void onFactSelected(final FloorMapFactListPresenter.FactObject factObject) {
        if (factObject == null) {
            selectedFactKey = null;
            floorMapCanvasPresenter.setSelectedObjectId(null);
            floorMapTimeListPresenter.setData(new ArrayList<>());
            return;
        }
        selectedFactKey = factObject.getKey();
        floorMapCanvasPresenter.setSelectedObjectId(selectedFactKey);

        // Pass object info to Properties panel
        floorMapObjectEditPresenter.setMapName(getMapName());
        floorMapObjectEditPresenter.setObject(selectedFactKey);

        loadTimeListForSelectedFact();
    }

    /**
     * Called when a row in the Time List is selected.
     *
     * @param entry the selected entry, or {@code null}
     */
    private void onEntrySelected(final TemporalEntry entry) {
        if (entry == null) {
            return;
        }
        // Show entry in Properties panel and enable dragging
        floorMapCanvasPresenter.setIsDraggingEnabled(true);
    }

    /**
     * Called when the Time List's Add button is clicked.
     * Creates a new entry cloned from the currently selected one (or defaults),
     * staged in the pending-changes buffer.
     */
    private void onAddEntry() {
        final String mapName = getMapName();
        if (mapName == null || selectedFactKey == null) {
            return;
        }
        // Use current time as effective time for the new entry
        final long newTime = System.currentTimeMillis();
        final TemporalEntry selected = floorMapTimeListPresenter.getSelectedEntry();
        final TemporalEntry newEntry = cloneEntryAtTime(selected, mapName, selectedFactKey, newTime);
        pendingChanges.recordCreation(newEntry);
        setDirty(true);
        refreshTimeListOptimistic();
    }

    /**
     * Called when the Time List's Delete button is clicked on an entry.
     * Stages a deletion in the pending-changes buffer; the entry disappears
     * immediately from the Time List (optimistic hide).
     *
     * @param entry the entry to delete
     */
    private void onDeleteEntry(final TemporalEntry entry) {
        final TemporalEntryId id = new TemporalEntryId(
                entry.getMap(), entry.getKey(), entry.getEffectiveTimeMs());
        pendingChanges.recordDeletion(id);
        setDirty(true);
        refreshTimeListOptimistic();
    }

    // -----------------------------------------------------------------------
    // "Show all" toggle
    // -----------------------------------------------------------------------

    /**
     * Called when the Fact List's "Show all" button is toggled.
     *
     * @param showAll {@code true} to ignore the time filter
     */
    public void onShowAllToggled(final boolean showAll) {
        this.showAllFacts = showAll;
        final String mapName = getMapName();
        if (mapName == null) {
            return;
        }
        if (showAll) {
            fetchAll(mapName);
        } else {
            fetchAtTime(mapName, selectedTime);
        }
    }

    // -----------------------------------------------------------------------
    // Optimistic refresh helpers
    // -----------------------------------------------------------------------

    /**
     * Refreshes the Time List from {@link #serverEntriesForSelectedFact}
     * overlaid with pending changes, then auto-selects the last entry.
     */
    private void refreshTimeListOptimistic() {
        final List<TemporalEntry> merged = pendingChanges.applyTo(serverEntriesForSelectedFact);
        merged.sort(Comparator.comparingLong(TemporalEntry::getEffectiveTimeMs));
        floorMapTimeListPresenter.setData(merged);
        floorMapTimeListPresenter.selectLast();
    }

    /** Refreshes the canvas using the latest Fact List data at the current time. */
    private void refreshCanvas() {
        loadAtTime(selectedTime);
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
        pendingChanges.clear();
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
            loadAtTime(selectedTime);
            if (selectedFactKey != null) {
                fetchTimeList(mapName, selectedFactKey);
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
        if (mapName == null || selectedFactKey == null) {
            floorMapTimeListPresenter.setData(new ArrayList<>());
            return;
        }
        fetchTimeList(mapName, selectedFactKey);
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
                || doc.getTemporalStoreRef() == null
                || doc.getTemporalStoreRef().getName() == null
                || doc.getTemporalStoreRef().getName().isEmpty()) {
            return null;
        }
        return doc.getTemporalStoreRef().getName();
    }

    /**
     * Returns a new entry cloned from {@code source} but with {@code newTime}
     * as its effective time. If {@code source} is {@code null} a blank entry is
     * returned. Coordinates are preserved from the source.
     *
     * @param source     the entry to clone; may be {@code null}
     * @param mapName    the temporal store map name
     * @param key        the fact key
     * @param newTime    the effective time for the cloned entry
     * @return the new entry; never {@code null}
     */
    private static TemporalEntry cloneEntryAtTime(final TemporalEntry source,
                                                   final String mapName,
                                                   final String key,
                                                   final long newTime) {
        final String value = source != null ? source.getValue() : "{}";
        return new TemporalEntry(mapName, key, newTime, value != null ? value : "{}");
    }

    /**
     * Returns an updated copy of {@code original} with new x/y coordinates
     * patched into its JSON value. Returns {@code null} if the value cannot
     * be parsed.
     *
     * @param original the entry to update
     * @param x        the new x coordinate
     * @param y        the new y coordinate
     * @return the updated entry, or {@code null} on parse failure
     */
    private static TemporalEntry buildUpdatedEntryWithCoords(final TemporalEntry original,
                                                              final double x,
                                                              final double y) {
        try {
            final String raw = original.getValue();
            if (raw == null || !raw.trim().startsWith("{")) {
                return null;
            }
            final JSONObject json = JSONUtil.getObject(JSONUtil.parse(raw));
            if (json == null) {
                return null;
            }
            final JSONArray coordsArr = new JSONArray();
            coordsArr.set(0, new com.google.gwt.json.client.JSONNumber(x));
            coordsArr.set(1, new com.google.gwt.json.client.JSONNumber(y));
            json.put("coords", coordsArr);
            return new TemporalEntry(
                    original.getMap(), original.getKey(),
                    original.getEffectiveTimeMs(), json.toString());
        } catch (final Exception ex) {
            return null;
        }
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
