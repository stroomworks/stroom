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

package stroom.floormap.client;

import stroom.floormap.shared.FloorMapShapes;
import stroom.floormap.shared.TypeStyle;
import stroom.floormap.shared.TypeStyle.Shape;

import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;

/**
 * Builds the small preview of a layer's graphic — the swatch in each Layers panel
 * row and the preview in the appearance dialog.
 *
 * <p>The geometry comes from {@link FloorMapShapes}, the same source the canvas
 * glyphs use, so the legend and the map cannot drift apart. A layer with a
 * {@link TypeStyle#getGraphic() graphic} previews as a thumbnail of that image
 * instead of a shape, matching the canvas's render precedence.</p>
 */
public final class FloorMapSwatchHtml {

    /**
     * Shape extent as a fraction of the swatch box, leaving a little breathing
     * room around the edge.
     */
    private static final double SHAPE_EXTENT_RATIO = 0.34;

    private FloorMapSwatchHtml() {
        // Utility class
    }

    /**
     * A square preview of the given style at {@code sizePx}.
     *
     * @param style  the layer style; {@code null} previews as the fallback glyph
     * @param sizePx the width and height of the preview in pixels
     * @return safe markup for the preview
     */
    public static SafeHtml swatch(final TypeStyle style, final int sizePx) {
        if (style != null && style.hasGraphic()) {
            return imageSwatch(style.getGraphic(), sizePx);
        }
        return shapeSwatch(style == null ? null : style.getShape(),
                style == null ? null : style.getColour(),
                style == null ? null : style.getType(),
                sizePx);
    }

    /**
     * A thumbnail of an asset-store image, letterboxed into the box so a
     * non-square image is not distorted — the same {@code contain} fit the canvas
     * gives a layer graphic.
     *
     * <p><strong>Why there is no SVG scale compensation here.</strong> The canvas
     * has to compensate for SVGs with no {@code viewBox} (see
     * {@code FloorMapCanvasViewImpl.appendScaledImage}), because an SVG
     * {@code <image>} scales its referent <em>through that referent's viewBox</em>
     * and so cannot scale one that lacks it. An HTML {@code <img>} scales by a
     * different route: the element's own box is the concrete object size and the SVG
     * is scaled into it as an ordinary replaced element, viewBox or not. So sizing
     * the element is sufficient here, and the two surfaces agree.</p>
     *
     * <p>The size is set as both attributes and inline style so a stylesheet rule on
     * {@code img} cannot collapse the box and, with it, {@code object-fit}.</p>
     *
     * <p>The URL is document-controlled data going into {@code innerHTML}, so it
     * is escaped; an unescaped value (a stray quote) would allow attribute
     * injection.</p>
     */
    private static SafeHtml imageSwatch(final String url, final int sizePx) {
        return SafeHtmlUtils.fromTrustedString(
                "<img src=\"" + SafeHtmlUtils.htmlEscape(url) + "\""
                + " width=\"" + sizePx + "\" height=\"" + sizePx + "\""
                + " style=\"width:" + sizePx + "px;height:" + sizePx + "px;"
                + "object-fit:contain;\" alt=\"\"/>");
    }

    /**
     * An inline-SVG preview of {@code shape} filled with {@code colour}.
     *
     * <p>A layer with no colour of its own previews in the colour the canvas will
     * actually draw it in ({@link TypeStyle#colourForType}) rather than a swatch-only
     * grey, so the legend states the map's appearance.</p>
     */
    private static SafeHtml shapeSwatch(final Shape shape,
                                        final String colour,
                                        final String type,
                                        final int sizePx) {
        final String fill = isValidColour(colour)
                ? colour
                : TypeStyle.colourForType(type, null);
        final double half = sizePx / 2.0;
        final double extent = sizePx * SHAPE_EXTENT_RATIO;
        return SafeHtmlUtils.fromTrustedString(
                "<svg width=\"" + sizePx + "\" height=\"" + sizePx + "\""
                + " viewBox=\"0 0 " + sizePx + " " + sizePx + "\""
                + " xmlns=\"http://www.w3.org/2000/svg\">"
                // Centre the origin so FloorMapShapes' origin-centred, y-down
                // geometry can be reused verbatim.
                + "<g transform=\"translate(" + half + "," + half + ")\">"
                + shapeSvg(shape, fill, extent)
                + "</g></svg>");
    }

    /**
     * The inner SVG for a shape centred on the origin and spanning
     * {@code ±extent}. Mirrors the canvas's dispatch in
     * {@code FloorMapCanvasViewImpl.appendStyledGlyph}.
     */
    private static String shapeSvg(final Shape shape, final String fill, final double extent) {
        if (shape == null) {
            // The fallback graphic for an unconfigured layer is a rounded rect.
            return "<rect x=\"" + (-extent) + "\" y=\"" + (-extent * 0.62) + "\""
                    + " width=\"" + (extent * 2) + "\" height=\"" + (extent * 1.24) + "\""
                    + " rx=\"" + (extent * 0.2) + "\" fill=\"" + fill + "\"/>";
        }
        //noinspection EnhancedSwitchMigration
        switch (shape) {
            case SQUARE:
                return "<rect x=\"" + (-extent) + "\" y=\"" + (-extent) + "\""
                        + " width=\"" + (extent * 2) + "\" height=\"" + (extent * 2) + "\""
                        + " rx=\"" + (extent * 0.15) + "\" fill=\"" + fill + "\"/>";
            case TRIANGLE:
            case DIAMOND:
                return "<polygon points=\"" + FloorMapShapes.polygonPoints(shape, extent) + "\""
                        + " fill=\"" + fill + "\"/>";
            case PIN:
                return "<path d=\"" + FloorMapShapes.pinPath(extent) + "\" fill=\"" + fill + "\"/>"
                        + "<circle cx=\"0\""
                        + " cy=\"" + (extent * FloorMapShapes.PIN_HOLE_CENTRE_Y_RATIO) + "\""
                        + " r=\"" + (extent * FloorMapShapes.PIN_HOLE_RADIUS_RATIO) + "\""
                        + " fill=\"#ffffff\"/>";
            case CIRCLE:
            default:
                return "<circle cx=\"0\" cy=\"0\" r=\"" + extent + "\" fill=\"" + fill + "\"/>";
        }
    }

    /**
     * True if {@code colour} is a hex colour literal. Guards the swatch against a
     * hand-edited document injecting markup through the colour field, which is
     * interpolated into trusted SVG.
     */
    private static boolean isValidColour(final String colour) {
        // Checked by hand rather than with a regex: this runs once per layer row on
        // every Layers rebuild, and GWT does not emulate java.util.regex.Pattern,
        // so a precompiled pattern is not an option here — String.matches would
        // build a fresh JS RegExp on each call.
        if (colour == null || colour.length() < 4 || colour.length() > 9) {
            return false;
        }
        if (colour.charAt(0) != '#') {
            return false;
        }
        for (int i = 1; i < colour.length(); i++) {
            final char c = colour.charAt(i);
            final boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
