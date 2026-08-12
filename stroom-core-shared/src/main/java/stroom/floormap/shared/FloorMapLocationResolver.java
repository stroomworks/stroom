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
 * Places event entities on the map by resolving the <em>location</em> their
 * events name.
 *
 * <p>An event says "this entity was at that location". The location can arrive
 * in one of two shapes, and this class is what tells them apart:</p>
 *
 * <ul>
 *   <li><b>Coordinates</b> — {@code "<map>, <x>, <y>"}, the position already
 *       baked into the event when it was ingested (typically by an XSLT
 *       {@code lookup} against a location store). Used as-is.</li>
 *   <li><b>A reference</b> — anything else is read as the <em>key of the fact</em>
 *       the event happened at (a desk, a gate, a camera). The entity is then
 *       drawn wherever that fact currently is.</li>
 * </ul>
 *
 * <p>The distinction matters because baked coordinates are frozen at ingest
 * time: move the desk on the Editor tab and every event that ever happened at
 * it still reports the desk's old position, so entities keep visiting a place
 * nothing occupies any more. A reference is resolved against the facts loaded
 * for the current timeline instant, so moving the fact moves the entities with
 * it — retroactively, which matches how the editor persists a move (the fact's
 * existing shard is rewritten in place rather than time-versioned).</p>
 *
 * <p>Kept out of the presenters, and free of GWT types, so the parse and the
 * placement rules are unit-testable on the JVM.</p>
 */
public final class FloorMapLocationResolver {

    private FloorMapLocationResolver() {
        // Utility class.
    }

    /**
     * Parses a location value as literal coordinates.
     *
     * <p>The expected form is {@code "<map>, <x>, <y>"} — the leading token
     * records which floor/background the point is on and is not needed here,
     * because every fact and entity already shares one map space.</p>
     *
     * @param location the raw location column value; may be {@code null}
     * @return {@code {x, y}}, or {@code null} when the value is not coordinates
     *         (which makes it a {@link #parseReference reference})
     */
    public static double[] parseCoordinates(final String location) {
        if (location == null) {
            return null;
        }
        final String[] parts = location.split(",");
        if (parts.length < 3) {
            return null;
        }
        try {
            return new double[]{
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim())};
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses a location value as a reference to the fact the event happened at.
     *
     * @param location the raw location column value; may be {@code null}
     * @return the referenced fact key, or {@code null} when the value is blank
     *         or is literal {@link #parseCoordinates coordinates}
     */
    public static String parseReference(final String location) {
        if (location == null) {
            return null;
        }
        final String trimmed = location.trim();
        if (trimmed.isEmpty() || parseCoordinates(location) != null) {
            return null;
        }
        return trimmed;
    }

    /**
     * Places every entity that references a fact at that fact's current
     * position, passing through the ones that carry their own coordinates.
     *
     * <p>An entity whose reference matches no current fact is <em>omitted</em>:
     * it has no position, and the alternative — drawing it at its parsed
     * {@code (0, 0)} — would pile every unresolved entity onto the map origin
     * as if they were really there. The caller re-runs this whenever the facts
     * refresh, so an entity dropped because the facts had not loaded yet
     * reappears as soon as they do.</p>
     *
     * @param entities the entities parsed from the events query; may be
     *                 {@code null}
     * @param facts    the facts loaded for the current timeline instant; may be
     *                 {@code null} (then only coordinate-bearing entities survive)
     * @return the placed entities; never {@code null}
     */
    public static List<FloorMapObject> resolve(final List<FloorMapObject> entities,
                                               final List<Fact> facts) {
        final List<FloorMapObject> placed = new ArrayList<>();
        if (entities == null) {
            return placed;
        }
        final Map<String, double[]> anchors = anchorsByKey(facts);
        for (final FloorMapObject entity : entities) {
            if (entity == null) {
                continue;
            }
            final String ref = entity.getLocationRef();
            if (ref == null) {
                placed.add(entity);
                continue;
            }
            final double[] anchor = anchors.get(ref);
            if (anchor == null) {
                continue; // Dangling reference — nowhere to draw it.
            }
            final FloorMapObject located = new FloorMapObject(
                    entity.getId(), entity.getType(), anchor[0], anchor[1]);
            located.setLocationRef(ref);
            placed.add(located);
        }
        return placed;
    }

    /**
     * Compares two placement results by what actually reaches the canvas — the
     * entities present and where they are. Lets a caller skip re-pushing an
     * overlay a facts refresh did not move, which is the common case (the facts
     * query re-runs on every playback tick).
     *
     * @param a one list; may be {@code null}
     * @param b the other; may be {@code null}
     * @return {@code true} if both hold the same ids, in the same order, at the
     *         same positions
     */
    public static boolean samePositions(final List<FloorMapObject> a,
                                        final List<FloorMapObject> b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            final FloorMapObject one = a.get(i);
            final FloorMapObject other = b.get(i);
            if (one == null || other == null) {
                if (one != other) {
                    return false;
                }
                continue;
            }
            if (!Objects.equals(one.getId(), other.getId())
                || one.getX() != other.getX()
                || one.getY() != other.getY()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Indexes the facts by key at their map-space test point — the same point
     * area containment locates a fact at, so an entity anchored to a fact is
     * inside exactly the areas that fact is.
     */
    private static Map<String, double[]> anchorsByKey(final List<Fact> facts) {
        final Map<String, double[]> anchors = new HashMap<>();
        if (facts != null) {
            for (final Fact fact : facts) {
                if (fact != null && fact.getKey() != null) {
                    anchors.put(fact.getKey(), FloorMapGeometry.mapTestPoint(fact));
                }
            }
        }
        return anchors;
    }
}
