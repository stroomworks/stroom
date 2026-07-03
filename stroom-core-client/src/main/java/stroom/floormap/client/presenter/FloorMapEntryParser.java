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

import stroom.floormap.client.FloorMapJsonKeys;
import stroom.floormap.client.ValuePathAccessor;
import stroom.floormap.shared.FloorMapFieldMapping;
import stroom.floormap.shared.FloorMapFieldMapping.Role;
import stroom.floormap.shared.FloorMapObject;
import stroom.floormap.shared.FloorMapTransformationMatrix;
import stroom.util.client.JSONUtil;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared utility that parses a list of {@link stroom.util.shared.TemporalEntry}
 * objects into canvas-ready data: a background image, a background
 * transformation matrix, the background's temporal-store key, and a list of
 * {@link FloorMapObject}s.
 *
 * <p>Parsing is driven by a list of {@link FloorMapFieldMapping}s (the value
 * schema). Each mapping's {@link Role} determines how the extracted value is
 * interpreted. A schema must always be provided; it is stored in the
 * {@link stroom.floormap.shared.FloorMapDoc} and configured via the Settings tab.</p>
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
        private final String backgroundKey;
        private final FloorMapTransformationMatrix backgroundMatrix;
        private final List<FloorMapObject> objects;

        public ParseResult(final String backgroundImage,
                           final String backgroundKey,
                           final FloorMapTransformationMatrix backgroundMatrix,
                           final List<FloorMapObject> objects) {
            this.backgroundImage = backgroundImage;
            this.backgroundKey = backgroundKey;
            this.backgroundMatrix = backgroundMatrix;
            this.objects = objects;
        }

        /** The background image path, or {@code null} if none. */
        public String getBackgroundImage() {
            return backgroundImage;
        }

        /** The temporal-store key for the background entry, or {@code null}. */
        public String getBackgroundKey() {
            return backgroundKey;
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
     * Parses temporal entries into canvas-ready data using the supplied schema.
     *
     * <p>For each entry:</p>
     * <ul>
     *   <li>If the type is {@code "background"} (or the key is
     *       {@code "background"}), extracts the image path and map-to-screen
     *       matrix.</li>
     *   <li>Otherwise, extracts coords and the world-to-map matrix, applies the
     *       coordinate transformation, and adds a {@link FloorMapObject}.</li>
     * </ul>
     *
     * @param entries the temporal entries to parse; may be {@code null} or empty
     * @param schema  the value schema to use; must not be {@code null}
     * @return the parse result; never {@code null}
     */
    public static ParseResult parse(
            final List<stroom.util.shared.TemporalEntry> entries,
            final List<FloorMapFieldMapping> schema) {
        String backgroundImage = null;
        String backgroundKey = null;
        FloorMapTransformationMatrix bgMatrix = FloorMapTransformationMatrix.identity();
        final List<FloorMapObject> objects = new ArrayList<>();

        if (entries == null) {
            return new ParseResult(null, null, bgMatrix, objects);
        }

        // Resolve the path for each role from the schema.
        final String typePath = findPath(schema, Role.TYPE);
        final String positionPath = findPath(schema, Role.POSITION);
        final String imagePath = findPath(schema, Role.IMAGE);
        final String worldToMapPath = findPath(schema, Role.WORLD_TO_MAP);
        final String mapToScreenPath = findPath(schema, Role.MAP_TO_SCREEN);

        for (final stroom.util.shared.TemporalEntry entry : entries) {
            try {
                final String valueStr = entry.getValue();
                if (valueStr == null || !valueStr.trim().startsWith("{")) {
                    continue;
                }
                final JSONObject json = ValuePathAccessor.parse(valueStr);
                if (json == null) {
                    continue;
                }

                final String type = typePath != null
                        ? JSONUtil.getString(ValuePathAccessor.get(json, typePath))
                        : null;

                if (FloorMapJsonKeys.BACKGROUND.equalsIgnoreCase(type)
                        || FloorMapJsonKeys.BACKGROUND.equalsIgnoreCase(entry.getKey())) {
                    // Background entry
                    backgroundImage = imagePath != null
                            ? JSONUtil.getString(ValuePathAccessor.get(json, imagePath))
                            : null;
                    backgroundKey = entry.getKey();

                    bgMatrix = parseMatrix(json, mapToScreenPath);
                } else {
                    // Regular object
                    double worldX = 0;
                    double worldY = 0;
                    if (positionPath != null) {
                        final JSONArray coordsArr =
                                JSONUtil.getArray(ValuePathAccessor.get(json, positionPath));
                        if (coordsArr != null && coordsArr.size() >= 2) {
                            worldX = JSONUtil.getDouble(coordsArr.get(0));
                            worldY = JSONUtil.getDouble(coordsArr.get(1));
                        }
                    }

                    final FloorMapTransformationMatrix worldToMap =
                            parseMatrix(json, worldToMapPath);

                    // Apply world-to-map transformation
                    final double mapX =
                            worldToMap.getA() * worldX + worldToMap.getC() * worldY
                                    + worldToMap.getE();
                    final double mapY =
                            worldToMap.getB() * worldX + worldToMap.getD() * worldY
                                    + worldToMap.getF();

                    objects.add(new FloorMapObject(
                            entry.getKey(), type != null ? type : "", mapX, mapY));
                }
            } catch (final Exception ex) {
                // Skip malformed entries
            }
        }

        return new ParseResult(backgroundImage, backgroundKey, bgMatrix, objects);
    }

    /**
     * Finds the path for a given role in the schema, or {@code null} if not
     * mapped.
     */
    public static String findPath(final List<FloorMapFieldMapping> schema,
                           final Role role) {
        if (schema == null) {
            return null;
        }
        for (final FloorMapFieldMapping mapping : schema) {
            if (mapping.getRole() == role) {
                return mapping.getPath();
            }
        }
        return null;
    }

    /**
     * Parses a 6-element transformation matrix from a JSON array at the given
     * path. Returns {@link FloorMapTransformationMatrix#identity()} if the path
     * is null or the array is missing/malformed.
     */
    private static FloorMapTransformationMatrix parseMatrix(
            final JSONObject json, final String path) {
        if (path == null) {
            return FloorMapTransformationMatrix.identity();
        }
        final JSONArray arr = JSONUtil.getArray(ValuePathAccessor.get(json, path));
        if (arr != null && arr.size() >= 6) {
            return new FloorMapTransformationMatrix(
                    JSONUtil.getDouble(arr.get(0)),
                    JSONUtil.getDouble(arr.get(1)),
                    JSONUtil.getDouble(arr.get(2)),
                    JSONUtil.getDouble(arr.get(3)),
                    JSONUtil.getDouble(arr.get(4)),
                    JSONUtil.getDouble(arr.get(5)));
        }
        return FloorMapTransformationMatrix.identity();
    }
}
