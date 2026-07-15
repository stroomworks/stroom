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

import stroom.document.client.event.DirtyUiHandlers;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.floormap.client.presenter.FloorMapCanvasPresenter.FloorMapCanvasView;
import stroom.floormap.shared.Fact;
import stroom.floormap.shared.FloorMapJsonKeys;
import stroom.floormap.shared.FloorMapObject;
import stroom.floormap.shared.FloorMapShapes;
import stroom.floormap.shared.FloorMapTransformationMatrix;
import stroom.floormap.shared.TypeStyle;
import stroom.widget.util.client.HtmlBuilder;
import stroom.widget.util.client.HtmlBuilder.Attribute;
import stroom.widget.util.client.SafeHtmlUtil;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.HasMouseMoveHandlers;
import com.google.gwt.event.dom.client.HasMouseUpHandlers;
import com.google.gwt.event.dom.client.HasMouseWheelHandlers;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FocusPanel;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GWT view implementation for the interactive SVG floor-map canvas.
 *
 * <p>Renders the map from a list of {@link Fact}s — each fact placed by its own
 * {@code world-to-map} matrix — plus an event/person overlay:</p>
 * <ul>
 *   <li>Facts with an image are drawn as scaled images (multiple backgrounds are
 *       simply several image facts).</li>
 *   <li>Imageless facts are drawn as a fixed-size default graphic whose shape and
 *       colour come from the per-type settings ({@link TypeStyle}).</li>
 *   <li>Events (people) are drawn as filled circles with movement trails.</li>
 * </ul>
 *
 * <p>Facts are painted in the order supplied by the presenter (the configured
 * type z-order); events paint on top. Pan and zoom are applied via a wrapping
 * SVG {@code <g>} group.</p>
 */
public class FloorMapCanvasViewImpl
        extends ViewWithUiHandlers<DirtyUiHandlers>
        implements FloorMapCanvasView, ReadOnlyChangeHandler {

    /**
     * The local display width of an image fact in SVG user-units. The height is
     * derived from this and the image's aspect ratio; the fact's world-to-map
     * matrix then places and scales the image in map space.
     */
    private static final int IMAGE_DISPLAY_WIDTH = 1000;

    /**
     * On-screen size (SVG user-units, i.e. pixels at the fixed-size transform) of
     * an imageless default graphic and of an event marker. 0.6&times; the original
     * 100 — the label {@code font-size} is deliberately left unchanged.
     */
    private static final int OBJECT_SIZE = 60;
    /** On-screen radius of a person marker: 0.6&times; the original 30. */
    private static final int PERSON_RADIUS = 18;

    private final Widget widget;

    private final Map<String, Double> imageAspectRatioCache = new HashMap<>();
    private final Set<String> loadingImages = new HashSet<>();
    private Runnable redrawListener;
    private Runnable resizeListener;

    @UiField
    HTML svgContainer;

    @UiField
    FocusPanel focusPanel;

    /**
     * Constructs the canvas view, inflating the UiBinder template.
     *
     * @param binder the UiBinder that produces the widget tree
     */
    @Inject
    public FloorMapCanvasViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    /** {@inheritDoc} */
    @Override
    public Widget asWidget() {
        return widget;
    }

    /** {@inheritDoc} — no read-only visual changes required for the canvas. */
    @Override
    public void onReadOnly(final boolean readOnly) {
    }

    /**
     * Handles resize events. If the parent container has no size yet (e.g. during
     * initial attachment), the call is deferred until layout completes.
     */
    @Override
    public void onResize() {
        final Element parent = svgContainer.getElement().getParentElement();
        final int width = parent.getOffsetWidth();
        final int height = parent.getOffsetHeight();

        // Defer if the parent hasn't been laid out yet — retry once the browser
        // gives it a size.
        if (width <= 0 || height <= 0) {
            Scheduler.get().scheduleDeferred((this::onResize));
            return;
        }

        // SVG handles its own responsiveness via 100% width/height. Notify the
        // presenter that the canvas now has a real size so it can apply its
        // size-dependent default view (see setResizeListener).
        if (resizeListener != null) {
            resizeListener.run();
        }
    }

    /** {@inheritDoc} */
    @Override
    public FocusPanel getFocusPanel() {
        return focusPanel;
    }

    /** {@inheritDoc} */
    @Override
    public HasMouseMoveHandlers getMouseMoveHandlers() {
        return focusPanel;
    }

    /** {@inheritDoc} */
    @Override
    public HasMouseUpHandlers getMouseUpHandlers() {
        return focusPanel;
    }

    /** {@inheritDoc} */
    @Override
    public HasMouseWheelHandlers getMouseWheelHandlers() {
        return focusPanel;
    }

    /**
     * Rebuilds the entire SVG DOM to reflect the current map state.
     *
     * <p>The SVG structure is {@code <svg> → <g pan/zoom> → [facts…, events…]}.
     * Each image fact is wrapped in its own {@code <g matrix>} and scales with
     * the map; imageless facts and events are anchored in map space but drawn at
     * a fixed screen size (see {@link #fixedSizeTransform}).</p>
     *
     * @param scale            current zoom factor (1.0 = 100 %)
     * @param x                horizontal pan offset in SVG user-units
     * @param y                vertical pan offset in SVG user-units
     * @param facts            the facts to render, already in paint (z) order
     * @param events           the event/person overlay objects (map coordinates)
     * @param selectedObjectIds IDs of the currently selected objects (all highlighted)
     * @param typeStyles       per-type presentation settings (default graphic shape/colour)
     * @param showGrid         {@code true} to draw the (non-interactive) grid overlay
     */
    @Override
    public void draw(final double scale,
                     final double x,
                     final double y,
                     final List<Fact> facts,
                     final List<FloorMapObject> events,
                     final Set<String> selectedObjectIds,
                     final List<TypeStyle> typeStyles,
                     final boolean showGrid) {
        final HtmlBuilder htmlBuilder = new HtmlBuilder();

        htmlBuilder.elem(svg -> {

            // Grid overlay — a non-interactive UI aid, drawn at the SVG root.
            if (showGrid) {
                FloorMapGrid.appendGrid(
                        svg, FloorMapTransformationMatrix.identity(), scale, x, y);
            }

            // Pan/zoom group.
            svg.elem(panGroup ->
                // Y-up flip: map space is Y-up; scale(1,-1) maps it to the SVG's
                // Y-down space. Imageless facts and events counter-flip AND
                // counter-scale so their glyphs stay upright and fixed screen
                // size; image facts carry the flip in their matrix and scale
                // with the map.
                panGroup.elem(flipGroup -> {
                    // ---- Facts (paint order = z-order supplied by the presenter) ----
                    if (facts != null) {
                        for (final Fact fact : facts) {
                            final boolean isSelected = selectedObjectIds.contains(fact.getKey());
                            if (fact.hasImage()) {
                                appendImageFact(flipGroup, fact, isSelected);
                            } else {
                                appendDefaultGraphic(flipGroup, fact, isSelected, typeStyles, scale);
                            }
                        }
                    }

                    // ---- Events (people) drawn on top ----
                    if (events != null) {
                        for (final FloorMapObject ev : events) {
                            appendEvent(flipGroup, ev, selectedObjectIds.contains(ev.getId()), scale);
                        }
                    }
                }, SafeHtmlUtil.from("g"), new Attribute("transform", "scale(1,-1)")),
                SafeHtmlUtil.from("g"),
                    new Attribute("transform", "translate(" + x + "," + y + ") scale(" + scale + ")"));
        },
            SafeHtmlUtil.from("svg"),
            new Attribute("width", "100%"),
            new Attribute("height", "100%"),
            new Attribute("xmlns", "http://www.w3.org/2000/svg")
        );

        svgContainer.setHTML(htmlBuilder.toSafeHtml());
    }

    /**
     * Draws an image fact — an {@code <image>} at its local size, wrapped in a
     * {@code <g>} carrying the fact's world-to-map matrix so it is placed and
     * scaled in map space. A selection border is added when selected.
     */
    private void appendImageFact(final HtmlBuilder parent,
                                 final Fact fact,
                                 final boolean isSelected) {
        final Double cachedAspectRatio = imageAspectRatioCache.get(fact.getImage());
        final double aspectRatio = cachedAspectRatio != null ? cachedAspectRatio : 1.0;
        if (cachedAspectRatio == null) {
            loadImageAspectRatio(fact.getImage());
        }
        final double imgHeight = (double) IMAGE_DISPLAY_WIDTH / aspectRatio;

        parent.elem(imgGroup -> {
            imgGroup.elem(SafeHtmlUtil.from("image"),
                new Attribute(SafeHtmlUtils.fromSafeConstant("href"),
                        SafeHtmlUtils.fromTrustedString(fact.getImage())),
                new Attribute("x", "0"),
                new Attribute("y", "0"),
                new Attribute("width", String.valueOf(IMAGE_DISPLAY_WIDTH)),
                new Attribute("height", String.valueOf(imgHeight)),
                new Attribute("preserveAspectRatio", "none"),
                new Attribute("id", fact.getKey()));

            if (isSelected) {
                imgGroup.elem(SafeHtmlUtil.from("rect"),
                    new Attribute("x", "0"),
                    new Attribute("y", "0"),
                    new Attribute("width", String.valueOf(IMAGE_DISPLAY_WIDTH)),
                    new Attribute("height", String.valueOf(imgHeight)),
                    new Attribute("fill", "none"),
                    new Attribute("stroke", "#1e88e5"),
                    new Attribute("stroke-width", "8"),
                    new Attribute("vector-effect", "non-scaling-stroke"),
                    new Attribute("pointer-events", "none"));
            }
        }, SafeHtmlUtil.from("g"),
                // Anchor the raster at its BOTTOM-left in map space: translate up
                // by its own height, then counter-flip (scale 1,-1) to undo the
                // Y-up flip group and keep it upright. So an identity world-to-map
                // places the image in the visible first quadrant (up-and-right of
                // the origin) rather than below it; a real world-to-map (applied
                // outermost) then positions/scales/rotates from there.
                new Attribute("transform", fact.getWorldToMap().toSvgMatrix()
                        + " translate(0," + imgHeight + ") scale(1,-1)"));
    }

    /**
     * Draws the default graphic for an imageless fact at its map position
     * (world-to-map applied to the fact's world coordinates), using the shape and
     * colour configured for its type. The graphic and its label are drawn at a
     * fixed screen size (independent of zoom) via {@link #fixedSizeTransform}.
     */
    private void appendDefaultGraphic(final HtmlBuilder parent,
                                      final Fact fact,
                                      final boolean isSelected,
                                      final List<TypeStyle> typeStyles,
                                      final double scale) {
        final double[] pos = fact.getPosition();
        final double worldX = pos != null ? pos[0] : 0;
        final double worldY = pos != null ? pos[1] : 0;
        final FloorMapTransformationMatrix w2m = fact.getWorldToMap();
        final double mapX = w2m.getA() * worldX + w2m.getC() * worldY + w2m.getE();
        final double mapY = w2m.getB() * worldX + w2m.getD() * worldY + w2m.getF();

        final String fillColour = colourForType(fact.getType(), typeStyles);
        final TypeStyle.Shape shape = shapeForType(fact.getType(), typeStyles);
        final String stroke = isSelected ? "#ff9800" : "none";
        final String strokeWidth = isSelected ? "4" : "0";
        final String vectorEffect = isSelected ? "non-scaling-stroke" : "none";
        final String polygon = FloorMapShapes.polygonPoints(shape, OBJECT_SIZE / 2.0);
        final String label = shortLabel(fact.getKey());

        parent.elem(objGroup -> {
            if (shape == TypeStyle.Shape.CIRCLE) {
                objGroup.elem(SafeHtmlUtil.from("circle"),
                    new Attribute("cx", "0"),
                    new Attribute("cy", "0"),
                    new Attribute("r", String.valueOf(OBJECT_SIZE / 2)),
                    new Attribute("fill", fillColour),
                    new Attribute("stroke", stroke),
                    new Attribute("stroke-width", strokeWidth),
                    new Attribute("vector-effect", vectorEffect),
                    new Attribute("id", fact.getKey()));
            } else if (polygon != null) {
                objGroup.elem(SafeHtmlUtil.from("polygon"),
                    new Attribute("points", polygon),
                    new Attribute("fill", fillColour),
                    new Attribute("stroke", stroke),
                    new Attribute("stroke-width", strokeWidth),
                    new Attribute("vector-effect", vectorEffect),
                    new Attribute("id", fact.getKey()));
            } else {
                objGroup.elem(SafeHtmlUtil.from("rect"),
                    new Attribute("x", String.valueOf(-OBJECT_SIZE / 2)),
                    new Attribute("y", String.valueOf(-OBJECT_SIZE / 2)),
                    new Attribute("width", String.valueOf(OBJECT_SIZE)),
                    new Attribute("height", String.valueOf(OBJECT_SIZE)),
                    new Attribute("fill", fillColour),
                    new Attribute("rx", "6"),
                    new Attribute("ry", "6"),
                    new Attribute("stroke", stroke),
                    new Attribute("stroke-width", strokeWidth),
                    new Attribute("vector-effect", vectorEffect),
                    new Attribute("id", fact.getKey()));
            }

            objGroup.elem(label,
                    SafeHtmlUtil.from("text"),
                    new Attribute("x", "0"),
                    new Attribute("y", "0"),
                    new Attribute("dy", "0.35em"),
                    new Attribute("text-anchor", "middle"),
                    new Attribute("fill", "white"),
                    new Attribute("font-size", "14px"),
                    new Attribute("font-family", "sans-serif"),
                    new Attribute("pointer-events", "none"));
        },
                SafeHtmlUtil.from("g"),
                // Counter-flip + counter-scale so the graphic + label stay
                // upright and a fixed screen size inside the Y-up flip / zoom group.
                new Attribute("transform", fixedSizeTransform(mapX, mapY, scale)),
                new Attribute("id", FloorMapJsonKeys.SVG_GROUP_PREFIX + fact.getKey()));
    }

    /**
     * Draws an event overlay object at its map coordinates: a person as a filled
     * circle with an optional movement trail, or any other event type as a small
     * coloured rectangle. Both carry a short label.
     */
    private void appendEvent(final HtmlBuilder parent,
                             final FloorMapObject obj,
                             final boolean isSelected,
                             final double scale) {
        final boolean isPerson = FloorMapJsonKeys.PERSON.equalsIgnoreCase(obj.getType());
        final String displayLabel = shortLabel(obj.getId());

        // Movement trail (people only) — rendered before the circle so it sits behind.
        if (isPerson && obj.getTrail() != null && obj.getTrail().size() >= 2) {
            final List<double[]> trail = obj.getTrail();
            final StringBuilder pathD = new StringBuilder();
            double maxAlpha = 0.0;
            for (int i = 0; i < trail.size(); i++) {
                final double[] pt = trail.get(i);
                if (pt[2] > maxAlpha) {
                    maxAlpha = pt[2];
                }
                if (i == 0) {
                    pathD.append("M").append(pt[0]).append(",").append(pt[1]);
                } else {
                    pathD.append("L").append(pt[0]).append(",").append(pt[1]);
                }
            }
            if (maxAlpha > 0.0) {
                parent.elem(SafeHtmlUtil.from("path"),
                    new Attribute("d", pathD.toString()),
                    new Attribute("fill", "none"),
                    new Attribute("stroke", "#1f77b4"),
                    new Attribute("stroke-width", "6"),
                    new Attribute("stroke-linecap", "round"),
                    new Attribute("stroke-linejoin", "round"),
                    new Attribute("vector-effect", "non-scaling-stroke"),
                    new Attribute("opacity", String.valueOf(maxAlpha)),
                    new Attribute("pointer-events", "none"));
            }
        }

        parent.elem(objGroup -> {
            if (isPerson) {
                objGroup.elem(SafeHtmlUtil.from("circle"),
                    new Attribute("cx", "0"),
                    new Attribute("cy", "0"),
                    new Attribute("r", String.valueOf(PERSON_RADIUS)),
                    new Attribute("fill", "#1f77b4"),
                    new Attribute("stroke", isSelected ? "#ff9800" : "#ffffff"),
                    new Attribute("stroke-width", isSelected ? "4" : "2"),
                    new Attribute("vector-effect", "non-scaling-stroke"),
                    new Attribute("id", obj.getId()));

                objGroup.elem(displayLabel,
                        SafeHtmlUtil.from("text"),
                        new Attribute("x", "0"),
                        new Attribute("y", String.valueOf(PERSON_RADIUS + 4)),
                        new Attribute("dy", "0.85em"),
                        new Attribute("text-anchor", "middle"),
                        new Attribute("fill", "#1f77b4"),
                        new Attribute("font-size", "14px"),
                        new Attribute("font-family", "sans-serif"),
                        new Attribute("font-weight", "600"),
                        new Attribute("pointer-events", "none"));
            } else {
                final String fillColour = colourForType(obj.getType(), null);
                objGroup.elem(SafeHtmlUtil.from("rect"),
                    new Attribute("x", String.valueOf(-OBJECT_SIZE / 2)),
                    new Attribute("y", String.valueOf(-OBJECT_SIZE / 2)),
                    new Attribute("width", String.valueOf(OBJECT_SIZE)),
                    new Attribute("height", String.valueOf(OBJECT_SIZE)),
                    new Attribute("fill", fillColour),
                    new Attribute("rx", "6"),
                    new Attribute("ry", "6"),
                    new Attribute("stroke", isSelected ? "#ff9800" : "none"),
                    new Attribute("stroke-width", isSelected ? "4" : "0"),
                    new Attribute("vector-effect", isSelected ? "non-scaling-stroke" : "none"),
                    new Attribute("id", obj.getId()));

                objGroup.elem(displayLabel,
                        SafeHtmlUtil.from("text"),
                        new Attribute("x", "0"),
                        new Attribute("y", "0"),
                        new Attribute("dy", "0.35em"),
                        new Attribute("text-anchor", "middle"),
                        new Attribute("fill", "white"),
                        new Attribute("font-size", "14px"),
                        new Attribute("font-family", "sans-serif"),
                        new Attribute("pointer-events", "none"));
            }
        },
                SafeHtmlUtil.from("g"),
                // Counter-flip + counter-scale so the circle/label stay upright
                // and a fixed screen size in the Y-up flip / zoom group. (The
                // movement trail above stays in map space so it scales with the map.)
                new Attribute("transform", fixedSizeTransform(obj.getX(), obj.getY(), scale)),
                new Attribute("id", FloorMapJsonKeys.SVG_GROUP_PREFIX + obj.getId()));
    }

    /**
     * Builds the SVG transform for a <strong>fixed-screen-size</strong> glyph
     * anchored at a map-space point.
     *
     * <p>The {@code translate} places the glyph's origin in map space, so it
     * tracks pan/zoom position exactly like everything else. The
     * {@code scale(1/zoom, -1/zoom)} then cancels two things at once: the
     * pan/zoom group's {@code scale(zoom)} (so the glyph's own geometry renders
     * at a constant screen size regardless of zoom) and the Y-up
     * {@code scale(1,-1)} flip (so the glyph and its label stay upright — the
     * two negatives cancel).</p>
     *
     * @param mapX  map-space X of the anchor point
     * @param mapY  map-space Y of the anchor point
     * @param scale the current zoom factor (never zero — clamped by the presenter)
     * @return the {@code transform} attribute value
     */
    private static String fixedSizeTransform(final double mapX,
                                             final double mapY,
                                             final double scale) {
        final double inv = 1.0 / scale;
        return "translate(" + mapX + "," + mapY + ") scale(" + inv + "," + (-inv) + ")";
    }

    /**
     * Short display label: the part before {@code '@'} for email-like ids, or the
     * full id otherwise.
     */
    private static String shortLabel(final String id) {
        final String rawId = id != null ? id : "";
        final int atIdx = rawId.indexOf('@');
        return atIdx > 0 ? rawId.substring(0, atIdx) : rawId;
    }

    /**
     * Returns a fill colour for the given type: the colour configured on the
     * Settings tab if present, otherwise a built-in default per type.
     *
     * @param type       the object type string (e.g. "gate"), case-insensitive
     * @param typeStyles per-type settings, or {@code null}
     * @return a CSS hex colour string
     */
    private static String colourForType(final String type, final List<TypeStyle> typeStyles) {
        // Prefer the colour configured for this type on the Settings tab.
        if (type != null && typeStyles != null) {
            for (final TypeStyle style : typeStyles) {
                if (style != null && type.equals(style.getType())
                        && style.getColour() != null && !style.getColour().isEmpty()) {
                    return style.getColour();
                }
            }
        }

        // Return default
        return "#607d8b"; // blue-grey
    }

    /**
     * Returns the configured default-graphic shape for the given type, or
     * {@code null} if the type is unconfigured or has no shape set (the view then
     * falls back to the default rounded rectangle).
     */
    private static TypeStyle.Shape shapeForType(final String type, final List<TypeStyle> typeStyles) {
        if (type != null && typeStyles != null) {
            for (final TypeStyle style : typeStyles) {
                if (style != null && type.equals(style.getType())) {
                    return style.getShape();
                }
            }
        }
        return null;
    }

    /**
     * Registers a callback that is invoked whenever the canvas needs to be redrawn
     * (e.g. after an asynchronous image aspect-ratio resolution completes).
     *
     * @param redrawListener the callback, or {@code null} to clear
     */
    @Override
    public void setRedrawListener(final Runnable redrawListener) {
        this.redrawListener = redrawListener;
    }

    /** {@inheritDoc} */
    @Override
    public void setResizeListener(final Runnable resizeListener) {
        this.resizeListener = resizeListener;
    }

    /**
     * Callback invoked (via JSNI) when the browser has finished loading a background image
     * and its natural dimensions are known.
     *
     * @param url         the image URL that was loaded
     * @param aspectRatio the image's natural width / height ratio
     */
    @SuppressWarnings("unused")
    void onImageAspectRatioResolved(final String url, final double aspectRatio) {
        imageAspectRatioCache.put(url, aspectRatio);
        loadingImages.remove(url);
        if (redrawListener != null) {
            redrawListener.run();
        }
    }

    /**
     * Starts an asynchronous image load to determine the aspect ratio of the given URL.
     * No-ops if a load for this URL is already in flight.
     *
     * @param url the image URL to load
     */
    private void loadImageAspectRatio(final String url) {
        if (loadingImages.contains(url)) {
            return;
        }
        loadingImages.add(url);
        startImageLoad(url);
    }

    /**
     * JSNI method that creates a browser {@code Image} element and starts loading the given URL.
     * On success, calls back {@link #onImageAspectRatioResolved} with the computed aspect ratio;
     * on error, falls back to an aspect ratio of {@code 1.0}.
     */
    private native void startImageLoad(final String url) /*-{
        var self = this;
        var img = new Image();
        img.onload = function() {
            var width = img.naturalWidth || img.width || 0;
            var height = img.naturalHeight || img.height || 0;
            var aspectRatio = 1.0;
            if (width > 0 && height > 0) {
                aspectRatio = width / height;
            }
            self.@stroom.floormap.client.view.FloorMapCanvasViewImpl::onImageAspectRatioResolved(Ljava/lang/String;D)
                    (url, aspectRatio);
        };
        img.onerror = function() {
            self.@stroom.floormap.client.view.FloorMapCanvasViewImpl::onImageAspectRatioResolved(Ljava/lang/String;D)
                    (url, 1.0);
        };
        img.src = url;
    }-*/;

    /** GWT UiBinder interface for {@link FloorMapCanvasViewImpl}. */
    public interface Binder extends UiBinder<Widget, FloorMapCanvasViewImpl> {

    }
}
