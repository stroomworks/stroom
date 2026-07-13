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
 * <p><strong>Transitional note:</strong> {@link #getWorldToMap()} holds the affine
 * that places this fact into map space. For a background entry it currently
 * carries the entry's {@code MAP_TO_SCREEN} matrix (where background placement
 * lives today); for a regular fact it carries {@code WORLD_TO_MAP}. Migration
 * (redesign Phase 1 · WS5) will unify these onto {@code WORLD_TO_MAP}.</p>
 */
public final class Fact {

    private final String key;
    private final String type;
    private final String image;
    private final FloorMapTransformationMatrix worldToMap;
    private final double[] position;

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
        this.key = key;
        this.type = type != null ? type : "";
        this.image = image;
        this.worldToMap = worldToMap != null
                ? worldToMap
                : FloorMapTransformationMatrix.identity();
        this.position = position != null
                ? new double[]{position[0], position[1]}
                : null;
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
     * Whether this fact is the map background, using the same rule as the parser:
     * its key or type equals {@code "background"} (case-insensitive).
     */
    public boolean isBackground() {
        return FloorMapJsonKeys.BACKGROUND.equalsIgnoreCase(type)
                || FloorMapJsonKeys.BACKGROUND.equalsIgnoreCase(key);
    }
}
