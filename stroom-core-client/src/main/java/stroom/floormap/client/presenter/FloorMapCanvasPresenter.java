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

import stroom.floormap.client.event.MapObjectMovedEvent;
import stroom.floormap.client.event.MapObjectSelectedEvent;
import stroom.floormap.client.presenter.FloorMapCanvasPresenter.FloorMapCanvasView;
import stroom.floormap.shared.FloorMapObject;
import stroom.floormap.shared.FloorMapTransformationMatrix;

import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.EventTarget;
import com.google.gwt.event.dom.client.HasMouseDownHandlers;
import com.google.gwt.event.dom.client.HasMouseMoveHandlers;
import com.google.gwt.event.dom.client.HasMouseUpHandlers;
import com.google.gwt.event.dom.client.HasMouseWheelHandlers;
import com.google.gwt.user.client.ui.RequiresResize;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

public class FloorMapCanvasPresenter extends MyPresenterWidget<FloorMapCanvasView> {

    // Zoom and pan state
    private double scale = 1.0;
    private double offsetX = 0;
    private double offsetY = 0;
    private String backgroundImage;
    private FloorMapTransformationMatrix matrix;

    // Dragging state
    private boolean isDraggingEnabled = false;
    private boolean isDragging = false;
    private double lastMouseX;
    private double lastMouseY;

    // Objects on the map
    private List<FloorMapObject> objects = new ArrayList<>();

    // Edit mode
    private boolean editMode = false;
    private String selectedObjectId = null;
    private String activelyEditedObjectId = null;

    @Inject
    public FloorMapCanvasPresenter(final EventBus eventBus,
                                   final FloorMapCanvasView view) {
        super(eventBus, view);
    }

    @Override
    protected void onBind() {
        super.onBind();
        handleMouseEvents();

        if (getView() != null) {
            getView().onResize();
        }

        // Perform initial draw
        redraw();
    }

    private void handleMouseEvents() {

        // Check if we clicked on an object in edit mode
        registerHandler(getView().getFocusPanel().addMouseDownHandler(event -> {
            // Check if we clicked an object while in edit mode
            if (editMode) {
                final EventTarget target = event.getNativeEvent().getEventTarget();

                if (Element.is(target)) {
                    final Element element = Element.as(target);
                    final String id = element.getId();

                    // Check if we clicked on an actual map object shape (which does not start with "obj-")
                    if (id != null && !id.isEmpty() && !id.startsWith("obj-")) {
                        // Find the object to verify its type
                        FloorMapObject clickedObj = null;
                        for (final FloorMapObject obj : objects) {
                            if (obj.getId().equals(id)) {
                                clickedObj = obj;
                                break;
                            }
                        }

                        // Do not allow selecting or dragging people/users
                        if (clickedObj != null && !"person".equalsIgnoreCase(clickedObj.getType())) {
                            selectedObjectId = id;

                            // Fire an event to tell the parent presenter to show the edit menu
                            MapObjectSelectedEvent.fire(this, selectedObjectId);
                            isDragging = true;
                            lastMouseX = event.getX();
                            lastMouseY = event.getY();

                            // Stop panning
                            return;
                        }
                    }
                }

                // Clicked on background/empty space, clear selection and allow panning
                selectedObjectId = null;
            }

            // Normal panning logic
            isDragging = true;
            lastMouseX = event.getX();
            lastMouseY = event.getY();
        }));

        registerHandler(getView().getMouseMoveHandlers().addMouseMoveHandler(event -> {
            if (isDragging) {
                final double deltaX = event.getX() - lastMouseX;
                final double deltaY = event.getY() - lastMouseY;

                if (editMode && isDraggingEnabled && selectedObjectId != null && selectedObjectId.equals(activelyEditedObjectId)) {
                    // Move the selected object
                    for (final FloorMapObject obj : objects) {
                        if (obj.getId().equals(selectedObjectId)) {
                            // Adjust for scale
                            obj.setX(obj.getX() + (deltaX / scale));
                            obj.setY(obj.getY() + (deltaY / scale));
                            break;
                        }
                    }

                    redraw();
                } else {
                    // Pan the map
                    offsetX += deltaX;
                    offsetY += deltaY;
                    redraw();
                }

                lastMouseX = event.getX();
                lastMouseY = event.getY();
            }
        }));

        registerHandler(getView().getMouseUpHandlers().addMouseUpHandler(event -> {
            if (isDragging && editMode && selectedObjectId != null) {
                // Find the object's current coordinates
                for (final FloorMapObject obj : objects) {
                    if (obj.getId().equals(selectedObjectId)) {
                        MapObjectMovedEvent.fire(this, selectedObjectId, obj.getX(), obj.getY());
                        break;
                    }
                }
            }

            isDragging = false;
        }));

        // Mouse Wheel (Zoom toward cursor)
        registerHandler(getView().getMouseWheelHandlers().addMouseWheelHandler(event -> {
            event.preventDefault();

            double zoomFactor = 1.1;
            if (event.getNativeDeltaY() > 0) {
                zoomFactor = 1 / zoomFactor; // Zoom out
            }

            final double mouseX = event.getX();
            final double mouseY = event.getY();

            // Coordinate shift to ensure we zoom toward the mouse pointer
            offsetX = mouseX - (mouseX - offsetX) * zoomFactor;
            offsetY = mouseY - (mouseY - offsetY) * zoomFactor;
            scale *= zoomFactor;

            redraw();
        }));
    }

    private void redraw() {
        getView().draw(scale, offsetX, offsetY, backgroundImage, matrix, objects);
    }

    public void setMatrix(final FloorMapTransformationMatrix matrix) {
        this.matrix = matrix;
        redraw();
    }

    /**
     * Updates the background image for the SVG map.
     *
     * @param backgroundImage Base64 data URL or external URL.
     */
    public void setBackgroundImage(final String backgroundImage) {
        this.backgroundImage = backgroundImage;
        redraw();
    }

    public void setObjects(final List<FloorMapObject> objects) {
        this.objects = objects;
        redraw();
    }

    public void setEditMode(final boolean editMode) {
        this.editMode = editMode;
        if (!editMode) {
            selectedObjectId = null;
            activelyEditedObjectId = null;
        }

        isDraggingEnabled = false;
        redraw();
    }

    public void setActivelyEditedObjectId(final String activelyEditedObjectId) {
        this.activelyEditedObjectId = activelyEditedObjectId;
    }

    public void setIsDraggingEnabled(final boolean isDraggingEnabled) {
        this.isDraggingEnabled = isDraggingEnabled;
    }

    public interface FloorMapCanvasView extends View, RequiresResize {

        HasMouseDownHandlers getFocusPanel();

        HasMouseMoveHandlers getMouseMoveHandlers();

        HasMouseUpHandlers getMouseUpHandlers();

        HasMouseWheelHandlers getMouseWheelHandlers();

        void draw(double scale, double x, double y, String backgroundImage, FloorMapTransformationMatrix matrix, List<FloorMapObject> objects);
    }

}
