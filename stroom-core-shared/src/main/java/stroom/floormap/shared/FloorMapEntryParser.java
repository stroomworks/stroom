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

import stroom.floormap.shared.FloorMapFieldMapping.Role;
import stroom.util.shared.TemporalEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;


/**
 * Shared utility that parses a list of {@link TemporalEntry}
 * objects into canvas-ready data: a background image, a background
 * transformation matrix, the background's temporal-store key, and a list of
 * {@link FloorMapObject}s.
 *
 * <p>Parsing is driven by a list of {@link FloorMapFieldMapping}s (the value
 * schema). Each mapping's {@link Role} determines how the extracted value is
 * interpreted. A schema must always be provided; it is stored in the
 * {@link FloorMapDoc} and configured via the Settings tab.</p>
 *
 * <p>Both JSON and XML value formats are supported. The caller supplies
 * an appropriate {@link ValueAccessor} implementation for parsing and field
 * extraction.</p>
 *
 * <p>This class has no GWT dependencies and can be tested with standard
 * JUnit.</p>
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
     * Parses temporal entries into canvas-ready data using the supplied schema
     * and value accessor.
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
     * @param entries         the temporal entries to parse; may be {@code null} or empty
     * @param schema          the value schema to use; may be {@code null}
     * @param accessor        the value accessor for parsing; must not be {@code null}
     * @param warningConsumer callback for warning messages (e.g. malformed entries);
     *                        may be {@code null} to silently ignore warnings
     * @return the parse result; never {@code null}
     */
    public static ParseResult parse(
            final List<TemporalEntry> entries,
            final List<FloorMapFieldMapping> schema,
            final ValueAccessor accessor,
            final Consumer<String> warningConsumer) {
        String backgroundImage = null;
        FloorMapTransformationMatrix bgMatrix = FloorMapTransformationMatrix.identity();
        final List<FloorMapObject> objects = new ArrayList<>();

        if (entries == null) {
            return new ParseResult(null, null, objects);
        }

        // Resolve the path for each role from the schema.
        final String typePath = findPath(schema, Role.TYPE);
        final String positionPath = findPath(schema, Role.POSITION);
        final String imagePath = findPath(schema, Role.IMAGE);
        final String worldToMapPath = findPath(schema, Role.WORLD_TO_MAP);
        final String mapToScreenPath = findPath(schema, Role.MAP_TO_SCREEN);

        for (final TemporalEntry entry : entries) {
            try {
                final String valueStr = entry.getValue();
                if (valueStr == null) {
                    warn(warningConsumer, "Skipping temporal entry with null value (key='"
                            + entry.getKey() + "')");
                    continue;
                }
                if (!accessor.canParse(valueStr.trim())) {
                    warn(warningConsumer, "Skipping unparseable temporal entry (key='"
                            + entry.getKey() + "'): value does not match expected format");
                    continue;
                }
                final ParsedValue parsed = accessor.parse(valueStr);
                if (parsed == null) {
                    warn(warningConsumer, "Skipping temporal entry that parsed to null (key='"
                            + entry.getKey() + "')");
                    continue;
                }

                final String type = typePath != null
                        ? accessor.getString(parsed, typePath)
                        : null;

                if (FloorMapJsonKeys.BACKGROUND.equalsIgnoreCase(type)
                        || FloorMapJsonKeys.BACKGROUND.equalsIgnoreCase(entry.getKey())) {
                    // Background entry
                    backgroundImage = imagePath != null
                            ? accessor.getString(parsed, imagePath)
                            : null;

                    bgMatrix = parseMatrix(accessor, parsed, mapToScreenPath);
                } else {
                    // Regular object
                    double worldX = 0;
                    double worldY = 0;
                    if (positionPath != null) {
                        final double[] coords = accessor.getArray(parsed, positionPath);
                        if (coords != null && coords.length >= 2) {
                            worldX = coords[0];
                            worldY = coords[1];
                        }
                    }

                    final FloorMapTransformationMatrix worldToMap =
                            parseMatrix(accessor, parsed, worldToMapPath);

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
                warn(warningConsumer, "Skipping malformed temporal entry (key='"
                        + entry.getKey() + "'): " + ex.getMessage());
            }
        }

        return new ParseResult(backgroundImage, bgMatrix, objects);
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
     * Parses a 6-element transformation matrix from a numeric array at the
     * given path. Returns {@link FloorMapTransformationMatrix#identity()} if
     * the path is null or the array is missing/malformed.
     */
    private static FloorMapTransformationMatrix parseMatrix(
            final ValueAccessor accessor, final ParsedValue parsed,
            final String path) {
        if (path == null) {
            return FloorMapTransformationMatrix.identity();
        }
        final double[] arr = accessor.getArray(parsed, path);
        if (arr != null && arr.length >= 6) {
            return new FloorMapTransformationMatrix(
                    arr[0], arr[1], arr[2], arr[3], arr[4], arr[5]);
        }
        return FloorMapTransformationMatrix.identity();
    }

    /**
     * Emits a warning message via the consumer, if one is provided.
     */
    private static void warn(final Consumer<String> warningConsumer,
                             final String message) {
        if (warningConsumer != null) {
            warningConsumer.accept(message);
        }
    }
}
