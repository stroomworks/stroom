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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Accumulating roster of the person objects seen on a floor map.
 *
 * <p>The events query at a given playback instant only returns users with
 * events near that time, so this roster is a union of every person seen since
 * the last {@link #clear()} — users are never removed when absent from a
 * refresh. This keeps the tracking panel's rows (and the user's selection)
 * stable across the ~300ms playback query refreshes.</p>
 *
 * <p>Holds no GWT or DOM types so it can be unit-tested on the JVM.</p>
 */
public class FloorMapUserList {

    private final Map<String, UserEntry> byId = new HashMap<>();

    /**
     * Derives the short display name for a user id, matching the rule used for
     * canvas labels: the portion before an {@code '@'} when one is present
     * beyond the first character, otherwise the full id.
     */
    public static String displayName(final String id) {
        if (id == null) {
            return null;
        }
        final int atIdx = id.indexOf('@');
        return atIdx > 0
                ? id.substring(0, atIdx)
                : id;
    }

    /**
     * Returns {@code true} if the object is a person. Type-only check — the
     * {@code '@'}-in-id fallback is applied upstream when event rows are
     * parsed, so by the time objects reach this class the type is definitive.
     */
    public static boolean isPerson(final FloorMapObject object) {
        return object != null
               && FloorMapJsonKeys.PERSON.equalsIgnoreCase(object.getType());
    }

    /**
     * Merges the person objects from a query refresh into the roster.
     * Non-person objects and null/empty ids are ignored; repeated ids are
     * deduplicated.
     *
     * @param objects the objects from the latest refresh; may be {@code null}
     * @return {@code true} only if the roster membership changed, so callers
     *         can skip re-pushing unchanged data to the grid
     */
    public boolean update(final List<FloorMapObject> objects) {
        if (objects == null) {
            return false;
        }
        boolean changed = false;
        for (final FloorMapObject object : objects) {
            if (isPerson(object)) {
                final String id = object.getId();
                if (id != null && !id.isEmpty() && !byId.containsKey(id)) {
                    byId.put(id, new UserEntry(id, displayName(id)));
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * Returns the roster sorted by display name (case-insensitive), with the
     * full id as a tiebreak so the order is stable.
     */
    public List<UserEntry> getUsers() {
        final List<UserEntry> users = new ArrayList<>(byId.values());
        users.sort((a, b) -> {
            final int cmp = a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
            return cmp != 0
                    ? cmp
                    : a.getId().compareTo(b.getId());
        });
        return users;
    }

    public boolean contains(final String id) {
        return id != null && byId.containsKey(id);
    }

    public void clear() {
        byId.clear();
    }

    /**
     * A single user row. Equality is on {@link #id} only so that a re-created
     * entry for the same user compares equal to the one a selection model is
     * already holding — a grid data refresh must not read as a selection change.
     */
    public static class UserEntry {

        private final String id;
        private final String displayName;

        public UserEntry(final String id, final String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof final UserEntry that)) {
                return false;
            }
            return Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(id);
        }
    }
}
