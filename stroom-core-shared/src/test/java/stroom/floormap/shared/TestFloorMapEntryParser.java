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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TestFloorMapEntryParser {

    private static final String MAP = "testMap";
    private static final List<FloorMapFieldMapping> SCHEMA =
            FloorMapFieldMapping.initialValueSchema();
    private static final MapValueAccessor ACCESSOR = MapValueAccessor.INSTANCE;

    /** Collects warnings emitted during parsing. Reset before each test. */
    private final List<String> warnings = new ArrayList<>();

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        warnings.clear();
    }

    // -----------------------------------------------------------------------
    // findPath
    // -----------------------------------------------------------------------

    /**
     * The path mapped to the {@code TYPE} role in the schema is returned.
     */
    @Test
    void testFindPath_type() {
        assertThat(FloorMapEntryParser.findPath(SCHEMA, Role.TYPE))
                .isEqualTo(".type");
    }

    /**
     * The path mapped to the {@code POSITION} role in the schema is returned.
     */
    @Test
    void testFindPath_position() {
        assertThat(FloorMapEntryParser.findPath(SCHEMA, Role.POSITION))
                .isEqualTo(".coords");
    }

    /**
     * The path mapped to the {@code IMAGE} role in the schema is returned.
     */
    @Test
    void testFindPath_image() {
        assertThat(FloorMapEntryParser.findPath(SCHEMA, Role.IMAGE))
                .isEqualTo(".img");
    }

    /**
     * The path mapped to the {@code WORLD_TO_MAP} role in the schema is
     * returned.
     */
    @Test
    void testFindPath_worldToMap() {
        assertThat(FloorMapEntryParser.findPath(SCHEMA, Role.WORLD_TO_MAP))
                .isEqualTo(".tm-world-to-map");
    }

    /**
     * A {@code null} schema yields {@code null} rather than throwing.
     */
    @Test
    void testFindPath_nullSchema() {
        assertThat(FloorMapEntryParser.findPath(null, Role.TYPE)).isNull();
    }

    /**
     * When the schema has no mapping for the requested role, {@code null} is
     * returned instead of matching an unrelated entry.
     */
    @Test
    void testFindPath_missingRole() {
        final List<FloorMapFieldMapping> partial = List.of(
                new FloorMapFieldMapping(".type", Role.TYPE, "Type", null));
        assertThat(FloorMapEntryParser.findPath(partial, Role.POSITION)).isNull();
    }

    // -----------------------------------------------------------------------
    // parse — null/empty inputs
    // -----------------------------------------------------------------------

    /**
     * A {@code null} entry list produces an empty result with no warnings.
     */
    @Test
    void testParse_nullEntries() {
        final FloorMapEntryParser.ParseResult result =
                FloorMapEntryParser.parse(null, SCHEMA, ACCESSOR, warnings::add);
        assertThat(result.getObjects()).isEmpty();
        assertThat(result.getBackgroundImage()).isNull();
        assertThat(warnings).isEmpty();
    }

    /**
     * An empty entry list produces an empty result with no warnings.
     */
    @Test
    void testParse_emptyEntries() {
        final FloorMapEntryParser.ParseResult result =
                FloorMapEntryParser.parse(List.of(), SCHEMA, ACCESSOR, warnings::add);
        assertThat(result.getObjects()).isEmpty();
        assertThat(warnings).isEmpty();
    }

    // -----------------------------------------------------------------------
    // parse — background entry
    // -----------------------------------------------------------------------

    /**
     * A background entry (declared via {@code type}) yields its image and
     * transform matrix, and contributes no regular objects.
     */
    @Test
    void testParse_backgroundEntry() {
        final String json = "{\"type\":\"background\",\"img\":\"floor1.png\","
                + "\"tm-map-to-screen\":[2,0,0,2,10,20]}";
        final TemporalEntry bgEntry = entry("background", 100, json);

        final FloorMapEntryParser.ParseResult result =
                FloorMapEntryParser.parse(List.of(bgEntry), SCHEMA, ACCESSOR, warnings::add);

        assertThat(result.getBackgroundImage()).isEqualTo("floor1.png");
        assertThat(result.getBackgroundMatrix()).isNotNull();
        assertThat(result.getBackgroundMatrix().getA()).isEqualTo(2.0);
        assertThat(result.getBackgroundMatrix().getE()).isEqualTo(10.0);
        assertThat(result.getBackgroundMatrix().getF()).isEqualTo(20.0);
        assertThat(result.getObjects()).isEmpty();
        assertThat(warnings).as("valid background should not emit warnings").isEmpty();
    }

    /**
     * An entry keyed {@code "background"} is treated as the background even
     * when its {@code type} field is absent.
     */
    @Test
    void testParse_backgroundDetectedByKey() {
        // Even if type is missing, if the key is "background" it should
        // be treated as background.
        final String json = "{\"img\":\"floor1.png\"}";
        final TemporalEntry bgEntry = entry("background", 100, json);

        final FloorMapEntryParser.ParseResult result =
                FloorMapEntryParser.parse(List.of(bgEntry), SCHEMA, ACCESSOR, warnings::add);
        assertThat(result.getBackgroundImage()).isEqualTo("floor1.png");
        assertThat(result.getObjects()).isEmpty();
        assertThat(warnings).isEmpty();
    }

    // -----------------------------------------------------------------------
    // parse — regular object entries
    // -----------------------------------------------------------------------

    /**
     * A regular object entry with no world-to-map matrix is parsed with its
     * raw coordinates unchanged.
     */
    @Test
    void testParse_regularObject_identityMatrix() {
        final String json = "{\"type\":\"gate\",\"name\":\"Gate-1\","
                + "\"coords\":[100,200]}";
        final TemporalEntry objEntry = entry("gate-1", 100, json);

        final FloorMapEntryParser.ParseResult result =
                FloorMapEntryParser.parse(List.of(objEntry), SCHEMA, ACCESSOR, warnings::add);

        assertThat(result.getObjects()).hasSize(1);
        final FloorMapObject obj = result.getObjects().getFirst();
        assertThat(obj.getId()).isEqualTo("gate-1");
        assertThat(obj.getType()).isEqualTo("gate");
        assertThat(obj.getX()).isCloseTo(100.0, within(0.001));
        assertThat(obj.getY()).isCloseTo(200.0, within(0.001));
        assertThat(warnings).as("valid entry should not emit warnings").isEmpty();
    }

    /**
     * A regular object entry's coordinates are transformed through its
     * world-to-map matrix (scale and translation) into map space.
     */
    @Test
    void testParse_regularObject_withWorldToMapTransform() {
        // World-to-map: scale 2x, translate (50, 100)
        // World coords (10, 20) → map coords (2*10 + 50, 2*20 + 100) = (70, 140)
        final String json = "{\"type\":\"camera\",\"name\":\"Cam-1\","
                + "\"coords\":[10,20],"
                + "\"tm-world-to-map\":[2,0,0,2,50,100]}";
        final TemporalEntry objEntry = entry("cam-1", 100, json);

        final FloorMapEntryParser.ParseResult result =
                FloorMapEntryParser.parse(List.of(objEntry), SCHEMA, ACCESSOR, null);

        assertThat(result.getObjects()).hasSize(1);
        final FloorMapObject obj = result.getObjects().getFirst();
        assertThat(obj.getX()).isCloseTo(70.0, within(0.001));
        assertThat(obj.getY()).isCloseTo(140.0, within(0.001));
    }

    /**
     * An object entry with no {@code coords} field defaults to (0, 0) rather
     * than failing to parse.
     */
    @Test
    void testParse_missingCoords_defaultsToZero() {
        final String json = "{\"type\":\"sensor\",\"name\":\"S1\"}";
        final TemporalEntry objEntry = entry("s1", 100, json);

        final FloorMapEntryParser.ParseResult result =
                FloorMapEntryParser.parse(List.of(objEntry), SCHEMA, ACCESSOR, null);

        assertThat(result.getObjects()).hasSize(1);
        assertThat(result.getObjects().getFirst().getX()).isEqualTo(0.0);
        assertThat(result.getObjects().getFirst().getY()).isEqualTo(0.0);
    }

    /**
     * An object entry with no world-to-map matrix passes its coordinates
     * through unchanged, i.e. as if transformed by the identity matrix.
     */
    @Test
    void testParse_missingMatrix_usesIdentity() {
        // Without a world-to-map matrix, coordinates should pass through unchanged
        final String json = "{\"type\":\"gate\",\"coords\":[50,75]}";
        final TemporalEntry objEntry = entry("g1", 100, json);

        final FloorMapEntryParser.ParseResult result =
                FloorMapEntryParser.parse(List.of(objEntry), SCHEMA, ACCESSOR, null);

        assertThat(result.getObjects().getFirst().getX()).isCloseTo(50.0, within(0.001));
        assertThat(result.getObjects().getFirst().getY()).isCloseTo(75.0, within(0.001));
    }

    // -----------------------------------------------------------------------
    // parse — mixed entries
    // -----------------------------------------------------------------------

    /**
     * A mixed batch of one background entry and several regular objects is
     * split correctly between {@code getBackgroundImage} and
     * {@code getObjects}.
     */
    @Test
    void testParse_backgroundAndMultipleObjects() {
        final String bgJson = "{\"type\":\"background\",\"img\":\"floor.png\"}";
        final String obj1Json = "{\"type\":\"gate\",\"coords\":[10,20]}";
        final String obj2Json = "{\"type\":\"camera\",\"coords\":[30,40]}";

        final FloorMapEntryParser.ParseResult result = FloorMapEntryParser.parse(
                List.of(entry("background", 100, bgJson),
                        entry("gate-1", 100, obj1Json),
                        entry("cam-1", 100, obj2Json)),
                SCHEMA, ACCESSOR, null);

        assertThat(result.getBackgroundImage()).isEqualTo("floor.png");
        assertThat(result.getObjects()).hasSize(2);
    }

    // -----------------------------------------------------------------------
    // parse — error handling
    // -----------------------------------------------------------------------

    /**
     * An entry whose value is not valid JSON is skipped (excluded from the
     * result) while other, well-formed entries still parse, and a warning is
     * emitted for the bad one.
     */
    @Test
    void testParse_malformedEntry_skippedWithWarning() {
        final TemporalEntry badEntry = entry("bad", 100, "not-json");
        final TemporalEntry goodEntry = entry("good", 100,
                "{\"type\":\"gate\",\"coords\":[1,2]}");

        final FloorMapEntryParser.ParseResult result = FloorMapEntryParser.parse(
                List.of(badEntry, goodEntry), SCHEMA, ACCESSOR, warnings::add);

        // Only the good entry should be parsed
        assertThat(result.getObjects()).hasSize(1);
        assertThat(result.getObjects().getFirst().getId()).isEqualTo("good");
        // A warning should have been emitted for the bad entry
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains("bad");
    }

    /**
     * The warning emitted for a malformed entry includes that entry's key,
     * so the user can identify which fact failed to parse.
     */
    @Test
    void testParse_malformedEntry_warningContainsKey() {
        final TemporalEntry badEntry = entry("sensor-42", 100, "totally invalid");

        FloorMapEntryParser.parse(
                List.of(badEntry), SCHEMA, ACCESSOR, warnings::add);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst())
                .as("warning message should include the entry key")
                .contains("sensor-42");
    }

    /**
     * Multiple malformed entries in the same batch each produce their own
     * warning, and do not prevent the well-formed entry from parsing.
     */
    @Test
    void testParse_multipleMalformedEntries_emitsMultipleWarnings() {
        final FloorMapEntryParser.ParseResult result = FloorMapEntryParser.parse(
                List.of(entry("bad1", 100, "xxx"),
                        entry("bad2", 100, "yyy"),
                        entry("good", 100, "{\"type\":\"gate\",\"coords\":[1,2]}")),
                SCHEMA, ACCESSOR, warnings::add);

        assertThat(result.getObjects()).hasSize(1);
        assertThat(warnings).hasSize(2);
        assertThat(warnings.getFirst()).contains("bad1");
        assertThat(warnings.get(1)).contains("bad2");
    }

    /**
     * Passing a {@code null} warning consumer silently skips warning
     * emission rather than throwing a {@code NullPointerException}.
     */
    @Test
    void testParse_nullWarningConsumer_doesNotThrow() {
        // Passing null consumer should silently skip, not NPE
        final TemporalEntry badEntry = entry("bad", 100, "not-json");
        final FloorMapEntryParser.ParseResult result = FloorMapEntryParser.parse(
                List.of(badEntry), SCHEMA, ACCESSOR, null);
        assertThat(result.getObjects()).isEmpty();
    }

    /**
     * An entry with a {@code null} value is skipped and produces a warning
     * that identifies the key and the fact that the value was null.
     */
    @Test
    void testParse_nullValue_emitsWarning() {
        final TemporalEntry nullValue = new TemporalEntry(MAP, "k1", 100L, null);
        final FloorMapEntryParser.ParseResult result =
                FloorMapEntryParser.parse(List.of(nullValue), SCHEMA, ACCESSOR, warnings::add);
        assertThat(result.getObjects()).isEmpty();
        assertThat(warnings).as("null value should emit a warning").hasSize(1);
        assertThat(warnings.getFirst()).contains("k1").contains("null");
    }

    /**
     * An entry with an empty-string value is skipped and produces a warning
     * identifying the key.
     */
    @Test
    void testParse_emptyValue_emitsWarning() {
        final TemporalEntry emptyValue = entry("k1", 100, "");
        final FloorMapEntryParser.ParseResult result =
                FloorMapEntryParser.parse(List.of(emptyValue), SCHEMA, ACCESSOR, warnings::add);
        assertThat(result.getObjects()).isEmpty();
        assertThat(warnings).as("empty value should emit a warning").hasSize(1);
        assertThat(warnings.getFirst()).contains("k1");
    }

    /**
     * An entry missing the {@code type} field still parses successfully,
     * defaulting to an empty type string without emitting a warning.
     */
    @Test
    void testParse_nullType_usesEmptyString_noWarning() {
        final String json = "{\"coords\":[5,10]}";
        final TemporalEntry objEntry = entry("k1", 100, json);

        final FloorMapEntryParser.ParseResult result =
                FloorMapEntryParser.parse(List.of(objEntry), SCHEMA, ACCESSOR, warnings::add);

        assertThat(result.getObjects()).hasSize(1);
        assertThat(result.getObjects().getFirst().getType()).isEmpty();
        assertThat(warnings).as("missing type is not a warning-worthy condition").isEmpty();
    }

    // -----------------------------------------------------------------------
    // getFacts() — the authoritative fact list
    // -----------------------------------------------------------------------

    /**
     * Every entry becomes one {@link Fact}, in order — including the background.
     */
    @Test
    void testFacts_oneFactPerEntry() {
        final FloorMapEntryParser.ParseResult result = FloorMapEntryParser.parse(List.of(
                        entry("background", 100,
                                "{\"type\":\"background\",\"img\":\"floor.png\","
                                        + "\"tm-map-to-screen\":[2,0,0,2,10,20]}"),
                        entry("gate-1", 100,
                                "{\"type\":\"gate\",\"coords\":[3,4]}")),
                SCHEMA, ACCESSOR, warnings::add);

        assertThat(result.getFacts()).hasSize(2);
        assertThat(result.getFacts().get(0).getKey()).isEqualTo("background");
        assertThat(result.getFacts().get(1).getKey()).isEqualTo("gate-1");
    }

    /**
     * A background fact carries its image and its (map-to-screen) placement
     * matrix, is flagged as background, and has no position.
     */
    @Test
    void testFacts_backgroundFact() {
        final FloorMapEntryParser.ParseResult result = FloorMapEntryParser.parse(List.of(
                        entry("background", 100,
                                "{\"type\":\"background\",\"img\":\"floor.png\","
                                        + "\"tm-map-to-screen\":[2,0,0,2,10,20]}")),
                SCHEMA, ACCESSOR, warnings::add);

        final Fact bg = result.getFacts().getFirst();
        assertThat(bg.isBackground()).isTrue();
        assertThat(bg.hasImage()).isTrue();
        assertThat(bg.getImage()).isEqualTo("floor.png");
        assertThat(bg.getPosition()).isNull();
        assertThat(bg.getWorldToMap().getA()).isEqualTo(2.0);
        assertThat(bg.getWorldToMap().getE()).isEqualTo(10.0);
        assertThat(bg.getWorldToMap().getF()).isEqualTo(20.0);
    }

    /**
     * A regular fact carries its world position and world-to-map matrix, has no
     * image, and is not flagged as background.
     */
    @Test
    void testFacts_regularFact() {
        final FloorMapEntryParser.ParseResult result = FloorMapEntryParser.parse(List.of(
                        entry("gate-1", 100,
                                "{\"type\":\"gate\",\"coords\":[3,4],"
                                        + "\"tm-world-to-map\":[1,0,0,1,0,0]}")),
                SCHEMA, ACCESSOR, warnings::add);

        final Fact fact = result.getFacts().getFirst();
        assertThat(fact.isBackground()).isFalse();
        assertThat(fact.hasImage()).isFalse();
        assertThat(fact.getType()).isEqualTo("gate");
        assertThat(fact.getPosition()).containsExactly(3.0, 4.0);
    }

    /**
     * The legacy {@code getObjects()} / {@code getBackground*} adapter stays
     * consistent with the fact list it is derived from.
     */
    @Test
    void testFacts_adapterConsistentWithFacts() {
        final FloorMapEntryParser.ParseResult result = FloorMapEntryParser.parse(List.of(
                        entry("background", 100,
                                "{\"type\":\"background\",\"img\":\"floor.png\","
                                        + "\"tm-map-to-screen\":[1,0,0,1,0,0]}"),
                        entry("gate-1", 100, "{\"type\":\"gate\",\"coords\":[3,4]}")),
                SCHEMA, ACCESSOR, warnings::add);

        // One background fact + one object fact → one object, one background image.
        assertThat(result.getFacts()).hasSize(2);
        assertThat(result.getObjects()).hasSize(1);
        assertThat(result.getObjects().getFirst().getId()).isEqualTo("gate-1");
        assertThat(result.getBackgroundImage()).isEqualTo("floor.png");
    }

    /**
     * A {@code null} entry list yields an empty (non-null) fact list.
     */
    @Test
    void testFacts_nullEntries_emptyList() {
        final FloorMapEntryParser.ParseResult result =
                FloorMapEntryParser.parse(null, SCHEMA, ACCESSOR, warnings::add);
        assertThat(result.getFacts()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static TemporalEntry entry(final String key,
                                       @SuppressWarnings("SameParameterValue") final long time,
                                       final String value) {
        return new TemporalEntry(MAP, key, time, value);
    }
}
