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

import java.util.List;

public class FloorMapCanvasViewImpl
        extends ViewWithUiHandlers<DirtyUiHandlers>
        implements FloorMapCanvasView, ReadOnlyChangeHandler {

    private final static int OBJECT_SIZE = 100;
    private final Widget widget;

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
        // Get the parent element from the DOM and its width/height.
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
                     final List<FloorMapObject> objects) {
        final HtmlBuilder htmlBuilder = new HtmlBuilder();
        final String svgMatrix = matrix != null ? matrix.toSvgMatrix() : FloorMapTransformationMatrix.identity().toSvgMatrix();

        // Build the SVG structure dynamically
        htmlBuilder.elem(svg -> {
            // Group 1: User zoom/pan (applied first).
            svg.elem(group -> {
                // Group 2: The transformation matrix (applied inside zoom).
                group.elem(matrixGroup -> {
                    // Render the background image if set
                    if (backgroundImage != null) {
                        matrixGroup.elem(SafeHtmlUtil.from("image"),
                            new Attribute(SafeHtmlUtils.fromSafeConstant("href"),
                                    SafeHtmlUtils.fromTrustedString(backgroundImage)),
                            new Attribute("x", "0"),
                            new Attribute("y", "0"),
                            new Attribute("width", "1000"),
                            new Attribute("height", "1000"),
                            new Attribute("preserveAspectRatio", "none"));
                    } else {
                        // Fallback background rect
                        group.elem(SafeHtmlUtil.from("rect"),
                            new Attribute("x", "0"),
                            new Attribute("y", "0"),
                            new Attribute("width", "1000"),
                            new Attribute("height", "1000"),
                            new Attribute("fill", "#FFFFFF"));
                    }

                    if (objects != null) {
                        for (final FloorMapObject obj : objects) {
                            matrixGroup.elem(objectGroup -> {

                                // Determine if this object is a person.
                                final boolean isPerson = obj.getType() != null && obj.getType().equalsIgnoreCase("person");

                                if (isPerson) {
                                    // Render a small blue circle for users.
                                    objectGroup.elem(SafeHtmlUtil.from("circle"),
                                        new Attribute("cx", "0"),
                                        new Attribute("cy", "0"),
                                        new Attribute("r", (OBJECT_SIZE / 4) + ""),
                                        new Attribute("fill", "#1f77b4"),
                                        new Attribute("stroke", "#ffffff"),
                                        new Attribute("stroke-width", "2"),
                                        new Attribute("id", obj.getId())
                                    );
                                } else {
                                    objectGroup.elem(SafeHtmlUtil.from("rect"),
                                        // Shifting x and y by half the square's size puts it's center over the map coordinate.
                                        new Attribute("x", (-OBJECT_SIZE/2) + ""),
                                        new Attribute("y", (-OBJECT_SIZE/2) + ""),
                                        new Attribute("width", OBJECT_SIZE + ""),
                                        new Attribute("height", OBJECT_SIZE + ""),
                                        new Attribute("fill", "grey"),
                                        new Attribute("rx", "4"), // Round corners
                                        new Attribute("ry", "4"),
                                        new Attribute("id", obj.getId())
                                    );
                                }

                                double counterRotationDegrees = 0;
                                if (matrix != null) {
                                    final double radians = Math.atan2(matrix.getB(), matrix.getA());
                                    counterRotationDegrees = -Math.toDegrees(radians);
                                }

                                // Adjust label text position for circles vs squares.
                                final String textDy = isPerson ? "1.5em" : "0.35em";

                                objectGroup.elem(obj.getId(), SafeHtmlUtil.from("text"),
                                        new Attribute("x", "0"),
                                        new Attribute("y", "0"),
                                        new Attribute("dy", "0.35em"), // Centres text vertically
                                        new Attribute("text-anchor", "middle"), // Centres text horizontally
                                        new Attribute("fill", "white"),
                                        new Attribute("font-size", "11px"),
                                        new Attribute("font-family", "sans-serif"),
                                        new Attribute("pointer-events", "none"), // Prevents text from interfering with mouse panning
                                        new Attribute("transform", "rotate(" + counterRotationDegrees + ")")
                                );
                            },
                            SafeHtmlUtil.from("g"),
                                new Attribute("transform", "translate(" + obj.getX() + "," + obj.getY() + ")"),
                                new Attribute("id", "obj-" + obj.getId())
                            );
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

        // Inject the generated SVG into the HTML container
        svgContainer.setHTML(htmlBuilder.toSafeHtml());
    }

    // --------------------------------------------------------------------------------

    public interface Binder extends UiBinder<Widget, FloorMapCanvasViewImpl> {

    }
}
