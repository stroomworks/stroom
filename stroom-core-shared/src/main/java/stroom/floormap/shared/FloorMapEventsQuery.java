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

/**
 * The events query a new {@link FloorMapDoc} starts with, and the column names it aliases.
 *
 * <p>These belong together because they have to agree. The floor map finds the entity and its
 * location by matching {@link FloorMapDoc#getEntityIdColumn()} and
 * {@link FloorMapDoc#getLocationIdColumn()} against the result column names, exactly; a name that
 * matches nothing leaves the index at {@code -1} and the whole parse returns no entities. The map
 * then looks exactly as it does when animation is switched off, while the query itself still
 * returns rows perfectly well — so a disagreement between the query text and the two column
 * settings is both easy to introduce and hard to read back from the symptom.</p>
 *
 * <p>{@link #defaultQuery()} therefore interpolates the same constants that
 * {@code FloorMapInitPresenter} stores as the initial column settings, rather than repeating the
 * aliases as literal text. Renaming a column here changes the query and the settings together or
 * not at all.</p>
 */
public final class FloorMapEventsQuery {

    /**
     * Result column holding the entity identity — the key events are grouped by.
     *
     * <p>Also the default value of {@link FloorMapDoc#getEntityIdColumn()}.</p>
     */
    public static final String ENTITY_ID_COLUMN = "Entity ID";

    /**
     * Result column holding the entity's location.
     *
     * <p>Read either as literal {@code map, x, y} coordinates or as the key of the fact the event
     * happened at — the second form is what lets a moved object take its visitors with it. Also
     * the default value of {@link FloorMapDoc#getLocationIdColumn()}.</p>
     */
    public static final String LOCATION_ID_COLUMN = "Location ID";

    /** Result column holding the entry's effective time; drives the timeline. */
    public static final String EFFECTIVE_TIME_COLUMN = "Effective Time";

    /** Result column holding the event's own type. Not the entity type — see the note below. */
    public static final String EVENT_TYPE_COLUMN = "Event Type";

    /** Result column holding the event status. */
    public static final String STATUS_COLUMN = "Status";

    /** Result column holding the event message. */
    public static final String MESSAGE_COLUMN = "Message";

    private FloorMapEventsQuery() {
        // Constants only.
    }

    /**
     * Builds the StroomQL a newly created floor map starts with.
     *
     * <p>Reads the Plan B temporal-state fields {@code Key}, {@code EffectiveTime} and
     * {@code Value}, pulling the individual event properties out of the JSON value with
     * {@code jq}. The store is referenced as {@code param('EventStore')}, which
     * {@code FloorMapQueryPresenter.buildQueryVariables} substitutes with the configured store's
     * name at query time.</p>
     *
     * @return the default events query; never null
     */
    public static String defaultQuery() {
        return "from param('EventStore')\n"
               + "select EffectiveTime as \"" + EFFECTIVE_TIME_COLUMN + "\",\n"
               + "  Key as \"" + ENTITY_ID_COLUMN + "\",\n"
               + "  jq(Value, '.location') as \"" + LOCATION_ID_COLUMN + "\",\n"
               + "  jq(Value, '.type') as \"" + EVENT_TYPE_COLUMN + "\",\n"
               + "  jq(Value, '.status') as \"" + STATUS_COLUMN + "\",\n"
               + "  jq(Value, '.message') as \"" + MESSAGE_COLUMN + "\"";
    }
}
