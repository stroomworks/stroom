/*
 * Copyright 2026 Crown Copyright
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

package stroom.dependency.client.layout;

import stroom.dependency.shared.EdgeKind;

/// Routes an edge as a cubic Bézier curve.
///
/// Shared by the renderer and the audit, because whether an edge passes through a node is a layout
/// question, not a painting one. Ported from `edgePath()` in the reference harness.
///
/// An edge to something in a row above or below leaves from the node's top or bottom rather than its end,
/// because routing it end to end sent endpoint edges sideways across other nodes. An edge along a row bows
/// over anything sitting between its ends.
public final class EdgePaths {

    // Points in the array returned by points(): start, two control points, end.
    private static final int POINT_COUNT = 8;
    private static final double MIN_HORIZONTAL_PULL = 46;
    private static final double HORIZONTAL_PULL = 0.45;
    private static final double MIN_VERTICAL_PULL = 40;
    private static final double VERTICAL_PULL = 0.6;
    // How close to the edge's row a node's centre must be to count as in the way.
    private static final double SAME_ROW_TOLERANCE = 8;
    private static final double BOW_FACTOR = 0.95;
    private static final double BOW_CLEARANCE = 22;

    private EdgePaths() {
        // Static methods only.
    }

    /// @param layout The layout the edge belongs to.
    /// @param edge   An edge of that layout.
    /// @return the curve's start, first control point, second control point and end, as eight numbers
    /// `x1, y1, cx1, cy1, cx2, cy2, x2, y2`; or null if either end is not placed.
    public static double[] points(final LayoutResult layout, final LayoutEdge edge) {
        final Instance a = edge.a();
        final Instance b = edge.b();
        if (a.getX() == null || b.getX() == null) {
            return null;
        }
        final Dims da = layout.dims(a);
        final Dims db = layout.dims(b);
        final double ax = a.getX();
        final double ay = a.getY();
        final double bx = b.getX();
        final double by = b.getY();
        final boolean down = ay + da.h() <= by;
        final boolean up = by + db.h() <= ay;
        final EdgeKind kind = edge.kind();
        final boolean stacked = (kind == EdgeKind.REFERENCE || kind == EdgeKind.INHERITANCE || kind == EdgeKind.HTTP)
                                && (down || up);

        if (stacked) {
            final double x1 = ax + da.w() / 2.0;
            final double y1 = down
                    ? ay + da.h()
                    : ay;
            final double x2 = bx + db.w() / 2.0;
            final double y2 = down
                    ? by
                    : by + db.h();
            final double sign = y2 >= y1
                    ? 1
                    : -1;
            final double dy = Math.max(MIN_VERTICAL_PULL, Math.abs(y2 - y1) * VERTICAL_PULL);
            return new double[]{x1, y1, x1, y1 + sign * dy, x2, y2 - sign * dy, x2, y2};
        }

        final double x1 = ax + da.w();
        final double y1 = ay + da.h() / 2.0;
        final double x2 = bx;
        final double y2 = by + db.h() / 2.0;
        final double bow = isAnythingBetween(layout, edge, x1, y1, x2)
                ? da.h() * BOW_FACTOR + BOW_CLEARANCE
                : 0;
        final double dx = Math.max(MIN_HORIZONTAL_PULL, Math.abs(x2 - x1) * HORIZONTAL_PULL);
        return new double[]{x1, y1, x1 + dx, y1 - bow, x2 - dx, y2 - bow, x2, y2};
    }

    /// @param layout The layout the edge belongs to.
    /// @param edge   An edge of that layout.
    /// @return the curve as an SVG path, e.g. `M356,97 C545,97 291,97 480,97`; or null if either end is not
    /// placed. Numbers are written as JavaScript writes them, so a whole number has no decimal point.
    public static String path(final LayoutResult layout, final LayoutEdge edge) {
        final double[] p = points(layout, edge);
        if (p == null) {
            return null;
        }
        return "M" + format(p[0]) + "," + format(p[1])
               + " C" + format(p[2]) + "," + format(p[3])
               + " " + format(p[4]) + "," + format(p[5])
               + " " + format(p[6]) + "," + format(p[7]);
    }

    /// @param points The eight numbers from [#points].
    /// @param t      How far along the curve, from 0 to 1.
    /// @return the point on the curve at `t`, as `{x, y}`.
    public static double[] pointAt(final double[] points, final double t) {
        if (points.length != POINT_COUNT) {
            throw new IllegalArgumentException("Expected " + POINT_COUNT + " numbers, got " + points.length);
        }
        final double u = 1 - t;
        final double x = u * u * u * points[0]
                         + 3 * u * u * t * points[2]
                         + 3 * u * t * t * points[4]
                         + t * t * t * points[6];
        final double y = u * u * u * points[1]
                         + 3 * u * u * t * points[3]
                         + 3 * u * t * t * points[5]
                         + t * t * t * points[7];
        return new double[]{x, y};
    }

    // True if another instance in the source's lane sits on the edge's row between its ends.
    private static boolean isAnythingBetween(final LayoutResult layout,
                                             final LayoutEdge edge,
                                             final double x1,
                                             final double y1,
                                             final double x2) {
        final double left = Math.min(x1, x2);
        final double right = Math.max(x1, x2);
        for (final Instance other : layout.getInstances()) {
            if (other == edge.a()
                || other == edge.b()
                || other.getX() == null
                || other.getLane() != edge.a().getLane()) {
                continue;
            }
            final Dims dims = layout.dims(other);
            if (Math.abs((other.getY() + dims.h() / 2.0) - y1) < SAME_ROW_TOLERANCE
                && other.getX() > left
                && other.getX() + dims.w() < right) {
                return true;
            }
        }
        return false;
    }

    /// Writes a number as JavaScript does, so a whole number has no decimal point, for SVG attributes.
    /// Coordinates are far from the range where JavaScript switches to exponent notation.
    ///
    /// @param value A number.
    /// @return the number as text, e.g. `97` or `37.5`.
    public static String format(final double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
