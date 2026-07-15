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

import stroom.floormap.shared.TypeStyle.Shape;

/**
 * Geometry for the default graphic drawn for an imageless fact (see the redesign
 * §5 rendering rules and §6 type settings). Kept as pure, GWT-free logic so the
 * point maths can be unit-tested; the canvas view turns these into SVG elements.
 *
 * <p>Shapes are centred on the origin and fit within {@code ±halfSize}. Circle and
 * square are rendered natively by the view (as {@code <circle>} / {@code <rect>});
 * triangle and diamond are polygons whose points this helper computes. Pin has no
 * polygon form (the view draws it as a path, falling back to a circle).</p>
 */
public final class FloorMapShapes {

    private FloorMapShapes() {
        // Utility class
    }

    /**
     * The SVG {@code points} attribute (space-separated {@code "x,y"} pairs) for a
     * polygon shape centred on the origin, or {@code null} for shapes the view
     * renders natively ({@code CIRCLE}, {@code SQUARE}, {@code PIN}, or {@code null}).
     *
     * @param shape    the shape, or {@code null}
     * @param halfSize half the graphic's extent (so it spans {@code 2 * halfSize})
     * @return the polygon points string, or {@code null} if not a polygon shape
     */
    public static String polygonPoints(final Shape shape, final double halfSize) {
        if (shape == null) {
            return null;
        }
        switch (shape) {
            case TRIANGLE:
                // Upward-pointing triangle.
                return point(0, -halfSize)
                        + " " + point(halfSize, halfSize)
                        + " " + point(-halfSize, halfSize);
            case DIAMOND:
                return point(0, -halfSize)
                        + " " + point(halfSize, 0)
                        + " " + point(0, halfSize)
                        + " " + point(-halfSize, 0);
            case CIRCLE:
            case SQUARE:
            case PIN:
            default:
                return null;
        }
    }

    private static String point(final double x, final double y) {
        return x + "," + y;
    }
}
