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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
 *   <li>{@link #selectedFactKey} — key of the selected fact, or {@code null}</li>
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

    public String getSelectedFactKey() {
        return selectedFactKey;
    }

    public void setSelectedFactKey(final String selectedFactKey) {
        this.selectedFactKey = selectedFactKey;
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
        final List<TemporalEntry> merged = pendingChanges.applyTo(serverEntriesForSelectedFact);
        merged.removeIf(e -> !e.getKey().equals(selectedFactKey));
        merged.sort(Comparator.comparingLong(TemporalEntry::getEffectiveTimeMs));
        return merged;
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
     * Parses merged entries into canvas-ready data.
     *
     * @param entries  the merged entries to parse
     * @param schema   the value schema
     * @param accessor the value accessor
     * @return the parse result; never {@code null}
     */
    public FloorMapEntryParser.ParseResult parseForCanvas(
            final List<TemporalEntry> entries,
            final List<FloorMapFieldMapping> schema,
            final ValueAccessor accessor) {
        return FloorMapEntryParser.parse(entries, schema, accessor, warningConsumer);
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
        // The canvas always identifies the background by the literal id
        // "background" regardless of the entry's actual key, so locate the
        // background by the same rule the parser uses (key OR type).
        final boolean wantBackground = FloorMapJsonKeys.BACKGROUND.equalsIgnoreCase(objectId);
        final List<TemporalEntry> all = pendingChanges.applyTo(serverEntriesAtCurrentTime);
        for (final TemporalEntry e : all) {
            final boolean matches = wantBackground
                    ? isBackgroundEntry(e, schema, accessor)
                    : objectId.equals(e.getKey());
            if (matches) {
                if (schema == null || schema.isEmpty()) {
                    throw new IllegalStateException(
                            "No Value Schema is configured. "
                            + "Please configure a Value Schema in the Settings tab.");
                }
                // The background's position is stored in its map-to-screen matrix,
                // not in the POSITION coords field used by regular objects.
                final TemporalEntry updated = wantBackground
                        ? buildUpdatedBackgroundEntry(e, mapX, mapY, schema, accessor)
                        : buildUpdatedEntryWithCoords(e, mapX, mapY, schema, accessor);
                pendingChanges.recordUpdate(updated);
                return true;
            }
        }
        return false;
    }

    /**
     * Records a full affine transform for a fact by writing all six components
     * ({@code a,b,c,d,e,f}) of its placement matrix. This is the general
     * capability behind future rotate/scale tools — a drag is simply the case
     * where only the translation changes.
     *
     * <p>The matrix is written to the fact's placement role: {@code MAP_TO_SCREEN}
     * for the background, {@code WORLD_TO_MAP} otherwise (matching where placement
     * lives today). Fact lookup mirrors {@link #recordObjectMove} — by background
     * identity or by key.</p>
     *
     * <p>Note: the live editor drag still goes through {@link #recordObjectMove};
     * routing drag/rotate/scale through this method is Phase 2 work, once fact
     * placement moves fully onto {@code WORLD_TO_MAP}.</p>
     *
     * @return {@code true} if a matching fact was found and an update staged
     * @throws IllegalStateException if the schema is null or empty
     */
    public boolean recordFactTransform(final String objectId,
                                       final FloorMapTransformationMatrix matrix,
                                       final List<FloorMapFieldMapping> schema,
                                       final ValueAccessor accessor) {
        final boolean wantBackground = FloorMapJsonKeys.BACKGROUND.equalsIgnoreCase(objectId);
        final List<TemporalEntry> all = pendingChanges.applyTo(serverEntriesAtCurrentTime);
        for (final TemporalEntry e : all) {
            final boolean matches = wantBackground
                    ? isBackgroundEntry(e, schema, accessor)
                    : objectId.equals(e.getKey());
            if (matches) {
                if (schema == null || schema.isEmpty()) {
                    throw new IllegalStateException(
                            "No Value Schema is configured. "
                            + "Please configure a Value Schema in the Settings tab.");
                }
                final Role role = wantBackground ? Role.MAP_TO_SCREEN : Role.WORLD_TO_MAP;
                pendingChanges.recordUpdate(
                        buildUpdatedEntryWithMatrix(e, role, matrix, schema, accessor));
                return true;
            }
        }
        return false;
    }

    /**
     * Determines whether an entry represents the map background, using the same
     * rule as {@link FloorMapEntryParser}: its key is {@code "background"} or its
     * {@code TYPE} field is {@code "background"} (case-insensitive). Falls back to
     * a key-only test when no schema is available.
     *
     * @param entry    the entry to test
     * @param schema   the value schema, or {@code null}
     * @param accessor the value accessor
     * @return {@code true} if the entry is the background
     */
    private static boolean isBackgroundEntry(final TemporalEntry entry,
                                             final List<FloorMapFieldMapping> schema,
                                             final ValueAccessor accessor) {
        if (FloorMapJsonKeys.BACKGROUND.equalsIgnoreCase(entry.getKey())) {
            return true;
        }
        if (schema == null || schema.isEmpty()) {
            return false;
        }
        try {
            final ParsedValue parsed = accessor.parse(entry.getValue());
            if (parsed == null) {
                return false;
            }
            final String type = accessor.getString(
                    parsed, FloorMapEntryParser.findPath(schema, Role.TYPE));
            return FloorMapJsonKeys.BACKGROUND.equalsIgnoreCase(type);
        } catch (final RuntimeException ex) {
            return false;
        }
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
        if (key.equals(selectedFactKey)) {
            selectedFactKey = null;
        }
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
     * Builds an updated background entry, moving it to a new position by
     * updating the translation components of its {@code MAP_TO_SCREEN} matrix.
     *
     * <p>Unlike a regular object (whose position lives in the {@code POSITION}
     * coords field — see {@link #buildUpdatedEntryWithCoords}), the background's
     * position is the translation of its map-to-screen matrix. Only the
     * translation (matrix components {@code e} and {@code f}, i.e. indices 4 and
     * 5) is changed; rotation/scale ({@code a,b,c,d}) is preserved, matching the
     * canvas drag behaviour which only ever translates the background.</p>
     *
     * @param original the entry to update
     * @param e        the new map-to-screen translation X (matrix component e)
     * @param f        the new map-to-screen translation Y (matrix component f)
     * @param schema   the value schema
     * @param accessor the value accessor
     * @return a new {@link TemporalEntry} with the updated matrix
     * @throws IllegalStateException if the entry's value cannot be parsed
     */
    public static TemporalEntry buildUpdatedBackgroundEntry(final TemporalEntry original,
                                                            final double e,
                                                            final double f,
                                                            final List<FloorMapFieldMapping> schema,
                                                            final ValueAccessor accessor) {
        final String raw = original.getValue();
        final ParsedValue parsed = accessor.parse(raw);
        if (parsed == null) {
            throw new IllegalStateException(
                    "Entry value could not be parsed: " + raw);
        }

        final String path = FloorMapEntryParser.findPath(schema, Role.MAP_TO_SCREEN);
        double[] matrix = accessor.getArray(parsed, path);
        if (matrix == null || matrix.length < 6) {
            // No (usable) matrix present — start from identity, then translate.
            matrix = new double[]{1, 0, 0, 1, 0, 0};
        }
        // Preserve rotation/scale (a, b, c, d); update only the translation (e, f).
        matrix[4] = e;
        matrix[5] = f;
        accessor.setArray(parsed, path, matrix);

        return new TemporalEntry(
                original.getMap(),
                original.getKey(),
                original.getEffectiveTimeMs(),
                accessor.serialize(parsed));
    }

    /**
     * Builds an updated entry with the given {@code role}'s matrix set to the
     * full six components of {@code matrix}. This is the general full-affine
     * write behind {@link #recordFactTransform} (translate, rotate and scale),
     * as opposed to the translation-only {@link #buildUpdatedBackgroundEntry}.
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
}
