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

package stroom.floormap.client.presenter;

import stroom.floormap.shared.FloorMapObject;
import stroom.floormap.shared.FloorMapTransformationMatrix;
import stroom.util.client.JSONUtil;
import stroom.util.shared.TemporalEntry;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONObject;

import java.util.ArrayList;
import java.util.List;

import static stroom.floormap.client.FloorMapJsonKeys.COORDS;
import static stroom.floormap.client.FloorMapJsonKeys.IMG;
import static stroom.floormap.client.FloorMapJsonKeys.TM_MAP_TO_SCREEN;
import static stroom.floormap.client.FloorMapJsonKeys.TM_WORLD_TO_MAP;
import static stroom.floormap.client.FloorMapJsonKeys.TYPE;

/**
 * Shared utility that parses a list of {@link TemporalEntry} objects into
 * canvas-ready data: a background image, a background transformation matrix,
 * the background's temporal-store key, and a list of {@link FloorMapObject}s.
 *
 * <p>Used by both the Map tab ({@link FloorMapMapPresenter}) and the Editor tab
 * ({@link FloorMapEditorPresenter}) to ensure consistent parsing logic.</p>
 */
public final class FloorMapEntryParser {

    private FloorMapEntryParser() {
        // Utility class
    }

    /**
     * Result of parsing a list of temporal entries for canvas rendering.
     */
    public static final class ParseResult {

        private final String backgroundImage;
        private final FloorMapTransformationMatrix backgroundMatrix;
        private final List<FloorMapObject> objects;

        public ParseResult(final String backgroundImage,
                           final FloorMapTransformationMatrix backgroundMatrix,
                           final List<FloorMapObject> objects) {
            this.backgroundImage = backgroundImage;
            this.backgroundMatrix = backgroundMatrix;
            this.objects = objects;
        }

        /** The background image path, or {@code null} if none. */
        public String getBackgroundImage() {
            return backgroundImage;
        }

        /** The map-to-screen transformation matrix for the background. */
        public FloorMapTransformationMatrix getBackgroundMatrix() {
            return backgroundMatrix;
        }

        /** The list of regular (non-background) objects to plot. */
        public List<FloorMapObject> getObjects() {
            return objects;
        }
    }

    /**
     * Parses temporal entries into canvas-ready data.
     *
     * <p>For each entry:</p>
     * <ul>
     *   <li>If the type is {@code "background"} (or the key is {@code "background"}),
     *       extracts the image path and map-to-screen matrix.</li>
     *   <li>Otherwise, extracts coords and the world-to-map matrix, applies the
     *       coordinate transformation, and adds a {@link FloorMapObject}.</li>
     * </ul>
     *
     * @param entries the temporal entries to parse; may be {@code null} or empty
     * @return the parse result; never {@code null}
     */
    public static ParseResult parse(final List<TemporalEntry> entries) {
        String backgroundImage = null;
        FloorMapTransformationMatrix bgMatrix = FloorMapTransformationMatrix.identity();
        final List<FloorMapObject> objects = new ArrayList<>();

        if (entries == null) {
            return new ParseResult(null, null, objects);
        }

        for (final TemporalEntry entry : entries) {
            try {
                final String valueStr = entry.getValue();
                if (valueStr == null || !valueStr.trim().startsWith("{")) {
                    continue;
                }
                final JSONObject json = JSONUtil.getObject(JSONUtil.parse(valueStr));
                if (json == null) {
                    continue;
                }

                final String type = JSONUtil.getString(json.get(TYPE));

                if ("background".equalsIgnoreCase(type)
                        || "background".equalsIgnoreCase(entry.getKey())) {
                    // Background entry
                    backgroundImage = JSONUtil.getString(json.get(IMG));

                    final JSONArray m2sArr = JSONUtil.getArray(json.get(TM_MAP_TO_SCREEN));
                    bgMatrix = parseMatrix(m2sArr);
                } else {
                    // Regular object
                    double worldX = 0;
                    double worldY = 0;
                    final JSONArray coordsArr = JSONUtil.getArray(json.get(COORDS));
                    if (coordsArr != null && coordsArr.size() >= 2) {
                        worldX = JSONUtil.getDouble(coordsArr.get(0));
                        worldY = JSONUtil.getDouble(coordsArr.get(1));
                    }

                    final FloorMapTransformationMatrix worldToMap =
                            parseMatrix(JSONUtil.getArray(json.get(TM_WORLD_TO_MAP)));

                    // Apply world-to-map transformation
                    final double mapX =
                            worldToMap.getA() * worldX + worldToMap.getC() * worldY + worldToMap.getE();
                    final double mapY =
                            worldToMap.getB() * worldX + worldToMap.getD() * worldY + worldToMap.getF();

                    objects.add(new FloorMapObject(
                            entry.getKey(), type != null ? type : "", mapX, mapY));
                }
            } catch (final Exception ex) {
                // Skip malformed entries
            }
        }

        return new ParseResult(backgroundImage, bgMatrix, objects);
    }

    /**
     * Parses a 6-element JSON array into a transformation matrix.
     * Returns {@link FloorMapTransformationMatrix#identity()} if the array is
     * {@code null} or has fewer than 6 elements.
     */
    private static FloorMapTransformationMatrix parseMatrix(final JSONArray arr) {
        if (arr == null || arr.size() < 6) {
            return FloorMapTransformationMatrix.identity();
        }
        return new FloorMapTransformationMatrix(
                JSONUtil.getDouble(arr.get(0)),
                JSONUtil.getDouble(arr.get(1)),
                JSONUtil.getDouble(arr.get(2)),
                JSONUtil.getDouble(arr.get(3)),
                JSONUtil.getDouble(arr.get(4)),
                JSONUtil.getDouble(arr.get(5)));
    }
}
