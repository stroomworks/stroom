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

import java.util.function.Function;

/// The pan and zoom of the Structure view's canvas, and the arithmetic that depends on them.
///
/// A graph point `(x, y)` is drawn at screen point `(tx + k·x, ty + k·y)`, where `k` is the zoom and
/// `(tx, ty)` the translation. Holds no GWT or DOM types, so it is tested on the JVM. Ported from the
/// reference harness's wheel handler, `fit()` and `fitOnce()`, following `FloorMapViewport`.
public final class GraphViewport {

    /// The smallest zoom allowed.
    public static final double MIN_ZOOM = 0.08;
    /// The largest zoom allowed.
    public static final double MAX_ZOOM = 2.4;
    /// How far one pixel of wheel movement zooms, as the exponent of the zoom factor.
    public static final double WHEEL_SENSITIVITY = 0.0016;

    // Fitting leaves this margin around the graph's instances, in graph units.
    private static final double FIT_MARGIN = 40;
    // Where the graph's top-left corner lands, in screen pixels, when a fit anchors it there.
    private static final double FIT_ANCHOR = 28;
    // Fitting never zooms in beyond this.
    private static final double FIT_CAP = 1.1;
    // Fitting a graph with nothing in it.
    private static final double EMPTY_OFFSET = 40;

    // Below zoom 1 labels are counter-scaled to stay legible, up to this factor.
    private static final double MAX_LABEL_SCALE = 2.0;
    // At or below this zoom a node's second line of text is hidden.
    private static final double META_HIDDEN_AT_OR_BELOW = 0.62;

    private double zoom;
    private double tx;
    private double ty;

    /// Creates a viewport at actual size, with no translation.
    public GraphViewport() {
        this(1, 0, 0);
    }

    /// @param zoom The zoom, where 1 is actual size. Clamped between [#MIN_ZOOM] and [#MAX_ZOOM].
    /// @param tx   The horizontal translation, in screen pixels.
    /// @param ty   The vertical translation, in screen pixels.
    public GraphViewport(final double zoom, final double tx, final double ty) {
        this.zoom = clamp(zoom);
        this.tx = tx;
        this.ty = ty;
    }

    /// @return the zoom, where 1 is actual size.
    public double getZoom() {
        return zoom;
    }

    /// @return the horizontal translation, in screen pixels.
    public double getTx() {
        return tx;
    }

    /// @return the vertical translation, in screen pixels.
    public double getTy() {
        return ty;
    }

    /// @return the detail level the current zoom calls for.
    public DetailLevel getDetailLevel() {
        return DetailLevel.forZoom(zoom);
    }

    /// @return the factor to multiply label font sizes by, so labels stay legible when zoomed out: `1/zoom`
    /// below actual size, up to 2, and 1 otherwise.
    public double getLabelScale() {
        return zoom < 1
                ? Math.min(1 / zoom, MAX_LABEL_SCALE)
                : 1;
    }

    /// @return true if a node's second line of text, its type and stream type, should be shown.
    public boolean isMetaVisible() {
        return zoom > META_HIDDEN_AT_OR_BELOW;
    }

    /// @return the SVG `transform` attribute value that applies this viewport to the graph.
    public String toSvgTransform() {
        return "translate(" + EdgePaths.format(tx) + "," + EdgePaths.format(ty) + ") scale("
               + EdgePaths.format(zoom) + ")";
    }

    /// @param graphX A horizontal position in the graph.
    /// @param graphY A vertical position in the graph.
    /// @return where it is drawn, as `{x, y}` in screen pixels relative to the canvas.
    public double[] graphToScreen(final double graphX, final double graphY) {
        return new double[]{tx + zoom * graphX, ty + zoom * graphY};
    }

    /// @param screenX A horizontal position in screen pixels, relative to the canvas.
    /// @param screenY A vertical position in screen pixels, relative to the canvas.
    /// @return the graph position drawn there, as `{x, y}`.
    public double[] screenToGraph(final double screenX, final double screenY) {
        return new double[]{(screenX - tx) / zoom, (screenY - ty) / zoom};
    }

    /// Moves the graph by a distance on screen, as a drag does.
    ///
    /// @param dx The horizontal distance, in screen pixels.
    /// @param dy The vertical distance, in screen pixels.
    public void pan(final double dx, final double dy) {
        tx += dx;
        ty += dy;
    }

    /// Zooms to a new level, keeping the graph point under a screen point fixed there. The zoom is clamped
    /// first, so the point stays fixed even at a limit. A zoom or point that is not a number is ignored.
    ///
    /// @param screenX The horizontal position to zoom about, relative to the canvas.
    /// @param screenY The vertical position to zoom about, relative to the canvas.
    /// @param newZoom The zoom wanted.
    public void zoomAbout(final double screenX, final double screenY, final double newZoom) {
        if (Double.isNaN(newZoom) || Double.isNaN(screenX) || Double.isNaN(screenY)) {
            // Would make every position unknown, for good
            return;
        }
        final double clamped = clamp(newZoom);
        final double factor = clamped / zoom;
        tx = screenX - (screenX - tx) * factor;
        ty = screenY - (screenY - ty) * factor;
        zoom = clamped;
    }

    /// Zooms as a mouse wheel does, about the pointer.
    ///
    /// @param screenX The pointer's horizontal position, relative to the canvas.
    /// @param screenY The pointer's vertical position, relative to the canvas.
    /// @param deltaY  The wheel's vertical movement in pixels. Positive zooms out.
    public void zoomByWheel(final double screenX, final double screenY, final double deltaY) {
        zoomAbout(screenX, screenY, zoom * Math.exp(-deltaY * WHEEL_SENSITIVITY));
    }

    /// Pans, keeping the zoom, so a graph point is at the centre of the canvas.
    ///
    /// @param graphX     A horizontal position in the graph.
    /// @param graphY     A vertical position in the graph.
    /// @param viewWidth  The canvas width, in screen pixels.
    /// @param viewHeight The canvas height, in screen pixels.
    public void centreOn(final double graphX,
                         final double graphY,
                         final double viewWidth,
                         final double viewHeight) {
        tx = viewWidth / 2 - zoom * graphX;
        ty = viewHeight / 2 - zoom * graphY;
    }

    /// Fits a layout to the canvas.
    ///
    /// [FitMode#READ] will not zoom out below the zoom at which labels stay legible, and [FitMode#ALL] only
    /// below 12%. Neither zooms in beyond 110%. A graph that fits is centred. One too big to fit at the
    /// mode's smallest zoom is anchored at its top-left corner, so the first lanes are what the user lands
    /// on, and centred vertically if it is shorter than the canvas.
    ///
    /// @param layout     The layout to fit.
    /// @param viewWidth  The canvas width, in screen pixels.
    /// @param viewHeight The canvas height, in screen pixels.
    /// @param mode       How to fit.
    /// @return false if the layout has nothing placed, in which case the viewport is reset to actual size.
    public boolean fit(final LayoutResult layout,
                       final double viewWidth,
                       final double viewHeight,
                       final FitMode mode) {
        double x1 = Double.POSITIVE_INFINITY;
        double y1 = Double.POSITIVE_INFINITY;
        double x2 = Double.NEGATIVE_INFINITY;
        double y2 = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (final Instance instance : layout.getInstances()) {
            if (instance.getX() == null) {
                continue;
            }
            final Dims dims = layout.dims(instance);
            x1 = Math.min(x1, instance.getX());
            y1 = Math.min(y1, instance.getY());
            x2 = Math.max(x2, instance.getX() + dims.w());
            y2 = Math.max(y2, instance.getY() + dims.h());
            any = true;
        }
        if (!any) {
            zoom = 1;
            tx = EMPTY_OFFSET;
            ty = EMPTY_OFFSET;
            return false;
        }
        x1 -= FIT_MARGIN;
        y1 -= FIT_MARGIN;
        x2 += FIT_MARGIN;
        y2 += FIT_MARGIN;

        final double raw = Math.min(viewWidth / (x2 - x1), viewHeight / (y2 - y1));
        zoom = Math.min(Math.max(raw, mode.getFloor()), FIT_CAP);
        // The harness anchors whenever zoom >= raw, which includes the usual case of a graph that fits
        // exactly. Anchoring that one at FIT_ANCHOR rather than its margin pushes its far edge off the
        // canvas below about 70% zoom, so only a graph too big to fit is anchored here.
        if (zoom > raw) {
            // Too big to fit: anchor it left, and centre it vertically if it is shorter than the canvas.
            tx = FIT_ANCHOR - x1 * zoom;
            final double contentHeight = (y2 - y1) * zoom;
            ty = contentHeight < viewHeight
                    ? (viewHeight - contentHeight) / 2 - y1 * zoom
                    : FIT_ANCHOR - y1 * zoom;
        } else {
            tx = (viewWidth - (x2 - x1) * zoom) / 2 - x1 * zoom;
            ty = (viewHeight - (y2 - y1) * zoom) / 2 - y1 * zoom;
        }
        return true;
    }

    /// Fits a layout, then lays it out again once if the fitted zoom calls for a different detail level, and
    /// fits that. Only once: collapsing raises the fitted zoom, which can call for the detail back, and a
    /// loop would oscillate rather than settle.
    ///
    /// @param layout     The layout to fit, laid out at its own detail level.
    /// @param layoutAt   Lays the graph out again at a given detail level.
    /// @param viewWidth  The canvas width, in screen pixels.
    /// @param viewHeight The canvas height, in screen pixels.
    /// @param mode       How to fit.
    /// @return the layout to draw: `layout`, or the one laid out again.
    public LayoutResult fitAndSettle(final LayoutResult layout,
                                     final Function<DetailLevel, LayoutResult> layoutAt,
                                     final double viewWidth,
                                     final double viewHeight,
                                     final FitMode mode) {
        if (!fit(layout, viewWidth, viewHeight, mode)) {
            return layout;
        }
        final DetailLevel wanted = getDetailLevel();
        if (wanted == layout.getDetail()) {
            return layout;
        }
        final LayoutResult again = layoutAt.apply(wanted);
        fit(again, viewWidth, viewHeight, mode);
        return again;
    }

    private static double clamp(final double zoom) {
        return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom));
    }

    /// How [GraphViewport#fit] fits a layout to the canvas.
    public enum FitMode {

        /// Fits as much as stays legible, anchored at the top left. What the user lands on.
        READ(0.7),

        /// Fits everything, however small.
        ALL(0.12);

        private final double floor;

        FitMode(final double floor) {
            this.floor = floor;
        }

        /// @return the smallest zoom this mode fits to.
        public double getFloor() {
            return floor;
        }
    }
}
