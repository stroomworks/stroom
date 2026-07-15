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

import stroom.floormap.shared.FloorMapUserList.UserEntry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TestFloorMapUserList {

    private FloorMapUserList userList;

    @BeforeEach
    void setUp() {
        userList = new FloorMapUserList();
    }

    private static FloorMapObject person(final String id) {
        return new FloorMapObject(id, FloorMapJsonKeys.PERSON, 0, 0);
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
        assertThat(FloorMapUserList.displayName("alice@example.com")).isEqualTo("alice");
    }

    /**
     * An id without an '@' is used verbatim.
     */
    @Test
    void testDisplayNamePlainId() {
        assertThat(FloorMapUserList.displayName("gate-1")).isEqualTo("gate-1");
    }

    /**
     * A leading '@' does not produce an empty display name — the full id is
     * kept (the canvas rule only shortens when the '@' is beyond index 0).
     */
    @Test
    void testDisplayNameLeadingAt() {
        assertThat(FloorMapUserList.displayName("@odd-id")).isEqualTo("@odd-id");
    }

    @Test
    void testDisplayNameNull() {
        assertThat(FloorMapUserList.displayName(null)).isNull();
    }

    // -----------------------------------------------------------------------
    // Person filtering
    // -----------------------------------------------------------------------

    /**
     * Only objects whose type is "person" (case-insensitive) are added; other
     * types and null types are ignored.
     */
    @Test
    void testFiltersNonPersons() {
        final boolean changed = userList.update(Arrays.asList(
                person("alice@example.com"),
                new FloorMapObject("bob@example.com", "PERSON", 1, 1),
                new FloorMapObject("carol@example.com", "Person", 2, 2),
                new FloorMapObject("gate-1", "gate", 3, 3),
                new FloorMapObject("bg", FloorMapJsonKeys.BACKGROUND, 4, 4),
                new FloorMapObject("untyped", null, 5, 5)));

        assertThat(changed).isTrue();
        assertThat(userList.getUsers())
                .extracting(UserEntry::getId)
                .containsExactly("alice@example.com", "bob@example.com", "carol@example.com");
    }

    /**
     * Persons with null or empty ids cannot be listed or tracked and are skipped.
     */
    @Test
    void testSkipsNullAndEmptyIds() {
        final boolean changed = userList.update(Arrays.asList(
                person(null),
                person("")));

        assertThat(changed).isFalse();
        assertThat(userList.getUsers()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Deduplication and accumulation
    // -----------------------------------------------------------------------

    /**
     * The same id repeated within a single update produces one row.
     */
    @Test
    void testDedupesWithinUpdate() {
        userList.update(Arrays.asList(
                person("alice@example.com"),
                person("alice@example.com")));

        assertThat(userList.getUsers()).hasSize(1);
    }

    /**
     * A user absent from a later refresh stays in the roster — the roster is
     * a union of everyone seen, so playback refreshes don't drop rows.
     */
    @Test
    void testAccumulatesAcrossUpdates() {
        userList.update(Collections.singletonList(person("alice@example.com")));
        userList.update(Collections.singletonList(person("bob@example.com")));

        assertThat(userList.getUsers())
                .extracting(UserEntry::getId)
                .containsExactly("alice@example.com", "bob@example.com");
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
        assertThat(userList.update(Collections.singletonList(person("alice@example.com"))))
                .isTrue();
        // Same membership again — no change.
        assertThat(userList.update(Collections.singletonList(person("alice@example.com"))))
                .isFalse();
        // Subset (alice absent) — roster unchanged because entries accumulate.
        assertThat(userList.update(Collections.emptyList())).isFalse();
        assertThat(userList.update(null)).isFalse();
        // New member — change.
        assertThat(userList.update(Collections.singletonList(person("bob@example.com"))))
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // Sorting
    // -----------------------------------------------------------------------

    /**
     * Users are sorted by display name case-insensitively with the full id as
     * a tiebreak, and the order is stable across updates.
     */
    @Test
    void testSortOrder() {
        userList.update(Arrays.asList(
                person("dave@example.com"),
                person("Alice@other.org"),
                person("alice@example.com"),
                person("bob@example.com")));

        final List<String> ids = userList.getUsers().stream()
                .map(UserEntry::getId)
                .collect(Collectors.toList());
        // "alice@example.com" and "Alice@other.org" share the display name
        // case-insensitively, so the id tiebreak orders the upper-case id first.
        assertThat(ids).containsExactly(
                "Alice@other.org",
                "alice@example.com",
                "bob@example.com",
                "dave@example.com");

        // Order is unchanged by a repeat update.
        userList.update(Collections.singletonList(person("bob@example.com")));
        assertThat(userList.getUsers().stream()
                .map(UserEntry::getId)
                .collect(Collectors.toList()))
                .isEqualTo(ids);
    }

    // -----------------------------------------------------------------------
    // contains / clear
    // -----------------------------------------------------------------------

    @Test
    void testContains() {
        userList.update(Collections.singletonList(person("alice@example.com")));

        assertThat(userList.contains("alice@example.com")).isTrue();
        assertThat(userList.contains("bob@example.com")).isFalse();
        assertThat(userList.contains(null)).isFalse();
    }

    /**
     * Clearing empties the roster and the next update reports a change again.
     */
    @Test
    void testClear() {
        userList.update(Collections.singletonList(person("alice@example.com")));
        userList.clear();

        assertThat(userList.getUsers()).isEmpty();
        assertThat(userList.update(Collections.singletonList(person("alice@example.com"))))
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // UserEntry equality
    // -----------------------------------------------------------------------

    /**
     * Entry equality is id-based so a re-created entry for the same user reads
     * as already-selected to a selection model across grid data refreshes.
     */
    @Test
    void testUserEntryEqualsOnIdOnly() {
        final UserEntry a = new UserEntry("alice@example.com", "alice");
        final UserEntry b = new UserEntry("alice@example.com", "different label");
        final UserEntry c = new UserEntry("bob@example.com", "alice");

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }
}
