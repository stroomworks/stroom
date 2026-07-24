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

package stroom.floormap.shared;

import stroom.floormap.shared.FloorMapFieldMapping.Role;
import stroom.util.shared.TemporalEntry;
import stroom.util.shared.TemporalEntryId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * GWT-free model for the FloorMap Editor tab.
 *
 * <p>This class encapsulates all shared selection state and pure business
 * logic that was previously embedded in the GWT presenter. It has no
 * dependencies on GWT, GWTP, or any client-side framework, making it
 * fully testable with standard JUnit.</p>
 *
 * <h3>Shared selection model (single source of truth)</h3>
 * <ul>
 *   <li>{@link #selectedFactKeys} — the selected fact keys (single-select today)</li>
 *   <li>{@link #selectedTime} — current timeline position in ms</li>
 *   <li>{@link #showAllFacts} — whether "show all" mode is active</li>
 *   <li>{@link #pendingChanges} — staged edits awaiting flush</li>
 * </ul>
 *
 * <h3>Staged saves</h3>
 * <p>All edits are buffered in {@link FloorMapPendingChanges}. The presenter
 * is responsible for flushing them to the server and calling
 * {@link #clearPendingChanges()} on success or failure.</p>
 */
public class FloorMapEditorModel {

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    /**
     * Currently selected fact keys, in selection order. Backs both the
     * single-select façade ({@link #getSelectedFactKey()} /
     * {@link #setSelectedFactKey(String)}) used by the current UI and the
     * multi-select API ({@link #getSelectedFactKeys()} etc.) that a future
     * rubber-band / modifier-key UI will drive. A {@link java.util.LinkedHashSet}
     * so the first-selected key can serve as the "primary" selection for the
     * properties panel and time list.
     */
    private final Set<String> selectedFactKeys = new LinkedHashSet<>();

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
     * Populated whenever entries are fetched from the server.
     */
    private List<TemporalEntry> serverEntriesAtCurrentTime = new ArrayList<>();

    /** Buffer of staged edits awaiting the next flush. */
    private final FloorMapPendingChanges pendingChanges = new FloorMapPendingChanges();

    /** Random number generator for key generation. */
    private final Random random;

    /** Warning consumer, e.g. wired to Console.warn() in GWT. */
    private final Consumer<String> warningConsumer;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Creates a new model.
     *
     * @param random          random number generator for key generation
     * @param warningConsumer callback for warning messages; may be {@code null}
     */
    public FloorMapEditorModel(final Random random,
                               final Consumer<String> warningConsumer) {
        this.random = random != null ? random : new Random();
        this.warningConsumer = warningConsumer;
    }

    // -----------------------------------------------------------------------
    // State accessors
    // -----------------------------------------------------------------------

    /**
     * The "primary" selected fact key — the first-selected of the current
     * selection, or {@code null} if nothing is selected. Drives the properties
     * panel and time list (single-fact views).
     */
    public String getSelectedFactKey() {
        final Iterator<String> it = selectedFactKeys.iterator();
        return it.hasNext() ? it.next() : null;
    }

    /**
     * Single-select façade: replaces the whole selection with {@code key}
     * (or clears it when {@code key} is {@code null}).
     */
    public void setSelectedFactKey(final String selectedFactKey) {
        selectedFactKeys.clear();
        if (selectedFactKey != null) {
            selectedFactKeys.add(selectedFactKey);
        }
    }

    /** The full selection, in selection order; never {@code null}. */
    public Set<String> getSelectedFactKeys() {
        return java.util.Collections.unmodifiableSet(selectedFactKeys);
    }

    /** Adds a key to the selection (multi-select). No-op if already selected. */
    public void addToSelection(final String key) {
        if (key != null) {
            selectedFactKeys.add(key);
        }
    }

    /** Removes a key from the selection. */
    public void removeFromSelection(final String key) {
        selectedFactKeys.remove(key);
    }

    /** Toggles a key's presence in the selection (multi-select with modifiers). */
    public void toggleSelection(final String key) {
        if (key != null && !selectedFactKeys.remove(key)) {
            selectedFactKeys.add(key);
        }
    }

    /** Replaces the whole selection with the given keys, in order. */
    public void setSelection(final Collection<String> keys) {
        selectedFactKeys.clear();
        if (keys != null) {
            for (final String key : keys) {
                if (key != null) {
                    selectedFactKeys.add(key);
                }
            }
        }
    }

    /** Clears the selection. */
    public void clearSelection() {
        selectedFactKeys.clear();
    }

    /** Whether the given key is currently selected. */
    public boolean isSelected(final String key) {
        return selectedFactKeys.contains(key);
    }

    public long getSelectedTime() {
        return selectedTime;
    }

    public void setSelectedTime(final long selectedTime) {
        this.selectedTime = selectedTime;
    }

    public boolean isShowAllFacts() {
        return showAllFacts;
    }

    public void setShowAllFacts(final boolean showAllFacts) {
        this.showAllFacts = showAllFacts;
    }

    public List<TemporalEntry> getServerEntriesForSelectedFact() {
        return serverEntriesForSelectedFact;
    }

    public void setServerEntriesForSelectedFact(final List<TemporalEntry> entries) {
        this.serverEntriesForSelectedFact = entries != null ? entries : new ArrayList<>();
    }

    public List<TemporalEntry> getServerEntriesAtCurrentTime() {
        return serverEntriesAtCurrentTime;
    }

    public FloorMapPendingChanges getPendingChanges() {
        return pendingChanges;
    }

    // -----------------------------------------------------------------------
    // Pending changes
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when there are staged edits awaiting flush.
     */
    public boolean hasPendingChanges() {
        return pendingChanges.isDirty();
    }

    /**
     * Clears all pending changes. Called after a successful or failed flush.
     */
    public void clearPendingChanges() {
        pendingChanges.clear();
    }

    // -----------------------------------------------------------------------
    // Data loading callbacks
    // -----------------------------------------------------------------------

    /**
     * Called when entries are fetched from the server (at the current time or
     * for "show all"). Updates the server entries snapshot and returns a
     * merged list (server + pending changes) for UI display.
     *
     * @param entries the entries from the server; may be {@code null}
     * @return merged entries for UI display; never {@code null}
     */
    public List<TemporalEntry> onEntriesFetched(final List<TemporalEntry> entries) {
        serverEntriesAtCurrentTime = entries != null ? entries : new ArrayList<>();
        return pendingChanges.applyTo(entries);
    }

    /**
     * Called when the time list entries are fetched for the selected fact.
     * Stores the entries, sorts them, and returns them merged with pending
     * changes for UI display.
     *
     * @param entries the server-sourced entries for the selected fact
     */
    public void onTimeListFetched(final List<TemporalEntry> entries) {
        if (entries != null) {
            serverEntriesForSelectedFact = new ArrayList<>(entries);
        } else {
            serverEntriesForSelectedFact = new ArrayList<>();
        }
        serverEntriesForSelectedFact.sort(
                Comparator.comparingLong(TemporalEntry::getEffectiveTimeMs));
    }

    // -----------------------------------------------------------------------
    // Merged data views
    // -----------------------------------------------------------------------

    /**
     * Returns the merged canvas entries (server + pending changes).
     *
     * @return merged entries; never {@code null}
     */
    public List<TemporalEntry> buildMergedCanvasEntries() {
        return pendingChanges.applyTo(serverEntriesAtCurrentTime);
    }

    /**
     * Overlays the pending changes on an arbitrary entry list <em>without</em>
     * touching the canvas snapshot ({@code serverEntriesAtCurrentTime}). Used
     * for side lists fetched independently of the canvas — e.g. the "show all"
     * Fact List — so unflushed creations/updates still appear in them.
     *
     * @param entries the server-sourced entries; may be {@code null}
     * @return the entries with pending changes applied; never {@code null}
     */
    public List<TemporalEntry> mergePendingChanges(final List<TemporalEntry> entries) {
        return pendingChanges.applyTo(entries);
    }

    /**
     * Builds a merged and filtered time list for the currently selected fact.
     *
     * <p>The returned list is independent of the current timeline position:
     * it contains every time shard for the selected fact key (server entries
     * overlaid with pending changes), sorted ascending by effective time.
     * Callers that need to highlight the entry active at a particular time do
     * so separately (e.g. via {@link #findActiveIndexAtTime(List, long)}).</p>
     *
     * @return a sorted, filtered list of entries for the selected fact key;
     *         never {@code null}
     */
    public List<TemporalEntry> buildMergedTimeList() {
        final String primaryKey = getSelectedFactKey();
        final List<TemporalEntry> merged = pendingChanges.applyTo(serverEntriesForSelectedFact);
        merged.removeIf(e -> !e.getKey().equals(primaryKey));
        merged.sort(Comparator.comparingLong(TemporalEntry::getEffectiveTimeMs));
        return merged;
    }

    /**
     * Returns {@code true} if the selected fact already has a time shard at
     * exactly {@code timeMs} (server or pending, staged deletions applied).
     * Used to warn before "Add Time Version" would otherwise overwrite an
     * existing shard at the same effective time.
     *
     * @param timeMs the candidate effective time
     * @return {@code true} if a shard already exists at that exact time
     */
    public boolean selectedFactHasEntryAtTime(final long timeMs) {
        for (final TemporalEntry e : buildMergedTimeList()) {
            if (e.getEffectiveTimeMs() == timeMs) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the index in a sorted time list of the entry that is "active" at
     * the given time (i.e. the last entry with effectiveTimeMs ≤ timeMs).
     *
     * @param timeList the sorted time list
     * @param timeMs   the time position
     * @return the index, or -1 if no entry qualifies
     */
    public static int findActiveIndexAtTime(final List<TemporalEntry> timeList,
                                             final long timeMs) {
        int bestIndex = -1;
        for (int i = 0; i < timeList.size(); i++) {
            if (timeList.get(i).getEffectiveTimeMs() <= timeMs) {
                bestIndex = i;
            } else {
                break;
            }
        }
        return bestIndex;
    }

    // -----------------------------------------------------------------------
    // Canvas parsing
    // -----------------------------------------------------------------------

    /**
     * Parses merged entries into the ordered {@link Fact} list the canvas renders.
     *
     * <p>The canvas renders, per key, the single shard <strong>active at
     * {@link #getSelectedTime()}</strong> — the latest shard whose effective
     * time is {@code <= selectedTime}. The server fetch is already
     * time-filtered one-per-key, so for server entries this is a no-op; the
     * filter matters for the overlaid <em>pending changes</em>, which may sit
     * at other effective times (an edit of a future shard, a translate staged
     * against an earlier shard). Without it a key could render — and drag — as
     * several overlaid time versions, or show a not-yet-effective pending
     * value.</p>
     *
     * <p>Safety net: when the selected time is unset ({@code <= 0}) no time
     * filter is applied and the latest shard per key wins, so the canvas is not
     * blanked before the scrubber is initialised.</p>
     *
     * @param entries  the merged entries to parse
     * @param schema   the value schema
     * @param accessor the value accessor
     * @return the fact list, one per key active at the scrubber; never {@code null}
     */
    public List<Fact> parseForCanvas(
            final List<TemporalEntry> entries,
            final List<FloorMapFieldMapping> schema,
            final ValueAccessor accessor) {
        return FloorMapEntryParser.parse(
                activeEntriesAtSelectedTime(entries), schema, accessor, warningConsumer);
    }

    /**
     * Reduces entries to a single entry per key — the one with the greatest
     * effective time that is active at {@link #selectedTime} (i.e.
     * {@code effectiveTime <= selectedTime}) — preserving first-seen key order.
     * Shards not yet effective at the scrubber are dropped. When
     * {@code selectedTime <= 0} the time filter is skipped and the latest shard
     * per key wins. Returns {@code null} unchanged so the parser's own null
     * handling applies.
     */
    private List<TemporalEntry> activeEntriesAtSelectedTime(final List<TemporalEntry> entries) {
        if (entries == null) {
            return null;
        }
        final Map<String, TemporalEntry> byKey = new LinkedHashMap<>();
        for (final TemporalEntry e : entries) {
            if (selectedTime > 0 && e.getEffectiveTimeMs() > selectedTime) {
                continue;
            }
            final TemporalEntry existing = byKey.get(e.getKey());
            if (existing == null
                    || e.getEffectiveTimeMs() >= existing.getEffectiveTimeMs()) {
                byKey.put(e.getKey(), e);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * Returns the single merged (server + pending) entry for {@code key} that is
     * <em>active at {@link #selectedTime}</em> — i.e. the exact shard the canvas
     * is currently rendering (see {@link #parseForCanvas}). Edits must target
     * this shard; matching the first entry by key instead can land an edit on a
     * historical shard when a pending time-version exists at another effective
     * time, silently moving the wrong version.
     *
     * @param key the fact key
     * @return the active merged entry, or {@code null} if none
     */
    private TemporalEntry activeMergedEntryForKey(final String key) {
        final List<TemporalEntry> active = activeEntriesAtSelectedTime(
                pendingChanges.applyTo(serverEntriesAtCurrentTime));
        if (active == null) {
            return null;
        }
        for (final TemporalEntry e : active) {
            if (key.equals(e.getKey())) {
                return e;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Object move (coordinate update)
    // -----------------------------------------------------------------------

    /**
     * Records an object move by updating the coordinates in the entry's value
     * and staging the update in the pending-changes buffer.
     *
     * @param objectId the moved object's fact key
     * @param mapX     new X coordinate in map space
     * @param mapY     new Y coordinate in map space
     * @param schema   the value schema
     * @param accessor the value accessor
     * @return {@code true} if the move was recorded, {@code false} if the
     *         object was not found or an error occurred
     * @throws IllegalStateException if the schema is null or empty
     */
    public boolean recordObjectMove(final String objectId,
                                    final double mapX,
                                    final double mapY,
                                    final List<FloorMapFieldMapping> schema,
                                    final ValueAccessor accessor) {
        if (schema == null || schema.isEmpty()) {
            throw new IllegalStateException(
                    "No Value Schema is configured. "
                    + "Please configure a Value Schema in the Settings tab.");
        }
        if (FloorMapEntryParser.findPath(schema, Role.POSITION) == null) {
            // Without a Position mapping the new coordinates have nowhere to be
            // written; fail loudly rather than staging an update that would
            // silently persist nothing.
            throw new IllegalStateException(
                    "No Position field is mapped in the Value Schema. "
                    + "Add a field with the Position role in the Settings tab.");
        }
        // Target the shard the canvas is showing, not just the first key match.
        final TemporalEntry e = activeMergedEntryForKey(objectId);
        if (e != null) {
            pendingChanges.recordUpdate(
                    buildUpdatedEntryWithCoords(e, mapX, mapY, schema, accessor));
            return true;
        }
        return false;
    }

    /**
     * Records a full affine transform for a fact by writing all six components
     * ({@code a,b,c,d,e,f}) of its placement matrix. This is the general
     * capability behind future rotate/scale tools — a drag is simply the case
     * where only the translation changes.
     *
     * <p>The matrix is written to the fact's {@code WORLD_TO_MAP} role; the fact
     * is looked up by key. Backgrounds are not special-cased — they use
     * {@code WORLD_TO_MAP} like every other fact.</p>
     *
     * @return {@code true} if a matching fact was found and an update staged
     * @throws IllegalStateException if the schema is null or empty
     */
    public boolean recordFactTransform(final String objectId,
                                       final FloorMapTransformationMatrix matrix,
                                       final List<FloorMapFieldMapping> schema,
                                       final ValueAccessor accessor) {
        if (schema == null || schema.isEmpty()) {
            throw new IllegalStateException(
                    "No Value Schema is configured. "
                    + "Please configure a Value Schema in the Settings tab.");
        }
        if (FloorMapEntryParser.findPath(schema, Role.WORLD_TO_MAP) == null) {
            // No placement field mapped — skip rather than stage a no-op.
            return false;
        }
        // Target the shard the canvas is showing, not just the first key match.
        final TemporalEntry e = activeMergedEntryForKey(objectId);
        if (e != null) {
            pendingChanges.recordUpdate(buildUpdatedEntryWithMatrix(
                    e, Role.WORLD_TO_MAP, matrix, schema, accessor));
            return true;
        }
        return false;
    }

    /**
     * Translates each of the given facts by {@code (dx, dy)} in map space as a
     * single batch action — the model side of a multi-select move. Each fact's
     * placement matrix has {@code (dx, dy)} added to its translation
     * ({@code e, f}); rotation/scale ({@code a, b, c, d}) are preserved. Facts
     * not found are skipped.
     *
     * <p>Thin wrapper over {@link #transformFacts} with a pure-translation
     * transform: {@code translate(dx,dy) · oldMatrix} adds {@code (dx,dy)} to
     * {@code e, f} and leaves {@code a, b, c, d} untouched.</p>
     *
     * @return the number of facts translated
     * @throws IllegalStateException if the schema is null or empty
     */
    public int translateFacts(final Collection<String> objectIds,
                              final double dx,
                              final double dy,
                              final List<FloorMapFieldMapping> schema,
                              final ValueAccessor accessor) {
        return transformFacts(objectIds,
                FloorMapTransformationMatrix.translate(dx, dy), schema, accessor);
    }

    /**
     * Applies a map-space affine {@code mapSpaceTransform} to each of the given
     * facts as a single batch action — the model side of a multi-select
     * move/rotate/scale. Because a fact's placement is {@code map = worldToMap ·
     * world}, transforming the whole selection by {@code T} means setting each
     * fact's {@code newWorldToMap = T · oldWorldToMap} (via
     * {@link FloorMapTransformationMatrix#multiply}). This simultaneously
     * repositions the fact (its {@code e, f}) and reorients/rescales its own
     * {@code a, b, c, d} about the transform's pivot — correct for both single
     * and group transforms. A missing/short matrix defaults to identity. Facts
     * not found are skipped, and {@code POSITION} coords are left untouched
     * (placement is matrix-based).
     *
     * <p>Build {@code mapSpaceTransform} with the pivot baked in — e.g.
     * {@link FloorMapTransformationMatrix#rotateAbout} /
     * {@link FloorMapTransformationMatrix#scaleAbout} about the selection's
     * bounding-box centre. Reads the merged (server + pending) state so gestures
     * compose onto already-staged edits.</p>
     *
     * @return the number of facts transformed
     * @throws IllegalStateException if the schema is null or empty
     */
    public int transformFacts(final Collection<String> objectIds,
                              final FloorMapTransformationMatrix mapSpaceTransform,
                              final List<FloorMapFieldMapping> schema,
                              final ValueAccessor accessor) {
        if (objectIds == null || objectIds.isEmpty()) {
            return 0;
        }
        if (schema == null || schema.isEmpty()) {
            throw new IllegalStateException(
                    "No Value Schema is configured. "
                    + "Please configure a Value Schema in the Settings tab.");
        }
        if (FloorMapEntryParser.findPath(schema, Role.WORLD_TO_MAP) == null) {
            // No placement field mapped — an update would write nowhere and
            // stage a no-op that still marks the doc dirty. Skip.
            return 0;
        }
        int transformed = 0;
        for (final String objectId : objectIds) {
            // Target the shard the canvas is showing, not just the first key
            // match (which may be a historical shard under a pending edit).
            final TemporalEntry e = activeMergedEntryForKey(objectId);
            if (e != null) {
                final ParsedValue parsed = accessor.parse(e.getValue());
                if (parsed != null) {
                    double[] m = accessor.getArray(
                            parsed, FloorMapEntryParser.findPath(schema, Role.WORLD_TO_MAP));
                    if (m == null || m.length < 6) {
                        m = new double[]{1, 0, 0, 1, 0, 0};
                    }
                    final FloorMapTransformationMatrix oldMatrix =
                            new FloorMapTransformationMatrix(
                                    m[0], m[1], m[2], m[3], m[4], m[5]);
                    final FloorMapTransformationMatrix newMatrix =
                            mapSpaceTransform.multiply(oldMatrix);
                    pendingChanges.recordUpdate(buildUpdatedEntryWithMatrix(
                            e, Role.WORLD_TO_MAP, newMatrix, schema, accessor));
                    transformed++;
                }
            }
        }
        return transformed;
    }

    /**
     * Records an edit to an area's geometry (moved / inserted / deleted vertices).
     * The vertices are in the fact's local frame and written verbatim to the
     * {@code GEOMETRY} role — {@code WORLD_TO_MAP} is left unchanged, so the area
     * is edited in place. Staged like any other change and flushed on save.
     *
     * @param key           the area fact's key
     * @param localVertices the new vertices in local frame ({@code >= 3})
     * @param schema        the value schema
     * @param accessor      the value accessor
     * @return {@code true} if an entry was found and updated
     */
    public boolean updateFactGeometry(final String key,
                                      final double[][] localVertices,
                                      final List<FloorMapFieldMapping> schema,
                                      final ValueAccessor accessor) {
        if (key == null || localVertices == null || localVertices.length < 3) {
            return false;
        }
        if (schema == null || schema.isEmpty()) {
            throw new IllegalStateException(
                    "No Value Schema is configured. "
                    + "Please configure a Value Schema in the Settings tab.");
        }
        if (FloorMapEntryParser.findPath(schema, Role.GEOMETRY) == null) {
            // No geometry field mapped — writing would be a no-op that still
            // marks the doc dirty and snaps back on refresh. Skip.
            return false;
        }
        final double[] flatLocal = new double[localVertices.length * 2];
        for (int i = 0; i < localVertices.length; i++) {
            flatLocal[i * 2] = localVertices[i][0];
            flatLocal[i * 2 + 1] = localVertices[i][1];
        }
        // Target the shard the canvas is showing, not just the first key match.
        final TemporalEntry e = activeMergedEntryForKey(key);
        if (e != null) {
            pendingChanges.recordUpdate(
                    buildUpdatedEntryWithGeometry(e, flatLocal, schema, accessor));
            return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Object deletion
    // -----------------------------------------------------------------------

    /**
     * Stages deletions for all known time entries of the given fact key.
     * Returns {@code true} if at least one deletion was staged.
     *
     * @param key the fact key to delete
     * @return {@code true} if any deletions were staged
     */
    public boolean stageFactDeletion(final String key) {
        final List<TemporalEntry> all = pendingChanges.applyTo(serverEntriesAtCurrentTime);
        final List<TemporalEntry> merged = new ArrayList<>(all);
        final Set<TemporalEntryId> seenIds = new HashSet<>();
        for (final TemporalEntry e : merged) {
            seenIds.add(new TemporalEntryId(e.getMap(), e.getKey(), e.getEffectiveTimeMs()));
        }
        for (final TemporalEntry e : serverEntriesForSelectedFact) {
            final TemporalEntryId id = new TemporalEntryId(
                    e.getMap(), e.getKey(), e.getEffectiveTimeMs());
            if (e.getKey().equals(key) && !seenIds.contains(id)) {
                merged.add(e);
                seenIds.add(id);
            }
        }
        boolean staged = false;
        for (final TemporalEntry e : merged) {
            if (key.equals(e.getKey())) {
                pendingChanges.recordDeletion(
                        new TemporalEntryId(e.getMap(), e.getKey(), e.getEffectiveTimeMs()));
                staged = true;
            }
        }
        // Deselect the deleted fact if it was part of the selection.
        selectedFactKeys.remove(key);
        return staged;
    }

    /**
     * Stages deletions for <em>every</em> shard of {@code key} given the full
     * set of that key's server shards (fetched by the caller across all
     * effective times), plus any pending creation for the key. Use this rather
     * than {@link #stageFactDeletion(String)} for a "delete object" action: the
     * latter only sees the shard active at the scrubber plus the selected
     * fact's time list, so it silently leaves other time versions behind and
     * the fact reappears.
     *
     * @param key         the fact key to delete
     * @param serverShards all server shards for the key (any effective time)
     * @return {@code true} if at least one deletion was staged
     */
    public boolean stageFactDeletionForAllShards(final String key,
                                                 final List<TemporalEntry> serverShards) {
        if (key == null) {
            return false;
        }
        final List<TemporalEntry> all = new ArrayList<>();
        if (serverShards != null) {
            all.addAll(serverShards);
        }
        // Include pending creations for the key (no server shard yet), so a
        // just-created-but-unsaved fact is removed too.
        all.addAll(pendingChanges.applyTo(new ArrayList<>()));
        final Set<TemporalEntryId> seen = new HashSet<>();
        boolean staged = false;
        for (final TemporalEntry e : all) {
            if (key.equals(e.getKey())) {
                final TemporalEntryId id = new TemporalEntryId(
                        e.getMap(), e.getKey(), e.getEffectiveTimeMs());
                if (seen.add(id)) {
                    pendingChanges.recordDeletion(id);
                    staged = true;
                }
            }
        }
        selectedFactKeys.remove(key);
        return staged;
    }

    /**
     * Stages the deletion of a single time entry and returns the rebuilt time
     * list with a suggested selection index (the entry above the deleted one).
     *
     * @param entry the entry to delete
     * @return the suggested selection index after deletion, or -1
     */
    public int stageTimeEntryDeletion(final TemporalEntry entry) {
        final TemporalEntryId id = new TemporalEntryId(
                entry.getMap(), entry.getKey(), entry.getEffectiveTimeMs());
        pendingChanges.recordDeletion(id);

        final List<TemporalEntry> merged = buildMergedTimeList();

        // Find where the deleted entry would have sat.
        int deletedIndex = merged.size();
        for (int i = 0; i < merged.size(); i++) {
            if (merged.get(i).getEffectiveTimeMs() > entry.getEffectiveTimeMs()) {
                deletedIndex = i;
                break;
            }
        }
        return deletedIndex - 1;
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
     * @param prefix a human-readable prefix (e.g. {@code "new"}, {@code "gate-1-copy"})
     * @return a key string suitable for use as a temporal-store fact key
     */
    public String generateObjectKey(final String prefix) {
        final List<TemporalEntry> merged =
                pendingChanges.applyTo(serverEntriesAtCurrentTime);
        final Set<String> existingKeys = new HashSet<>();
        for (final TemporalEntry e : merged) {
            existingKeys.add(e.getKey());
        }

        final int maxAttempts = 1_000;
        for (int i = 0; i < maxAttempts; i++) {
            final String candidate = prefix + "-"
                    + random.nextInt(99999);
            if (!existingKeys.contains(candidate)) {
                return candidate;
            }
        }

        // Extremely unlikely fallback — append a timestamp to guarantee uniqueness
        return prefix + "-" + System.currentTimeMillis();
    }

    // -----------------------------------------------------------------------
    // Static utility methods
    // -----------------------------------------------------------------------

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
    public static TemporalEntry cloneEntryAtTime(final TemporalEntry source,
                                                  final String mapName,
                                                  final String key,
                                                  final long newTime) {
        final String value = source != null ? source.getValue() : "{}";
        return new TemporalEntry(mapName, key, newTime, value != null ? value : "{}");
    }

    /**
     * Builds a new time shard for the currently selected fact at the given
     * scrubber time, cloning its attributes from the shard active at that time
     * (the latest shard whose effective time is at or before {@code timeMs}).
     * When the time precedes every shard, a blank entry is returned.
     *
     * @param mapName the temporal store map name
     * @param timeMs  the effective time for the new shard (the scrubber position)
     * @return the new entry; never {@code null}
     */
    public TemporalEntry buildNewEntryAtTime(final String mapName, final long timeMs) {
        final List<TemporalEntry> timeList = buildMergedTimeList();
        final int activeIndex = findActiveIndexAtTime(timeList, timeMs);
        final TemporalEntry source = activeIndex >= 0 ? timeList.get(activeIndex) : null;
        return cloneEntryAtTime(source, mapName, getSelectedFactKey(), timeMs);
    }

    /**
     * Builds a duplicate of {@code source} under {@code newKey}, offset by
     * ({@code dx}, {@code dy}) map-space units so it does not sit on top of the
     * original.
     *
     * <p>The offset is applied to the fact's {@code WORLD_TO_MAP} translation —
     * the same components a drag-move shifts (see {@link #translateFacts}) —
     * because that is what actually positions a fact on the canvas: image facts
     * are placed solely by the matrix, and imageless facts are placed by their
     * POSITION <em>through</em> the matrix. Offsetting only POSITION would
     * therefore leave an image-bearing duplicate directly on top of the
     * original. The copy's label is repointed at {@code newKey}.</p>
     *
     * <p>If the source value cannot be parsed it is copied verbatim under the
     * new key (no offset, no relabel).</p>
     *
     * @param source          the entry to duplicate
     * @param mapName         the temporal store map name
     * @param newKey          the key for the duplicate
     * @param effectiveTimeMs the effective time for the duplicate
     * @param dx              the map-space X offset
     * @param dy              the map-space Y offset
     * @param schema          the value schema
     * @param accessor        the value accessor
     * @return the new duplicate entry; never {@code null}
     */
    public static TemporalEntry buildDuplicateEntry(final TemporalEntry source,
                                                    final String mapName,
                                                    final String newKey,
                                                    final long effectiveTimeMs,
                                                    final double dx,
                                                    final double dy,
                                                    final List<FloorMapFieldMapping> schema,
                                                    final ValueAccessor accessor) {
        final ParsedValue parsed = accessor.parse(source.getValue());
        if (parsed == null) {
            return new TemporalEntry(mapName, newKey, effectiveTimeMs, source.getValue());
        }

        // Shift the placement matrix's translation (indices 4, 5) by (dx, dy) in
        // map space so the duplicate moves clear of the original — for image and
        // imageless facts alike. Defaults to identity when no matrix is present.
        final String w2mPath = FloorMapEntryParser.findPath(schema, Role.WORLD_TO_MAP);
        double[] m = accessor.getArray(parsed, w2mPath);
        if (m == null || m.length < 6) {
            m = new double[]{1, 0, 0, 1, 0, 0};
        }
        m[4] += dx;
        m[5] += dy;
        accessor.setArray(parsed, w2mPath, m);

        // Repoint the copy's label at its new key.
        accessor.setString(parsed, FloorMapEntryParser.findPath(schema, Role.LABEL), newKey);

        return new TemporalEntry(mapName, newKey, effectiveTimeMs, accessor.serialize(parsed));
    }

    /**
     * Builds a new area entry from a polygon drawn on the canvas.
     *
     * <p>The vertices arrive in <em>map space</em> (click order). They are
     * stored in the fact's <em>local</em> frame, centred on their centroid,
     * with {@code WORLD_TO_MAP = translate(centroid)} — so the existing
     * move/scale/rotate handles pivot about the polygon's middle and areas
     * inherit duplicate/time-versioning like every other fact. {@code POSITION}
     * is set to the local origin (the centroid).</p>
     *
     * @param mapName         the temporal store map name
     * @param key             the new fact key (also used as the label)
     * @param mapVertices     the polygon vertices in map space; at least 3
     * @param effectiveTimeMs the effective time for the new entry
     * @param schema          the value schema (must map the area roles)
     * @param accessor        the value accessor
     * @return the new entry; never {@code null}
     * @throws IllegalArgumentException if fewer than 3 vertices are supplied
     * @throws IllegalStateException    if the schema lacks a required role
     */
    public static TemporalEntry buildAreaEntry(final String mapName,
                                               final String key,
                                               final List<double[]> mapVertices,
                                               final long effectiveTimeMs,
                                               final List<FloorMapFieldMapping> schema,
                                               final ValueAccessor accessor) {
        if (mapVertices == null || mapVertices.size() < 3) {
            throw new IllegalArgumentException(
                    "An area needs at least 3 vertices");
        }

        double cx = 0;
        double cy = 0;
        for (final double[] v : mapVertices) {
            cx += v[0];
            cy += v[1];
        }
        cx /= mapVertices.size();
        cy /= mapVertices.size();

        final double[] flatLocal = new double[mapVertices.size() * 2];
        for (int i = 0; i < mapVertices.size(); i++) {
            flatLocal[i * 2] = mapVertices.get(i)[0] - cx;
            flatLocal[i * 2 + 1] = mapVertices.get(i)[1] - cy;
        }

        final ParsedValue value = accessor.createEmpty("entry");
        accessor.setString(value, requirePath(schema, Role.TYPE), FloorMapJsonKeys.AREA);
        accessor.setString(value, requirePath(schema, Role.LABEL), key);
        accessor.setArray(value, requirePath(schema, Role.POSITION), new double[]{0, 0});
        accessor.setArray(value, requirePath(schema, Role.WORLD_TO_MAP),
                new double[]{1, 0, 0, 1, cx, cy});
        accessor.setArray(value, requirePath(schema, Role.GEOMETRY), flatLocal);

        return new TemporalEntry(mapName, key, effectiveTimeMs, accessor.serialize(value));
    }

    /**
     * Resolves the path for {@code role}, failing loudly (rather than writing
     * to a null path, which the accessors silently ignore) when the schema
     * does not map it.
     */
    private static String requirePath(final List<FloorMapFieldMapping> schema,
                                      final Role role) {
        final String path = FloorMapEntryParser.findPath(schema, role);
        if (path == null) {
            throw new IllegalStateException(
                    "The Value Schema for this Floor Map does not define a mapping "
                    + "for the '" + role + "' role. Please add a '" + role
                    + "' mapping in the Settings tab under Value Schema.");
        }
        return path;
    }


    /**
     * Builds a copy of {@code original} with its {@code coords} field replaced
     * by the supplied map-space {@code x} and {@code y} values.
     *
     * <p>The canvas fires coordinates in <em>map space</em> (after the
     * world-to-map transform). Since the JSON {@code coords} field stores
     * <em>world-space</em> values, this method applies the inverse of the
     * entry's world-to-map matrix before writing.</p>
     *
     * @param original the entry to update; must not be {@code null}
     * @param mapX     the new X coordinate in map space
     * @param mapY     the new Y coordinate in map space
     * @param schema   the value schema
     * @param accessor the value accessor
     * @return a new {@link TemporalEntry} with the updated value
     * @throws IllegalStateException if the entry's value cannot be parsed
     */
    public static TemporalEntry buildUpdatedEntryWithCoords(final TemporalEntry original,
                                                             final double mapX,
                                                             final double mapY,
                                                             final List<FloorMapFieldMapping> schema,
                                                             final ValueAccessor accessor) {
        final String raw = original.getValue();
        final ParsedValue parsed = accessor.parse(raw);
        if (parsed == null) {
            throw new IllegalStateException(
                    "Entry value could not be parsed: " + raw);
        }

        // Convert map-space coordinates back to world space using the
        // inverse of the entry's world-to-map matrix.
        FloorMapTransformationMatrix worldToMap = FloorMapTransformationMatrix.identity();
        final double[] w2mArr = accessor.getArray(
                parsed, FloorMapEntryParser.findPath(schema, Role.WORLD_TO_MAP));
        if (w2mArr != null && w2mArr.length >= 6) {
            worldToMap = new FloorMapTransformationMatrix(
                    w2mArr[0], w2mArr[1], w2mArr[2],
                    w2mArr[3], w2mArr[4], w2mArr[5]);
        }
        final FloorMapTransformationMatrix inv = worldToMap.inverse();
        final double worldX = inv.getA() * mapX + inv.getC() * mapY + inv.getE();
        final double worldY = inv.getB() * mapX + inv.getD() * mapY + inv.getF();

        accessor.setArray(parsed,
                FloorMapEntryParser.findPath(schema, Role.POSITION),
                new double[]{worldX, worldY});
        return new TemporalEntry(
                original.getMap(),
                original.getKey(),
                original.getEffectiveTimeMs(),
                accessor.serialize(parsed));
    }

    /**
     * Builds an updated entry with the given {@code role}'s matrix set to the
     * full six components of {@code matrix}. This is the general full-affine
     * write behind {@link #recordFactTransform} (translate, rotate and scale).
     *
     * @param original the entry to update
     * @param role     the matrix role to write (e.g. {@code WORLD_TO_MAP})
     * @param matrix   the full transform to persist
     * @param schema   the value schema
     * @param accessor the value accessor
     * @return a new {@link TemporalEntry} with the updated matrix
     * @throws IllegalStateException if the entry's value cannot be parsed
     */
    public static TemporalEntry buildUpdatedEntryWithMatrix(
            final TemporalEntry original,
            final Role role,
            final FloorMapTransformationMatrix matrix,
            final List<FloorMapFieldMapping> schema,
            final ValueAccessor accessor) {
        final ParsedValue parsed = accessor.parse(original.getValue());
        if (parsed == null) {
            throw new IllegalStateException(
                    "Entry value could not be parsed: " + original.getValue());
        }
        accessor.setArray(parsed, FloorMapEntryParser.findPath(schema, role),
                new double[]{matrix.getA(), matrix.getB(), matrix.getC(),
                        matrix.getD(), matrix.getE(), matrix.getF()});
        return new TemporalEntry(
                original.getMap(),
                original.getKey(),
                original.getEffectiveTimeMs(),
                accessor.serialize(parsed));
    }

    /**
     * Builds a copy of {@code original} with new area geometry — the flat local
     * vertex array {@code [x0,y0,x1,y1,...]} written to the {@code GEOMETRY} role.
     *
     * @param original          the entry to update
     * @param flatLocalVertices the new geometry (local frame, flat pairs)
     * @param schema            the value schema
     * @param accessor          the value accessor
     * @return a new {@link TemporalEntry} with the updated geometry
     * @throws IllegalStateException if the entry's value cannot be parsed
     */
    public static TemporalEntry buildUpdatedEntryWithGeometry(
            final TemporalEntry original,
            final double[] flatLocalVertices,
            final List<FloorMapFieldMapping> schema,
            final ValueAccessor accessor) {
        final ParsedValue parsed = accessor.parse(original.getValue());
        if (parsed == null) {
            throw new IllegalStateException(
                    "Entry value could not be parsed: " + original.getValue());
        }
        accessor.setArray(parsed, FloorMapEntryParser.findPath(schema, Role.GEOMETRY),
                flatLocalVertices);
        return new TemporalEntry(
                original.getMap(),
                original.getKey(),
                original.getEffectiveTimeMs(),
                accessor.serialize(parsed));
    }
}
