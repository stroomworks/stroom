/*
 * Copyright 2016-2025 Crown Copyright
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

import com.google.gwt.canvas.client.Canvas;
import com.google.gwt.canvas.dom.client.Context2d;
import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.HasMouseDownHandlers;
import com.google.gwt.event.dom.client.HasMouseMoveHandlers;
import com.google.gwt.event.dom.client.HasMouseUpHandlers;
import com.google.gwt.event.dom.client.HasMouseWheelHandlers;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiFactory;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FocusPanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

public class FloorMapCanvasViewImpl
        extends ViewWithUiHandlers<DirtyUiHandlers>
        implements FloorMapCanvasView, ReadOnlyChangeHandler {

    private final Widget widget;

    @UiField
    Canvas canvas;

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
        // Get the canvas' parent element from the DOM and its width/height.
        final Element parent = canvas.getElement().getParentElement();
        final int width = parent.getOffsetWidth();
        final int height = parent.getOffsetHeight();

        // Defer the drawing logic if the parent hasn't been rendered yet.
        if (width <= 0 || height <= 0) {
            Scheduler.get().scheduleDeferred((this::onResize));
        }

        GWT.log("FloorMapCanvas.onResize(): " + width + " x " + height);

        // Set the canvas' internal drawing resolution to the parent values to prevent blurring.
        canvas.setCoordinateSpaceWidth(width);
        canvas.setCoordinateSpaceHeight(height);

        // Drawing logic - white background and orange border.
        final Context2d context2d = canvas.getContext2d();
        context2d.clearRect(0, 0, width, height);

        context2d.setFillStyle("#FFFFFF");
        context2d.fillRect(0, 0, width, height);
    }

    @UiFactory
    public Canvas createCanvas() {
        return Canvas.createIfSupported();
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
    public void draw(final double scale, final double x, final double y) {
        final Context2d context2d = canvas.getContext2d();
        final int width = canvas.getCoordinateSpaceWidth();
        final int height = canvas.getCoordinateSpaceHeight();

        // Clear the canvas
        context2d.clearRect(0, 0, width, height);

        // Save the normal state (no zoom/pan)
        context2d.save();

        // Apply the translation
        context2d.translate(x, y);
        context2d.scale(scale, scale);

        // Draw the map
        context2d.setFillStyle("#FFFFFF");
        context2d.fillRect(0, 0, 1000, 1000);

        // Restore back to normal for next frame
        context2d.restore();
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, FloorMapCanvasViewImpl> {

    }
}
