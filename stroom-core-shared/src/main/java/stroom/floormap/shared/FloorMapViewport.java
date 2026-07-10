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
 * The pan/zoom state of the floor-map canvas and the pure coordinate maths that
 * depends on it.
 *
 * <p>The viewport applies a uniform {@code scale} and a translation
 * ({@code offsetX}, {@code offsetY}) on top of the active background's
 * map-to-screen transform. The full screen mapping for a map-space point is:</p>
 * <pre>
 *   unzoomed = background · [mapX, mapY]      (the map-to-screen matrix)
 *   screen   = offset + scale · unzoomed
 * </pre>
 *
 * <p>This class holds no GWT or DOM types so it can be unit-tested on the JVM.
 * The presenter is responsible for pulling coordinates out of native mouse
 * events and pushing the resulting state into the view; all the arithmetic
 * lives here. See {@code FloorMapNotes.md} section 3.</p>
 */
public final class FloorMapViewport {

    /** Lower bound on {@link #scale} to avoid floating-point breakdown. */
    public static final double MIN_SCALE = 1e-12;
    /** Upper bound on {@link #scale} to avoid floating-point breakdown. */
    public static final double MAX_SCALE = 1e12;
    /** Multiplicative zoom step applied per wheel notch. */
    public static final double ZOOM_STEP = 1.1;

    private double scale;
    private double offsetX;
    private double offsetY;

    /** Creates a viewport at the default state (scale 1, no offset). */
    public FloorMapViewport() {
        this(1.0, 0.0, 0.0);
    }

    public FloorMapViewport(final double scale,
                            final double offsetX,
                            final double offsetY) {
        this.scale = scale;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    public double getScale() {
        return scale;
    }

    public double getOffsetX() {
        return offsetX;
    }

    public double getOffsetY() {
        return offsetY;
    }

    // -----------------------------------------------------------------------
    // Coordinate conversions
    // -----------------------------------------------------------------------

    /**
     * Converts an element-relative screen point to map space by reversing the
     * zoom/pan transform and then applying the inverse of the background matrix.
     *
     * @param screenX    element-relative X (matching {@code MouseEvent.getX()})
     * @param screenY    element-relative Y (matching {@code MouseEvent.getY()})
     * @param background the active background's map-to-screen matrix; may be
     *                   {@code null} (treated as identity)
     * @return {@code {mapX, mapY}} in map space
     */
    public double[] screenToMap(final double screenX,
                                final double screenY,
                                final FloorMapTransformationMatrix background) {
        final double unzoomedX = (screenX - offsetX) / scale;
        final double unzoomedY = (screenY - offsetY) / scale;

        final FloorMapTransformationMatrix inverse = safeInverse(background);
        final double mapX = inverse.getA() * unzoomedX + inverse.getC() * unzoomedY + inverse.getE();
        final double mapY = inverse.getB() * unzoomedX + inverse.getD() * unzoomedY + inverse.getF();
        return new double[]{mapX, mapY};
    }

    /**
     * Converts a map-space point to an element-relative screen point. This is
     * the exact inverse of {@link #screenToMap} and is used for hit-testing and
     * round-trip verification.
     *
     * @param mapX       map-space X
     * @param mapY       map-space Y
     * @param background the active background's map-to-screen matrix; may be
     *                   {@code null} (treated as identity)
     * @return {@code {screenX, screenY}} in element-relative screen space
     */
    public double[] mapToScreen(final double mapX,
                                final double mapY,
                                final FloorMapTransformationMatrix background) {
        final FloorMapTransformationMatrix matrix = background != null
                ? background
                : FloorMapTransformationMatrix.identity();
        final double unzoomedX = matrix.getA() * mapX + matrix.getC() * mapY + matrix.getE();
        final double unzoomedY = matrix.getB() * mapX + matrix.getD() * mapY + matrix.getF();
        return new double[]{
                offsetX + scale * unzoomedX,
                offsetY + scale * unzoomedY};
    }

    // -----------------------------------------------------------------------
    // Mutating gestures
    // -----------------------------------------------------------------------

    /**
     * Pans the viewport by a raw screen delta (as reported by mouse-move).
     */
    public void pan(final double screenDeltaX, final double screenDeltaY) {
        offsetX += screenDeltaX;
        offsetY += screenDeltaY;
    }

    /**
     * Zooms toward the cursor by one {@link #ZOOM_STEP} notch, keeping the
     * map point under the cursor fixed on screen. The resulting scale is
     * clamped to {@code [MIN_SCALE, MAX_SCALE]}.
     *
     * @param cursorX element-relative cursor X to zoom toward
     * @param cursorY element-relative cursor Y to zoom toward
     * @param zoomIn  {@code true} to zoom in, {@code false} to zoom out
     */
    public void zoom(final double cursorX, final double cursorY, final boolean zoomIn) {
        final double zoomFactor = zoomIn
                ? ZOOM_STEP
                : 1.0 / ZOOM_STEP;

        // Shift the offset so the point under the cursor stays put, then scale.
        offsetX = cursorX - (cursorX - offsetX) * zoomFactor;
        offsetY = cursorY - (cursorY - offsetY) * zoomFactor;
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * zoomFactor));
    }

    // -----------------------------------------------------------------------
    // Drag maths (used in Edit Mode)
    // -----------------------------------------------------------------------

    /**
     * Returns a copy of the background matrix translated by a screen drag
     * delta. Only the translation components ({@code e}, {@code f}) change; the
     * scale/rotation components ({@code a}, {@code b}, {@code c}, {@code d}) are
     * preserved.
     *
     * @param current     the background matrix being dragged; may be
     *                    {@code null} (treated as identity)
     * @param screenDeltaX raw screen X delta
     * @param screenDeltaY raw screen Y delta
     * @return a new translated matrix
     */
    public FloorMapTransformationMatrix dragBackground(final FloorMapTransformationMatrix current,
                                                       final double screenDeltaX,
                                                       final double screenDeltaY) {
        final FloorMapTransformationMatrix matrix = current != null
                ? current
                : FloorMapTransformationMatrix.identity();
        final double deltaUnzoomedX = screenDeltaX / scale;
        final double deltaUnzoomedY = screenDeltaY / scale;
        return new FloorMapTransformationMatrix(
                matrix.getA(), matrix.getB(),
                matrix.getC(), matrix.getD(),
                matrix.getE() + deltaUnzoomedX,
                matrix.getF() + deltaUnzoomedY);
    }

    /**
     * Converts a raw screen drag delta into the equivalent delta in map space,
     * for repositioning a plotted (non-background) item. The delta is reduced by
     * the zoom scale and then rotated/scaled by the inverse of the background's
     * linear part; translation is irrelevant to a delta and is not applied.
     *
     * @param background   the active background's map-to-screen matrix; may be
     *                     {@code null} (treated as identity)
     * @param screenDeltaX raw screen X delta
     * @param screenDeltaY raw screen Y delta
     * @return {@code {deltaMapX, deltaMapY}} to add to the item's map coordinates
     */
    public double[] dragItemMapDelta(final FloorMapTransformationMatrix background,
                                     final double screenDeltaX,
                                     final double screenDeltaY) {
        final double deltaUnzoomedX = screenDeltaX / scale;
        final double deltaUnzoomedY = screenDeltaY / scale;

        final FloorMapTransformationMatrix inverse = safeInverse(background);
        final double deltaMapX = inverse.getA() * deltaUnzoomedX + inverse.getC() * deltaUnzoomedY;
        final double deltaMapY = inverse.getB() * deltaUnzoomedX + inverse.getD() * deltaUnzoomedY;
        return new double[]{deltaMapX, deltaMapY};
    }

    private static FloorMapTransformationMatrix safeInverse(final FloorMapTransformationMatrix matrix) {
        return matrix != null
                ? matrix.inverse()
                : FloorMapTransformationMatrix.identity();
    }
}
