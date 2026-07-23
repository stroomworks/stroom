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
import com.google.gwt.user.client.ui.Label;
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
 * {@code world-to-map} matrix — plus an event entity overlay:</p>
 * <ul>
 *   <li>Facts with an image are drawn as scaled images (multiple backgrounds are
 *       simply several image facts).</li>
 *   <li>Imageless facts and event entities are drawn as the same fixed-size
 *       default graphic whose shape and colour come from the per-type settings
 *       ({@link TypeStyle}); event entities additionally carry movement trails
 *       tinted with the type colour.</li>
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

    /** On-screen size (px) of a square scale handle. */
    private static final double HANDLE_SIZE_PX = 8;
    /** On-screen radius (px) of the round rotation handle. */
    private static final double ROTATE_HANDLE_RADIUS_PX = 5;
    /** Gap (px) between the top edge of the selection frame and the rotation handle. */
    private static final double ROTATE_HANDLE_OFFSET_PX = 24;
    /** Minimum on-screen frame size (px) so the handles stay separable on tiny objects. */
    private static final double MIN_FRAME_PX = 24;
    private static final String HANDLE_STROKE = "#1e88e5";
    private static final String HANDLE_FILL = "#ffffff";
    /** Greyed handle colours, shown when the selection can't be scaled/rotated. */
    private static final String HANDLE_DISABLED_STROKE = "#9e9e9e";
    private static final String HANDLE_DISABLED_FILL = "#e0e0e0";
    /** Tooltip explaining why the handles are inert for a non-transformable fact. */
    private static final String HANDLE_DISABLED_TOOLTIP =
            "Only image facts and areas can be scaled or rotated";

    /** Default translucency of an area fact's fill when no opacity is stored. */
    private static final double DEFAULT_AREA_FILL_OPACITY = 0.3;
    /**
     * On-screen radius (px) of the vertex-0 close-target ring in the area
     * drawing draft. Keep in step with
     * {@code FloorMapCanvasPresenter.AREA_CLOSE_RADIUS_PX}, which decides when
     * a click actually closes the polygon.
     */
    private static final double AREA_DRAFT_CLOSE_RADIUS_PX = 10;

    private final Widget widget;

    private final Map<String, Double> imageAspectRatioCache = new HashMap<>();
    private final Set<String> loadingImages = new HashSet<>();
    private Runnable redrawListener;
    private Runnable resizeListener;

    // Geometry of the last draw(), captured so hitTestScreenRect() can project
    // fact bounds to screen after the fact (map→screen uses these + pan/zoom).
    private double lastScale = 1;
    private double lastOffsetX;
    private double lastOffsetY;
    private List<Fact> lastFacts;
    private Set<String> lastSelectedIds;

    @UiField
    HTML svgContainer;

    @UiField
    FocusPanel focusPanel;

    /**
     * Instruction pill overlaid on the canvas while the area-drawing mode is
     * active — an HTML element (not SVG text) so it uses the theme variables
     * and reads clearly over any floor plan, matching the timeline scrub
     * tooltip's visual language.
     */
    @UiField
    Label areaDrawHint;

    private static final String AREA_DRAW_HINT_VISIBLE =
            "stroom-floormap-area-draw-hint--visible";

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
                     final boolean showGrid,
                     final Set<String> dimmedTypes,
                     final double[] marqueeRectPx,
                     final boolean drawSelectionHandles,
                     final boolean scaleRotateEnabled,
                     final double[] areaDraftPx) {
        final HtmlBuilder htmlBuilder = new HtmlBuilder();

        // Cache this frame's geometry so hitTestScreenRect()/getSelectionFrame()
        // can project fact bounds to screen afterwards (image sizes live only in
        // this view).
        this.lastScale = scale;
        this.lastOffsetX = x;
        this.lastOffsetY = y;
        this.lastFacts = facts;
        this.lastSelectedIds = selectedObjectIds;

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
                            // A dimmed layer wraps its facts in a group at 30%
                            // opacity; otherwise the fact is drawn directly.
                            if (dimmedTypes != null && dimmedTypes.contains(fact.getType())) {
                                flipGroup.elem(g -> renderFact(g, fact, isSelected, typeStyles, scale),
                                        SafeHtmlUtil.from("g"), new Attribute("opacity", "0.3"));
                            } else {
                                renderFact(flipGroup, fact, isSelected, typeStyles, scale);
                            }
                        }
                    }

                    // ---- Event entities drawn on top ----
                    if (events != null) {
                        for (final FloorMapObject ev : events) {
                            final boolean evSelected = selectedObjectIds.contains(ev.getId());
                            if (dimmedTypes != null && dimmedTypes.contains(ev.getType())) {
                                flipGroup.elem(g -> appendEvent(g, ev, evSelected, typeStyles, scale),
                                        SafeHtmlUtil.from("g"), new Attribute("opacity", "0.3"));
                            } else {
                                appendEvent(flipGroup, ev, evSelected, typeStyles, scale);
                            }
                        }
                    }
                }, SafeHtmlUtil.from("g"), new Attribute("transform", "scale(1,-1)")),
                SafeHtmlUtil.from("g"),
                    new Attribute("transform", "translate(" + x + "," + y + ") scale(" + scale + ")"));

            // Rubber-band selection marquee — screen space, drawn at the SVG
            // root (no pan/zoom transform), painted on top of the scene.
            if (marqueeRectPx != null) {
                appendMarquee(svg, marqueeRectPx);
            }

            // In-progress area-drawing draft — screen space, on top.
            if (areaDraftPx != null && areaDraftPx.length >= 2) {
                appendAreaDraft(svg, areaDraftPx);
            }

            // Selection frame + scale/rotate handles — screen space, on top.
            if (drawSelectionHandles) {
                appendSelectionHandles(svg, scaleRotateEnabled);
            }
        },
            SafeHtmlUtil.from("svg"),
            new Attribute("width", "100%"),
            new Attribute("height", "100%"),
            new Attribute("xmlns", "http://www.w3.org/2000/svg")
        );

        svgContainer.setHTML(htmlBuilder.toSafeHtml());
        updateAreaDrawHint(areaDraftPx);
    }

    /**
     * Shows, hides and words the area-drawing instruction pill. It is an HTML
     * overlay (see the ui.xml) rather than part of the rebuilt SVG, styled via
     * {@code stroom-floormap-area-draw-hint} to match the timeline scrub
     * tooltip.
     *
     * @param areaDraftPx the draft passed to this draw, or {@code null} when
     *                    the drawing mode is not active
     */
    private void updateAreaDrawHint(final double[] areaDraftPx) {
        if (areaDraftPx == null) {
            areaDrawHint.removeStyleName(AREA_DRAW_HINT_VISIBLE);
            return;
        }
        final int committed = areaDraftPx.length / 2 - 1;
        areaDrawHint.setText(committed >= 3
                ? "Drawing area — click the first point, double-click or press Enter"
                + " to finish · Esc cancels · right-click undoes the last point"
                : "Drawing area — click to add points (at least 3)"
                + " · Esc cancels · right-click undoes the last point");
        areaDrawHint.addStyleName(AREA_DRAW_HINT_VISIBLE);
    }

    /**
     * Draws the rubber-band selection rectangle at the SVG root in screen space.
     *
     * @param svg    the SVG root builder
     * @param rectPx {@code {minX, minY, maxX, maxY}} in element pixels
     */
    private void appendMarquee(final HtmlBuilder svg, final double[] rectPx) {
        svg.elem(SafeHtmlUtil.from("rect"),
                new Attribute("x", String.valueOf(rectPx[0])),
                new Attribute("y", String.valueOf(rectPx[1])),
                new Attribute("width", String.valueOf(rectPx[2] - rectPx[0])),
                new Attribute("height", String.valueOf(rectPx[3] - rectPx[1])),
                new Attribute("fill", "#1e88e5"),
                new Attribute("fill-opacity", "0.12"),
                new Attribute("stroke", "#1e88e5"),
                new Attribute("stroke-width", "1"),
                new Attribute("stroke-dasharray", "4,4"),
                new Attribute("pointer-events", "none"));
    }

    /**
     * Draws the in-progress area-drawing draft at the SVG root in screen space:
     * a solid polyline through the committed vertices, a dashed rubber-band
     * segment from the last committed vertex to the live cursor, a small dot on
     * each committed vertex, and a close-target ring on vertex 0 that fills in
     * when the polygon can be closed (≥ 3 committed vertices and the cursor
     * within the close radius). Everything is non-interactive.
     *
     * @param draftPx flat polyline {@code [x0, y0, ..., xn, yn]} in element
     *                pixels; the last point is the live cursor position
     */
    private void appendAreaDraft(final HtmlBuilder svg, final double[] draftPx) {
        final int points = draftPx.length / 2;
        final int committed = points - 1;

        // Solid polyline through the committed vertices.
        if (committed >= 2) {
            final StringBuilder committedPoints = new StringBuilder();
            for (int i = 0; i < committed; i++) {
                if (i > 0) {
                    committedPoints.append(" ");
                }
                committedPoints.append(draftPx[i * 2]).append(",").append(draftPx[i * 2 + 1]);
            }
            svg.elem(SafeHtmlUtil.from("polyline"),
                    new Attribute("points", committedPoints.toString()),
                    new Attribute("fill", "none"),
                    new Attribute("stroke", "#1e88e5"),
                    new Attribute("stroke-width", "2"),
                    new Attribute("pointer-events", "none"));
        }

        // Dashed rubber-band edge from the last committed vertex to the cursor.
        if (committed >= 1) {
            svg.elem(SafeHtmlUtil.from("line"),
                    new Attribute("x1", String.valueOf(draftPx[(committed - 1) * 2])),
                    new Attribute("y1", String.valueOf(draftPx[(committed - 1) * 2 + 1])),
                    new Attribute("x2", String.valueOf(draftPx[(points - 1) * 2])),
                    new Attribute("y2", String.valueOf(draftPx[(points - 1) * 2 + 1])),
                    new Attribute("stroke", "#1e88e5"),
                    new Attribute("stroke-width", "1"),
                    new Attribute("stroke-dasharray", "4,4"),
                    new Attribute("pointer-events", "none"));
        }

        // Committed vertex dots.
        for (int i = 0; i < committed; i++) {
            svg.elem(SafeHtmlUtil.from("circle"),
                    new Attribute("cx", String.valueOf(draftPx[i * 2])),
                    new Attribute("cy", String.valueOf(draftPx[i * 2 + 1])),
                    new Attribute("r", "3"),
                    new Attribute("fill", "#1e88e5"),
                    new Attribute("pointer-events", "none"));
        }

        // Close-target ring on vertex 0, highlighted when the polygon is
        // closable (enough vertices and the cursor within the close radius).
        if (committed >= 1) {
            final double dx = draftPx[(points - 1) * 2] - draftPx[0];
            final double dy = draftPx[(points - 1) * 2 + 1] - draftPx[1];
            final boolean closable = committed >= 3
                    && (dx * dx + dy * dy)
                    <= AREA_DRAFT_CLOSE_RADIUS_PX * AREA_DRAFT_CLOSE_RADIUS_PX;
            svg.elem(SafeHtmlUtil.from("circle"),
                    new Attribute("cx", String.valueOf(draftPx[0])),
                    new Attribute("cy", String.valueOf(draftPx[1])),
                    new Attribute("r", String.valueOf(AREA_DRAFT_CLOSE_RADIUS_PX)),
                    new Attribute("fill", closable ? "#1e88e5" : "none"),
                    new Attribute("fill-opacity", closable ? "0.4" : "0"),
                    new Attribute("stroke", "#1e88e5"),
                    new Attribute("stroke-width", closable ? "2" : "1"),
                    new Attribute("pointer-events", "none"));
        }
    }

    /**
     * Renders a single fact into the given builder, dispatching by content:
     * image facts render their image; imageless facts with vertices render as
     * areas; everything else renders as the type's default glyph.
     */
    private void renderFact(final HtmlBuilder builder,
                            final Fact fact,
                            final boolean isSelected,
                            final List<TypeStyle> typeStyles,
                            final double scale) {
        if (fact.hasImage()) {
            appendImageFact(builder, fact, isSelected);
        } else if (fact.hasVertices()) {
            appendAreaFact(builder, fact, isSelected, typeStyles);
        } else {
            appendDefaultGraphic(builder, fact, isSelected, typeStyles, scale);
        }
    }

    /**
     * Draws an area fact — a filled polygon whose vertices are in the fact's
     * local frame, placed into map space by its world-to-map matrix inside the
     * Y-up flip group (so it pans, zooms and rotates with the map, like an
     * image fact).
     *
     * <p>The translucent fill is deliberately non-interactive so a large area
     * cannot hijack panning, marquee selection or the empty-canvas context
     * menu; selection is by clicking the border, via an invisible wide "hit"
     * stroke that carries the fact key as its id (the same click-detection
     * convention as every other object shape).</p>
     */
    private void appendAreaFact(final HtmlBuilder parent,
                                final Fact fact,
                                final boolean isSelected,
                                final List<TypeStyle> typeStyles) {
        final double[][] vertices = fact.getVertices();
        final StringBuilder points = new StringBuilder();
        for (int i = 0; i < vertices.length; i++) {
            if (i > 0) {
                points.append(" ");
            }
            points.append(vertices[i][0]).append(",").append(vertices[i][1]);
        }

        // Fall back to the colour configured for the fact's own type (which is
        // "area" for areas created by the editor, but users may retype areas —
        // e.g. "restricted" — and expect that type's Settings colour).
        final String colour = fact.getFill() != null && !fact.getFill().isEmpty()
                ? fact.getFill()
                : colourForType(fact.getType(), typeStyles);
        final double opacity = fact.getOpacity() != null
                ? Math.max(0.0, Math.min(1.0, fact.getOpacity()))
                : DEFAULT_AREA_FILL_OPACITY;
        final String stroke = isSelected ? "#ff9800" : colour;
        final String strokeWidth = isSelected ? "4" : "2";

        parent.elem(areaGroup -> {
            // Visible polygon — non-interactive.
            areaGroup.elem(SafeHtmlUtil.from("polygon"),
                    new Attribute("points", points.toString()),
                    new Attribute("fill", colour),
                    new Attribute("fill-opacity", String.valueOf(opacity)),
                    new Attribute("fill-rule", "evenodd"),
                    new Attribute("stroke", stroke),
                    new Attribute("stroke-width", strokeWidth),
                    new Attribute("vector-effect", "non-scaling-stroke"),
                    new Attribute("pointer-events", "none"));
            // Invisible hit polygon — the clickable element. pointer-events
            // "all" makes both the interior and a generous 10px border band
            // clickable regardless of paint, so an area is selected by
            // clicking anywhere inside it. The presenter treats a press on an
            // UNSELECTED area like a background press (drag pans, click
            // selects), so a large area cannot hijack map panning.
            areaGroup.elem(SafeHtmlUtil.from("polygon"),
                    new Attribute("points", points.toString()),
                    new Attribute("fill", "#000000"),
                    new Attribute("fill-opacity", "0"),
                    new Attribute("stroke", "#000000"),
                    new Attribute("stroke-opacity", "0"),
                    new Attribute("stroke-width", "10"),
                    new Attribute("vector-effect", "non-scaling-stroke"),
                    new Attribute("pointer-events", "all"),
                    new Attribute("id", fact.getKey()));
        }, SafeHtmlUtil.from("g"),
                new Attribute("transform", fact.getWorldToMap().toSvgMatrix()),
                new Attribute("id", FloorMapJsonKeys.SVG_GROUP_PREFIX + fact.getKey()));
    }

    @Override
    public Set<String> hitTestScreenRect(final double[] rectPx) {
        final Set<String> hits = new HashSet<>();
        if (lastFacts == null || rectPx == null) {
            return hits;
        }
        final double minX = rectPx[0];
        final double minY = rectPx[1];
        final double maxX = rectPx[2];
        final double maxY = rectPx[3];
        for (final Fact fact : lastFacts) {
            final double[] b = factScreenBounds(fact);
            // AABB intersection (touch counts as a hit).
            if (b != null && b[0] <= maxX && b[2] >= minX && b[1] <= maxY && b[3] >= minY) {
                hits.add(fact.getKey());
            }
        }
        return hits;
    }

    /**
     * Returns a fact's on-screen bounding box {@code {minX, minY, maxX, maxY}}
     * using the last-drawn scale/pan. Image facts use the projected corners of
     * their placed image rect; imageless glyphs use a fixed-size box around the
     * projected anchor. Returns {@code null} if the fact has no matrix.
     */
    private double[] factScreenBounds(final Fact fact) {
        final FloorMapTransformationMatrix w2m = fact.getWorldToMap();
        if (w2m == null) {
            return null;
        }
        // Same dispatch order as draw(): image wins over vertices.
        if (!fact.hasImage() && fact.hasVertices()) {
            // Area polygon: the AABB of every vertex projected local → map →
            // screen. (An AABB over-selects concave/rotated areas on marquee —
            // acceptable for v1.)
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (final double[] v : fact.getVertices()) {
                final double[] mapPt = w2m.transformPoint(v[0], v[1]);
                final double px = lastOffsetX + lastScale * mapPt[0];
                final double py = lastOffsetY - lastScale * mapPt[1];
                minX = Math.min(minX, px);
                minY = Math.min(minY, py);
                maxX = Math.max(maxX, px);
                maxY = Math.max(maxY, py);
            }
            return new double[]{minX, minY, maxX, maxY};
        }
        if (fact.hasImage()) {
            final Double ar = imageAspectRatioCache.get(fact.getImage());
            final double aspect = ar != null ? ar : 1.0;
            final double w = IMAGE_DISPLAY_WIDTH;
            final double h = w / aspect;
            // image-local → map space (matches the render wrapper transform:
            // worldToMap · translate(0,h) · scale(1,-1)).
            final FloorMapTransformationMatrix m = w2m
                    .multiply(FloorMapTransformationMatrix.translate(0, h))
                    .multiply(FloorMapTransformationMatrix.scale(1, -1));
            final double[][] corners = {
                    m.transformPoint(0, 0), m.transformPoint(w, 0),
                    m.transformPoint(0, h), m.transformPoint(w, h)};
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (final double[] c : corners) {
                final double sx = lastOffsetX + lastScale * c[0];
                final double sy = lastOffsetY - lastScale * c[1];
                minX = Math.min(minX, sx);
                minY = Math.min(minY, sy);
                maxX = Math.max(maxX, sx);
                maxY = Math.max(maxY, sy);
            }
            return new double[]{minX, minY, maxX, maxY};
        }
        // Imageless glyph: a fixed screen-size box around the projected anchor.
        final double[] pos = fact.getPosition();
        final double[] mapPt = w2m.transformPoint(
                pos != null ? pos[0] : 0, pos != null ? pos[1] : 0);
        final double sx = lastOffsetX + lastScale * mapPt[0];
        final double sy = lastOffsetY - lastScale * mapPt[1];
        final double half = OBJECT_SIZE / 2.0;
        return new double[]{sx - half, sy - half, sx + half, sy + half};
    }

    @Override
    public double[] getFactMapAnchor(final Fact fact) {
        return fact.mapAnchor(IMAGE_DISPLAY_WIDTH,
                imageAspectRatioCache.get(fact.getImage()));
    }

    @Override
    public double[] getSelectionFrame() {
        return computeSelectionFrame();
    }

    /**
     * Returns the screen-space bounding box {@code {minX, minY, maxX, maxY}} of
     * the currently selected facts (union of their on-screen bounds), padded to
     * a minimum size so the handles stay separable. Returns {@code null} when
     * nothing is selected or laid out.
     */
    private double[] computeSelectionFrame() {
        if (lastFacts == null || lastSelectedIds == null || lastSelectedIds.isEmpty()) {
            return null;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        boolean any = false;
        for (final Fact f : lastFacts) {
            if (lastSelectedIds.contains(f.getKey())) {
                final double[] b = factScreenBounds(f);
                if (b != null) {
                    minX = Math.min(minX, b[0]);
                    minY = Math.min(minY, b[1]);
                    maxX = Math.max(maxX, b[2]);
                    maxY = Math.max(maxY, b[3]);
                    any = true;
                }
            }
        }
        if (!any) {
            return null;
        }
        if (maxX - minX < MIN_FRAME_PX) {
            final double c = (minX + maxX) / 2;
            minX = c - MIN_FRAME_PX / 2;
            maxX = c + MIN_FRAME_PX / 2;
        }
        if (maxY - minY < MIN_FRAME_PX) {
            final double c = (minY + maxY) / 2;
            minY = c - MIN_FRAME_PX / 2;
            maxY = c + MIN_FRAME_PX / 2;
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    /**
     * Draws the selection frame outline, the 4 corner scale handles and the
     * rotation handle above the top edge, all in screen space at the SVG root.
     * Each handle carries an id of {@code FloorMapJsonKeys.HANDLE_PREFIX + role}
     * so the presenter can route a mousedown on it to a scale/rotate gesture.
     * Scaling is always aspect-preserving, so only corner handles are offered.
     */
    private void appendSelectionHandles(final HtmlBuilder svg, final boolean enabled) {
        final double[] f = computeSelectionFrame();
        if (f == null) {
            return;
        }
        final double minX = f[0];
        final double minY = f[1];
        final double maxX = f[2];
        final double maxY = f[3];
        final double cx = (minX + maxX) / 2;

        // Frame outline (non-interactive).
        svg.elem(SafeHtmlUtil.from("rect"),
                new Attribute("x", String.valueOf(minX)),
                new Attribute("y", String.valueOf(minY)),
                new Attribute("width", String.valueOf(maxX - minX)),
                new Attribute("height", String.valueOf(maxY - minY)),
                new Attribute("fill", "none"),
                new Attribute("stroke", HANDLE_STROKE),
                new Attribute("stroke-width", "1"),
                new Attribute("stroke-dasharray", "4,3"),
                new Attribute("pointer-events", "none"));

        // Rotation handle above the top edge, with a connector line.
        final double ry = minY - ROTATE_HANDLE_OFFSET_PX;
        svg.elem(SafeHtmlUtil.from("line"),
                new Attribute("x1", String.valueOf(cx)),
                new Attribute("y1", String.valueOf(minY)),
                new Attribute("x2", String.valueOf(cx)),
                new Attribute("y2", String.valueOf(ry)),
                new Attribute("stroke", HANDLE_STROKE),
                new Attribute("stroke-width", "1"),
                new Attribute("pointer-events", "none"));
        appendRotateHandle(svg, cx, ry, enabled);

        // 4 corner scale handles (aspect-preserving scale about the opposite corner).
        appendScaleHandle(svg, minX, minY, "scale-nw", "nwse-resize", enabled);
        appendScaleHandle(svg, maxX, minY, "scale-ne", "nesw-resize", enabled);
        appendScaleHandle(svg, maxX, maxY, "scale-se", "nwse-resize", enabled);
        appendScaleHandle(svg, minX, maxY, "scale-sw", "nesw-resize", enabled);
    }

    private void appendScaleHandle(final HtmlBuilder svg, final double x, final double y,
                                   final String role, final String cursor, final boolean enabled) {
        svg.elem(
                builder -> {
                    if (!enabled) {
                        builder.elem(HANDLE_DISABLED_TOOLTIP, SafeHtmlUtil.from("title"));
                    }
                },
                SafeHtmlUtil.from("rect"),
                new Attribute("id", FloorMapJsonKeys.HANDLE_PREFIX + role),
                new Attribute("x", String.valueOf(x - HANDLE_SIZE_PX / 2)),
                new Attribute("y", String.valueOf(y - HANDLE_SIZE_PX / 2)),
                new Attribute("width", String.valueOf(HANDLE_SIZE_PX)),
                new Attribute("height", String.valueOf(HANDLE_SIZE_PX)),
                new Attribute("fill", enabled ? HANDLE_FILL : HANDLE_DISABLED_FILL),
                new Attribute("stroke", enabled ? HANDLE_STROKE : HANDLE_DISABLED_STROKE),
                new Attribute("stroke-width", "1"),
                new Attribute("cursor", enabled ? cursor : "not-allowed"));
    }

    private void appendRotateHandle(final HtmlBuilder svg, final double x, final double y,
                                    final boolean enabled) {
        svg.elem(
                builder -> {
                    if (!enabled) {
                        builder.elem(HANDLE_DISABLED_TOOLTIP, SafeHtmlUtil.from("title"));
                    }
                },
                SafeHtmlUtil.from("circle"),
                new Attribute("id", FloorMapJsonKeys.HANDLE_PREFIX + "rotate"),
                new Attribute("cx", String.valueOf(x)),
                new Attribute("cy", String.valueOf(y)),
                new Attribute("r", String.valueOf(ROTATE_HANDLE_RADIUS_PX)),
                new Attribute("fill", enabled ? HANDLE_FILL : HANDLE_DISABLED_FILL),
                new Attribute("stroke", enabled ? HANDLE_STROKE : HANDLE_DISABLED_STROKE),
                new Attribute("stroke-width", "1"),
                new Attribute("cursor", enabled ? "grab" : "not-allowed"));
    }

    /**
     * Draws an image fact — an {@code <image>} at its local size, wrapped in a
     * {@code <g>} carrying the fact's world-to-map matrix so it is placed and
     * scaled in map space. A selection border is added when selected.
     */
    private void appendImageFact(final HtmlBuilder parent,
                                 final Fact fact,
                                 final boolean isSelected) {
        appendImageGlyph(parent, fact, fact.getWorldToMap(), false, isSelected, "#1e88e5");
    }

    /**
     * Draws an image glyph — an {@code <image>} at its local size, wrapped in a
     * {@code <g>} carrying the given placement matrix so it is placed and
     * scaled in map space. Used for image facts (placed by their own
     * world-to-map) and for event entities with an image-bearing fact twin
     * (placed by the twin's scale/rotation but the entity's live position).
     *
     * @param placement       the full placement matrix to apply
     * @param centred         when {@code true}, the image's centre (rather than
     *                        its bottom-left corner) lands on the placement
     *                        translation point — used for event entities so the
     *                        icon sits on the entity position like a shape
     *                        glyph would
     * @param selectionColour selection border colour when selected (facts use
     *                        the Editor blue, tracked entities the orange
     *                        selection colour)
     */
    private void appendImageGlyph(final HtmlBuilder parent,
                                  final Fact fact,
                                  final FloorMapTransformationMatrix placement,
                                  final boolean centred,
                                  final boolean isSelected,
                                  final String selectionColour) {
        final Double cachedAspectRatio = imageAspectRatioCache.get(fact.getImage());
        final double aspectRatio = cachedAspectRatio != null ? cachedAspectRatio : 1.0;
        if (cachedAspectRatio == null) {
            loadImageAspectRatio(fact.getImage());
        }
        final double imgHeight = (double) IMAGE_DISPLAY_WIDTH / aspectRatio;

        FloorMapTransformationMatrix effective = placement;
        if (centred) {
            // Shift the translation by the placement-transformed local centre
            // so the image's midpoint lands on the translation point.
            final double cx = IMAGE_DISPLAY_WIDTH / 2.0;
            final double cy = imgHeight / 2.0;
            effective = new FloorMapTransformationMatrix(
                    placement.getA(), placement.getB(), placement.getC(), placement.getD(),
                    placement.getE() - (placement.getA() * cx + placement.getC() * cy),
                    placement.getF() - (placement.getB() * cx + placement.getD() * cy));
        }

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
                    new Attribute("stroke", selectionColour),
                    new Attribute("stroke-width", "8"),
                    new Attribute("vector-effect", "non-scaling-stroke"),
                    new Attribute("pointer-events", "none"));
            }
        }, SafeHtmlUtil.from("g"),
                // Anchor the raster at its BOTTOM-left in map space: translate up
                // by its own height, then counter-flip (scale 1,-1) to undo the
                // Y-up flip group and keep it upright. So an identity placement
                // places the image in the visible first quadrant (up-and-right of
                // the origin) rather than below it; a real placement matrix
                // (applied outermost) then positions/scales/rotates from there.
                new Attribute("transform", effective.toSvgMatrix()
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

        appendStyledGlyph(parent, fact.getKey(), fact.getType(), mapX, mapY,
                isSelected, typeStyles, scale);
    }

    /**
     * Draws an event entity at its map coordinates, preceded by its movement
     * trail — a fading path tinted with the entity's type colour. The entity
     * itself renders as its attached icon (when an image-bearing fact twin is
     * present, scaled/rotated by that fact's world-to-map but placed at the
     * live position) or otherwise as the type-styled default graphic, the same
     * rendering as an imageless fact.
     */
    private void appendEvent(final HtmlBuilder parent,
                             final FloorMapObject obj,
                             final boolean isSelected,
                             final List<TypeStyle> typeStyles,
                             final double scale) {
        // Movement trail — rendered before the glyph so it sits behind.
        if (obj.getTrail() != null && obj.getTrail().size() >= 2) {
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
                    new Attribute("stroke", colourForType(obj.getType(), typeStyles)),
                    new Attribute("stroke-width", "6"),
                    new Attribute("stroke-linecap", "round"),
                    new Attribute("stroke-linejoin", "round"),
                    new Attribute("vector-effect", "non-scaling-stroke"),
                    new Attribute("opacity", String.valueOf(maxAlpha)),
                    new Attribute("pointer-events", "none"));
            }
        }

        final Fact imageFact = obj.getImageFact();
        if (imageFact != null) {
            // The entity has an attached icon: keep the icon's configured
            // scale/rotation (a,b,c,d) but centre it on the entity's live
            // position, so the icon follows the events and animates.
            final FloorMapTransformationMatrix w2m = imageFact.getWorldToMap();
            final FloorMapTransformationMatrix placement = new FloorMapTransformationMatrix(
                    w2m.getA(), w2m.getB(), w2m.getC(), w2m.getD(),
                    obj.getX(), obj.getY());
            appendImageGlyph(parent, imageFact, placement, true, isSelected, "#ff9800");
        } else {
            // The trail above stays in map space so it scales with the map;
            // the glyph itself is fixed screen size.
            appendStyledGlyph(parent, obj.getId(), obj.getType(), obj.getX(), obj.getY(),
                    isSelected, typeStyles, scale);
        }
    }

    /**
     * Draws the type-styled default graphic — the single glyph rendering shared
     * by imageless facts and event entities — anchored at a map-space point and
     * drawn at a fixed screen size. Shape and colour come from the type's
     * {@link TypeStyle}; the shape element carries {@code id} so click-detection
     * works, and a centred short label is drawn on top.
     */
    private void appendStyledGlyph(final HtmlBuilder parent,
                                   final String id,
                                   final String type,
                                   final double mapX,
                                   final double mapY,
                                   final boolean isSelected,
                                   final List<TypeStyle> typeStyles,
                                   final double scale) {
        final String fillColour = colourForType(type, typeStyles);
        final TypeStyle.Shape shape = shapeForType(type, typeStyles);
        final String stroke = isSelected ? "#ff9800" : "none";
        final String strokeWidth = isSelected ? "4" : "0";
        final String vectorEffect = isSelected ? "non-scaling-stroke" : "none";
        final String polygon = FloorMapShapes.polygonPoints(shape, OBJECT_SIZE / 2.0);
        final String label = shortLabel(id);

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
                    new Attribute("id", id));
            } else if (polygon != null) {
                objGroup.elem(SafeHtmlUtil.from("polygon"),
                    new Attribute("points", polygon),
                    new Attribute("fill", fillColour),
                    new Attribute("stroke", stroke),
                    new Attribute("stroke-width", strokeWidth),
                    new Attribute("vector-effect", vectorEffect),
                    new Attribute("id", id));
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
                    new Attribute("id", id));
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
                new Attribute("id", FloorMapJsonKeys.SVG_GROUP_PREFIX + id));
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

        // Built-in defaults: people keep their traditional blue on maps that
        // haven't configured a person TypeStyle yet.
        if (FloorMapJsonKeys.PERSON.equalsIgnoreCase(type)) {
            return "#1f77b4"; // blue
        }
        return "#607d8b"; // blue-grey
    }

    /**
     * Returns the configured default-graphic shape for the given type. Falls
     * back to a circle for unconfigured {@code person} types (continuity with
     * the traditional person marker), or {@code null} otherwise (the view then
     * falls back to the default rounded rectangle).
     */
    private static TypeStyle.Shape shapeForType(final String type, final List<TypeStyle> typeStyles) {
        if (type != null && typeStyles != null) {
            for (final TypeStyle style : typeStyles) {
                if (style != null && type.equals(style.getType()) && style.getShape() != null) {
                    return style.getShape();
                }
            }
        }
        if (FloorMapJsonKeys.PERSON.equalsIgnoreCase(type)) {
            return TypeStyle.Shape.CIRCLE;
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
