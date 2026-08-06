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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Which entities the canvas merges into summary glyphs for one frame, because
 * they are closer together on screen than a glyph is wide.
 *
 * <h2>The problem this solves</h2>
 * <p>Point glyphs are drawn at a <strong>fixed screen size</strong> while their
 * positions live in map space, so the ratio of glyph size to the distance
 * between neighbours is unbounded. Two consequences, which are the same bug:
 * entities at the <em>same</em> position are zero pixels apart at every zoom, so
 * no amount of zooming in separates them and only the last one painted is
 * visible; and zooming out drives every distance toward zero while the glyphs
 * stay the same size, clumping the whole map into a mass.</p>
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li><strong>Only fixed-size glyphs cluster.</strong> Facts with an image or
 *       with vertices — floor plans, backgrounds, areas — scale with the map, so
 *       they shrink out of the way on their own and are excluded. That is
 *       exactly the set the renderer draws through {@code appendStyledGlyph}:
 *       event entities plus imageless, vertex-less facts.</li>
 *   <li><strong>Work in map space</strong>, with the screen radius converted
 *       once via {@link #mapThreshold}. This keeps projection maths out of the
 *       algorithm, and it means <em>panning cannot change the clustering</em> —
 *       only zoom can. A screen-space lattice would reshuffle every cluster as
 *       the user dragged.</li>
 *   <li><strong>One type per cluster.</strong> Clustering runs per type, so a
 *       cluster keeps its type's colour, shape and layer dimming, and its
 *       caption can read "10 users" instead of "12 entities".</li>
 *   <li><strong>The focus clusters like everything else, but its cluster is
 *       drawn around it.</strong> A cluster holding the tracked or selected entity
 *       is anchored at <em>that member's</em> position rather than the centroid,
 *       reports it via {@link FloorMapCluster#getFocusedMemberId()}, and is drawn
 *       with the selection ring and a caption naming it. Excluding the focus from
 *       clustering instead would leave two glyphs a few pixels apart fighting for
 *       the same space — the crowding this whole class exists to remove. One glyph
 *       suffices because clusters are homogeneous: the glyph comes from the shared
 *       type, so a focused member's glyph and its cluster's are the same shape and
 *       colour anyway.</li>
 *   <li><strong>A cluster of one is not a cluster</strong> — its member renders
 *       normally.</li>
 *   <li>When an id appears as both an event and a fact (an image-bearing fact
 *       twin) the <strong>event</strong> wins, as it does everywhere else; it is
 *       the live position.</li>
 * </ul>
 *
 * <h2>Determinism</h2>
 * <p>This is recomputed on every frame, including every animation frame, so an
 * order-dependent result would make badges flicker while entities move. The
 * partition is fixed by geometry alone: items are bucketed into a lattice
 * anchored at the map origin with cells the size of the threshold, cells are
 * visited row-major, and items within a cell are visited by id. The first
 * unassigned item seeds a cluster and absorbs every unassigned item within the
 * threshold <em>of the seed</em> — searching only the 3×3 neighbouring cells,
 * which is sufficient because a cell is exactly one threshold wide. Absorbing
 * around the seed rather than a moving centroid caps each cluster's diameter at
 * twice the threshold and prevents the long chains single-linkage clustering
 * produces across a crowded floor.</p>
 *
 * <p>Two consequences worth knowing, neither of which a user action can trigger:
 * an entity sitting exactly at the threshold during playback flips in and out of
 * its cluster from frame to frame, so a badge can alternate between 9 and 10
 * (damping that needs state carried between frames and is deliberately not done
 * here); and because the lattice is anchored at the map origin, where the cell
 * boundaries fall relative to a crowd can decide which of its members seeds it,
 * so the same relative arrangement of entities elsewhere on the map may partition
 * differently. Both outcomes are always valid partitions — bounded diameter, no
 * entity in two clusters — and panning cannot cause either, because the pan is
 * not an input here.</p>
 *
 * <h2>What a count means</h2>
 * <p>The same thing an area's occupant badge means: entities that <em>have a
 * position</em> at the current timeline instant. An entity whose events have
 * gone quiet has no position and is not counted, so a cluster count is not an
 * occupancy figure.</p>
 *
 * <p>Holds no GWT or DOM types so it can be unit-tested on the JVM.</p>
 */
public final class FloorMapClusterOverlay {

    /** Nothing merged — every entity renders on its own. */
    public static final FloorMapClusterOverlay EMPTY =
            new FloorMapClusterOverlay(Collections.emptyList(),
                    Collections.emptyMap(),
                    Collections.emptyMap());

    /**
     * Stands in for "no type" as a map key, since a {@link TreeMap} cannot hold
     * a null one. Converted back to {@code null} on the way out.
     */
    private static final String NO_TYPE = "";

    private final List<FloorMapCluster> clusters;
    private final Map<String, FloorMapCluster> byMemberId;
    private final Map<String, FloorMapCluster> byKey;

    private FloorMapClusterOverlay(final List<FloorMapCluster> clusters,
                                   final Map<String, FloorMapCluster> byMemberId,
                                   final Map<String, FloorMapCluster> byKey) {
        this.clusters = clusters;
        this.byMemberId = byMemberId;
        this.byKey = byKey;
    }

    /**
     * Converts a screen-space clustering radius into the map-space threshold the
     * algorithm works in.
     *
     * <p>Guards the divisor. A zoomed-right-out or not-yet-laid-out canvas can
     * present a zero, negative or NaN scale, and letting that through would
     * produce a NaN threshold — which does not fail loudly, it silently merges
     * nothing (or everything). Returns {@code 0}, which {@link #compute} treats
     * as "do not cluster".</p>
     *
     * @param radiusPx the clustering radius in screen pixels
     * @param scale    the current zoom, i.e. screen pixels per map unit
     * @return the map-space threshold, or {@code 0} when it cannot be computed
     */
    public static double mapThreshold(final double radiusPx, final double scale) {
        if (isUsableNumber(radiusPx) || isUsableNumber(scale) || radiusPx <= 0 || scale <= 0) {
            return 0;
        }
        return radiusPx / scale;
    }

    /**
     * Computes the frame's clusters.
     *
     * @param facts       the static facts already filtered to the visible layers;
     *                    may be {@code null}
     * @param events      the live event entities, already filtered to the visible
     *                    layers, whose {@code x}/{@code y} are map-space; may be
     *                    {@code null}
     * @param thresholdMap the map-space merge distance, from
     *                    {@link #mapThreshold}; {@code 0} or invalid disables
     *                    clustering entirely
     * @param focusedIds  the tracked and selected ids. These still cluster, but a
     *                    cluster containing one is anchored on it and reports it as
     *                    its focused member, so it can be drawn as that entity.
     *                    May be {@code null}
     * @return the overlay; never {@code null}
     */
    public static FloorMapClusterOverlay compute(final List<Fact> facts,
                                                 final List<FloorMapObject> events,
                                                 final double thresholdMap,
                                                 final Set<String> focusedIds) {
        if (isUsableNumber(thresholdMap) || thresholdMap <= 0) {
            return EMPTY;
        }
        // Types are visited in name order so the cluster list — and therefore
        // the paint order — does not depend on query row order.
        final Map<String, List<Item>> byType = collectByType(facts, events);
        if (byType.isEmpty()) {
            return EMPTY;
        }

        final List<FloorMapCluster> clusters = new ArrayList<>();
        for (final Map.Entry<String, List<Item>> entry : byType.entrySet()) {
            final String type = NO_TYPE.equals(entry.getKey())
                    ? null
                    : entry.getKey();
            clusterOneType(type, entry.getValue(), thresholdMap, focusedIds, clusters);
        }
        if (clusters.isEmpty()) {
            return EMPTY;
        }

        final Map<String, FloorMapCluster> byMemberId = new HashMap<>();
        final Map<String, FloorMapCluster> byKey = new LinkedHashMap<>();
        for (final FloorMapCluster cluster : clusters) {
            byKey.put(cluster.getKey(), cluster);
            for (final String memberId : cluster.getMemberIds()) {
                byMemberId.put(memberId, cluster);
            }
        }
        return new FloorMapClusterOverlay(
                Collections.unmodifiableList(clusters),
                byMemberId,
                byKey);
    }

    /**
     * Gathers the clusterable entities, keyed by type. Events are taken first so
     * a live event position beats a static fact twin of the same id.
     */
    private static Map<String, List<Item>> collectByType(final List<Fact> facts,
                                                         final List<FloorMapObject> events) {
        final Map<String, List<Item>> byType = new TreeMap<>();
        final Set<String> seen = new HashSet<>();

        if (events != null) {
            for (final FloorMapObject event : events) {
                if (event != null
                        && isUsableId(event.getId())
                        && seen.add(event.getId())) {
                    add(byType, new Item(event.getId(), event.getType(),
                            event.getX(), event.getY()));
                }
            }
        }
        if (facts != null) {
            for (final Fact fact : facts) {
                if (isClusterableFact(fact)
                    && isUsableId(fact.getKey())
                    && seen.add(fact.getKey())) {
                    // The same route the renderer takes: a fact's map position is
                    // worldToMap applied to its coords, never either alone.
                    final double[] point = FloorMapGeometry.mapTestPoint(fact);
                    add(byType, new Item(fact.getKey(), fact.getType(), point[0], point[1]));
                }
            }
        }

        return byType;
    }

    private static void add(final Map<String, List<Item>> byType, final Item item) {
        final String key = item.type != null && !item.type.isEmpty()
                ? item.type
                : NO_TYPE;
        byType.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
    }

    /**
     * Clusters one type's entities, appending any cluster of two or more to
     * {@code out}. See the class javadoc for why the traversal order is what it
     * is.
     */
    private static void clusterOneType(final String type,
                                       final List<Item> items,
                                       final double thresholdMap,
                                       final Set<String> focusedIds,
                                       final List<FloorMapCluster> out) {
        if (items.size() < 2) {
            // One entity of a type can never cluster, so skip building a lattice
            // for it. Not just an optimisation for sparse maps — every type with
            // a lone member takes this path on every frame.
            return;
        }
        // Bucket into a lattice anchored at the map origin, one threshold per
        // cell — so everything within the threshold of a point is in one of the
        // nine cells around it.
        final Map<String, List<Item>> cells = new HashMap<>();
        final List<int[]> occupied = new ArrayList<>();
        for (final Item item : items) {
            final int cx = cellIndex(item.mapX, thresholdMap);
            final int cy = cellIndex(item.mapY, thresholdMap);
            final String cellKey = cellKey(cx, cy);
            List<Item> cell = cells.get(cellKey);
            if (cell == null) {
                cell = new ArrayList<>();
                cells.put(cellKey, cell);
                occupied.add(new int[]{cx, cy});
            }
            cell.add(item);
        }

        // Row-major cell order, and id order within a cell: a total order fixed
        // by geometry, so renaming an entity cannot reshuffle the map.
        occupied.sort((a, b) -> a[1] != b[1]
                ? Integer.compare(a[1], b[1])
                : Integer.compare(a[0], b[0]));
        for (final List<Item> cell : cells.values()) {
            cell.sort((a, b) -> a.id.compareTo(b.id));
        }

        final double thresholdSquared = thresholdMap * thresholdMap;
        final Set<String> assigned = new HashSet<>();
        for (final int[] cell : occupied) {
            for (final Item seed : cells.get(cellKey(cell[0], cell[1]))) {
                if (assigned.contains(seed.id)) {
                    continue;
                }
                assigned.add(seed.id);
                final List<Item> members = absorb(seed, cell, cells, assigned, thresholdSquared);
                if (members.size() >= 2) {
                    out.add(build(type, seed, members, focusedIds));
                }
                // A seed that absorbed nobody stays marked. That is safe rather
                // than wasteful: it had already offered itself to every
                // unassigned neighbour within range, and distance is symmetric,
                // so no later seed can be close enough to want it.
            }
        }
    }

    /**
     * Collects the seed plus every unassigned entity within the threshold of it,
     * marking each as assigned. Only the 3×3 cells around the seed are searched.
     */
    private static List<Item> absorb(final Item seed,
                                     final int[] seedCell,
                                     final Map<String, List<Item>> cells,
                                     final Set<String> assigned,
                                     final double thresholdSquared) {
        final List<Item> members = new ArrayList<>();
        members.add(seed);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                final List<Item> neighbours = cells.get(cellKey(seedCell[0] + dx, seedCell[1] + dy));
                if (neighbours == null) {
                    continue;
                }
                for (final Item candidate : neighbours) {
                    if (!assigned.contains(candidate.id)
                            && withinThreshold(seed, candidate, thresholdSquared)) {
                        members.add(candidate);
                        assigned.add(candidate.id);
                    }
                }
            }
        }
        return members;
    }

    /**
     * Builds the cluster: id-sorted members, keyed on the seed, anchored at the
     * centroid — or, when one member is focused, at that member's own position.
     */
    private static FloorMapCluster build(final String type,
                                         final Item seed,
                                         final List<Item> members,
                                         final Set<String> focusedIds) {
        final List<String> ids = new ArrayList<>(members.size());
        double sumX = 0;
        double sumY = 0;
        for (final Item member : members) {
            ids.add(member.id);
            sumX += member.mapX;
            sumY += member.mapY;
        }
        Collections.sort(ids);

        // The representative focused member, chosen in sorted member order so it
        // does not depend on the traversal. Only reachable as more than one on a
        // tab with multi-select, which is a tab where clustering is off.
        final Item focused = firstFocused(ids, members, focusedIds);
        if (focused != null) {
            // Anchored on the focused member, not the crowd's average: this glyph
            // IS that entity as far as the user is concerned — it carries their
            // selection ring and their name — so it has to sit where they are.
            return new FloorMapCluster(seed.id, type,
                    Collections.unmodifiableList(ids),
                    focused.mapX, focused.mapY, focused.id);
        }
        // The centroid, not the seed's own position, so the glyph drifts smoothly
        // as members move rather than jumping when the seed changes.
        return new FloorMapCluster(seed.id, type,
                Collections.unmodifiableList(ids),
                sumX / members.size(),
                sumY / members.size(),
                null);
    }

    /**
     * The first member (in sorted id order) that is focused, or {@code null} if
     * none is.
     */
    private static Item firstFocused(final List<String> sortedIds,
                                     final List<Item> members,
                                     final Set<String> focusedIds) {
        if (focusedIds == null || focusedIds.isEmpty()) {
            return null;
        }
        for (final String id : sortedIds) {
            if (focusedIds.contains(id)) {
                for (final Item member : members) {
                    if (member.id.equals(id)) {
                        return member;
                    }
                }
            }
        }
        return null;
    }

    private static boolean withinThreshold(final Item a,
                                           final Item b,
                                           final double thresholdSquared) {
        final double dx = a.mapX - b.mapX;
        final double dy = a.mapY - b.mapY;
        return (dx * dx) + (dy * dy) <= thresholdSquared;
    }

    private static String cellKey(final int cx, final int cy) {
        return cx + "," + cy;
    }

    /**
     * The lattice cell index a map coordinate falls in.
     *
     * <p>An {@code int} rather than a {@code long} deliberately: this runs for
     * every entity on every animation frame, and GWT emulates {@code long} as a
     * three-{@code int} tuple, so long arithmetic here would be paid for 60 times
     * a second. The clamp covers the coordinate range that would overflow — far
     * outside any real floor plan, and harmless if reached, since two entities
     * sharing an edge cell are still separated by the distance test.</p>
     */
    private static int cellIndex(final double value, final double cell) {
        final double index = Math.floor(value / cell);
        if (index <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (index >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) index;
    }

    /**
     * {@code true} if this fact is drawn as a fixed-size point glyph, and so
     * needs clustering.
     *
     * <p>Mirrors the renderer's dispatch. A fact with an image renders as that
     * image and a fact with vertices renders as an area — both scale with the
     * map, so they shrink as you zoom out instead of crowding. A background is
     * excluded outright: its placement origin is arbitrary, and it covers the
     * map.</p>
     *
     * @param fact the fact to test; may be {@code null}
     */
    public static boolean isClusterableFact(final Fact fact) {
        return fact != null
                && !fact.hasImage()
                && !fact.hasVertices()
                && !FloorMapJsonKeys.BACKGROUND.equalsIgnoreCase(fact.getKey())
                && !FloorMapJsonKeys.BACKGROUND.equalsIgnoreCase(fact.getType());
    }

    private static boolean isUsableId(final String id) {
        return id != null && !id.isEmpty();
    }

    private static boolean isUsableNumber(final double value) {
        return Double.isNaN(value) || Double.isInfinite(value);
    }

    /**
     * Every cluster this frame, in paint order (by type name, then row-major by
     * position). Empty when nothing merged.
     */
    public List<FloorMapCluster> getClusters() {
        return clusters;
    }

    /**
     * {@code true} if this entity has been merged into a cluster, and so must
     * <strong>not</strong> be drawn on its own.
     *
     * @param id an entity id or fact key; may be {@code null}
     */
    public boolean isClustered(final String id) {
        return id != null && byMemberId.containsKey(id);
    }

    /**
     * The cluster this entity was merged into, or {@code null} if it renders on
     * its own.
     *
     * @param id an entity id or fact key; may be {@code null}
     */
    public FloorMapCluster getClusterFor(final String id) {
        return id != null
                ? byMemberId.get(id)
                : null;
    }

    /**
     * The cluster with the given key, or {@code null} if there is none — which is
     * how a hover or click on a cluster that has since dissolved resolves.
     *
     * @param key a cluster key, as given by {@link FloorMapCluster#getKey()};
     *            may be {@code null}
     */
    public FloorMapCluster getCluster(final String key) {
        return key != null
                ? byKey.get(key)
                : null;
    }

    /**
     * The cluster nearest to a map-space point and within {@code radiusMap} of
     * it, or {@code null} if none is. Used to resolve what the pointer is over.
     *
     * <p>Works in map space, like everything else here, so the caller converts
     * the cursor once ({@code screenToMap}) rather than this projecting every
     * cluster. The radius is the glyph's own half-width converted by
     * {@link #mapThreshold}, which is what makes the hit area track the zoom.</p>
     *
     * <p>Nearest rather than first: per-type clustering means two clusters can
     * overlap on screen, and the pointer should resolve to the one whose glyph is
     * actually under it.</p>
     *
     * @param mapX      the test point in map space
     * @param mapY      the test point in map space
     * @param radiusMap the hit radius in map units; {@code 0} or invalid matches
     *                  nothing
     * @return the cluster, or {@code null}
     */
    public FloorMapCluster clusterNear(final double mapX,
                                       final double mapY,
                                       final double radiusMap) {
        if (isUsableNumber(radiusMap) || radiusMap <= 0) {
            return null;
        }
        final double radiusSquared = radiusMap * radiusMap;
        FloorMapCluster nearest = null;
        double nearestSquared = Double.MAX_VALUE;
        for (final FloorMapCluster cluster : clusters) {
            final double dx = cluster.getMapX() - mapX;
            final double dy = cluster.getMapY() - mapY;
            final double distanceSquared = (dx * dx) + (dy * dy);
            if (distanceSquared <= radiusSquared && distanceSquared < nearestSquared) {
                nearest = cluster;
                nearestSquared = distanceSquared;
            }
        }
        return nearest;
    }

    /** {@code true} if nothing at all merged. */
    public boolean isEmpty() {
        return clusters.isEmpty();
    }

    /** How many entities are hidden inside clusters, across the whole frame. */
    public int getClusteredEntityCount() {
        return byMemberId.size();
    }

    /**
     * Two overlays are equal when they describe the same merging. Lets callers
     * skip work when a frame changed nothing.
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof final FloorMapClusterOverlay that)) {
            return false;
        }
        return clusters.equals(that.clusters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clusters);
    }

    @Override
    public String toString() {
        return "FloorMapClusterOverlay" + clusters;
    }

    /** One clusterable entity, reduced to what the algorithm needs. */
    private static final class Item {

        private final String id;
        private final String type;
        private final double mapX;
        private final double mapY;

        private Item(final String id, final String type, final double mapX, final double mapY) {
            this.id = id;
            this.type = type;
            this.mapX = mapX;
            this.mapY = mapY;
        }
    }
}
