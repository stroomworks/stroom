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

    // Objects on the map — kept in two separate lists so facts and events never overwrite each other.
    private List<FloorMapObject> factObjects = new ArrayList<>();
    private List<FloorMapObject> eventObjects = new ArrayList<>();

    // Edit mode
    private boolean editMode = false;
    private String selectedObjectId = null;

    private DragHandler dragHandler;

    public void setDragHandler(final DragHandler dragHandler) {
        this.dragHandler = dragHandler;
    }

    public interface DragHandler {
        void onDrag(String objectId, double x, double y, FloorMapTransformationMatrix bgMatrix);
    }

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
            getView().setRedrawListener(this::redraw);
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
                        // If Ctrl or Shift is pressed and it is the background, allow panning instead of background drag
                        if (!("background".equals(id) && (event.getNativeEvent().getCtrlKey() || event.getNativeEvent().getShiftKey()))) {
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

                if (editMode && isDraggingEnabled && selectedObjectId != null) {
                    if ("background".equals(selectedObjectId)) {
                        // Dragging the background (updates the background's tm-map-to-screen matrix)
                        final double deltaUnzoomedX = deltaX / scale;
                        final double deltaUnzoomedY = deltaY / scale;
                        if (matrix != null) {
                            matrix = new FloorMapTransformationMatrix(
                                    matrix.getA(), matrix.getB(),
                                    matrix.getC(), matrix.getD(),
                                    matrix.getE() + deltaUnzoomedX,
                                    matrix.getF() + deltaUnzoomedY
                            );
                        } else {
                            matrix = new FloorMapTransformationMatrix(1, 0, 0, 1, deltaUnzoomedX, deltaUnzoomedY);
                        }
                        if (dragHandler != null) {
                            dragHandler.onDrag("background", matrix.getE(), matrix.getF(), matrix);
                        }
                    } else {
                        // Move the selected object.
                        for (final FloorMapObject obj : factObjects) {
                            if (obj.getId().equals(selectedObjectId)) {
                                // Revert scale to get unzoomed screen delta
                                final double deltaUnzoomedX = deltaX / scale;
                                final double deltaUnzoomedY = deltaY / scale;

                                // Revert active background's M_map_to_screen matrix to get delta in map space
                                final FloorMapTransformationMatrix invBgMatrix = matrix != null ? matrix.inverse() : FloorMapTransformationMatrix.identity();
                                final double deltaMapX = invBgMatrix.getA() * deltaUnzoomedX + invBgMatrix.getC() * deltaUnzoomedY;
                                final double deltaMapY = invBgMatrix.getB() * deltaUnzoomedX + invBgMatrix.getD() * deltaUnzoomedY;

                                obj.setX(obj.getX() + deltaMapX);
                                obj.setY(obj.getY() + deltaMapY);
                                if (dragHandler != null) {
                                    dragHandler.onDrag(selectedObjectId, obj.getX(), obj.getY(), matrix);
                                }
                                break;
                            }
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
                if ("background".equals(selectedObjectId)) {
                    MapObjectMovedEvent.fire(this, "background", matrix.getE(), matrix.getF());
                } else {
                    // Find the object's current coordinates
                    for (final FloorMapObject obj : factObjects) {
                        if (obj.getId().equals(selectedObjectId)) {
                            MapObjectMovedEvent.fire(this, selectedObjectId, obj.getX(), obj.getY());
                            break;
                        }
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
        // Merge facts and events into a single list so both are always visible simultaneously.
        final List<FloorMapObject> combined = new ArrayList<>(factObjects);
        combined.addAll(eventObjects);
        getView().draw(scale, offsetX, offsetY, backgroundImage, matrix, combined, selectedObjectId);
    }

    public void setSelectedObjectId(final String selectedObjectId) {
        this.selectedObjectId = selectedObjectId;
        redraw();
    }

    public void setMatrix(final FloorMapTransformationMatrix matrix) {
        this.matrix = matrix;
        redraw();
    }

    public FloorMapTransformationMatrix getMatrix() {
        return matrix;
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

    /**
     * Sets the static floor-plan objects (facts query result).
     * These are gates, doors, desks etc. whose positions come from the facts store.
     */
    public void setFactObjects(final List<FloorMapObject> objects) {
        this.factObjects = objects != null ? objects : new ArrayList<>();
        redraw();
    }

    /**
     * Sets the event-driven entity overlays (events query result).
     * These are person/entity positions at the currently selected time.
     */
    public void setEventObjects(final List<FloorMapObject> objects) {
        this.eventObjects = objects != null ? objects : new ArrayList<>();
        redraw();
    }

    /**
     * Legacy convenience alias — routes to {@link #setFactObjects} so existing
     * edit-mode code paths (which only deal with facts) continue to work.
     */
    public void setObjects(final List<FloorMapObject> objects) {
        setFactObjects(objects);
    }

    public void setEditMode(final boolean editMode) {
        this.editMode = editMode;
        if (!editMode) {
            selectedObjectId = null;
        }

        isDraggingEnabled = false;
        redraw();
    }

    public void setIsDraggingEnabled(final boolean isDraggingEnabled) {
        this.isDraggingEnabled = isDraggingEnabled;
    }

    public interface FloorMapCanvasView extends View, RequiresResize {

        HasMouseDownHandlers getFocusPanel();

        HasMouseMoveHandlers getMouseMoveHandlers();

        HasMouseUpHandlers getMouseUpHandlers();

        HasMouseWheelHandlers getMouseWheelHandlers();

        void draw(double scale, double x, double y, String backgroundImage, FloorMapTransformationMatrix matrix, List<FloorMapObject> objects, String selectedObjectId);

        void setRedrawListener(Runnable redrawListener);
    }

}
