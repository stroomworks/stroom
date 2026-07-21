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

import stroom.floormap.shared.FloorMapEntityList.EntityEntry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TestFloorMapEntityList {

    private FloorMapEntityList entityList;

    @BeforeEach
    void setUp() {
        entityList = new FloorMapEntityList();
    }

    private static FloorMapObject person() {
        return new FloorMapObject("alice@example.com", FloorMapJsonKeys.PERSON, 0, 0);
    }

    private static FloorMapObject entity(final String id, final String type) {
        return new FloorMapObject(id, type, 0, 0);
    }

    // -----------------------------------------------------------------------
    // displayName
    // -----------------------------------------------------------------------

    /**
     * An email-style id is shortened to the portion before the '@', matching
     * the canvas label rule.
     */
    @Test
    void testDisplayNameEmail() {
        assertThat(FloorMapEntityList.displayName("alice@example.com")).isEqualTo("alice");
    }

    /**
     * An id without an '@' is used verbatim.
     */
    @Test
    void testDisplayNamePlainId() {
        assertThat(FloorMapEntityList.displayName("forklift-1")).isEqualTo("forklift-1");
    }

    /**
     * A leading '@' does not produce an empty display name — the full id is
     * kept (the canvas rule only shortens when the '@' is beyond index 0).
     */
    @Test
    void testDisplayNameLeadingAt() {
        assertThat(FloorMapEntityList.displayName("@odd-id")).isEqualTo("@odd-id");
    }

    @Test
    void testDisplayNameNull() {
        assertThat(FloorMapEntityList.displayName(null)).isNull();
    }

    // -----------------------------------------------------------------------
    // Admission — every event type is tracked
    // -----------------------------------------------------------------------

    /**
     * All typed event objects are admitted, not just persons — the tracking
     * panel covers everything coming through the events stream.
     */
    @Test
    void testAdmitsAllTypes() {
        final boolean changed = entityList.update(Arrays.asList(
                person(),
                entity("forklift-1", "vehicle"),
                entity("asset-42", "object"),
                entity("untyped-1", null)));

        assertThat(changed).isTrue();
        assertThat(entityList.getEntities())
                .extracting(EntityEntry::getId)
                .containsExactly("alice@example.com", "asset-42", "forklift-1", "untyped-1");
    }

    /**
     * The entry records the entity's type; a null type is stored as an empty
     * string so grid columns never render "null".
     */
    @Test
    void testStoresType() {
        entityList.update(Arrays.asList(
                person(),
                entity("forklift-1", "vehicle"),
                entity("untyped-1", null)));

        assertThat(entityList.getEntities())
                .extracting(EntityEntry::getType)
                .containsExactly("person", "vehicle", "");
    }

    /**
     * Entities with null or empty ids cannot be listed or tracked and are skipped.
     */
    @Test
    void testSkipsNullAndEmptyIds() {
        final boolean changed = entityList.update(Arrays.asList(
                entity(null, "vehicle"),
                entity("", "vehicle"),
                null));

        assertThat(changed).isFalse();
        assertThat(entityList.getEntities()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Facts admission — the tracking panel covers static facts too
    // -----------------------------------------------------------------------

    /**
     * Static facts of every kind — point objects, backgrounds and areas — are
     * admitted alongside event entities, keyed by their fact key.
     */
    @Test
    void testUpdateFactsAdmitsAllKinds() {
        final boolean changed = entityList.updateFacts(Arrays.asList(
                fact("gate-1", "gate"),
                fact("background", "background"),
                fact("zone-a", "area")));

        assertThat(changed).isTrue();
        assertThat(entityList.getEntities())
                .extracting(EntityEntry::getId)
                .containsExactly("background", "gate-1", "zone-a");
        assertThat(entityList.getEntities())
                .extracting(EntityEntry::getType)
                .containsExactly("background", "gate", "area");
    }

    /**
     * Facts with null or empty keys cannot be listed or tracked and are
     * skipped, as are null list elements and a null list.
     */
    @Test
    void testUpdateFactsSkipsNullAndEmptyKeys() {
        assertThat(entityList.updateFacts(Arrays.asList(
                fact(null, "gate"),
                fact("", "gate"),
                null))).isFalse();
        assertThat(entityList.updateFacts(null)).isFalse();
        assertThat(entityList.getEntities()).isEmpty();
    }

    /**
     * Facts deduplicate against entries already admitted from the events
     * stream (and vice versa) — first-seen type wins, and a repeat merge
     * reports no change so the grid is not refreshed.
     */
    @Test
    void testUpdateFactsDedupesAgainstEvents() {
        entityList.update(Collections.singletonList(entity("gate-1", "vehicle")));

        assertThat(entityList.updateFacts(
                Collections.singletonList(fact("gate-1", "gate")))).isFalse();

        final List<EntityEntry> entities = entityList.getEntities();
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).getType()).isEqualTo("vehicle");
    }

    private static Fact fact(final String key, final String type) {
        return new Fact(key, type, null, null, null);
    }

    // -----------------------------------------------------------------------
    // Deduplication and accumulation
    // -----------------------------------------------------------------------

    /**
     * The same id repeated within a single update produces one row, keeping
     * the first-seen type.
     */
    @Test
    void testDedupesWithinUpdate() {
        entityList.update(Arrays.asList(
                entity("forklift-1", "vehicle"),
                entity("forklift-1", "object")));

        final List<EntityEntry> entities = entityList.getEntities();
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).getType()).isEqualTo("vehicle");
    }

    /**
     * An entity absent from a later refresh stays in the roster — the roster
     * is a union of everything seen, so playback refreshes don't drop rows.
     */
    @Test
    void testAccumulatesAcrossUpdates() {
        entityList.update(Collections.singletonList(person()));
        entityList.update(Collections.singletonList(entity("forklift-1", "vehicle")));

        assertThat(entityList.getEntities())
                .extracting(EntityEntry::getId)
                .containsExactly("alice@example.com", "forklift-1");
    }

    // -----------------------------------------------------------------------
    // Change flag
    // -----------------------------------------------------------------------

    /**
     * The change flag is true only when membership actually changes, so the
     * presenter can skip grid refreshes for repeat data.
     */
    @Test
    void testChangeFlag() {
        assertThat(entityList.update(Collections.singletonList(person())))
                .isTrue();
        // Same membership again — no change.
        assertThat(entityList.update(Collections.singletonList(person())))
                .isFalse();
        // Subset (alice absent) — roster unchanged because entries accumulate.
        assertThat(entityList.update(Collections.emptyList())).isFalse();
        assertThat(entityList.update(null)).isFalse();
        // New member — change.
        assertThat(entityList.update(Collections.singletonList(entity("forklift-1", "vehicle"))))
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // Sorting
    // -----------------------------------------------------------------------

    /**
     * Entities are sorted by display name case-insensitively with the full id
     * as a tiebreak, and the order is stable across updates.
     */
    @Test
    void testSortOrder() {
        entityList.update(Arrays.asList(
                entity("dave@example.com", "person"),
                entity("Alice@other.org", "person"),
                entity("alice@example.com", "person"),
                entity("bob@example.com", "person")));

        final List<String> ids = entityList.getEntities().stream()
                .map(EntityEntry::getId)
                .collect(Collectors.toList());
        // "alice@example.com" and "Alice@other.org" share the display name
        // case-insensitively, so the id tiebreak orders the upper-case id first.
        assertThat(ids).containsExactly(
                "Alice@other.org",
                "alice@example.com",
                "bob@example.com",
                "dave@example.com");

        // Order is unchanged by a repeat update.
        entityList.update(Collections.singletonList(entity("bob@example.com", "person")));
        assertThat(entityList.getEntities().stream()
                .map(EntityEntry::getId)
                .collect(Collectors.toList()))
                .isEqualTo(ids);
    }

    // -----------------------------------------------------------------------
    // contains / clear
    // -----------------------------------------------------------------------

    @Test
    void testContains() {
        entityList.update(Collections.singletonList(entity("forklift-1", "vehicle")));

        assertThat(entityList.contains("forklift-1")).isTrue();
        assertThat(entityList.contains("forklift-2")).isFalse();
        assertThat(entityList.contains(null)).isFalse();
    }

    /**
     * Clearing empties the roster and the next update reports a change again.
     */
    @Test
    void testClear() {
        entityList.update(Collections.singletonList(person()));
        entityList.clear();

        assertThat(entityList.getEntities()).isEmpty();
        assertThat(entityList.update(Collections.singletonList(person())))
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // EntityEntry equality
    // -----------------------------------------------------------------------

    /**
     * Entry equality is id-based so a re-created entry for the same entity
     * reads as already-selected to a selection model across grid data refreshes.
     */
    @Test
    void testEntityEntryEqualsOnIdOnly() {
        final EntityEntry a = new EntityEntry("forklift-1", "forklift-1", "vehicle");
        final EntityEntry b = new EntityEntry("forklift-1", "different label", "object");
        final EntityEntry c = new EntityEntry("forklift-2", "forklift-1", "vehicle");

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }
}
