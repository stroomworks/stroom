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

    // -- Pattern ID (unique within a single SVG document) ------------------
    private static final String MAJOR_PATTERN_ID = "grid-major";

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

        // -- 3. Emit SVG <defs> with a single grid pattern -------------------
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
