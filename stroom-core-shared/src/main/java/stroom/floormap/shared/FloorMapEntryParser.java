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
 * objects into an ordered list of {@link Fact}s — the authoritative model the
 * canvas renders from. Backgrounds, static facts and events are all just facts.
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
     * Parses temporal entries into an ordered list of {@link Fact}s.
     *
     * <p>Every entry becomes one fact placed by its {@code WORLD_TO_MAP} matrix —
     * backgrounds are not special-cased; a background is simply an entry that
     * carries an image (and typically a {@code "background"} type for z-order).
     * The optional {@code POSITION} coords are read for every fact (used by the
     * imageless default-graphic renderer); an image fact usually has none.</p>
     *
     * @param entries         the temporal entries to parse; may be {@code null} or empty
     * @param schema          the value schema to use; may be {@code null}
     * @param accessor        the value accessor for parsing; must not be {@code null}
     * @param warningConsumer callback for warning messages (e.g. malformed entries);
     *                        may be {@code null} to silently ignore warnings
     * @return the ordered fact list; never {@code null}
     */
    public static List<Fact> parse(
            final List<TemporalEntry> entries,
            final List<FloorMapFieldMapping> schema,
            final ValueAccessor accessor,
            final Consumer<String> warningConsumer) {
        final List<Fact> facts = new ArrayList<>();

        if (entries == null) {
            return facts;
        }

        // Resolve the path for each role from the schema.
        final String typePath = findPath(schema, Role.TYPE);
        final String positionPath = findPath(schema, Role.POSITION);
        final String imagePath = findPath(schema, Role.IMAGE);
        final String worldToMapPath = findPath(schema, Role.WORLD_TO_MAP);
        final String geometryPath = findPath(schema, Role.GEOMETRY);
        final String fillPath = findPath(schema, Role.FILL);
        final String opacityPath = findPath(schema, Role.OPACITY);

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
                final String image = imagePath != null
                        ? accessor.getString(parsed, imagePath)
                        : null;

                // Every fact — background or not — is placed by its WORLD_TO_MAP.
                final FloorMapTransformationMatrix worldToMap =
                        parseMatrix(accessor, parsed, worldToMapPath);

                double[] position = null;
                if (positionPath != null) {
                    final double[] coords = accessor.getArray(parsed, positionPath);
                    if (coords != null && coords.length >= 2) {
                        position = new double[]{coords[0], coords[1]};
                    }
                }

                final double[][] vertices = geometryPath != null
                        ? parseVertices(accessor.getArray(parsed, geometryPath))
                        : null;
                final String fill = fillPath != null
                        ? accessor.getString(parsed, fillPath)
                        : null;
                final Double opacity = opacityPath != null
                        ? accessor.getNumber(parsed, opacityPath)
                        : null;

                facts.add(new Fact(entry.getKey(), type, image, worldToMap, position,
                        vertices, fill, opacity));
            } catch (final Exception ex) {
                warn(warningConsumer, "Skipping malformed temporal entry (key='"
                        + entry.getKey() + "'): " + ex.getMessage());
            }
        }

        return facts;
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
     * Folds a flat {@code [x0, y0, x1, y1, ...]} geometry array into vertex
     * pairs. Returns {@code null} for a missing or too-short array (fewer
     * than 3 vertices). A trailing odd value is ignored.
     */
    private static double[][] parseVertices(final double[] flat) {
        if (flat == null || flat.length < 6) {
            return null;
        }
        final int count = flat.length / 2;
        final double[][] vertices = new double[count][];
        for (int i = 0; i < count; i++) {
            vertices[i] = new double[]{flat[i * 2], flat[i * 2 + 1]};
        }
        return vertices;
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
