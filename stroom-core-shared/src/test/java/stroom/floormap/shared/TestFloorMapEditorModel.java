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

import stroom.util.shared.TemporalEntry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class TestFloorMapEditorModel {

    private static final String MAP = "testMap";
    private static final List<FloorMapFieldMapping> SCHEMA =
            FloorMapFieldMapping.initialValueSchema();
    private static final MapValueAccessor ACCESSOR = MapValueAccessor.INSTANCE;

    private FloorMapEditorModel model;
    private List<String> warnings;

    @BeforeEach
    void setUp() {
        warnings = new ArrayList<>();
        model = new FloorMapEditorModel(new Random(42), warnings::add);
        warnings.clear(); // clear any warnings emitted during construction
    }

    // -----------------------------------------------------------------------
    // Selection state
    // -----------------------------------------------------------------------

    /**
     * A freshly-constructed model has no selection, is at time zero, has
     * "show all facts" off, and has no pending changes.
     */
    @Test
    void testInitialState() {
        assertThat(model.getSelectedFactKey()).isNull();
        assertThat(model.getSelectedTime()).isEqualTo(0);
        assertThat(model.isShowAllFacts()).isFalse();
        assertThat(model.hasPendingChanges()).isFalse();
    }

    /**
     * Setting the selected fact key stores it and it can be read back.
     */
    @Test
    void testSelectFact() {
        model.setSelectedFactKey("gate-1");
        assertThat(model.getSelectedFactKey()).isEqualTo("gate-1");
    }

    /**
     * Setting the selected fact key to {@code null} clears the selection.
     */
    @Test
    void testDeselectFact() {
        model.setSelectedFactKey("gate-1");
        model.setSelectedFactKey(null);
        assertThat(model.getSelectedFactKey()).isNull();
    }

    /**
     * The selected time can be set and read back.
     */
    @Test
    void testTimeChange() {
        model.setSelectedTime(12345L);
        assertThat(model.getSelectedTime()).isEqualTo(12345L);
    }

    /**
     * The "show all facts" flag can be toggled on and off.
     */
    @Test
    void testShowAllToggle() {
        model.setShowAllFacts(true);
        assertThat(model.isShowAllFacts()).isTrue();
        model.setShowAllFacts(false);
        assertThat(model.isShowAllFacts()).isFalse();
    }

    // -----------------------------------------------------------------------
    // onEntriesFetched
    // -----------------------------------------------------------------------

    /**
     * Fetched server entries are stored, and the returned merged list
     * includes any pending creation staged before the fetch completed.
     */
    @Test
    void testOnEntriesFetched_storesAndMerges() {
        final List<TemporalEntry> server = List.of(entry("k1", 100, "{}"));
        model.getPendingChanges().recordCreation(entry("k2", 200, "{}"));

        final List<TemporalEntry> merged = model.onEntriesFetched(server);
        assertThat(merged).hasSize(2);
        assertThat(model.getServerEntriesAtCurrentTime()).hasSize(1);
    }

    /**
     * Fetching {@code null} entries is treated as an empty server result
     * rather than throwing.
     */
    @Test
    void testOnEntriesFetched_null() {
        final List<TemporalEntry> merged = model.onEntriesFetched(null);
        assertThat(merged).isEmpty();
        assertThat(model.getServerEntriesAtCurrentTime()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // buildMergedTimeList
    // -----------------------------------------------------------------------

    /**
     * The merged time list only includes entries whose key matches the
     * currently selected fact.
     */
    @Test
    void testBuildMergedTimeList_filtersToSelectedKey() {
        model.setSelectedFactKey("k1");
        model.setServerEntriesForSelectedFact(List.of(
                entry("k1", 100, "{}"),
                entry("k1", 200, "{}"),
                entry("k2", 150, "{}")));

        final List<TemporalEntry> result = model.buildMergedTimeList();
        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(e -> assertThat(e.getKey()).isEqualTo("k1"));
    }

    /**
     * The merged time list is returned sorted by effective time, regardless
     * of the input order.
     */
    @Test
    void testBuildMergedTimeList_sorted() {
        model.setSelectedFactKey("k1");
        model.setServerEntriesForSelectedFact(List.of(
                entry("k1", 300, "{}"),
                entry("k1", 100, "{}"),
                entry("k1", 200, "{}")));

        final List<TemporalEntry> result = model.buildMergedTimeList();
        assertThat(result.getFirst().getEffectiveTimeMs()).isEqualTo(100);
        assertThat(result.get(1).getEffectiveTimeMs()).isEqualTo(200);
        assertThat(result.get(2).getEffectiveTimeMs()).isEqualTo(300);
    }

    /**
     * A pending creation for the selected fact appears in the merged time
     * list alongside the server-known entries.
     */
    @Test
    void testBuildMergedTimeList_includesPendingCreation() {
        model.setSelectedFactKey("k1");
        model.setServerEntriesForSelectedFact(List.of(entry("k1", 100, "{}")));
        model.getPendingChanges().recordCreation(entry("k1", 200, "{\"new\":true}"));

        final List<TemporalEntry> result = model.buildMergedTimeList();
        assertThat(result).hasSize(2);
    }

    /**
     * A pending deletion for the selected fact hides the matching entry from
     * the merged time list.
     */
    @Test
    void testBuildMergedTimeList_excludesPendingDeletion() {
        model.setSelectedFactKey("k1");
        model.setServerEntriesForSelectedFact(List.of(
                entry("k1", 100, "{}"),
                entry("k1", 200, "{}")));
        model.getPendingChanges().recordDeletion(
                new stroom.util.shared.TemporalEntryId(MAP, "k1", 100L));

        final List<TemporalEntry> result = model.buildMergedTimeList();
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getEffectiveTimeMs()).isEqualTo(200);
    }

    // -----------------------------------------------------------------------
    // findActiveIndexAtTime
    // -----------------------------------------------------------------------

    /**
     * A query time before the earliest entry has no active entry, i.e. -1.
     */
    @Test
    void testFindActiveIndex_beforeAll() {
        final List<TemporalEntry> timeList = List.of(
                entry("k1", 100, "{}"),
                entry("k1", 200, "{}"));
        assertThat(FloorMapEditorModel.findActiveIndexAtTime(timeList, 50)).isEqualTo(-1);
    }

    /**
     * A query time that exactly matches an entry's effective time returns
     * that entry's index.
     */
    @Test
    void testFindActiveIndex_exactMatch() {
        final List<TemporalEntry> timeList = List.of(
                entry("k1", 100, "{}"),
                entry("k1", 200, "{}"));
        assertThat(FloorMapEditorModel.findActiveIndexAtTime(timeList, 100)).isEqualTo(0);
        assertThat(FloorMapEditorModel.findActiveIndexAtTime(timeList, 200)).isEqualTo(1);
    }

    /**
     * A query time falling between two entries returns the index of the
     * most recent entry at or before that time.
     */
    @Test
    void testFindActiveIndex_betweenEntries() {
        final List<TemporalEntry> timeList = List.of(
                entry("k1", 100, "{}"),
                entry("k1", 200, "{}"),
                entry("k1", 300, "{}"));
        assertThat(FloorMapEditorModel.findActiveIndexAtTime(timeList, 150)).isEqualTo(0);
        assertThat(FloorMapEditorModel.findActiveIndexAtTime(timeList, 250)).isEqualTo(1);
    }

    /**
     * A query time after the latest entry returns the index of the last
     * entry, since it remains the active value going forward.
     */
    @Test
    void testFindActiveIndex_afterAll() {
        final List<TemporalEntry> timeList = List.of(
                entry("k1", 100, "{}"),
                entry("k1", 200, "{}"));
        assertThat(FloorMapEditorModel.findActiveIndexAtTime(timeList, 999)).isEqualTo(1);
    }

    /**
     * An empty time list has no active entry at any time, i.e. -1.
     */
    @Test
    void testFindActiveIndex_empty() {
        assertThat(FloorMapEditorModel.findActiveIndexAtTime(List.of(), 100)).isEqualTo(-1);
    }

    // -----------------------------------------------------------------------
    // Key generation
    // -----------------------------------------------------------------------

    /**
     * A generated object key starts with the requested prefix followed by a
     * separator.
     */
    @Test
    void testGenerateObjectKey_prefixPreserved() {
        final String key = model.generateObjectKey("gate");
        assertThat(key).startsWith("gate-");
    }

    /**
     * Two successive calls with the same prefix generate distinct keys.
     */
    @Test
    void testGenerateObjectKey_unique() {
        final String k1 = model.generateObjectKey("obj");
        final String k2 = model.generateObjectKey("obj");
        assertThat(k1).isNotEqualTo(k2);
    }

    /**
     * Key generation avoids colliding with a key already known to the model
     * from previously-fetched server entries.
     */
    @Test
    void testGenerateObjectKey_avoidsExisting() {
        // Pre-populate the server entries so the model knows which keys exist
        model.onEntriesFetched(List.of(entry("gate-12345", 100, "{}")));
        final String key = model.generateObjectKey("gate");
        assertThat(key).isNotEqualTo("gate-12345");
    }

    // -----------------------------------------------------------------------
    // cloneEntryAtTime
    // -----------------------------------------------------------------------

    /**
     * Cloning an entry at a new time copies the source value and key while
     * moving the effective time to the requested value.
     */
    @Test
    void testCloneEntryAtTime_copiesValue() {
        final TemporalEntry source = entry("k1", 100, "{\"type\":\"gate\"}");
        final TemporalEntry clone = FloorMapEditorModel.cloneEntryAtTime(
                source, MAP, "k1", 200);
        assertThat(clone.getEffectiveTimeMs()).isEqualTo(200);
        assertThat(clone.getValue()).isEqualTo("{\"type\":\"gate\"}");
        assertThat(clone.getKey()).isEqualTo("k1");
    }

    /**
     * Cloning with a {@code null} source produces an entry with an empty
     * JSON object value rather than throwing.
     */
    @Test
    void testCloneEntryAtTime_nullSource() {
        final TemporalEntry clone = FloorMapEditorModel.cloneEntryAtTime(
                null, MAP, "k1", 200);
        assertThat(clone.getEffectiveTimeMs()).isEqualTo(200);
        assertThat(clone.getValue()).isEqualTo("{}");
    }

    // -----------------------------------------------------------------------
    // buildUpdatedEntryWithCoords
    // -----------------------------------------------------------------------

    /**
     * With no world-to-map matrix present, new map-space coordinates are
     * written into the entry's value unchanged.
     */
    @Test
    void testBuildUpdatedEntryWithCoords_identityMatrix() {
        final String json = "{\"type\":\"gate\",\"coords\":[0,0]}";
        final TemporalEntry original = entry("k1", 100, json);

        final TemporalEntry updated = FloorMapEditorModel.buildUpdatedEntryWithCoords(
                original, 50.0, 75.0, SCHEMA, ACCESSOR);

        final ParsedValue parsed = ACCESSOR.parse(updated.getValue());
        final double[] coords = ACCESSOR.getArray(parsed, ".coords");
        assertThat(coords[0]).isCloseTo(50.0, within(0.001));
        assertThat(coords[1]).isCloseTo(75.0, within(0.001));
    }

    /**
     * New coordinates given in map space are converted back to world space
     * via the inverse of the entry's world-to-map scale transform.
     */
    @Test
    void testBuildUpdatedEntryWithCoords_withScale() {
        // World-to-map: scale 2x. Inverse: scale 0.5x
        // Map coords (100, 200) → world coords (50, 100)
        final String json = "{\"type\":\"gate\",\"coords\":[0,0],"
                + "\"tm-world-to-map\":[2,0,0,2,0,0]}";
        final TemporalEntry original = entry("k1", 100, json);

        final TemporalEntry updated = FloorMapEditorModel.buildUpdatedEntryWithCoords(
                original, 100.0, 200.0, SCHEMA, ACCESSOR);

        final ParsedValue parsed = ACCESSOR.parse(updated.getValue());
        final double[] coords = ACCESSOR.getArray(parsed, ".coords");
        assertThat(coords[0]).isCloseTo(50.0, within(0.001));
        assertThat(coords[1]).isCloseTo(100.0, within(0.001));
    }

    /**
     * New coordinates given in map space are converted back to world space
     * via the inverse of the entry's world-to-map translation transform.
     */
    @Test
    void testBuildUpdatedEntryWithCoords_withTranslation() {
        // World-to-map: translate (10, 20). Inverse: translate (-10, -20)
        // Map coords (60, 80) → world coords (50, 60)
        final String json = "{\"type\":\"gate\",\"coords\":[0,0],"
                + "\"tm-world-to-map\":[1,0,0,1,10,20]}";
        final TemporalEntry original = entry("k1", 100, json);

        final TemporalEntry updated = FloorMapEditorModel.buildUpdatedEntryWithCoords(
                original, 60.0, 80.0, SCHEMA, ACCESSOR);

        final ParsedValue parsed = ACCESSOR.parse(updated.getValue());
        final double[] coords = ACCESSOR.getArray(parsed, ".coords");
        assertThat(coords[0]).isCloseTo(50.0, within(0.001));
        assertThat(coords[1]).isCloseTo(60.0, within(0.001));
    }

    // -----------------------------------------------------------------------
    // Background translation via WORLD_TO_MAP
    //
    // A background is no longer moved through a separate map-to-screen write
    // path (buildUpdatedBackgroundEntry is gone). It is translated like any
    // other fact, by shifting its WORLD_TO_MAP matrix translation. These tests
    // preserve the original intents — translation-only, rotation/scale
    // preserved, identity default — re-pointed at translateFacts / WORLD_TO_MAP.
    // -----------------------------------------------------------------------

    /**
     * Translating a background shifts the translation components (indices 4, 5)
     * of its WORLD_TO_MAP matrix, and leaves the POSITION coords field
     * untouched.
     */
    @Test
    void testBackgroundTranslate_updatesMatrixTranslation() {
        model.onEntriesFetched(List.of(entry("background", 100,
                "{\"type\":\"background\",\"coords\":[5,5],"
                        + "\"tm-world-to-map\":[1,0,0,1,0,0]}")));

        final int moved = model.translateFacts(List.of("background"), 60.0, 80.0, SCHEMA, ACCESSOR);
        assertThat(moved).isEqualTo(1);

        final TemporalEntry updated = model.buildMergedCanvasEntries().stream()
                .filter(e -> e.getKey().equals("background")).findFirst().orElseThrow();
        final ParsedValue parsed = ACCESSOR.parse(updated.getValue());
        final double[] m = ACCESSOR.getArray(parsed, ".tm-world-to-map");
        assertThat(m[4]).isCloseTo(60.0, within(0.001));
        assertThat(m[5]).isCloseTo(80.0, within(0.001));
        // The POSITION coords are not touched by a matrix translation.
        final double[] coords = ACCESSOR.getArray(parsed, ".coords");
        assertThat(coords[0]).isCloseTo(5.0, within(0.001));
        assertThat(coords[1]).isCloseTo(5.0, within(0.001));
    }

    /**
     * Translating a background preserves the existing rotation/scale components
     * (a, b, c, d) of its WORLD_TO_MAP matrix, changing only translation.
     */
    @Test
    void testBackgroundTranslate_preservesRotationScale() {
        model.onEntriesFetched(List.of(entry("background", 100,
                "{\"type\":\"background\","
                        + "\"tm-world-to-map\":[2,0.5,-0.5,2,10,20]}")));

        // Shift by (50, 60) so the translation lands on (60, 80).
        final int moved = model.translateFacts(List.of("background"), 50.0, 60.0, SCHEMA, ACCESSOR);
        assertThat(moved).isEqualTo(1);

        final TemporalEntry updated = model.buildMergedCanvasEntries().stream()
                .filter(e -> e.getKey().equals("background")).findFirst().orElseThrow();
        final double[] m = ACCESSOR.getArray(ACCESSOR.parse(updated.getValue()), ".tm-world-to-map");
        assertThat(m[0]).isCloseTo(2.0, within(0.001));
        assertThat(m[1]).isCloseTo(0.5, within(0.001));
        assertThat(m[2]).isCloseTo(-0.5, within(0.001));
        assertThat(m[3]).isCloseTo(2.0, within(0.001));
        assertThat(m[4]).isCloseTo(60.0, within(0.001));
        assertThat(m[5]).isCloseTo(80.0, within(0.001));
    }

    /**
     * When the background entry has no WORLD_TO_MAP matrix, it defaults to
     * identity before the translation is applied.
     */
    @Test
    void testBackgroundTranslate_defaultsToIdentityWhenMissing() {
        model.onEntriesFetched(List.of(entry("background", 100,
                "{\"type\":\"background\"}")));

        final int moved = model.translateFacts(List.of("background"), 60.0, 80.0, SCHEMA, ACCESSOR);
        assertThat(moved).isEqualTo(1);

        final TemporalEntry updated = model.buildMergedCanvasEntries().stream()
                .filter(e -> e.getKey().equals("background")).findFirst().orElseThrow();
        final double[] m = ACCESSOR.getArray(ACCESSOR.parse(updated.getValue()), ".tm-world-to-map");
        assertThat(m).containsExactly(1.0, 0.0, 0.0, 1.0, 60.0, 80.0);
    }

    // -----------------------------------------------------------------------
    // Pending changes integration
    // -----------------------------------------------------------------------

    /**
     * A pending creation appears in the merged canvas entries alongside the
     * server-known objects.
     */
    @Test
    void testPendingCreation_visibleInMergedList() {
        model.onEntriesFetched(List.of(entry("k1", 100, "{}")));
        model.getPendingChanges().recordCreation(entry("k2", 100, "{}"));
        final List<TemporalEntry> merged = model.buildMergedCanvasEntries();
        assertThat(merged).hasSize(2);
    }

    /**
     * A pending update overlays the server entry's value in the merged
     * canvas entries rather than appearing as an extra entry.
     */
    @Test
    void testPendingUpdate_replacesInMergedList() {
        model.onEntriesFetched(List.of(entry("k1", 100, "{\"old\":true}")));
        model.getPendingChanges().recordUpdate(entry("k1", 100, "{\"new\":true}"));
        final List<TemporalEntry> merged = model.buildMergedCanvasEntries();
        assertThat(merged).hasSize(1);
        assertThat(merged.getFirst().getValue()).isEqualTo("{\"new\":true}");
    }

    /**
     * A pending deletion hides the matching object from the merged canvas
     * entries, leaving other objects visible.
     */
    @Test
    void testPendingDeletion_hiddenFromMergedList() {
        model.onEntriesFetched(List.of(
                entry("k1", 100, "{}"),
                entry("k2", 100, "{}")));
        model.getPendingChanges().recordDeletion(
                new stroom.util.shared.TemporalEntryId(MAP, "k1", 100L));
        final List<TemporalEntry> merged = model.buildMergedCanvasEntries();
        assertThat(merged).hasSize(1);
        assertThat(merged.getFirst().getKey()).isEqualTo("k2");
    }

    /**
     * Clearing pending changes on the model resets {@code hasPendingChanges}
     * to {@code false}.
     */
    @Test
    void testClearPendingChanges() {
        model.getPendingChanges().recordCreation(entry("k1", 100, "{}"));
        assertThat(model.hasPendingChanges()).isTrue();
        model.clearPendingChanges();
        assertThat(model.hasPendingChanges()).isFalse();
    }

    // -----------------------------------------------------------------------
    // Time shard management
    // -----------------------------------------------------------------------

    /**
     * A fact with several time shards produces a merged time list
     * containing all of them, sorted by effective time.
     */
    @Test
    void testTimeListShards_multipleTimesForSameKey() {
        model.setSelectedFactKey("k1");
        model.setServerEntriesForSelectedFact(List.of(
                entry("k1", 100, "{\"v\":1}"),
                entry("k1", 200, "{\"v\":2}"),
                entry("k1", 300, "{\"v\":3}")));

        final List<TemporalEntry> timeList = model.buildMergedTimeList();
        assertThat(timeList).hasSize(3);
        assertThat(timeList.getFirst().getEffectiveTimeMs()).isEqualTo(100);
        assertThat(timeList.get(1).getEffectiveTimeMs()).isEqualTo(200);
        assertThat(timeList.get(2).getEffectiveTimeMs()).isEqualTo(300);
    }

    /**
     * Staging deletion of a middle time entry suggests selecting the index
     * immediately before the deleted entry's former position.
     */
    @Test
    void testTimeEntryDeletion_suggestsPreviousIndex() {
        model.setSelectedFactKey("k1");
        model.setServerEntriesForSelectedFact(List.of(
                entry("k1", 100, "{}"),
                entry("k1", 200, "{}"),
                entry("k1", 300, "{}")));
        model.setSelectedTime(200);

        final int suggestedIndex = model.stageTimeEntryDeletion(entry("k1", 200, "{}"));
        // After deleting 200, the list is [100, 300].
        // The entry at time 200 would have been at index 1, so suggestion is 0.
        assertThat(suggestedIndex).isEqualTo(0);
    }

    /**
     * Staging deletion of the first time entry, with nothing before it,
     * suggests no selection (index -1).
     */
    @Test
    void testTimeEntryDeletion_deletingFirst_suggestsMinusOne() {
        model.setSelectedFactKey("k1");
        model.setServerEntriesForSelectedFact(List.of(
                entry("k1", 100, "{}"),
                entry("k1", 200, "{}")));
        model.setSelectedTime(100);

        final int suggestedIndex = model.stageTimeEntryDeletion(entry("k1", 100, "{}"));
        assertThat(suggestedIndex).isEqualTo(-1);
    }

    /**
     * A newly staged time version for the selected fact appears in the
     * merged time list at its effective time.
     */
    @Test
    void testAddTimeVersion_appearsInTimeList() {
        model.setSelectedFactKey("k1");
        model.setServerEntriesForSelectedFact(List.of(
                entry("k1", 100, "{}")));
        model.getPendingChanges().recordCreation(entry("k1", 200, "{\"new\":true}"));

        final List<TemporalEntry> timeList = model.buildMergedTimeList();
        assertThat(timeList).hasSize(2);
        assertThat(timeList.get(1).getEffectiveTimeMs()).isEqualTo(200);
    }

    /**
     * When entries for multiple facts are present, the merged time list only
     * shows entries for the currently selected fact.
     */
    @Test
    void testOnlySelectedFactShownInTimeList() {
        model.setSelectedFactKey("k1");
        model.setServerEntriesForSelectedFact(List.of(
                entry("k1", 100, "{}"),
                entry("k2", 100, "{}"),
                entry("k1", 200, "{}")));

        final List<TemporalEntry> timeList = model.buildMergedTimeList();
        assertThat(timeList).hasSize(2);
        assertThat(timeList).allSatisfy(e -> assertThat(e.getKey()).isEqualTo("k1"));
    }

    // -----------------------------------------------------------------------
    // parseForCanvas
    // -----------------------------------------------------------------------

    /**
     * Parsing a valid batch of entries for the canvas yields one fact per
     * entry — the image (background) fact and the regular object — with no
     * warnings emitted.
     */
    @Test
    void testParseForCanvas() {
        final List<TemporalEntry> entries = List.of(
                entry("bg", 100, "{\"type\":\"background\",\"img\":\"f.png\"}"),
                entry("g1", 100, "{\"type\":\"gate\",\"coords\":[10,20]}"));

        final List<Fact> facts = model.parseForCanvas(entries, SCHEMA, ACCESSOR);

        assertThat(facts).hasSize(2);
        assertThat(facts.stream().filter(Fact::hasImage).findFirst().orElseThrow().getImage())
                .isEqualTo("f.png");
        assertThat(facts.stream().filter(f -> !f.hasImage()).count()).isEqualTo(1L);
        assertThat(warnings).as("valid entries should not emit warnings").isEmpty();
    }

    /**
     * The canvas shows, per key, the single shard active at the scrubber time —
     * so an object never renders (or drags) as several overlaid time versions,
     * and moving the scrubber changes which shard is shown. Here the scrubber
     * sits between shards, so the earlier one is active and the later is ignored.
     */
    @Test
    void testParseForCanvas_showsShardActiveAtSelectedTime() {
        model.setSelectedTime(250);
        final List<TemporalEntry> entries = List.of(
                entry("bg", 100, "{\"type\":\"background\",\"img\":\"old.png\"}"),
                entry("bg", 300, "{\"type\":\"background\",\"img\":\"future.png\"}"),
                entry("bg", 200, "{\"type\":\"background\",\"img\":\"active.png\"}"),
                entry("g1", 100, "{\"type\":\"gate\",\"coords\":[10,20]}"));

        final List<Fact> facts = model.parseForCanvas(entries, SCHEMA, ACCESSOR);

        assertThat(facts).as("one fact per key").hasSize(2);
        final Fact bg = facts.stream().filter(f -> "bg".equals(f.getKey())).findFirst().orElseThrow();
        assertThat(bg.getImage())
                .as("shard active at t=250 (t=200) wins; the future t=300 shard is ignored")
                .isEqualTo("active.png");
        assertThat(facts.stream().anyMatch(f -> "g1".equals(f.getKey()))).isTrue();
    }

    /**
     * When the entry list holds several shards per key (e.g. pending changes
     * staged at other effective times overlaid on the server data) and the
     * scrubber sits at/after all of them, each key shows its latest active
     * shard — no key is dropped.
     */
    @Test
    void testParseForCanvas_scrubberAtLatest_keepsAllKeys() {
        model.setSelectedTime(1000);
        final List<TemporalEntry> entries = List.of(
                entry("bg", 100, "{\"type\":\"background\",\"img\":\"old.png\"}"),
                entry("bg", 300, "{\"type\":\"background\",\"img\":\"new.png\"}"),
                entry("g1", 200, "{\"type\":\"gate\",\"coords\":[10,20]}"));

        final List<Fact> facts = model.parseForCanvas(entries, SCHEMA, ACCESSOR);

        assertThat(facts).as("one fact per key, none dropped").hasSize(2);
        final Fact bg = facts.stream().filter(f -> "bg".equals(f.getKey())).findFirst().orElseThrow();
        assertThat(bg.getImage()).as("latest active shard (t=300) wins").isEqualTo("new.png");
    }

    /**
     * With the scrubber time unset ({@code <= 0}) the time filter is skipped and
     * the latest shard per key wins, so the canvas is not blanked before the
     * scrubber is initialised.
     */
    @Test
    void testParseForCanvas_collapsesToLatestWhenTimeUnset() {
        model.setSelectedTime(0);
        final List<TemporalEntry> entries = List.of(
                entry("bg", 100, "{\"type\":\"background\",\"img\":\"old.png\"}"),
                entry("bg", 300, "{\"type\":\"background\",\"img\":\"new.png\"}"));

        final List<Fact> facts = model.parseForCanvas(entries, SCHEMA, ACCESSOR);

        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().getImage()).as("latest shard (t=300) wins").isEqualTo("new.png");
    }

    /**
     * A malformed entry in the canvas batch is skipped and reported via the
     * model's warning callback, while the well-formed entry still parses.
     */
    @Test
    void testParseForCanvas_malformedEntry_emitsWarning() {
        final List<TemporalEntry> entries = List.of(
                entry("good", 100, "{\"type\":\"gate\",\"coords\":[5,10]}"),
                entry("bad", 100, "not-json"));

        final List<Fact> facts = model.parseForCanvas(entries, SCHEMA, ACCESSOR);

        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().getKey()).isEqualTo("good");
        assertThat(warnings).as("malformed entry should trigger a warning")
                .hasSize(1);
        assertThat(warnings.getFirst()).contains("bad");
    }

    /**
     * Every malformed entry in the canvas batch produces its own warning via
     * the model's warning callback.
     */
    @Test
    void testParseForCanvas_multipleMalformed_emitsAllWarnings() {
        final List<TemporalEntry> entries = List.of(
                entry("bad1", 100, "xxx"),
                entry("bad2", 100, "yyy"));

        model.parseForCanvas(entries, SCHEMA, ACCESSOR);

        assertThat(warnings).hasSize(2);
        assertThat(warnings.getFirst()).contains("bad1");
        assertThat(warnings.get(1)).contains("bad2");
    }

    /**
     * Parsing an empty entry list for the canvas emits no warnings.
     */
    @Test
    void testParseForCanvas_noWarningsOnEmpty() {
        model.parseForCanvas(List.of(), SCHEMA, ACCESSOR);
        assertThat(warnings).isEmpty();
    }

    // -----------------------------------------------------------------------
    // recordObjectMove
    // -----------------------------------------------------------------------

    /**
     * Moving a known object stages a pending update whose coordinates
     * reflect the new position, and marks the model as having pending
     * changes.
     */
    @Test
    void testRecordObjectMove_existingObject_stagesUpdate() {
        model.onEntriesFetched(List.of(
                entry("g1", 100, "{\"type\":\"gate\",\"coords\":[0,0]}")));

        final boolean recorded = model.recordObjectMove("g1", 40.0, 60.0, SCHEMA, ACCESSOR);

        assertThat(recorded).isTrue();
        assertThat(model.hasPendingChanges()).isTrue();

        final TemporalEntry moved = model.buildMergedCanvasEntries().stream()
                .filter(e -> e.getKey().equals("g1"))
                .findFirst().orElseThrow();
        final double[] coords = ACCESSOR.getArray(ACCESSOR.parse(moved.getValue()), ".coords");
        assertThat(coords[0]).isCloseTo(40.0, within(0.001));
        assertThat(coords[1]).isCloseTo(60.0, within(0.001));
    }

    /**
     * Attempting to move an object key the model has no entry for returns
     * {@code false} and leaves the model with no pending changes.
     */
    @Test
    void testRecordObjectMove_unknownObject_returnsFalse() {
        model.onEntriesFetched(List.of(
                entry("g1", 100, "{\"type\":\"gate\",\"coords\":[0,0]}")));

        final boolean recorded = model.recordObjectMove(
                "does-not-exist", 40.0, 60.0, SCHEMA, ACCESSOR);

        assertThat(recorded).isFalse();
        assertThat(model.hasPendingChanges()).isFalse();
    }

    /**
     * Moving an object with an empty field-mapping schema (no position
     * mapping available) throws {@code IllegalStateException}.
     */
    @Test
    void testRecordObjectMove_noSchema_throws() {
        model.onEntriesFetched(List.of(
                entry("g1", 100, "{\"type\":\"gate\",\"coords\":[0,0]}")));

        assertThatThrownBy(() -> model.recordObjectMove("g1", 40.0, 60.0, List.of(), ACCESSOR))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * A "background" key is not special-cased: moving it writes the new
     * position into the POSITION coords field, exactly like any other fact
     * (there is no separate matrix write path any more). With an identity
     * world-to-map, the map-space drag position is written straight into coords.
     */
    @Test
    void testRecordObjectMove_backgroundKey_updatesCoords() {
        model.onEntriesFetched(List.of(entry("background", 100,
                "{\"type\":\"background\",\"coords\":[5,5],"
                        + "\"tm-world-to-map\":[1,0,0,1,0,0]}")));

        final boolean recorded = model.recordObjectMove("background", 60.0, 80.0, SCHEMA, ACCESSOR);

        assertThat(recorded).isTrue();
        final TemporalEntry moved = model.buildMergedCanvasEntries().stream()
                .filter(e -> e.getKey().equals("background"))
                .findFirst().orElseThrow();
        final ParsedValue parsed = ACCESSOR.parse(moved.getValue());
        final double[] coords = ACCESSOR.getArray(parsed, ".coords");
        assertThat(coords[0]).isCloseTo(60.0, within(0.001));
        assertThat(coords[1]).isCloseTo(80.0, within(0.001));
    }

    /**
     * Facts are matched by key only — there is no type-based "background"
     * detection any more. Firing the literal "background" id against an entry
     * whose key is NOT "background" (even if its type is) matches nothing, so
     * the move is not recorded and nothing is staged.
     */
    @Test
    void testRecordObjectMove_matchesByKeyOnly_notType() {
        model.onEntriesFetched(List.of(entry("floorPlan", 100,
                "{\"type\":\"background\",\"tm-world-to-map\":[1,0,0,1,0,0]}")));

        final boolean recorded = model.recordObjectMove("background", 12.0, 34.0, SCHEMA, ACCESSOR);

        assertThat(recorded).isFalse();
        assertThat(model.hasPendingChanges()).isFalse();
    }

    // -----------------------------------------------------------------------
    // recordFactTransform / buildUpdatedEntryWithMatrix
    // -----------------------------------------------------------------------

    /**
     * A full-matrix transform of a regular fact writes all six components into
     * its WORLD_TO_MAP matrix.
     */
    @Test
    void testRecordFactTransform_regularObject_writesWorldToMap() {
        model.onEntriesFetched(List.of(entry("g1", 100,
                "{\"type\":\"gate\",\"tm-world-to-map\":[1,0,0,1,0,0]}")));

        final boolean recorded = model.recordFactTransform("g1",
                new FloorMapTransformationMatrix(2, 0.1, -0.1, 2, 5, 6), SCHEMA, ACCESSOR);

        assertThat(recorded).isTrue();
        final TemporalEntry moved = model.buildMergedCanvasEntries().stream()
                .filter(e -> e.getKey().equals("g1")).findFirst().orElseThrow();
        final double[] m = ACCESSOR.getArray(ACCESSOR.parse(moved.getValue()), ".tm-world-to-map");
        assertThat(m).containsExactly(2.0, 0.1, -0.1, 2.0, 5.0, 6.0);
    }

    /**
     * A full-matrix transform of the background writes into its WORLD_TO_MAP
     * matrix — backgrounds are not special-cased, they use WORLD_TO_MAP like
     * every other fact.
     */
    @Test
    void testRecordFactTransform_background_writesWorldToMap() {
        model.onEntriesFetched(List.of(entry("background", 100,
                "{\"type\":\"background\",\"tm-world-to-map\":[1,0,0,1,0,0]}")));

        final boolean recorded = model.recordFactTransform("background",
                new FloorMapTransformationMatrix(3, 0, 0, 3, 7, 8), SCHEMA, ACCESSOR);

        assertThat(recorded).isTrue();
        final TemporalEntry moved = model.buildMergedCanvasEntries().stream()
                .filter(e -> e.getKey().equals("background")).findFirst().orElseThrow();
        final double[] m = ACCESSOR.getArray(ACCESSOR.parse(moved.getValue()), ".tm-world-to-map");
        assertThat(m).containsExactly(3.0, 0.0, 0.0, 3.0, 7.0, 8.0);
    }

    /**
     * Transforming an unknown fact returns {@code false} and stages nothing.
     */
    @Test
    void testRecordFactTransform_unknown_returnsFalse() {
        model.onEntriesFetched(List.of(entry("g1", 100, "{\"type\":\"gate\"}")));
        final boolean recorded = model.recordFactTransform("nope",
                FloorMapTransformationMatrix.identity(), SCHEMA, ACCESSOR);
        assertThat(recorded).isFalse();
        assertThat(model.hasPendingChanges()).isFalse();
    }

    /**
     * The static helper writes the full six-component matrix into the requested
     * role's field.
     */
    @Test
    void testBuildUpdatedEntryWithMatrix_writesFullMatrix() {
        final TemporalEntry original = entry("g1", 100, "{\"type\":\"gate\"}");
        final TemporalEntry updated = FloorMapEditorModel.buildUpdatedEntryWithMatrix(
                original, FloorMapFieldMapping.Role.WORLD_TO_MAP,
                new FloorMapTransformationMatrix(1, 2, 3, 4, 5, 6), SCHEMA, ACCESSOR);
        final double[] m = ACCESSOR.getArray(ACCESSOR.parse(updated.getValue()), ".tm-world-to-map");
        assertThat(m).containsExactly(1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
    }

    // -----------------------------------------------------------------------
    // Selection as a set
    // -----------------------------------------------------------------------

    /** The single-select façade sets/clears the whole selection. */
    @Test
    void testSelection_singleSelectFacade() {
        model.setSelectedFactKey("a");
        assertThat(model.getSelectedFactKey()).isEqualTo("a");
        assertThat(model.getSelectedFactKeys()).containsExactly("a");

        model.setSelectedFactKey(null);
        assertThat(model.getSelectedFactKey()).isNull();
        assertThat(model.getSelectedFactKeys()).isEmpty();
    }

    /** Multi-select APIs add/toggle/remove; the primary is the first-selected. */
    @Test
    void testSelection_multiSelectApis() {
        model.addToSelection("a");
        model.addToSelection("b");
        assertThat(model.getSelectedFactKeys()).containsExactly("a", "b");
        assertThat(model.getSelectedFactKey()).isEqualTo("a");
        assertThat(model.isSelected("b")).isTrue();

        model.toggleSelection("a");   // removes a
        model.toggleSelection("c");   // adds c
        assertThat(model.getSelectedFactKeys()).containsExactly("b", "c");

        model.removeFromSelection("b");
        assertThat(model.getSelectedFactKeys()).containsExactly("c");

        model.clearSelection();
        assertThat(model.getSelectedFactKeys()).isEmpty();
    }

    /** setSelection replaces the whole selection, preserving order. */
    @Test
    void testSelection_setSelection() {
        model.setSelection(List.of("x", "y", "z"));
        assertThat(model.getSelectedFactKeys()).containsExactly("x", "y", "z");
        assertThat(model.getSelectedFactKey()).isEqualTo("x");
    }

    /** Deleting a selected fact removes it from the selection. */
    @Test
    void testSelection_stageDeletionDeselects() {
        model.onEntriesFetched(List.of(entry("k1", 100, "{}")));
        model.setSelectedFactKey("k1");
        model.stageFactDeletion("k1");
        assertThat(model.isSelected("k1")).isFalse();
    }

    // -----------------------------------------------------------------------
    // translateFacts (batch move)
    // -----------------------------------------------------------------------

    /** Translating a regular fact shifts its WORLD_TO_MAP translation (e, f). */
    @Test
    void testTranslateFacts_regularObject() {
        model.onEntriesFetched(List.of(entry("g1", 100,
                "{\"type\":\"gate\",\"tm-world-to-map\":[1,0,0,1,10,20]}")));

        final int moved = model.translateFacts(List.of("g1"), 5, -3, SCHEMA, ACCESSOR);

        assertThat(moved).isEqualTo(1);
        final TemporalEntry e = model.buildMergedCanvasEntries().stream()
                .filter(x -> x.getKey().equals("g1")).findFirst().orElseThrow();
        final double[] m = ACCESSOR.getArray(ACCESSOR.parse(e.getValue()), ".tm-world-to-map");
        assertThat(m).containsExactly(1.0, 0.0, 0.0, 1.0, 15.0, 17.0);
    }

    /**
     * Translating the background shifts its WORLD_TO_MAP translation — a
     * background is translated like any other fact.
     */
    @Test
    void testTranslateFacts_background() {
        model.onEntriesFetched(List.of(entry("background", 100,
                "{\"type\":\"background\",\"tm-world-to-map\":[2,0,0,2,0,0]}")));

        final int moved = model.translateFacts(List.of("background"), 4, 4, SCHEMA, ACCESSOR);

        assertThat(moved).isEqualTo(1);
        final TemporalEntry e = model.buildMergedCanvasEntries().stream()
                .filter(x -> x.getKey().equals("background")).findFirst().orElseThrow();
        final double[] m = ACCESSOR.getArray(ACCESSOR.parse(e.getValue()), ".tm-world-to-map");
        assertThat(m).containsExactly(2.0, 0.0, 0.0, 2.0, 4.0, 4.0);
    }

    /** A batch translate moves every found fact; unknown ids are skipped. */
    @Test
    void testTranslateFacts_batchSkipsUnknown() {
        model.onEntriesFetched(List.of(
                entry("g1", 100, "{\"type\":\"gate\",\"tm-world-to-map\":[1,0,0,1,0,0]}"),
                entry("g2", 100, "{\"type\":\"gate\",\"tm-world-to-map\":[1,0,0,1,0,0]}")));

        final int moved = model.translateFacts(List.of("g1", "g2", "ghost"), 1, 1, SCHEMA, ACCESSOR);
        assertThat(moved).isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // stageFactDeletion
    // -----------------------------------------------------------------------

    /**
     * Staging deletion of a fact removes its object from the merged canvas
     * entries, leaving other facts' objects in place.
     */
    @Test
    void testStageFactDeletion_removesKeyFromCanvas() {
        model.onEntriesFetched(List.of(
                entry("k1", 100, "{}"),
                entry("k2", 100, "{}")));

        final boolean staged = model.stageFactDeletion("k1");

        assertThat(staged).isTrue();
        final List<TemporalEntry> merged = model.buildMergedCanvasEntries();
        assertThat(merged).hasSize(1);
        assertThat(merged.getFirst().getKey()).isEqualTo("k2");
    }

    /**
     * Staging deletion of a fact also removes time shards that are only
     * known to the time list (not the canvas), hiding them from the merged
     * time list as well.
     */
    @Test
    void testStageFactDeletion_alsoDeletesTimeListOnlyShards() {
        model.onEntriesFetched(List.of(entry("k1", 100, "{}")));
        // A second time shard for k1 that is only known to the time list.
        model.setServerEntriesForSelectedFact(List.of(
                entry("k1", 100, "{}"),
                entry("k1", 200, "{}")));

        assertThat(model.stageFactDeletion("k1")).isTrue();

        // Both the 100 and 200 shards should now be hidden from the time list.
        model.setSelectedFactKey("k1");
        assertThat(model.buildMergedTimeList()).isEmpty();
    }

    /**
     * Staging deletion of the currently selected fact clears the selection.
     */
    @Test
    void testStageFactDeletion_clearsSelectionWhenSelected() {
        model.onEntriesFetched(List.of(entry("k1", 100, "{}")));
        model.setSelectedFactKey("k1");

        model.stageFactDeletion("k1");

        assertThat(model.getSelectedFactKey()).isNull();
    }

    /**
     * Staging deletion of a fact other than the currently selected one
     * leaves the selection unchanged.
     */
    @Test
    void testStageFactDeletion_keepsSelectionWhenDifferentKey() {
        model.onEntriesFetched(List.of(
                entry("k1", 100, "{}"),
                entry("k2", 100, "{}")));
        model.setSelectedFactKey("k2");

        model.stageFactDeletion("k1");

        assertThat(model.getSelectedFactKey()).isEqualTo("k2");
    }

    /**
     * Attempting to stage deletion of a key the model has no entry for
     * returns {@code false}.
     */
    @Test
    void testStageFactDeletion_absentKey_returnsFalse() {
        model.onEntriesFetched(List.of(entry("k1", 100, "{}")));

        assertThat(model.stageFactDeletion("nope")).isFalse();
    }

    // -----------------------------------------------------------------------
    // onTimeListFetched
    // -----------------------------------------------------------------------

    /**
     * Entries fetched for the time list are stored sorted by effective time,
     * regardless of the order they were fetched in.
     */
    @Test
    void testOnTimeListFetched_sortsEntries() {
        model.onTimeListFetched(List.of(
                entry("k1", 300, "{}"),
                entry("k1", 100, "{}"),
                entry("k1", 200, "{}")));

        final List<TemporalEntry> stored = model.getServerEntriesForSelectedFact();
        assertThat(stored.getFirst().getEffectiveTimeMs()).isEqualTo(100);
        assertThat(stored.get(1).getEffectiveTimeMs()).isEqualTo(200);
        assertThat(stored.get(2).getEffectiveTimeMs()).isEqualTo(300);
    }

    /**
     * Fetching a {@code null} time list is treated as an empty result rather
     * than throwing.
     */
    @Test
    void testOnTimeListFetched_null_yieldsEmpty() {
        model.onTimeListFetched(null);
        assertThat(model.getServerEntriesForSelectedFact()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static TemporalEntry entry(final String key, final long time,
                                        final String value) {
        return new TemporalEntry(MAP, key, time, value);
    }
}
