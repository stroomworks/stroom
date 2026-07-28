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
 * Plain 2D polygon geometry — the single containment algorithm shared by the
 * FloorMap client and the {@code pointIsInsideXYPolygon} XSLT function.
 *
 * <p>Containment is an <strong>even-odd</strong> (ray-cast) test with an
 * axis-aligned bounding box prefilter, matching the {@code fill-rule="evenodd"}
 * the area renderer paints with — so what looks filled on the canvas is exactly
 * what tests as inside.</p>
 *
 * <p>Holds no GWT or DOM types so it can be unit-tested on the JVM and compiled
 * to JavaScript.</p>
 *
 * <p><strong>Coordinate space:</strong> every method here is space-agnostic —
 * it simply compares numbers. Callers are responsible for making the polygon
 * and the point share one space; for FloorMap that space is always
 * <strong>map space</strong> (see {@link #toMapVertices(Fact)}).</p>
 */
public final class FloorMapGeometry {

    private FloorMapGeometry() {
        // Utility class.
    }

    /**
     * Tests whether the point {@code (x, y)} lies inside the polygon, using an
     * even-odd ray cast behind an AABB prefilter.
     *
     * <p>Points exactly on an edge are not guaranteed either way (the usual
     * caveat for this family of tests) — the result is stable, but which side a
     * boundary point falls on depends on the edge's orientation.</p>
     *
     * @param polygon the polygon vertices {@code [[x,y], ...]}; a polygon with
     *                fewer than 3 usable vertices contains nothing
     * @param x       the test point's x
     * @param y       the test point's y
     * @return {@code true} if the point is inside
     */
    public static boolean contains(final double[][] polygon, final double x, final double y) {
        if (polygon == null || polygon.length < 3) {
            return false;
        }

        // AABB prefilter — cheap rejection for the common "nowhere near" case.
        final double[] bounds = aabb(polygon);
        if (bounds == null
                || x < bounds[0] || x > bounds[2]
                || y < bounds[1] || y > bounds[3]) {
            return false;
        }

        boolean inside = false;
        for (int i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
            final double[] pi = polygon[i];
            final double[] pj = polygon[j];
            if (pi == null || pj == null) {
                continue;
            }
            if ((pi[1] > y) != (pj[1] > y)
                    && x < (pj[0] - pi[0]) * (y - pi[1]) / (pj[1] - pi[1]) + pi[0]) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * Returns the axis-aligned bounding box of the polygon as
     * {@code {minX, minY, maxX, maxY}}, or {@code null} if it has no usable
     * vertices.
     *
     * @param polygon the polygon vertices {@code [[x,y], ...]}; may be {@code null}
     * @return the bounds, or {@code null}
     */
    public static double[] aabb(final double[][] polygon) {
        if (polygon == null || polygon.length == 0) {
            return null;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (final double[] v : polygon) {
            if (v != null && v.length >= 2) {
                minX = Math.min(minX, v[0]);
                minY = Math.min(minY, v[1]);
                maxX = Math.max(maxX, v[0]);
                maxY = Math.max(maxY, v[1]);
                any = true;
            }
        }
        return any
                ? new double[]{minX, minY, maxX, maxY}
                : null;
    }

    /**
     * Returns the unsigned area of the polygon via the shoelace formula.
     *
     * <p>Used to rank nested areas: when a point falls inside several
     * overlapping areas, the smallest one is the most specific answer to
     * "which area is it in?".</p>
     *
     * @param polygon the polygon vertices {@code [[x,y], ...]}; may be {@code null}
     * @return the unsigned area, or {@code 0} for a degenerate polygon
     */
    public static double area(final double[][] polygon) {
        if (polygon == null || polygon.length < 3) {
            return 0;
        }
        double sum = 0;
        for (int i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
            final double[] pi = polygon[i];
            final double[] pj = polygon[j];
            if (pi == null || pj == null) {
                continue;
            }
            sum += (pj[0] * pi[1]) - (pi[0] * pj[1]);
        }
        return Math.abs(sum) / 2.0;
    }

    /**
     * Projects an area fact's vertices from its local frame into map space by
     * pushing each through the fact's {@code WORLD_TO_MAP} matrix.
     *
     * <p>This is the load-bearing step for correctness: area vertices are
     * stored centred on their centroid in a local frame, so an untransformed
     * comparison against a map-space point is meaningless.</p>
     *
     * @param area the area fact; may be {@code null}
     * @return the map-space vertices, or {@code null} if the fact is not an area
     */
    public static double[][] toMapVertices(final Fact area) {
        if (area == null || !area.hasVertices()) {
            return null;
        }
        final double[][] local = area.getVertices();
        final FloorMapTransformationMatrix worldToMap = area.getWorldToMap();
        final double[][] out = new double[local.length][];
        for (int i = 0; i < local.length; i++) {
            out[i] = local[i] != null
                    ? worldToMap.transformPoint(local[i][0], local[i][1])
                    : null;
        }
        return out;
    }

    /**
     * Returns the map-space point at which a fact is tested for containment.
     *
     * <p>Deliberately <em>not</em> {@link Fact#mapAnchor(double, Double)}: that
     * needs the rendered image width and aspect ratio, which only the view
     * knows. Containment instead uses the fact's own placement, which is
     * view-independent and stable:</p>
     * <ul>
     *   <li>an area → its local vertex centroid through {@code worldToMap};</li>
     *   <li>anything else → its {@code position} through {@code worldToMap}.</li>
     * </ul>
     *
     * <p>For an image fact this is its placement origin rather than the centre
     * of the drawn image. That is the documented trade-off for keeping the test
     * free of view state; it only matters for facts whose image is large
     * relative to the areas around it.</p>
     *
     * @param fact the fact to locate; must not be {@code null}
     * @return the map-space test point {@code {mapX, mapY}}
     */
    public static double[] mapTestPoint(final Fact fact) {
        final FloorMapTransformationMatrix worldToMap = fact.getWorldToMap();
        if (fact.hasVertices()) {
            final double[][] local = fact.getVertices();
            double cx = 0;
            double cy = 0;
            int count = 0;
            for (final double[] v : local) {
                if (v != null) {
                    cx += v[0];
                    cy += v[1];
                    count++;
                }
            }
            if (count > 0) {
                return worldToMap.transformPoint(cx / count, cy / count);
            }
        }
        final double[] position = fact.getPosition();
        return worldToMap.transformPoint(
                position != null ? position[0] : 0,
                position != null ? position[1] : 0);
    }
}
