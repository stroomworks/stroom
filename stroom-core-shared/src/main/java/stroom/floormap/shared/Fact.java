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
 * A single parsed floor-map fact — the unified model behind backgrounds,
 * static facts and events (see the FloorMap coordinate/rendering redesign).
 *
 * <p>Every renderable thing on a floor map is a {@code Fact}. A background is
 * simply a fact that carries an image and sits at a low z-order; an event
 * (person) is a fact from the event stream that may or may not carry an
 * image.</p>
 *
 * <p>Instances are immutable, produced by {@link FloorMapEntryParser} from a
 * {@link stroom.util.shared.TemporalEntry}. This is a plain, GWT-friendly value
 * object with no serialisation dependencies — it never crosses the wire (the
 * {@code TemporalEntry} does).</p>
 *
 * <p>{@link #getWorldToMap()} holds the affine that places this fact into map
 * space. Every fact — backgrounds included — uses {@code WORLD_TO_MAP}; a
 * background is not special-cased, it is simply an image fact placed by its own
 * matrix and painted early (low z-order).</p>
 */
public final class Fact {

    private final String key;
    private final String type;
    private final String image;
    private final FloorMapTransformationMatrix worldToMap;
    private final double[] position;
    private final double[][] vertices;
    private final String fill;
    private final Double opacity;

    /**
     * @param key        the temporal-store key (fact identity within a map)
     * @param type       the fact type ({@code ""} if unset); also drives z-order
     * @param image      the Asset Store image URL, or {@code null} if none
     * @param worldToMap the affine placing this fact into map space; never {@code null}
     * @param position   world-space coordinates {@code [x, y]} for a point fact,
     *                   or {@code null} (e.g. for a background)
     */
    public Fact(final String key,
                final String type,
                final String image,
                final FloorMapTransformationMatrix worldToMap,
                final double[] position) {
        this(key, type, image, worldToMap, position, null, null, null);
    }

    /**
     * @param key        the temporal-store key (fact identity within a map)
     * @param type       the fact type ({@code ""} if unset); also drives z-order
     * @param image      the Asset Store image URL, or {@code null} if none
     * @param worldToMap the affine placing this fact into map space; never {@code null}
     * @param position   world-space coordinates {@code [x, y]} for a point fact,
     *                   or {@code null} (e.g. for a background)
     * @param vertices   area polygon vertices {@code [[x,y], ...]} in the fact's
     *                   local frame (placed by {@code worldToMap}), or {@code null}
     *                   for a non-area fact
     * @param fill       area fill colour (hex string), or {@code null} to use the
     *                   type's default colour
     * @param opacity    area fill opacity in {@code [0, 1]}, or {@code null} for
     *                   the default
     */
    public Fact(final String key,
                final String type,
                final String image,
                final FloorMapTransformationMatrix worldToMap,
                final double[] position,
                final double[][] vertices,
                final String fill,
                final Double opacity) {
        this.key = key;
        this.type = type != null ? type : "";
        this.image = image;
        this.worldToMap = worldToMap != null
                ? worldToMap
                : FloorMapTransformationMatrix.identity();
        this.position = position != null
                ? new double[]{position[0], position[1]}
                : null;
        this.vertices = copyVertices(vertices);
        this.fill = fill;
        this.opacity = opacity;
    }

    public String getKey() {
        return key;
    }

    public String getType() {
        return type;
    }

    /** The image URL, or {@code null} if this fact has no image. */
    public String getImage() {
        return image;
    }

    /** {@code true} if this fact has an image (and so scales in map space). */
    public boolean hasImage() {
        return image != null && !image.isEmpty();
    }

    /** The affine that places this fact into map space; never {@code null}. */
    public FloorMapTransformationMatrix getWorldToMap() {
        return worldToMap;
    }

    /** World coordinates {@code [x, y]} for a point fact, or {@code null}. */
    public double[] getPosition() {
        return position != null
                ? new double[]{position[0], position[1]}
                : null;
    }

    /**
     * Area polygon vertices {@code [[x,y], ...]} in the fact's local frame,
     * or {@code null} if this fact is not an area.
     */
    public double[][] getVertices() {
        return copyVertices(vertices);
    }

    /** {@code true} if this fact is a renderable area polygon (≥ 3 vertices). */
    public boolean hasVertices() {
        return vertices != null && vertices.length >= 3;
    }

    /** The area fill colour (hex string), or {@code null} for the type default. */
    public String getFill() {
        return fill;
    }

    /** The area fill opacity in {@code [0, 1]}, or {@code null} for the default. */
    public Double getOpacity() {
        return opacity;
    }

    /**
     * Returns this fact's anchor point in map space — the point a camera
     * should centre on when the fact is tracked. Dispatch matches the
     * renderer's (an image wins over vertices):
     * <ul>
     *   <li>Image fact: the centre of the placed image rectangle. Images render
     *       at a fixed width with height derived from the aspect ratio; the
     *       render wrapper transform is
     *       {@code worldToMap · translate(0,h) · scale(1,-1)}, and applying it
     *       to the image centre {@code (w/2, h/2)} reduces to
     *       {@code worldToMap · (w/2, h/2)}.</li>
     *   <li>Area fact: the local-frame vertex centroid pushed through
     *       {@code worldToMap}.</li>
     *   <li>Point fact: its position pushed through {@code worldToMap}.</li>
     * </ul>
     *
     * @param imageDisplayWidth the fixed map-space width images render at
     * @param aspectRatio       the image's width/height ratio, or {@code null}
     *                          when not yet known (treated as square, matching
     *                          the renderer's pre-load fallback)
     * @return the anchor {@code [mapX, mapY]}; never {@code null}
     */
    public double[] mapAnchor(final double imageDisplayWidth, final Double aspectRatio) {
        if (hasImage()) {
            final double aspect = aspectRatio != null ? aspectRatio : 1.0;
            return worldToMap.transformPoint(
                    imageDisplayWidth / 2,
                    imageDisplayWidth / aspect / 2);
        }
        if (hasVertices()) {
            double cx = 0;
            double cy = 0;
            int count = 0;
            for (final double[] v : vertices) {
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
        return worldToMap.transformPoint(
                position != null ? position[0] : 0,
                position != null ? position[1] : 0);
    }

    /**
     * Returns a copy of this fact with a different placement matrix — used for
     * live transform previews. All other fields (including area geometry) are
     * carried over unchanged.
     */
    public Fact withWorldToMap(final FloorMapTransformationMatrix newWorldToMap) {
        return new Fact(key, type, image, newWorldToMap, position, vertices, fill, opacity);
    }

    /**
     * Returns a copy of this fact with different area vertices (local frame) —
     * used for live vertex-edit previews. All other fields are carried over
     * unchanged.
     */
    public Fact withVertices(final double[][] newVertices) {
        return new Fact(key, type, image, worldToMap, position, newVertices, fill, opacity);
    }

    private static double[][] copyVertices(final double[][] source) {
        if (source == null) {
            return null;
        }
        final double[][] copy = new double[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] != null
                    ? new double[]{source[i][0], source[i][1]}
                    : null;
        }
        return copy;
    }
}
