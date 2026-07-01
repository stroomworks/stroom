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

package stroom.floormap.client.view;

import stroom.floormap.shared.FloorMapTransformationMatrix;
import stroom.widget.util.client.HtmlBuilder;
import stroom.widget.util.client.HtmlBuilder.Attribute;
import stroom.widget.util.client.SafeHtmlUtil;

/**
 * Generates an adaptive SVG grid background for the floor map canvas.
 *
 * <p>The grid fills the entire SVG viewport and dynamically adjusts its
 * spacing based on the combined zoom level (world-to-map matrix scale
 * &times; user zoom), so there is always a visually comfortable number of
 * grid squares on screen.</p>
 *
 * <h3>Grid levels</h3>
 * <p>Major grid lines are drawn at power-of-10 intervals in world-space
 * units. Each major square contains 10 subdivisions (minor grid). As the
 * user zooms in, minor lines fade in; once they are large enough on screen
 * they promote to become the next major level and a finer set of
 * subdivisions appears. Zooming out reverses this.</p>
 *
 * <h3>Rendering</h3>
 * <p>Uses SVG {@code <pattern>} elements with a {@code patternTransform}
 * that aligns the grid with the map coordinate system. The grid rects use
 * {@code width="100%" height="100%"} so the grid always extends to the
 * edges of the viewport regardless of pan/zoom. Grid lines use
 * {@code vector-effect="non-scaling-stroke"} so they remain a constant
 * pixel width.</p>
 *
 * <p>This method should be called at the <strong>SVG root level</strong>,
 * outside the pan/zoom and matrix transform groups.</p>
 */
public final class FloorMapGridBackground {

    // -- Appearance constants ------------------------------------------------

    /** Background fill (slightly off-black). */
    private static final String BG_FILL = "#1a1a1a";
    /** Major grid line colour. */
    private static final String MAJOR_STROKE = "rgba(255,255,255,0.6)";
    /** Desired screen-pixel width for major grid lines. */
    private static final double MAJOR_SCREEN_PX = 1.0;
    /** Maximum opacity for minor grid lines (reached mid-decade). */
    private static final double MINOR_MAX_OPACITY = 0.25;
    /** Desired screen-pixel width for minor grid lines. */
    private static final double MINOR_SCREEN_PX = 0.5;

    /** Stroom highlight colour (Material Design Blue 600). */
    private static final String HIGHLIGHT_COLOUR = "#1e88e5";
    /** Screen-pixel stroke width for origin axis lines. */
    private static final double AXIS_SCREEN_PX = 2.5;
    /** Arrowhead length expressed as a number of minor grid divisions. */
    private static final double ARROW_MINOR_DIVISIONS = 2.0;
    /** Screen-pixel font size for the origin scale labels. */
    private static final double LABEL_FONT_SCREEN_PX = 12.0;
    /** Screen-pixel gap between the arrowhead tip and the label. */
    private static final double LABEL_GAP_SCREEN_PX = 4.0;

    // -- Zoom range constants ------------------------------------------------

    /**
     * Minimum desired screen-pixel spacing for major grid lines.
     * Below this, we switch to the next coarser decade.
     */
    static final double TARGET_MIN_PX = 40.0;

    /**
     * Maximum desired screen-pixel spacing for major grid lines.
     * Above this, we switch to the next finer decade.
     */
    static final double TARGET_MAX_PX = 400.0;

    // -- Pattern / marker IDs (unique within a single SVG document) --------
    private static final String MAJOR_PATTERN_ID = "grid-major";
    private static final String ARROW_MARKER_ID = "origin-arrow";

    private FloorMapGridBackground() {
        // utility class
    }

    /**
     * Appends SVG elements for an adaptive grid background into the given
     * {@link HtmlBuilder}. This should be called at the <strong>SVG root
     * level</strong>, outside the pan/zoom and matrix transform groups.
     *
     * <p>The grid fills the entire viewport ({@code width="100%"
     * height="100%"}) and uses {@code patternTransform} to align the grid
     * with the map coordinate system. This means the grid always extends
     * to the edges of the screen regardless of pan or zoom.</p>
     *
     * <p>An origin indicator is drawn at world-space (0,0) showing the
     * X and Y axes, each one major grid division long, in the Stroom
     * highlight colour with arrowheads and scale labels.</p>
     *
     * @param builder    the HtmlBuilder to append into (at SVG root level)
     * @param matrix     the world-to-map transformation matrix
     * @param userZoom   the current user zoom level (the {@code scale} in
     *                   the pan group)
     * @param panX       the current horizontal pan offset (the {@code x}
     *                   translation in the pan group)
     * @param panY       the current vertical pan offset (the {@code y}
     *                   translation in the pan group)
     */
    public static void appendGrid(final HtmlBuilder builder,
                                  final FloorMapTransformationMatrix matrix,
                                  final double userZoom,
                                  final double panX,
                                  final double panY) {

        // -- 1. Compute effective pixels-per-world-unit ----------------------
        //    matrixScale = scale factor of the world-to-map affine matrix
        //    effectiveScale = matrixScale × userZoom
        final double matrixScale = Math.sqrt(
                matrix.getA() * matrix.getA()
                + matrix.getB() * matrix.getB());
        final double effectiveScale = matrixScale * userZoom;

        // -- 2. Pick the major grid decade -----------------------------------
        final double[] params = computeGridParams(effectiveScale);
        final double majorWorldSpacing = params[0];
        final double minorOpacity = params[1];

        // World-space size of one minor grid cell (1/10th of major)
        final double minorWorldSpacing = majorWorldSpacing / 10.0;

        final String minorStroke = "rgba(255,255,255," + formatDouble(minorOpacity) + ")";

        // Compute stroke widths in world-space units so that lines render
        // at a constant screen-pixel width.  We cannot use
        // vector-effect="non-scaling-stroke" because it does not work
        // inside SVG <pattern> tiles (the tile is rasterised once in
        // pattern-coordinate space, then tiled).
        final String majorStrokeWidth = formatDouble(MAJOR_SCREEN_PX / effectiveScale);
        final String minorStrokeWidth = formatDouble(MINOR_SCREEN_PX / effectiveScale);

        // The combined transform that maps pattern (world-space) coordinates
        // to SVG viewport coordinates: first the world-to-map matrix, then
        // the user pan/zoom.  Pattern tile dimensions are in world-space —
        // this transform handles the entire mapping to screen pixels.
        final String patternTransform = "translate(" + formatDouble(panX)
                + "," + formatDouble(panY) + ") scale(" + formatDouble(userZoom)
                + ") " + matrix.toSvgMatrix();

        // -- 3. Emit SVG <defs> with grid pattern and arrowhead marker -------
        //    One tile = one major grid cell. Minor subdivisions (9 lines)
        //    are drawn directly inside the tile — no nested patterns, which
        //    avoids patternTransform compounding issues.
        builder.elem(defs -> {

            defs.elem(gridPattern -> {

                // Minor subdivision lines (positions 1/10 .. 9/10 of the tile).
                // Line 0 is the major line drawn below.
                if (minorOpacity > 0.01) {
                    for (int i = 1; i < 10; i++) {
                        final String pos = formatDouble(i * minorWorldSpacing);
                        // Horizontal minor line
                        gridPattern.elem(SafeHtmlUtil.from("line"),
                                new Attribute("x1", "0"),
                                new Attribute("y1", pos),
                                new Attribute("x2", formatDouble(majorWorldSpacing)),
                                new Attribute("y2", pos),
                                new Attribute("stroke", minorStroke),
                                new Attribute("stroke-width", minorStrokeWidth));
                        // Vertical minor line
                        gridPattern.elem(SafeHtmlUtil.from("line"),
                                new Attribute("x1", pos),
                                new Attribute("y1", "0"),
                                new Attribute("x2", pos),
                                new Attribute("y2", formatDouble(majorWorldSpacing)),
                                new Attribute("stroke", minorStroke),
                                new Attribute("stroke-width", minorStrokeWidth));
                    }
                }

                // Major grid lines (at tile edges: top and left).
                // Horizontal major line
                gridPattern.elem(SafeHtmlUtil.from("line"),
                        new Attribute("x1", "0"),
                        new Attribute("y1", "0"),
                        new Attribute("x2", formatDouble(majorWorldSpacing)),
                        new Attribute("y2", "0"),
                        new Attribute("stroke", MAJOR_STROKE),
                        new Attribute("stroke-width", majorStrokeWidth));
                // Vertical major line
                gridPattern.elem(SafeHtmlUtil.from("line"),
                        new Attribute("x1", "0"),
                        new Attribute("y1", "0"),
                        new Attribute("x2", "0"),
                        new Attribute("y2", formatDouble(majorWorldSpacing)),
                        new Attribute("stroke", MAJOR_STROKE),
                        new Attribute("stroke-width", majorStrokeWidth));

            },
                    SafeHtmlUtil.from("pattern"),
                    new Attribute("id", MAJOR_PATTERN_ID),
                    new Attribute("width", formatDouble(majorWorldSpacing)),
                    new Attribute("height", formatDouble(majorWorldSpacing)),
                    new Attribute("patternUnits", "userSpaceOnUse"),
                    new Attribute("patternTransform", patternTransform));

            // Arrowhead marker definition for origin axis lines.
            // Uses a simple filled triangle pointing right; the marker
            // auto-rotates to match the line direction via orient="auto".
            appendArrowMarker(defs, majorWorldSpacing);

        }, SafeHtmlUtil.from("defs"));

        // -- 4. Background fill (dark) — fills entire viewport --------------
        //    No 'id' attribute: this rect is purely decorative and must not
        //    intercept edit-mode click detection (which keys on id="background").
        builder.elem(SafeHtmlUtil.from("rect"),
                new Attribute("width", "100%"),
                new Attribute("height", "100%"),
                new Attribute("fill", BG_FILL));

        // -- 5. Grid overlay — fills entire viewport ------------------------
        builder.elem(SafeHtmlUtil.from("rect"),
                new Attribute("width", "100%"),
                new Attribute("height", "100%"),
                new Attribute("fill", "url(#" + MAJOR_PATTERN_ID + ")"),
                new Attribute("pointer-events", "none"));

        // -- 6. Origin indicator — X and Y axes at (0,0) --------------------
        appendOriginIndicator(builder, matrix, userZoom, panX, panY,
                majorWorldSpacing, effectiveScale);
    }

    /**
     * Appends an arrowhead {@code <marker>} definition into the given
     * {@code <defs>} builder. The marker is a filled triangle in the
     * Stroom highlight colour, sized proportionally to the grid so it
     * always spans {@link #ARROW_MINOR_DIVISIONS} minor divisions.
     */
    private static void appendArrowMarker(final HtmlBuilder defs,
                                          final double majorWorldSpacing) {
        // Arrow length = ARROW_MINOR_DIVISIONS minor grid cells.
        // One minor cell = majorWorldSpacing / 10.
        final double arrowLen = majorWorldSpacing * ARROW_MINOR_DIVISIONS / 10.0;
        final double arrowHalfWidth = arrowLen * 0.4;

        // The marker viewBox is 0 0 arrowLen arrowLen, with refX at the
        // tip so the arrow abuts exactly at the line endpoint.
        final String viewBox = "0 0 " + formatDouble(arrowLen) + " " + formatDouble(arrowLen);
        final String halfStr = formatDouble(arrowLen / 2.0);
        final String lenStr = formatDouble(arrowLen);

        defs.elem(marker -> {
            // Triangle path: from (0, centre-halfWidth) to (arrowLen, centre) to (0, centre+halfWidth)
            final String pathD = "M0," + formatDouble(arrowLen / 2.0 - arrowHalfWidth)
                    + " L" + lenStr + "," + halfStr
                    + " L0," + formatDouble(arrowLen / 2.0 + arrowHalfWidth) + " Z";
            marker.elem(SafeHtmlUtil.from("path"),
                    new Attribute("d", pathD),
                    new Attribute("fill", HIGHLIGHT_COLOUR));
        },
                SafeHtmlUtil.from("marker"),
                new Attribute("id", ARROW_MARKER_ID),
                new Attribute("viewBox", viewBox),
                new Attribute("refX", lenStr),
                new Attribute("refY", halfStr),
                new Attribute("markerWidth", lenStr),
                new Attribute("markerHeight", lenStr),
                new Attribute("markerUnits", "userSpaceOnUse"),
                new Attribute("orient", "auto"));
    }

    /**
     * Appends the origin indicator at world-space (0,0). Draws two axis
     * lines (X rightward, Y downward), each one major grid division long,
     * with arrowheads at the far end and a text label showing the world-
     * space distance (the major grid spacing) in map units.
     *
     * <p>The indicator is rendered in a {@code <g>} group with the same
     * combined pan/zoom/matrix transform used by the grid pattern, so it
     * aligns exactly with the grid lines.</p>
     */
    private static void appendOriginIndicator(final HtmlBuilder builder,
                                              final FloorMapTransformationMatrix matrix,
                                              final double userZoom,
                                              final double panX,
                                              final double panY,
                                              final double majorWorldSpacing,
                                              final double effectiveScale) {

        // Axis stroke width in world-space units (renders at AXIS_SCREEN_PX on screen).
        final String axisStrokeWidth = formatDouble(AXIS_SCREEN_PX / effectiveScale);

        // Font size in world-space units (renders at LABEL_FONT_SCREEN_PX on screen).
        final String fontSize = formatDouble(LABEL_FONT_SCREEN_PX / effectiveScale);

        // Gap between the arrowhead and the label in world-space units.
        final double labelGap = LABEL_GAP_SCREEN_PX / effectiveScale;

        final String spacing = formatDouble(majorWorldSpacing);
        final String markerUrl = "url(#" + ARROW_MARKER_ID + ")";

        // Human-readable label for the axis length.
        final String label = formatScaleLabel(majorWorldSpacing);

        // Counter-rotation angle so labels remain horizontal on screen.
        // The world-to-map matrix may include rotation; we undo it for
        // text readability.
        final double radians = Math.atan2(matrix.getB(), matrix.getA());
        final double counterRotDeg = -Math.toDegrees(radians);

        // Combined transform: user pan/zoom then world-to-map matrix.
        final String groupTransform = "translate(" + formatDouble(panX)
                + "," + formatDouble(panY) + ") scale(" + formatDouble(userZoom)
                + ") " + matrix.toSvgMatrix();

        builder.elem(originGroup -> {

            // --- X axis: from (0,0) to (majorWorldSpacing, 0) ---
            originGroup.elem(SafeHtmlUtil.from("line"),
                    new Attribute("x1", "0"),
                    new Attribute("y1", "0"),
                    new Attribute("x2", spacing),
                    new Attribute("y2", "0"),
                    new Attribute("stroke", HIGHLIGHT_COLOUR),
                    new Attribute("stroke-width", axisStrokeWidth),
                    new Attribute("marker-end", markerUrl));

            // X axis label — positioned just past the arrowhead.
            final String xLabelX = formatDouble(majorWorldSpacing + labelGap);
            originGroup.elem(label,
                    SafeHtmlUtil.from("text"),
                    new Attribute("x", xLabelX),
                    new Attribute("y", "0"),
                    new Attribute("dy", "0.35em"),
                    new Attribute("text-anchor", "start"),
                    new Attribute("fill", HIGHLIGHT_COLOUR),
                    new Attribute("font-size", fontSize),
                    new Attribute("font-family", "sans-serif"),
                    new Attribute("font-weight", "600"),
                    new Attribute("pointer-events", "none"),
                    new Attribute("transform",
                            "rotate(" + formatDouble(counterRotDeg)
                            + "," + xLabelX + ",0)"));

            // --- Y axis: from (0,0) to (0, majorWorldSpacing) ---
            originGroup.elem(SafeHtmlUtil.from("line"),
                    new Attribute("x1", "0"),
                    new Attribute("y1", "0"),
                    new Attribute("x2", "0"),
                    new Attribute("y2", spacing),
                    new Attribute("stroke", HIGHLIGHT_COLOUR),
                    new Attribute("stroke-width", axisStrokeWidth),
                    new Attribute("marker-end", markerUrl));

            // Y axis label — positioned just past the arrowhead.
            final String yLabelY = formatDouble(majorWorldSpacing + labelGap);
            originGroup.elem(label,
                    SafeHtmlUtil.from("text"),
                    new Attribute("x", "0"),
                    new Attribute("y", yLabelY),
                    new Attribute("dy", "1em"),
                    new Attribute("text-anchor", "middle"),
                    new Attribute("fill", HIGHLIGHT_COLOUR),
                    new Attribute("font-size", fontSize),
                    new Attribute("font-family", "sans-serif"),
                    new Attribute("font-weight", "600"),
                    new Attribute("pointer-events", "none"),
                    new Attribute("transform",
                            "rotate(" + formatDouble(counterRotDeg)
                            + ",0," + yLabelY + ")"));

        },
                SafeHtmlUtil.from("g"),
                new Attribute("transform", groupTransform),
                new Attribute("pointer-events", "none"));
    }

    // -- Helpers -------------------------------------------------------------

    /**
     * Computes the grid parameters for a given effective scale.
     *
     * <p>Returns a two-element array:
     * <ol>
     *   <li>{@code majorWorldSpacing} &mdash; the world-unit distance between
     *       major grid lines (always a power of 10)</li>
     *   <li>{@code minorOpacity} &mdash; the opacity for the minor grid lines
     *       (0.0 when subdivisions are invisible, up to
     *       {@link #MINOR_MAX_OPACITY} when fully visible)</li>
     * </ol>
     *
     * @param effectiveScale combined pixels-per-world-unit
     *                       ({@code matrixScale &times; userZoom})
     * @return {@code [majorWorldSpacing, minorOpacity]}
     */
    static double[] computeGridParams(final double effectiveScale) {
        // Guard against non-positive or non-finite values that would
        // produce NaN/Infinity in the log calculations.
        if (!(effectiveScale > 0) || !Double.isFinite(effectiveScale)) {
            return new double[]{1.0, 0.0};
        }

        final double rawLogSpacing = Math.log10(TARGET_MIN_PX / effectiveScale);
        final double decadeExponent = Math.ceil(rawLogSpacing);
        final double majorWorldSpacing = Math.pow(10, decadeExponent);

        final double screenPx = majorWorldSpacing * effectiveScale;
        final double t = Math.log10(screenPx / TARGET_MIN_PX)
                / Math.log10(TARGET_MAX_PX / TARGET_MIN_PX);
        final double minorOpacity = clampZeroToOne(t) * MINOR_MAX_OPACITY;

        return new double[]{majorWorldSpacing, minorOpacity};
    }

    /**
     * Formats the major grid spacing as a clean, human-readable label in
     * map units (e.g. "1", "10", "100", "0.1", "0.01"). Since the
     * spacing is always a power of 10, the result is always a short,
     * unambiguous number.
     *
     * @param majorWorldSpacing the world-space distance (a power of 10)
     * @return a formatted label string
     */
    static String formatScaleLabel(final double majorWorldSpacing) {
        // Powers of 10 ≥ 1 are displayed as integers; < 1 as decimals.
        if (majorWorldSpacing >= 1.0 && majorWorldSpacing == (long) majorWorldSpacing) {
            return String.valueOf((long) majorWorldSpacing);
        }
        // For sub-unit values (0.1, 0.01, etc.), strip trailing zeros.
        // Double.toString for powers of 10 < 1 will produce e.g. "0.1", "0.01"
        // which is already clean — no trailing zeros to strip.
        return String.valueOf(majorWorldSpacing);
    }

    private static double clampZeroToOne(final double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String formatDouble(final double value) {
        // Avoid trailing zeros for clean SVG output
        if (value == (long) value) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
