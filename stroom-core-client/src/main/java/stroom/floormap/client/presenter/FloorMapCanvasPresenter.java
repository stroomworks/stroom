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

package stroom.floormap.client.presenter;

import stroom.floormap.client.presenter.FloorMapCanvasPresenter.FloorMapCanvasView;

import com.google.gwt.event.dom.client.HasMouseDownHandlers;
import com.google.gwt.event.dom.client.HasMouseMoveHandlers;
import com.google.gwt.event.dom.client.HasMouseUpHandlers;
import com.google.gwt.event.dom.client.HasMouseWheelHandlers;
import com.google.gwt.user.client.ui.RequiresResize;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import javax.inject.Inject;

public class FloorMapCanvasPresenter extends MyPresenterWidget<FloorMapCanvasView> {

    private double scale = 1.0;
    private double offsetX = 0;
    private double offsetY = 0;

    private boolean isDragging = false;
    private double lastMouseX;
    private double lastMouseY;

    @Inject
    public FloorMapCanvasPresenter(final EventBus eventBus,
                                   final FloorMapCanvasView view) {
        super(eventBus, view);
    }

    @Override
    protected void onBind() {
        super.onBind();

        // Mouse Down (Start Panning)
        registerHandler(getView().getFocusPanel().addMouseDownHandler(event -> {
            isDragging = true;
            lastMouseX = event.getX();
            lastMouseY = event.getY();
        }));

        // Mouse Move (Dragging)
        registerHandler(getView().getMouseMoveHandlers().addMouseMoveHandler(event -> {
            if (isDragging) {
                final double deltaX = event.getX() - lastMouseX;
                final double deltaY = event.getY() - lastMouseY;

                offsetX += deltaX;
                offsetY += deltaY;

                lastMouseX = event.getX();
                lastMouseY = event.getY();

                redraw();
            }
        }));

        // Mouse Up (Stop Panning)
        registerHandler(getView().getMouseUpHandlers().addMouseUpHandler(event -> {
            isDragging = false;
        }));

        // Mouse Wheel (Zooming)
        registerHandler(getView().getMouseWheelHandlers().addMouseWheelHandler(event -> {
            event.preventDefault();

            double zoomFactor = 1.1;
            if (event.getNativeDeltaY() > 0) {
                zoomFactor = 1 / zoomFactor; // Zoom out
            }

            final double mouseX = event.getX();
            final double mouseY = event.getY();

            // Zoom towards the cursor
            offsetX = mouseX - (mouseX - offsetX) * zoomFactor;
            offsetY = mouseY - (mouseY - offsetY) * zoomFactor;
            scale *= zoomFactor;

            redraw();
        }));

        if (getView() != null) {
            getView().onResize();
        }
    }

    private void redraw() {
        getView().draw(scale, offsetX, offsetY);
    }

    public interface FloorMapCanvasView extends View, RequiresResize {
        HasMouseDownHandlers getFocusPanel();
        HasMouseMoveHandlers getMouseMoveHandlers();
        HasMouseUpHandlers getMouseUpHandlers();
        HasMouseWheelHandlers getMouseWheelHandlers();

        void draw(double scale, double x, double y);
    }

}
