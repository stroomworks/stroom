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
 * Accumulating roster of every entity seen on a floor map's events stream —
 * people, assets, vehicles, or any other typed event object.
 *
 * <p>The events query at a given playback instant only returns entities with
 * events near that time, so this roster is a union of everything seen since
 * the last {@link #clear()} — entities are never removed when absent from a
 * refresh. This keeps the tracking panel's rows (and the user's selection)
 * stable across the ~300ms playback query refreshes.</p>
 *
 * <p>Holds no GWT or DOM types so it can be unit-tested on the JVM.</p>
 */
public class FloorMapEntityList {

    private final Map<String, EntityEntry> byId = new HashMap<>();

    /**
     * Derives the short display name for an entity id, matching the rule used
     * for canvas labels: the portion before an {@code '@'} when one is present
     * beyond the first character (email-style person ids), otherwise the full id.
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
     * Merges the entities from a query refresh into the roster. Every event
     * object is admitted regardless of type; null/empty ids are ignored and
     * repeated ids are deduplicated (first-seen type wins).
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
            if (object != null) {
                final String id = object.getId();
                if (id != null && !id.isEmpty() && !byId.containsKey(id)) {
                    final String type = object.getType() != null
                            ? object.getType()
                            : "";
                    byId.put(id, new EntityEntry(id, displayName(id), type));
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
    public List<EntityEntry> getEntities() {
        final List<EntityEntry> entities = new ArrayList<>(byId.values());
        entities.sort((a, b) -> {
            final int cmp = a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
            return cmp != 0
                    ? cmp
                    : a.getId().compareTo(b.getId());
        });
        return entities;
    }

    public boolean contains(final String id) {
        return id != null && byId.containsKey(id);
    }

    public void clear() {
        byId.clear();
    }

    /**
     * A single entity row. Equality is on {@link #id} only so that a re-created
     * entry for the same entity compares equal to the one a selection model is
     * already holding — a grid data refresh must not read as a selection change.
     */
    public static class EntityEntry {

        private final String id;
        private final String displayName;
        private final String type;

        public EntityEntry(final String id, final String displayName, final String type) {
            this.id = id;
            this.displayName = displayName;
            this.type = type;
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getType() {
            return type;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof final EntityEntry that)) {
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
