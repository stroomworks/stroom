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
import stroom.floormap.shared.FloorMapObject;
import stroom.floormap.shared.FloorMapTransformationMatrix;
import stroom.widget.util.client.HtmlBuilder;
import stroom.widget.util.client.HtmlBuilder.Attribute;
import stroom.widget.util.client.SafeHtmlUtil;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.HasMouseDownHandlers;
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

public class FloorMapCanvasViewImpl
        extends ViewWithUiHandlers<DirtyUiHandlers>
        implements FloorMapCanvasView, ReadOnlyChangeHandler {

    /**
     * The display width of a background image in map-space SVG coordinate
     * units. The image height is derived from this and the image's aspect
     * ratio. This is an arbitrary normalisation — the world-to-map
     * transformation matrix determines the real-world meaning.
     */
    private static final int IMAGE_DISPLAY_WIDTH = 1000;

    private static final int OBJECT_SIZE = 100;
    private static final int PERSON_RADIUS = 30;

    private final Widget widget;

    private final Map<String, Double> imageAspectRatioCache = new HashMap<>();
    private final Set<String> loadingImages = new HashSet<>();
    private Runnable redrawListener;

    @UiField
    HTML svgContainer;

    @UiField
    FocusPanel focusPanel;

    @Inject
    public FloorMapCanvasViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void onReadOnly(final boolean readOnly) {
    }

    @Override
    public void onResize() {
        final Element parent = svgContainer.getElement().getParentElement();
        final int width = parent.getOffsetWidth();
        final int height = parent.getOffsetHeight();

        // Defer the drawing logic if the parent hasn't been rendered yet.
        if (width <= 0 || height <= 0) {
            Scheduler.get().scheduleDeferred((this::onResize));
        }
        // SVG handles its own responsiveness via 100% width/height.
    }

    @Override
    public HasMouseDownHandlers getFocusPanel() {
        return focusPanel;
    }

    @Override
    public HasMouseMoveHandlers getMouseMoveHandlers() {
        return focusPanel;
    }

    @Override
    public HasMouseUpHandlers getMouseUpHandlers() {
        return focusPanel;
    }

    @Override
    public HasMouseWheelHandlers getMouseWheelHandlers() {
        return focusPanel;
    }

    @Override
    public void draw(final double scale,
                     final double x,
                     final double y,
                     final String backgroundImage,
                     final FloorMapTransformationMatrix matrix,
                     final List<FloorMapObject> objects,
                     final String selectedObjectId) {
        final HtmlBuilder htmlBuilder = new HtmlBuilder();
        final FloorMapTransformationMatrix effectiveMatrix =
                matrix != null ? matrix : FloorMapTransformationMatrix.identity();
        final String svgMatrix = effectiveMatrix.toSvgMatrix();

        // Pre-compute counter-rotation so labels stay upright when the
        // background image has been rotated via the world-to-map matrix.
        final double radians = Math.atan2(effectiveMatrix.getB(), effectiveMatrix.getA());
        final double counterRotationDeg = -Math.toDegrees(radians);
        final String counterRotation = "rotate(" + counterRotationDeg + ")";

        htmlBuilder.elem(svg -> {

            // ----------------------------------------------------------------
            // Background
            // ----------------------------------------------------------------
            if (backgroundImage == null) {
                // Adaptive grid background — fills the entire viewport.
                // Spacing adjusts with zoom: white grid on dark background,
                // with 10 subdivisions per major square that fade in/out.
                FloorMapGridBackground.appendGrid(
                        svg, effectiveMatrix, scale, x, y);
            }

            // Group 1: User zoom/pan (applied first).
            svg.elem(panGroup -> {
                // Group 2: The map-to-screen transformation matrix.
                panGroup.elem(matrixGroup -> {

                    // ----------------------------------------------------------------
                    // Background image (when provided)
                    // ----------------------------------------------------------------
                    if (backgroundImage != null) {
                        final Double cachedAspectRatio = imageAspectRatioCache.get(backgroundImage);
                        final double aspectRatio = cachedAspectRatio != null ? cachedAspectRatio : 1.0;
                        if (cachedAspectRatio == null) {
                            loadImageAspectRatio(backgroundImage);
                        }
                        final double bgHeight = (double) IMAGE_DISPLAY_WIDTH / aspectRatio;

                        matrixGroup.elem(SafeHtmlUtil.from("image"),
                            new Attribute(SafeHtmlUtils.fromSafeConstant("href"),
                                    SafeHtmlUtils.fromTrustedString(backgroundImage)),
                            new Attribute("x", "0"),
                            new Attribute("y", "0"),
                            new Attribute("width", String.valueOf(IMAGE_DISPLAY_WIDTH)),
                            new Attribute("height", String.valueOf(bgHeight)),
                            new Attribute("preserveAspectRatio", "none"),
                            new Attribute("id", "background"));

                        if ("background".equals(selectedObjectId)) {
                            matrixGroup.elem(SafeHtmlUtil.from("rect"),
                                new Attribute("x", "0"),
                                new Attribute("y", "0"),
                                new Attribute("width", String.valueOf(IMAGE_DISPLAY_WIDTH)),
                                new Attribute("height", String.valueOf(bgHeight)),
                                new Attribute("fill", "none"),
                                new Attribute("stroke", "#1e88e5"),
                                new Attribute("stroke-width", "8"),
                                new Attribute("vector-effect", "non-scaling-stroke"),
                                new Attribute("pointer-events", "none"));
                        }
                    }

                    // ----------------------------------------------------------------
                    // Map objects (facts — gates, doors, etc.)
                    // ----------------------------------------------------------------
                    if (objects != null) {
                        for (final FloorMapObject obj : objects) {
                            final boolean isPerson = "person".equalsIgnoreCase(obj.getType());
                            final boolean isSelected = obj.getId().equals(selectedObjectId);

                            // Short display label: use the part before '@' for email addresses,
                            // or the full ID if no '@' is present.
                            final String rawId = obj.getId() != null ? obj.getId() : "";
                            final int atIdx = rawId.indexOf('@');
                            final String displayLabel = atIdx > 0 ? rawId.substring(0, atIdx) : rawId;

                            // Each object is a <g translate(x,y)> wrapper containing the shape + label.
                            // The wrapper id uses the "obj-" prefix so click-detection ignores it.
                            matrixGroup.elem(objGroup -> {
                                if (isPerson) {
                                    // ---- Person: filled circle ----
                                    objGroup.elem(SafeHtmlUtil.from("circle"),
                                        new Attribute("cx", "0"),
                                        new Attribute("cy", "0"),
                                        new Attribute("r", String.valueOf(PERSON_RADIUS)),
                                        new Attribute("fill", "#1f77b4"),
                                        new Attribute("stroke", isSelected ? "#ff9800" : "#ffffff"),
                                        new Attribute("stroke-width", isSelected ? "4" : "2"),
                                        new Attribute("vector-effect", "non-scaling-stroke"),
                                        // FIX (Bug 3): ID on the shape (no "obj-" prefix) so click-detection works.
                                        new Attribute("id", obj.getId()));

                                    // Label rendered BELOW the circle so it isn't hidden inside it.
                                    // FIX (Bug 4/5): use dy to shift below the circle radius.
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
                                            new Attribute("pointer-events", "none"),
                                            new Attribute("transform", counterRotation));
                                } else {
                                    // ---- Static object: rounded rectangle ----
                                    final String fillColour = colourForType(obj.getType());

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
                                        // FIX (Bug 3): ID on the shape so click-detection works.
                                        new Attribute("id", obj.getId()));

                                    // FIX (Bug 2): pass the actual display text as content.
                                    objGroup.elem(displayLabel,
                                            SafeHtmlUtil.from("text"),
                                            new Attribute("x", "0"),
                                            new Attribute("y", "0"),
                                            new Attribute("dy", "0.35em"),
                                            new Attribute("text-anchor", "middle"),
                                            new Attribute("fill", "white"),
                                            new Attribute("font-size", "14px"),
                                            new Attribute("font-family", "sans-serif"),
                                            new Attribute("pointer-events", "none"),
                                            new Attribute("transform", counterRotation));
                                }
                            },
                                    SafeHtmlUtil.from("g"),
                                    new Attribute("transform", "translate(" + obj.getX() + "," + obj.getY() + ")"),
                                    new Attribute("id", "obj-" + obj.getId()));
                        }
                    }

                }, SafeHtmlUtil.from("g"), new Attribute("transform", svgMatrix));
            }, SafeHtmlUtil.from("g"),
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
     * TODO EW: Potentially add a colour option to object edit screen so users can choose this
     * instead of being predefined.
     * Returns a fill colour based on object type so different fixture types are
     * visually distinguishable at a glance.
     */
    private static String colourForType(final String type) {
        if (type == null) {
            return "#607d8b"; // blue-grey default
        }
        return switch (type.toLowerCase()) {
            case "background" -> "#90a4ae";
            case "gates", "gate" -> "#43a047"; // green
            case "door", "doors" -> "#fb8c00"; // amber
            case "camera", "cameras" -> "#8e24aa"; // purple
            case "desk", "desks" -> "#039be5"; // light blue
            case "server", "servers" -> "#e53935"; // red
            default -> "#607d8b"; // blue-grey
        };
    }

    @Override
    public void setRedrawListener(final Runnable redrawListener) {
        this.redrawListener = redrawListener;
    }

    @SuppressWarnings("unused")
    void onImageAspectRatioResolved(final String url, final double aspectRatio) {
        imageAspectRatioCache.put(url, aspectRatio);
        loadingImages.remove(url);
        if (redrawListener != null) {
            redrawListener.run();
        }
    }

    private void loadImageAspectRatio(final String url) {
        if (loadingImages.contains(url)) {
            return;
        }
        loadingImages.add(url);
        startImageLoad(url);
    }

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

    // --------------------------------------------------------------------------------

    public interface Binder extends UiBinder<Widget, FloorMapCanvasViewImpl> {

    }
}
