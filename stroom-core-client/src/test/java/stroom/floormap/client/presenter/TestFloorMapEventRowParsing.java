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

import stroom.floormap.shared.Fact;
import stroom.floormap.shared.FloorMapLocationResolver;
import stroom.floormap.shared.FloorMapObject;
import stroom.floormap.shared.FloorMapTransformationMatrix;
import stroom.query.api.Column;
import stroom.query.api.Row;
import stroom.query.api.TableResult;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the whole path a single events row travels — query result → parsed
 * entity → position on the map — because the two halves fail identically from
 * the outside: the entities just stop appearing.
 */
class TestFloorMapEventRowParsing {

    private static final String ENTITY_COLUMN = "Entity ID";
    private static final String LOCATION_COLUMN = "Location ID";

    /** The legacy shape: coordinates baked into the event, used as they stand. */
    @Test
    void testCoordinateRowsAreParsedAndPassedThrough() {
        final List<FloorMapObject> parsed = FloorMapQueryPresenter.parseRows(
                result(row("joe.blogs@example.org", "B-GND, 120.5, 340")),
                ENTITY_COLUMN,
                LOCATION_COLUMN);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.getFirst().getLocationRef()).isNull();
        assertThat(parsed.getFirst().getX()).isEqualTo(120.5);

        final List<FloorMapObject> placed = FloorMapLocationResolver.resolve(parsed, facts());
        assertThat(placed.getFirst().getX()).isEqualTo(120.5);
        assertThat(placed.getFirst().getY()).isEqualTo(340);
    }

    /**
     * The shape that fixes the stale-position bug: the row names the object, and
     * the entity lands wherever that object currently is.
     */
    @Test
    void testReferenceRowsAreParsedAndPlacedOnTheObject() {
        final List<FloorMapObject> parsed = FloorMapQueryPresenter.parseRows(
                result(row("joe.blogs@example.org", "G-MAIN_ENTRANCE")),
                ENTITY_COLUMN,
                LOCATION_COLUMN);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.getFirst().getLocationRef()).isEqualTo("G-MAIN_ENTRANCE");

        final List<FloorMapObject> placed = FloorMapLocationResolver.resolve(parsed, facts());
        assertThat(placed).hasSize(1);
        assertThat(placed.getFirst().getId()).isEqualTo("joe.blogs@example.org");
        assertThat(placed.getFirst().getX()).isEqualTo(10);
        assertThat(placed.getFirst().getY()).isEqualTo(20);
    }

    /** An email entity id with no type column still reads as a person. */
    @Test
    void testTypeFallsBackToPersonForAnEmailId() {
        final List<FloorMapObject> parsed = FloorMapQueryPresenter.parseRows(
                result(row("joe.blogs@example.org", "G-MAIN_ENTRANCE")),
                ENTITY_COLUMN,
                LOCATION_COLUMN);

        assertThat(parsed.getFirst().getType()).isEqualTo("person");
    }

    /**
     * A location column the query does not select yields nothing at all — the
     * silent failure that looks like animation being switched off, and the
     * reason {@code FloorMapMapPresenter} logs this case.
     */
    @Test
    void testUnmappedLocationColumnYieldsNoEntities() {
        assertThat(FloorMapQueryPresenter.parseRows(
                result(row("joe.blogs@example.org", "G-MAIN_ENTRANCE")),
                ENTITY_COLUMN,
                "Location Ref"))
                .isEmpty();
    }

    /** A row whose location the query returned as null is skipped, not placed at the origin. */
    @Test
    void testNullLocationIsSkipped() {
        assertThat(FloorMapQueryPresenter.parseRows(
                result(row("joe.blogs@example.org", null)),
                ENTITY_COLUMN,
                LOCATION_COLUMN))
                .isEmpty();
    }

    // -----------------------------------------------------------------------

    /** The facts a floor plan would supply: the gate the events reference. */
    private static List<Fact> facts() {
        return Collections.singletonList(new Fact(
                "G-MAIN_ENTRANCE", "gate", null,
                FloorMapTransformationMatrix.identity(), new double[]{10, 20}));
    }

    private static Row row(@SuppressWarnings("SameParameterValue") final String entityId,
                           final String location) {
        return Row.builder()
                .values(Arrays.asList(entityId, location))
                .build();
    }

    private static TableResult result(final Row... rows) {
        final List<Column> columns = new ArrayList<>();
        columns.add(Column.builder().id(ENTITY_COLUMN).name(ENTITY_COLUMN).build());
        columns.add(Column.builder().id(LOCATION_COLUMN).name(LOCATION_COLUMN).build());
        return new TableResult(
                "table", columns, Arrays.asList(rows), null, (long) rows.length, null, null);
    }
}
