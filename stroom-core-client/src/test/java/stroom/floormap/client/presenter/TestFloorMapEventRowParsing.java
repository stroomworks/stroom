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
import stroom.floormap.shared.FloorMapEventsQuery;
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

    private static final String ENTITY_COLUMN = FloorMapEventsQuery.ENTITY_ID_COLUMN;
    private static final String LOCATION_COLUMN = FloorMapEventsQuery.LOCATION_ID_COLUMN;
    private static final String TIME_COLUMN = FloorMapEventsQuery.EFFECTIVE_TIME_COLUMN;

    /**
     * The default query must alias exactly the columns a new document is told to read.
     *
     * <p>These are two separate fields of {@link stroom.floormap.shared.FloorMapDoc} written by
     * the same statement in {@code FloorMapInitPresenter}, and nothing at runtime checks they
     * correspond: a mismatch leaves {@code parseRows} matching no column, returning no entities,
     * and the map looking as though playback is off while the query still returns rows. Building
     * the query from the constants makes the two agree by construction; this asserts the
     * construction actually holds, and that the surrounding StroomQL still quotes them as column
     * aliases rather than, say, interpolating them somewhere harmless.</p>
     */
    @Test
    void testDefaultQueryAliasesTheDefaultColumns() {
        final String query = FloorMapEventsQuery.defaultQuery();

        assertThat(query).contains("as \"" + FloorMapEventsQuery.ENTITY_ID_COLUMN + "\"");
        assertThat(query).contains("as \"" + FloorMapEventsQuery.LOCATION_ID_COLUMN + "\"");

        // And the parse agrees, given a result shaped the way that query describes.
        final List<FloorMapObject> parsed = FloorMapQueryPresenter.parseRows(
                result(row("joe.blogs@example.org", "B-GND, 1, 2")),
                FloorMapEventsQuery.ENTITY_ID_COLUMN,
                FloorMapEventsQuery.LOCATION_ID_COLUMN);
        assertThat(parsed).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // latestPerEntity - reducing a query window to one row per entity
    // -----------------------------------------------------------------------

    /** ISO-8601 times, the form Stroom emits with no pattern preference set. */
    @Test
    void testLatestPerEntityKeepsTheNewestIsoTimeForEachEntity() {
        final List<Row> reduced = FloorMapQueryPresenter.latestPerEntity(
                timedColumns(),
                List.of(
                        timedRow("2026-09-01T10:00:00.000Z", "a@x.org", "B-GND, 1, 1"),
                        timedRow("2026-09-01T10:00:05.000Z", "a@x.org", "B-GND, 2, 2"),
                        timedRow("2026-09-01T10:00:03.000Z", "b@x.org", "B-GND, 9, 9")),
                ENTITY_COLUMN,
                TIME_COLUMN);

        assertThat(reduced).hasSize(2);
        // a@x.org keeps the 10:00:05 position, not the 10:00:00 one.
        assertThat(reduced.get(0).getValues().get(2)).isEqualTo("B-GND, 2, 2");
        assertThat(reduced.get(1).getValues().get(2)).isEqualTo("B-GND, 9, 9");
    }

    /** Out-of-order rows must not fool it - the newest wins wherever it sits in the result. */
    @Test
    void testLatestPerEntityIgnoresRowOrderWhenTimesAreComparable() {
        final List<Row> reduced = FloorMapQueryPresenter.latestPerEntity(
                timedColumns(),
                List.of(
                        timedRow("2026-09-01T10:00:09.000Z", "a@x.org", "newest"),
                        timedRow("2026-09-01T10:00:01.000Z", "a@x.org", "oldest")),
                ENTITY_COLUMN,
                TIME_COLUMN);

        assertThat(reduced).hasSize(1);
        assertThat(reduced.getFirst().getValues().get(2)).isEqualTo("newest");
    }

    /** Epoch milliseconds must compare numerically, not as text - "9" is not after "10". */
    @Test
    void testLatestPerEntityComparesEpochMillisNumerically() {
        final List<Row> reduced = FloorMapQueryPresenter.latestPerEntity(
                timedColumns(),
                List.of(
                        timedRow("9", "a@x.org", "earlier"),
                        timedRow("10", "a@x.org", "later")),
                ENTITY_COLUMN,
                TIME_COLUMN);

        assertThat(reduced).hasSize(1);
        // As text "9" sorts above "10"; only a numeric compare gets this right.
        assertThat(reduced.getFirst().getValues().get(2)).isEqualTo("later");
    }

    /** With no time column the last row for an entity wins, deterministically. */
    @Test
    void testLatestPerEntityFallsBackToLastRowWinsWithNoTimeColumn() {
        final List<Column> columns = new ArrayList<>();
        columns.add(Column.builder().id(ENTITY_COLUMN).name(ENTITY_COLUMN).build());
        columns.add(Column.builder().id(LOCATION_COLUMN).name(LOCATION_COLUMN).build());

        final List<Row> reduced = FloorMapQueryPresenter.latestPerEntity(
                columns,
                List.of(row("a@x.org", "first"), row("a@x.org", "last")),
                ENTITY_COLUMN,
                TIME_COLUMN);

        assertThat(reduced).hasSize(1);
        assertThat(reduced.getFirst().getValues().get(1)).isEqualTo("last");
    }

    /**
     * A store that already deduplicates server-side must pass through untouched.
     *
     * <p>SqlTemporalStore returns one row per key, so this pass has to be a no-op there rather
     * than quietly dropping or reordering anything.</p>
     */
    @Test
    void testLatestPerEntityIsANoOpOnAlreadyUniqueRows() {
        final List<Row> rows = List.of(
                timedRow("2026-09-01T10:00:00.000Z", "a@x.org", "one"),
                timedRow("2026-09-01T10:00:00.000Z", "b@x.org", "two"),
                timedRow("2026-09-01T10:00:00.000Z", "c@x.org", "three"));

        final List<Row> reduced = FloorMapQueryPresenter.latestPerEntity(
                timedColumns(), rows, ENTITY_COLUMN, TIME_COLUMN);

        assertThat(reduced).containsExactlyElementsOf(rows);
    }

    /** Columns for the three-column shape the default events query produces. */
    private static List<Column> timedColumns() {
        final List<Column> columns = new ArrayList<>();
        columns.add(Column.builder().id(TIME_COLUMN).name(TIME_COLUMN).build());
        columns.add(Column.builder().id(ENTITY_COLUMN).name(ENTITY_COLUMN).build());
        columns.add(Column.builder().id(LOCATION_COLUMN).name(LOCATION_COLUMN).build());
        return columns;
    }

    private static Row timedRow(final String time, final String entity, final String location) {
        return Row.builder().values(Arrays.asList(time, entity, location)).build();
    }

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
