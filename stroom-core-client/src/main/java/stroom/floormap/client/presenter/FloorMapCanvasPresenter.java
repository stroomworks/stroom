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

import stroom.floormap.client.FloorMapJsonKeys;
import stroom.floormap.client.event.MapContextMenuEvent;
import stroom.floormap.client.event.MapObjectMovedEvent;
import stroom.floormap.client.event.MapObjectSelectedEvent;
import stroom.floormap.client.presenter.FloorMapCanvasPresenter.FloorMapCanvasView;
import stroom.floormap.shared.FloorMapObject;
import stroom.floormap.shared.FloorMapTransformationMatrix;

import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.EventTarget;
import com.google.gwt.event.dom.client.ContextMenuEvent;
import com.google.gwt.event.dom.client.HasMouseMoveHandlers;
import com.google.gwt.event.dom.client.HasMouseUpHandlers;
import com.google.gwt.event.dom.client.HasMouseWheelHandlers;
import com.google.gwt.user.client.ui.FocusPanel;
import com.google.gwt.user.client.ui.RequiresResize;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

public class FloorMapCanvasPresenter extends MyPresenterWidget<FloorMapCanvasView> {

    /**
     * Minimum zoom scale. At extreme zoom-out the grid decade selection and
     * SVG coordinate values lose precision. This limit (~1e-12) provides
     * roughly 12 orders of magnitude of zoom-out from the default — far
     * beyond any practical use.
     */
    private static final double MIN_SCALE = 1e-12;

    /**
     * Maximum zoom scale. At extreme zoom-in the same precision issues
     * apply. This limit (~1e12) provides roughly 12 orders of magnitude
     * of zoom-in from the default.
     */
    private static final double MAX_SCALE = 1e12;

    // Zoom and pan state
    private double scale = 1.0;
    private double offsetX = 0;
    private double offsetY = 0;
    private String backgroundImage;
    private FloorMapTransformationMatrix matrix;

    // Dragging state
    private boolean isDraggingEnabled = false;
    private boolean isDragging = false;
    /** True only if the mouse actually moved while dragging an object (distinguishes click-to-select from drag). */
    private boolean hasMoved = false;
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

        // Check if we clicked on an object in edit mode.
        // Only react to the primary (left) mouse button — right-click is
        // handled by the contextmenu event handler below.
        registerHandler(getView().getFocusPanel().addMouseDownHandler(event -> {
            // Ignore right-click (button 2 in the W3C DOM spec).
            // Without this guard the mousedown sets isDragging = true, but
            // the subsequent mouseup lands on the context menu popup (outside
            // the canvas), leaving isDragging permanently stuck.
            if (event.getNativeEvent().getButton() == 2) {
                return;
            }

            // Check if we clicked an object while in edit mode
            if (editMode) {
                final EventTarget target = event.getNativeEvent().getEventTarget();

                if (Element.is(target)) {
                    final Element element = Element.as(target);
                    final String id = element.getId();

                    // Check if we clicked on an actual map object shape
                    // (whose ID does NOT start with the SVG group prefix)
                    if (id != null && !id.isEmpty()
                            && !id.startsWith(FloorMapJsonKeys.SVG_GROUP_PREFIX)) {
                        // If Ctrl or Shift is pressed and it is the background, allow panning
                        if (!(FloorMapJsonKeys.BACKGROUND.equals(id)
                                && (event.getNativeEvent().getCtrlKey()
                                || event.getNativeEvent().getShiftKey()))) {
                            selectedObjectId = id;

                            // Fire an event to tell the parent presenter to show the edit menu
                            MapObjectSelectedEvent.fire(this, selectedObjectId);
                            isDragging = true;
                            hasMoved = false;
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
            // Guard: if no mouse button is actually pressed, cancel any
            // stale drag state. This catches the case where mousedown fired
            // on the canvas but mouseup landed outside (on a toolbar button
            // or dialog), so the canvas never received the mouseup event.
            // The DOM 'buttons' property returns a bitmask of currently held
            // buttons (W3C spec); 0 means nothing is pressed.
            if (isDragging && nativeButtons(event.getNativeEvent()) == 0) {
                isDragging = false;
                hasMoved = false;
                return;
            }

            if (isDragging) {
                final double deltaX = event.getX() - lastMouseX;
                final double deltaY = event.getY() - lastMouseY;

                if (editMode && isDraggingEnabled && selectedObjectId != null) {
                    if (FloorMapJsonKeys.BACKGROUND.equals(selectedObjectId)) {
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
                        hasMoved = true;
                        if (dragHandler != null) {
                            dragHandler.onDrag(FloorMapJsonKeys.BACKGROUND, matrix.getE(), matrix.getF(), matrix);
                        }
                    } else {
                        // Move the selected object.
                        for (final FloorMapObject obj : factObjects) {
                            if (obj.getId().equals(selectedObjectId)) {
                                // Revert scale to get unzoomed screen delta
                                final double deltaUnzoomedX = deltaX / scale;
                                final double deltaUnzoomedY = deltaY / scale;

                                // Revert active background's M_map_to_screen matrix to get delta in map space
                                final FloorMapTransformationMatrix invBgMatrix = matrix != null
                                        ? matrix.inverse()
                                        : FloorMapTransformationMatrix.identity();
                                final double deltaMapX =
                                        invBgMatrix.getA() * deltaUnzoomedX + invBgMatrix.getC() * deltaUnzoomedY;
                                final double deltaMapY =
                                        invBgMatrix.getB() * deltaUnzoomedX + invBgMatrix.getD() * deltaUnzoomedY;

                                obj.setX(obj.getX() + deltaMapX);
                                obj.setY(obj.getY() + deltaMapY);
                                hasMoved = true;
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

        //noinspection unused event
        registerHandler(getView().getMouseUpHandlers().addMouseUpHandler(event -> {
            // Only fire a move event when the object was actually dragged, not just clicked.
            if (isDragging && hasMoved && editMode && selectedObjectId != null) {
                if (FloorMapJsonKeys.BACKGROUND.equals(selectedObjectId)) {
                    MapObjectMovedEvent.fire(this, FloorMapJsonKeys.BACKGROUND, matrix.getE(), matrix.getF());
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
            hasMoved = false;
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

            // Clamp to prevent floating-point precision breakdown at
            // extreme zoom levels.
            scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));

            redraw();
        }));

        // Right-click context menu — suppress the browser default and fire a MapContextMenuEvent.
        // Only fires in edit mode; in read-only (Map tab) mode the browser default is allowed.
        registerHandler(getView().getFocusPanel().addDomHandler(event -> {
            if (!editMode) {
                return;
            }
            event.preventDefault();
            event.stopPropagation();

            // Reset any drag state that may have leaked from a preceding
            // mousedown (e.g. if the mouseup landed on a dialog or toolbar
            // outside the canvas).
            isDragging = false;
            hasMoved = false;

            final int clientX = event.getNativeEvent().getClientX();
            final int clientY = event.getNativeEvent().getClientY();

            // Convert viewport-relative client coordinates to element-relative
            // coordinates, matching the coordinate space used by event.getX()/getY()
            // and the zoom/pan model (offsetX, offsetY, scale).
            final Element panelElement = getView().getFocusPanel().getElement();
            final double elementX = clientX - panelElement.getAbsoluteLeft();
            final double elementY = clientY - panelElement.getAbsoluteTop();

            // Determine whether an object was right-clicked
            String objectId = null;
            final EventTarget target = event.getNativeEvent().getEventTarget();
            if (Element.is(target)) {
                final Element element = Element.as(target);
                final String id = element.getId();
                if (id != null && !id.isEmpty()
                        && !id.startsWith(FloorMapJsonKeys.SVG_GROUP_PREFIX)) {
                    objectId = id;
                }
            }

            // Convert element-relative position to map-space coordinates
            final double[] mapCoords = screenToMapCoords(elementX, elementY);

            MapContextMenuEvent.fire(this, objectId, mapCoords[0], mapCoords[1], clientX, clientY);
        }, ContextMenuEvent.getType()));
    }

    /**
     * Converts element-relative coordinates to map-space coordinates by
     * reversing the zoom/pan transform and then applying the inverse of
     * the background transformation matrix.
     *
     * <p>The input coordinates should be relative to the FocusPanel element
     * (matching the coordinate space of {@code MouseEvent.getX()/getY()}
     * and the zoom/pan model's {@code offsetX}/{@code offsetY}).
     * Viewport-relative client coordinates must be converted first by
     * subtracting the element's absolute position.</p>
     *
     * @param screenX the X coordinate relative to the FocusPanel element
     * @param screenY the Y coordinate relative to the FocusPanel element
     * @return a two-element array {@code {mapX, mapY}} in map space
     */
    private double[] screenToMapCoords(final double screenX, final double screenY) {
        // Step 1: Remove the zoom/pan offset and scale
        final double unzoomedX = (screenX - offsetX) / scale;
        final double unzoomedY = (screenY - offsetY) / scale;

        // Step 2: Apply the inverse of the background matrix
        final FloorMapTransformationMatrix invMatrix = matrix != null
                ? matrix.inverse()
                : FloorMapTransformationMatrix.identity();
        final double mapX = invMatrix.getA() * unzoomedX + invMatrix.getC() * unzoomedY + invMatrix.getE();
        final double mapY = invMatrix.getB() * unzoomedX + invMatrix.getD() * unzoomedY + invMatrix.getF();

        return new double[]{mapX, mapY};
    }

    /**
     * Returns the W3C DOM {@code buttons} property from a native mouse event.
     *
     * <p>GWT's {@code NativeEvent} doesn't expose {@code getButtons()}, so we
     * access it via JSNI. The {@code buttons} property is a bitmask of
     * currently pressed buttons (1 = primary, 2 = secondary, 4 = auxiliary).
     * Returns {@code 0} when no button is pressed.</p>
     *
     * @param event the native event to query
     * @return the {@code buttons} bitmask, or 0 if unsupported
     */
    private static native int nativeButtons(com.google.gwt.dom.client.NativeEvent event) /*-{
        return event.buttons || 0;
    }-*/;

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
     * Returns the map-space coordinates of the centre of the currently
     * visible canvas area.
     *
     * <p>This is useful for placing new objects at "the middle of what the
     * user can see" when no specific click position is available (e.g. when
     * using a toolbar Add button rather than a canvas right-click).</p>
     *
     * @return a two-element array {@code {mapX, mapY}} representing the
     *         visible centre in map space
     */
    public double[] getVisibleCentreMapCoords() {
        final Element panel = getView().getFocusPanel().getElement();
        final double centreX = panel.getOffsetWidth() / 2.0;
        final double centreY = panel.getOffsetHeight() / 2.0;
        return screenToMapCoords(centreX, centreY);
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

    /**
     * View contract for the floor map canvas.
     *
     * <p>The canvas is an SVG-based rendering surface that displays a
     * background image (the floor plan), overlaid with draggable map objects
     * (gates, doors, cameras, people, etc.). It supports zoom, pan, object
     * selection, and right-click context menus.</p>
     *
     * <p>The view is responsible for rendering; all interaction logic
     * (drag handling, selection, coordinate transforms) lives in
     * {@link FloorMapCanvasPresenter}.</p>
     */
    public interface FloorMapCanvasView extends View, RequiresResize {

        /**
         * Returns the {@link FocusPanel} that wraps the SVG canvas.
         *
         * <p>The presenter registers mouse and context-menu handlers on this
         * panel. {@code FocusPanel} is returned (rather than a narrower
         * {@code Has*Handlers} type) so that the presenter can also attach
         * DOM-level handlers via {@code addDomHandler} (e.g. for the native
         * {@code contextmenu} event).</p>
         *
         * @return the focus panel containing the SVG canvas
         */
        FocusPanel getFocusPanel();

        /**
         * Returns the handler source for mouse-move events on the canvas.
         *
         * @return the mouse-move handler source
         */
        HasMouseMoveHandlers getMouseMoveHandlers();

        /**
         * Returns the handler source for mouse-up events on the canvas.
         *
         * @return the mouse-up handler source
         */
        HasMouseUpHandlers getMouseUpHandlers();

        /**
         * Returns the handler source for mouse-wheel events on the canvas.
         *
         * @return the mouse-wheel handler source
         */
        HasMouseWheelHandlers getMouseWheelHandlers();

        /**
         * Renders the complete SVG canvas contents.
         *
         * <p>This is called on every state change (zoom, pan, object move,
         * selection change, data load) and rebuilds the entire SVG DOM.
         * The rendering layers are, from back to front:</p>
         * <ol>
         *   <li>Adaptive grid background (when no image is set)</li>
         *   <li>Background floor-plan image (transformed by {@code matrix})</li>
         *   <li>Map objects (gates, doors, people, etc.)</li>
         * </ol>
         *
         * @param scale           the current zoom scale factor
         * @param x               the current pan offset X (pixels)
         * @param y               the current pan offset Y (pixels)
         * @param backgroundImage the background image URL or data-URI,
         *                        or {@code null} for grid-only mode
         * @param matrix          the map-to-screen transformation matrix,
         *                        or {@code null} for identity
         * @param objects         the list of map objects to render
         * @param selectedObjectId the ID of the currently selected object,
         *                         or {@code null} if nothing is selected
         */
        void draw(double scale, double x, double y, String backgroundImage,
                FloorMapTransformationMatrix matrix, List<FloorMapObject> objects,
                String selectedObjectId);

        /**
         * Registers a listener that is called whenever the view needs to
         * trigger a redraw from outside the normal presenter flow (e.g.
         * after an asynchronous image aspect-ratio calculation completes).
         *
         * @param redrawListener the callback to invoke, typically
         *                       {@code FloorMapCanvasPresenter::redraw}
         */
        void setRedrawListener(Runnable redrawListener);
    }

}
