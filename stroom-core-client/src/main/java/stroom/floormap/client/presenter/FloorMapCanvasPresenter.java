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

import stroom.floormap.client.event.MapContextMenuEvent;
import stroom.floormap.client.event.MapObjectSelectedEvent;
import stroom.floormap.client.presenter.FloorMapCanvasPresenter.FloorMapCanvasView;
import stroom.floormap.client.view.FloorMapGrid;
import stroom.floormap.shared.Fact;
import stroom.floormap.shared.FloorMapEntityAnimator;
import stroom.floormap.shared.FloorMapJsonKeys;
import stroom.floormap.shared.FloorMapObject;
import stroom.floormap.shared.FloorMapTransformationMatrix;
import stroom.floormap.shared.FloorMapViewport;
import stroom.floormap.shared.FloorMapZOrder;
import stroom.floormap.shared.TypeStyle;

import com.google.gwt.animation.client.AnimationScheduler;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.EventTarget;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.dom.client.ContextMenuEvent;
import com.google.gwt.event.dom.client.HasMouseMoveHandlers;
import com.google.gwt.event.dom.client.HasMouseUpHandlers;
import com.google.gwt.event.dom.client.HasMouseWheelHandlers;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.user.client.ui.FocusPanel;
import com.google.gwt.user.client.ui.RequiresResize;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.inject.Inject;

/**
 * Presenter for the interactive SVG map canvas.
 *
 * <p>Manages zoom, pan, object selection and drag-move in edit mode, and
 * smooth entity-movement animations with fading trails during timeline
 * playback.  Static floor-plan content comes from the {@code facts} list
 * (rendered by type z-order); event-driven entities come from
 * {@code eventObjects}.  An entity that exists as both (its positions are
 * recorded in the facts store AND it streams events) renders once: the
 * animated event overlay suppresses its static fact twin.</p>
 *
 * <p>The canvas view renders the z-ordered facts plus the event draw list
 * produced by {@link #buildAnimatedDrawList(double)} via its
 * {@link FloorMapCanvasView#draw draw()} method.</p>
 */
public class FloorMapCanvasPresenter extends MyPresenterWidget<FloorMapCanvasView> {


    // -------------------------------------------------------------------------
    /**
     * Minimum/maximum zoom scale — the single source of truth lives on
     * {@link FloorMapViewport} (shared, unit-tested) so the clamp used here and
     * in the viewport maths cannot drift apart. Beyond these (~1e±12) the grid
     * decade selection and SVG coordinate values lose precision.
     */
    private static final double MIN_SCALE = FloorMapViewport.MIN_SCALE;
    private static final double MAX_SCALE = FloorMapViewport.MAX_SCALE;

    /** The zoom level a freshly opened map starts at (100 %). */
    private static final double DEFAULT_SCALE = 1.0;

    /**
     * The map→screen "background" matrix handed to {@link FloorMapViewport} for
     * projection: map space is Y-up, the SVG render pipeline is Y-down, so the
     * only fixed transform on top of pan/zoom is a Y flip. With this as the
     * viewport's background, {@code viewport.mapToScreen}/{@code screenToMap}
     * reproduce this presenter's projection exactly, so the (unit-tested)
     * viewport maths is the single source of the projection formula.
     */
    private static final FloorMapTransformationMatrix Y_FLIP =
            FloorMapTransformationMatrix.scale(1, -1);

    /**
     * How far the origin (0,0) is inset from the bottom-left corner in the
     * default view, expressed in major grid divisions. Half a division places
     * the axis indicator comfortably clear of the corner (e.g. the bottom-left
     * of the screen reads as (-50,-50) when the major division is 100).
     */
    private static final double ORIGIN_INSET_MAJOR_DIVISIONS = 0.5;

    /**
     * Fraction of the viewport kept as empty margin on each side when the
     * initial view is zoomed to fit all content.
     */
    private static final double FIT_MARGIN = 0.08;

    // Zoom and pan state
    private double scale = DEFAULT_SCALE;
    private double offsetX = 0;
    private double offsetY = 0;
    /**
     * Whether the initial view (zoom-to-fit, or the bottom-left origin fallback)
     * has been applied yet. Applied once, when the canvas first has both a real
     * size and — for the fit — content or an injected view; user pan/zoom
     * afterwards is left untouched.
     */
    private boolean initialViewApplied = false;

    /**
     * An initial view {@code {scale, offsetX, offsetY}} handed over from another
     * tab (Map → Editor) so the first frame matches exactly and nothing jumps.
     * {@code null} when this canvas must compute its own fit. Consulted once, on
     * the first {@link #maybeApplyInitialView()}.
     */
    private double[] injectedInitialView;

    /**
     * Notified once with this canvas's computed initial view
     * {@code {scale, offsetX, offsetY}} so another tab can reuse it. {@code null}
     * when nothing is listening.
     */
    private Consumer<double[]> initialViewListener;

    // Dragging state
    private DragHandler dragHandler;
    private GeometryHandler geometryHandler;
    private SelectionHandler selectionHandler;
    private boolean isDragging = false;
    /** True only if the mouse actually moved while dragging an object (distinguishes click-to-select from drag). */
    private boolean hasMoved = false;
    private double lastMouseX;
    private double lastMouseY;
    /** Accumulated drag delta in map space (Y-up) for the current drag gesture. */
    private double dragDxMap;
    private double dragDyMap;

    /** The kind of pointer gesture currently in progress. */
    private enum Gesture {
        NONE, PANNING, MOVING, MARQUEE, SCALING, ROTATING, DRAWING_AREA, MOVING_VERTEX
    }

    private Gesture gesture = Gesture.NONE;

    // Area vertex-edit state (valid while gesture == MOVING_VERTEX).
    /** Key of the area whose vertices are being edited. */
    private String editingAreaKey;
    /** The area's world-to-map at edit start (to map screen ↔ local frame). */
    private FloorMapTransformationMatrix editingWorldToMap;
    /** Working copy of the area's local-frame vertices during the edit. */
    private double[][] workingVertices;
    /** Index of the vertex being dragged, or -1. */
    private int editingVertexIndex = -1;
    /** True when the current vertex edit inserted a new vertex (persist even if not dragged). */
    private boolean vertexInserted;

    /** Rubber-band marquee corners in element-pixel space (valid while MARQUEE). */
    private double marqueeStartX;
    private double marqueeStartY;
    private double marqueeCurX;
    private double marqueeCurY;

    /** Minimum vertices needed to close an area polygon. */
    private static final int AREA_MIN_VERTICES = 3;
    /**
     * Screen-pixel radius around vertex 0 within which a click closes the
     * polygon, and the radius of the close-target ring the view draws. Public
     * so {@code FloorMapCanvasViewImpl} shares this single value (the hit test
     * and the drawn ring must match).
     */
    public static final double AREA_CLOSE_RADIUS_PX = 10;

    /**
     * Committed draft vertices for the in-progress DRAWING_AREA gesture, in
     * map space (so panning/zooming mid-draw doesn't shear the draft).
     */
    private final List<double[]> areaDraftMap = new ArrayList<>();
    /** Live cursor position in element pixels (valid while DRAWING_AREA). */
    private double areaCursorX;
    private double areaCursorY;
    private AreaHandler areaHandler;

    /**
     * On a plain press over the background fact or empty canvas, the fact key to
     * select if the press turns out to be a click rather than a pan
     * ({@code null} for empty canvas). Lets a click select the background image
     * while a drag still pans.
     */
    private String pendingClickSelectId;

    /**
     * The in-progress map-space transform for the current MOVING/SCALING/ROTATING
     * gesture (composed onto each selected fact for live preview and committed on
     * release). {@code null} when no transform gesture is active.
     */
    private FloorMapTransformationMatrix pendingTransform;
    /** Scale pivot in map space (opposite corner / edge midpoint), for SCALING. */
    private double gesturePivotX;
    private double gesturePivotY;
    /** The grabbed scale handle's map-space position at gesture start, for SCALING. */
    private double gestureRefX;
    private double gestureRefY;
    /** Rotation centre in map space, for ROTATING. */
    private double gestureCentreX;
    private double gestureCentreY;
    /** Pointer position in map space at gesture start, for ROTATING. */
    private double gestureStartMapX;
    private double gestureStartMapY;
    /** Smallest scale factor a handle drag may produce (avoids zero/flip/singular). */
    private static final double MIN_SCALE_FACTOR = 0.05;

    /**
     * Keys of facts that behave like the canvas background: a plain drag over
     * them pans (matching the read-only Map viewer) rather than moving them.
     * Recomputed on each {@link #setFacts(List)}.
     */
    private final Set<String> backgroundKeys = new HashSet<>();

    /**
     * Keys of area (polygon) facts. Their whole interior is clickable, so a
     * press on an <em>unselected</em> area gets the background treatment —
     * drag pans, plain click selects — otherwise a large area would make the
     * map impossible to pan. Once selected, a drag moves it like any object.
     * Recomputed on each {@link #setFacts(List)}.
     */
    private final Set<String> areaKeys = new HashSet<>();

    // Edit mode
    private boolean editMode = false;
    /**
     * Currently selected object ids, in selection order. Backed as a set so a
     * future rubber-band / modifier-key UI can select many; the current UI
     * selects exactly one (see {@link #setSelectedObjectId(String)}). The view
     * highlights every id in this set; a drag translates the whole selection.
     */
    private final Set<String> selectedObjectIds = new LinkedHashSet<>();

    /**
     * Whether the grid overlay is drawn. The grid is a non-interactive UI aid
     * (it visualises map space) and is independent of {@link #editMode} and of
     * whether a background image is present. The Editor tab enables it; other
     * tabs (e.g. the Map tab) can opt in via {@link #setShowGrid(boolean)}.
     */
    private boolean showGrid = false;

    /** Per-type presentation settings (z-order + default graphic); may be null. */
    private List<TypeStyle> typeStyles;

    /** The facts to render (backgrounds + static facts), from the parser. */
    private List<Fact> facts = new ArrayList<>();

    /** Types the Layers panel has hidden: not drawn and not hit-tested. */
    private final Set<String> hiddenTypes = new HashSet<>();
    /** Types the Layers panel has dimmed to 30% opacity. */
    private final Set<String> dimmedTypes = new HashSet<>();
    /** Types the Layers panel has locked (Editor): their items can't be moved. */
    private final Set<String> lockedTypes = new HashSet<>();
    /** Fact keys belonging to a locked type — recomputed whenever facts change. */
    private final Set<String> lockedKeys = new HashSet<>();

    // -------------------------------------------------------------------------
    // Entity tracking (Map tab)
    // -------------------------------------------------------------------------

    /**
     * How far (px) the mouse must move with the button down before the gesture
     * counts as a deliberate pan rather than click jitter. Without this, the
     * couple of pixels of movement inside an ordinary click would pause
     * following the instant it was enabled.
     */
    private static final double PAN_INTENT_THRESHOLD_PX = 4.0;

    /**
     * Below this outstanding follow correction (px) the camera snaps the
     * remainder instead of gliding, so a damped follow terminates rather than
     * trailing sub-pixel movements forever.
     */
    private static final double FOLLOW_SNAP_PX = 0.5;

    /** Id of the entity the camera is following, or {@code null} when not tracking. */
    private String trackedObjectId = null;

    /**
     * {@code true} after a deliberate manual pan while tracking — the highlight
     * stays but the camera stops following until the user re-selects (or
     * re-clicks) the tracked entity, which calls
     * {@link #setTrackedObjectId(String)} again and clears this flag.
     */
    private boolean followPaused = false;

    /**
     * When {@code true}, the next follow with a known position hard-centres the
     * tracked entity instead of dead-zone panning. Set on (re-)selection so
     * tracking gives immediate visible feedback even when the entity is
     * already somewhere on screen.
     */
    private boolean centreOnNextFollow = false;

    /** Manual pan distance accumulated since the last mousedown, in px. */
    private double manualPanPx = 0;

    // -------------------------------------------------------------------------
    // Playback / animation state
    // -------------------------------------------------------------------------

    /**
     * The entity animation data machine (interpolation, trails, teleport). This
     * presenter owns only the scheduler loop, camera-follow and drawing; all the
     * per-entity bookkeeping lives in the shared, unit-tested animator.
     */
    private final FloorMapEntityAnimator animator = new FloorMapEntityAnimator();

    /** {@code true} while the {@link #animationCallback} loop is scheduled. */
    private boolean animationLoopRunning = false;

    /**
     * The {@code AnimationScheduler} timestamp of the most recently executed animation
     * frame.  Used to compute the per-frame delta for advancing animation progress.
     * Reset to {@code 0} when the loop terminates or is cleared.
     */
    private double lastAnimationTimestamp = 0;

    // -------------------------------------------------------------------------

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
            getView().setRedrawListener(this::redraw);
            // Apply the bottom-left default view as soon as the canvas has a
            // real size. onResize() self-defers until layout completes, then
            // fires this back.
            getView().setResizeListener(this::maybeApplyInitialView);
            getView().onResize();
        }

        // Perform initial draw
        redraw();
    }

    /**
     * Applies the one-time initial view the first time it can, then leaves the
     * view alone. Ordering of layout vs. fact loading is not guaranteed, so this
     * is called from both the resize listener and {@link #setFacts}; it runs at
     * most once. Priority:
     * <ol>
     *   <li>An injected view from another tab (Map → Editor) — applied verbatim
     *       so the first frame matches and nothing jumps.</li>
     *   <li>Zoom-to-fit all content, when facts are present.</li>
     *   <li>The bottom-left origin fallback, when there is no content (does
     *       <em>not</em> lock in, so a later {@link #setFacts} can still fit).</li>
     * </ol>
     */
    private void maybeApplyInitialView() {
        if (initialViewApplied) {
            return;
        }
        final int height = getView().getFocusPanel().getElement().getOffsetHeight();
        if (height <= 0) {
            // Canvas not laid out yet — a later onResize will call back.
            return;
        }

        if (injectedInitialView != null) {
            scale = injectedInitialView[0];
            offsetX = injectedInitialView[1];
            offsetY = injectedInitialView[2];
            initialViewApplied = true;
            redraw();
            return;
        }

        final double[] bounds = getView().getContentMapBounds();
        if (bounds != null && applyFitView(bounds, height)) {
            initialViewApplied = true;
            if (initialViewListener != null) {
                initialViewListener.accept(new double[]{scale, offsetX, offsetY});
            }
            return;
        }

        // No content yet — show the bottom-left origin view but leave the gate
        // open so the first setFacts can still fit.
        applyOriginView(height);
    }

    /**
     * Sets {@code scale}/{@code offsetX}/{@code offsetY} so the given map-space
     * content bounds are centred and fill the viewport with a {@link #FIT_MARGIN}
     * border. A degenerate (zero-extent) axis keeps {@link #DEFAULT_SCALE} rather
     * than zooming to the clamp. Does not redraw on its own when it returns
     * {@code false}.
     *
     * @param b      the content bounds {@code {minX, minY, maxX, maxY}}
     * @param height the current viewport height (already known to be {@code > 0})
     * @return {@code true} if a view was applied
     */
    private boolean applyFitView(final double[] b, final int height) {
        final int width = getView().getFocusPanel().getElement().getOffsetWidth();
        if (width <= 0) {
            return false;
        }
        final double cw = b[2] - b[0];
        final double ch = b[3] - b[1];
        final double usableW = width * (1 - 2 * FIT_MARGIN);
        final double usableH = height * (1 - 2 * FIT_MARGIN);

        double fit = DEFAULT_SCALE;
        final double sx = cw > 1e-9 ? usableW / cw : Double.MAX_VALUE;
        final double sy = ch > 1e-9 ? usableH / ch : Double.MAX_VALUE;
        final double candidate = Math.min(sx, sy);
        if (candidate != Double.MAX_VALUE) {
            fit = candidate;
        }
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, fit));

        // Centre the content's map-space midpoint in the viewport. Screen Y grows
        // downward and screenY = offsetY - scale·mapY, so offsetY gets +scale·cy.
        final double cx = (b[0] + b[2]) / 2;
        final double cy = (b[1] + b[3]) / 2;
        offsetX = width / 2.0 - scale * cx;
        offsetY = height / 2.0 + scale * cy;
        redraw();
        return true;
    }

    /**
     * Positions the view so the map origin (0,0) sits near the bottom-left
     * corner, inset by {@link #ORIGIN_INSET_MAJOR_DIVISIONS} of a major grid
     * division at the default zoom. The empty-map fallback.
     *
     * @param height the current viewport height (already known to be {@code > 0})
     */
    private void applyOriginView(final int height) {
        // Half a major grid division, in screen pixels, at the default zoom.
        // The grid is drawn with an identity world-to-map matrix, so its
        // effective scale is simply the user zoom (DEFAULT_SCALE).
        final double insetPx = ORIGIN_INSET_MAJOR_DIVISIONS
                * FloorMapGrid.majorDivisionScreenPx(DEFAULT_SCALE);

        // The origin (0,0) renders at screen pixel (offsetX, offsetY). Inset it
        // from the left and up from the bottom (SVG Y grows downward), so the
        // bottom-left corner reads as (-inset, -inset) in map space.
        scale = DEFAULT_SCALE;
        offsetX = insetPx;
        offsetY = height - insetPx;
        redraw();
    }

    // =========================================================================
    // Public API — called by FloorMapMapPresenter
    // =========================================================================

    /**
     * Notifies the canvas that the timeline is playing or has been paused.
     * When transitioning to paused, any in-flight animations are allowed to
     * finish naturally (they will terminate on the next loop iteration).
     *
     * @param playing {@code true} when playback starts, {@code false} when it stops.
     */
    public void setPlaying(final boolean playing) {
        animator.setPlaying(playing);
    }

    /**
     * Discards all in-flight movement animations and trail data.  Call this
     * whenever the timeline time jumps non-continuously (scrub, step, loop-around,
     * stop-at-end) so stale animation state does not carry over.
     * <p>
     * Arms a teleport (via {@link FloorMapEntityAnimator#clear()}) so the
     * <em>next</em> {@link #setEventObjects} places entities at their new
     * positions instantly rather than animating from stale positions, even
     * during playback.
     */
    public void clearAnimationState() {
        animator.clear();
        // Deliberately do NOT force animationLoopRunning = false here. A frame
        // may already be scheduled; clearing the flag and then calling
        // ensureAnimationLoop() (e.g. the teleport path re-arming camera-follow)
        // would start a SECOND loop while the old frame is still pending,
        // double-driving trails/draws. With the data cleared above, any running
        // loop simply finds nothing to do and self-terminates on its next frame
        // (which also resets lastAnimationTimestamp); a stopped loop stays
        // stopped.
        redraw();
    }

    // =========================================================================
    // Mouse event handling
    // =========================================================================

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

            // Shift, Ctrl or Meta (⌘) all act as the multi-select modifier, so a
            // modifier-click toggles an object in/out of the selection — matching
            // the Fact List grid, whose native selection manager also treats
            // Ctrl/Meta as the toggle key.
            final boolean modifier = event.getNativeEvent().getShiftKey()
                    || event.getNativeEvent().getCtrlKey()
                    || event.getNativeEvent().getMetaKey();
            final String hitId = hitObjectId(event.getNativeEvent().getEventTarget());

            if (editMode) {
                // Area drawing is modal: every LEFT press is either a vertex
                // click or a pan, resolved on mouseup by the PANNING
                // click-vs-pan logic (a press-drag pans, so large rooms can be
                // traced). Other buttons (e.g. middle) are ignored so a
                // habitual middle-click can't commit a spurious vertex.
                if (gesture == Gesture.DRAWING_AREA) {
                    if (event.getNativeEvent().getButton() == NativeEvent.BUTTON_LEFT) {
                        isDragging = true;
                        manualPanPx = 0;
                        lastMouseX = event.getX();
                        lastMouseY = event.getY();
                    }
                    return;
                }

                // (1) A transform handle takes priority over object/background
                // hit-testing so a handle drag starts a scale/rotate gesture.
                // Point glyphs (no image, no area geometry) are fixed screen-size
                // and can't be scaled/rotated, so their handles are drawn greyed
                // and are inert — the press is consumed but starts no gesture.
                final String handle = handleRole(event.getNativeEvent().getEventTarget());
                if (handle != null && !selectedObjectIds.isEmpty()) {
                    if (selectionTransformable()) {
                        beginHandleGesture(handle, event.getX(), event.getY());
                    }
                    return;
                }

                // A real (draggable) object is any non-background, non-area
                // fact, OR a background/area fact that is already selected —
                // so an unselected background or area press still pans (their
                // clickable surface can cover most of the map), but once you
                // have selected one (by clicking it) you can drag it to move it.
                final boolean pansWhenUnselected = hitId != null
                        && (backgroundKeys.contains(hitId) || areaKeys.contains(hitId));
                final boolean onObject = hitId != null
                        && (!pansWhenUnselected || selectedObjectIds.contains(hitId));

                if (onObject) {
                    // A locked layer's items stay selectable but can't be moved:
                    // update the selection like a normal click, but do not begin
                    // a MOVING gesture.
                    if (lockedKeys.contains(hitId)) {
                        if (modifier) {
                            if (!selectedObjectIds.remove(hitId)) {
                                selectedObjectIds.add(hitId);
                            }
                        } else if (!selectedObjectIds.contains(hitId)) {
                            selectedObjectIds.clear();
                            selectedObjectIds.add(hitId);
                        }
                        fireSelectionChanged();
                        gesture = Gesture.NONE;
                        isDragging = false;
                        return;
                    }
                    if (modifier) {
                        // Shift/Ctrl-click toggles the object in the selection.
                        if (!selectedObjectIds.remove(hitId)) {
                            selectedObjectIds.add(hitId);
                        }
                        fireSelectionChanged();
                        gesture = Gesture.NONE;
                        isDragging = false;
                        return;
                    }
                    // Plain click: select just this object (unless it is already
                    // part of a multi-selection — then keep the group) and begin
                    // moving the whole selection.
                    if (!selectedObjectIds.contains(hitId)) {
                        selectedObjectIds.clear();
                        selectedObjectIds.add(hitId);
                        fireSelectionChanged();
                    }
                    gesture = Gesture.MOVING;
                    isDragging = true;
                    hasMoved = false;
                    dragDxMap = 0;
                    dragDyMap = 0;
                    lastMouseX = event.getX();
                    lastMouseY = event.getY();
                    return;
                }

                // Empty canvas or the background fact.
                if (modifier) {
                    // Shift/Ctrl-drag draws a rubber-band selection marquee.
                    gesture = Gesture.MARQUEE;
                    isDragging = true;
                    hasMoved = false;
                    marqueeStartX = marqueeCurX = event.getX();
                    marqueeStartY = marqueeCurY = event.getY();
                    return;
                }
                // Plain press: a drag pans (matching the read-only Map viewer); a
                // click (no drag) selects the background fact under the cursor, or
                // clears the selection on truly empty canvas. Resolved on mouseup
                // by whether the pointer actually panned, so the selection is left
                // intact during a pan.
                pendingClickSelectId = hitId;
                gesture = Gesture.PANNING;
                isDragging = true;
                manualPanPx = 0;
                lastMouseX = event.getX();
                lastMouseY = event.getY();
                return;
            }

            // Read-only (Map tab) mode: pressing an object shape announces it so
            // the parent presenter can select/track it (the parent filters to
            // its roster). Backgrounds and areas are excluded — their clickable
            // surface can cover most of the map, and this fires on mousedown,
            // so a press over them must stay a plain pan; they remain trackable
            // from the tracking panel. Empty-canvas presses also just pan.
            if (hitId != null
                    && !backgroundKeys.contains(hitId)
                    && !areaKeys.contains(hitId)) {
                MapObjectSelectedEvent.fire(this, hitId);
            }
            gesture = Gesture.PANNING;
            isDragging = true;
            manualPanPx = 0;
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
                if (gesture == Gesture.DRAWING_AREA) {
                    // Only the mid-draw pan is stale — the modal drawing gesture
                    // and its draft survive (no button is held between vertex
                    // clicks by design).
                    isDragging = false;
                    manualPanPx = 0;
                } else {
                    isDragging = false;
                    hasMoved = false;
                    // A vertex drag whose mouseup was lost off-canvas must clear
                    // its editing state too, else factsForDraw() keeps rendering
                    // the never-persisted working vertices forever.
                    if (gesture == Gesture.MOVING_VERTEX) {
                        clearVertexEditState();
                    }
                    gesture = Gesture.NONE;
                    pendingTransform = null;
                    return;
                }
            }

            // Track the live cursor for the area-draft rubber band (both while
            // hovering between vertex clicks and during a mid-draw pan).
            if (gesture == Gesture.DRAWING_AREA) {
                areaCursorX = event.getX();
                areaCursorY = event.getY();
                if (!isDragging) {
                    if (!areaDraftMap.isEmpty()) {
                        redraw();
                    }
                    return;
                }
            }

            if (isDragging) {
                final double deltaX = event.getX() - lastMouseX;
                final double deltaY = event.getY() - lastMouseY;

                switch (gesture) {
                    case MOVING:
                        // Accumulate the drag in map space (Y-up) for live feedback
                        // and a single transform applied to the selection on drop.
                        // The shared viewport converts the screen delta to a map
                        // delta (scale + Y flip) — one projection code path.
                        final double[] dMap = new FloorMapViewport(scale, offsetX, offsetY)
                                .dragItemMapDelta(Y_FLIP, deltaX, deltaY);
                        dragDxMap += dMap[0];
                        dragDyMap += dMap[1];
                        pendingTransform = FloorMapTransformationMatrix.translate(
                                dragDxMap, dragDyMap);
                        hasMoved = true;
                        redraw();
                        break;
                    case MARQUEE:
                        marqueeCurX = event.getX();
                        marqueeCurY = event.getY();
                        hasMoved = true;
                        redraw();
                        break;
                    case SCALING:
                        pendingTransform = computeScaleTransform(event.getX(), event.getY());
                        hasMoved = true;
                        redraw();
                        break;
                    case MOVING_VERTEX:
                        if (workingVertices != null && editingWorldToMap != null
                                && editingVertexIndex >= 0
                                && editingVertexIndex < workingVertices.length) {
                            final double[] map = screenToMapCoords(event.getX(), event.getY());
                            final double[] local = editingWorldToMap.inverse()
                                    .transformPoint(map[0], map[1]);
                            workingVertices[editingVertexIndex] = new double[]{local[0], local[1]};
                            hasMoved = true;
                            redraw();
                        }
                        break;
                    case ROTATING: {
                        final double[] cur = screenToMapCoords(event.getX(), event.getY());
                        final double a0 = Math.atan2(gestureStartMapY - gestureCentreY,
                                gestureStartMapX - gestureCentreX);
                        final double a1 = Math.atan2(cur[1] - gestureCentreY,
                                cur[0] - gestureCentreX);
                        double deg = Math.toDegrees(a1 - a0);
                        if (event.getNativeEvent().getShiftKey()) {
                            // Snap to 15° increments.
                            deg = Math.round(deg / 15.0) * 15.0;
                        }
                        pendingTransform = FloorMapTransformationMatrix.rotateAbout(
                                deg, gestureCentreX, gestureCentreY);
                        hasMoved = true;
                        redraw();
                        break;
                    }
                    case PANNING:
                    default:
                        // Pan the map. A deliberate manual pan takes the camera off
                        // the tracked entity, so pause following until it is
                        // re-selected — but ignore the few pixels of jitter inside
                        // an ordinary click, which would otherwise pause following
                        // the moment click-to-track enabled it.
                        manualPanPx += Math.abs(deltaX) + Math.abs(deltaY);
                        if (manualPanPx > PAN_INTENT_THRESHOLD_PX) {
                            followPaused = true;
                        }
                        offsetX += deltaX;
                        offsetY += deltaY;
                        redraw();
                        break;
                }

                lastMouseX = event.getX();
                lastMouseY = event.getY();
            }
        }));

        //noinspection unused event
        registerHandler(getView().getMouseUpHandlers().addMouseUpHandler(event -> {
            // Area drawing is modal: resolve this press as vertex-click vs pan
            // and keep the gesture alive (the generic reset below must not run).
            if (gesture == Gesture.DRAWING_AREA) {
                final boolean click = isDragging && manualPanPx <= PAN_INTENT_THRESHOLD_PX;
                isDragging = false;
                hasMoved = false;
                manualPanPx = 0;
                if (click) {
                    if (areaDraftMap.size() >= AREA_MIN_VERTICES
                            && isNearFirstDraftVertex(event.getX(), event.getY())) {
                        finishAreaDrawing();
                    } else {
                        areaDraftMap.add(screenToMapCoords(event.getX(), event.getY()));
                        redraw();
                    }
                }
                return;
            }

            final Gesture finished = gesture;
            final FloorMapTransformationMatrix transform = pendingTransform;
            final boolean moved = hasMoved;
            final double panned = manualPanPx;
            final String clickSelectId = pendingClickSelectId;

            dragDxMap = 0;
            dragDyMap = 0;
            isDragging = false;
            hasMoved = false;
            gesture = Gesture.NONE;
            pendingTransform = null;
            pendingClickSelectId = null;

            if (finished == Gesture.MOVING
                    || finished == Gesture.SCALING
                    || finished == Gesture.ROTATING) {
                // Persist the move/scale/rotate as a single map-space transform
                // of the whole selection.
                if (moved && transform != null && !selectedObjectIds.isEmpty()
                        && dragHandler != null) {
                    // Never transform items on a locked layer (covers the case
                    // where a layer was locked after its items were selected).
                    final List<String> movable = new ArrayList<>(selectedObjectIds.size());
                    for (final String id : selectedObjectIds) {
                        if (!lockedKeys.contains(id)) {
                            movable.add(id);
                        }
                    }
                    if (!movable.isEmpty()) {
                        dragHandler.onTransform(movable, transform);
                    }
                }
            } else if (finished == Gesture.MOVING_VERTEX) {
                // Persist the edited/inserted vertex (skip a pure click that
                // didn't move an existing vertex, and locked layers).
                if ((moved || vertexInserted) && editingAreaKey != null
                        && workingVertices != null && workingVertices.length >= AREA_MIN_VERTICES
                        && geometryHandler != null
                        && !lockedKeys.contains(editingAreaKey)) {
                    geometryHandler.onGeometryEdited(editingAreaKey, workingVertices);
                }
                clearVertexEditState();
            } else if (finished == Gesture.MARQUEE) {
                // Select every fact the rubber-band touched, adding to the
                // existing selection (the marquee is a Shift/Ctrl gesture).
                final double[] rect = {
                        Math.min(marqueeStartX, marqueeCurX),
                        Math.min(marqueeStartY, marqueeCurY),
                        Math.max(marqueeStartX, marqueeCurX),
                        Math.max(marqueeStartY, marqueeCurY)};
                // The marquee never selects items on a locked layer.
                for (final String id : getView().hitTestScreenRect(rect)) {
                    if (!lockedKeys.contains(id)) {
                        selectedObjectIds.add(id);
                    }
                }
                fireSelectionChanged();
                redraw();
            } else if (finished == Gesture.PANNING
                    && panned <= PAN_INTENT_THRESHOLD_PX) {
                // A press that didn't pan is a click: select the background fact
                // under the cursor, or clear the selection on empty canvas.
                if (clickSelectId != null) {
                    selectedObjectIds.clear();
                    selectedObjectIds.add(clickSelectId);
                    fireSelectionChanged();
                } else if (!selectedObjectIds.isEmpty()) {
                    selectedObjectIds.clear();
                    fireSelectionChanged();
                }
            }
        }));

        // Mouse Wheel (Zoom toward cursor)
        registerHandler(getView().getMouseWheelHandlers().addMouseWheelHandler(event -> {
            event.preventDefault();

            // Note: zooming deliberately does NOT pause following — zooming in
            // on a tracked entity is the natural way to watch it, and the
            // dead-zone follow simply keeps it in view at the new zoom level.
            // Delegate the zoom-toward-cursor + clamp maths to the shared,
            // unit-tested viewport, then read the updated pan/zoom back.
            final boolean zoomIn = event.getNativeDeltaY() <= 0;
            final FloorMapViewport vp = new FloorMapViewport(scale, offsetX, offsetY);
            vp.zoom(event.getX(), event.getY(), zoomIn);
            scale = vp.getScale();
            offsetX = vp.getOffsetX();
            offsetY = vp.getOffsetY();

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

            // While drawing an area, right-click undoes the last vertex
            // (misclicks are the dominant error when tracing) — or cancels an
            // empty draft. The context menu never opens mid-draw.
            if (gesture == Gesture.DRAWING_AREA) {
                isDragging = false;
                hasMoved = false;
                if (areaDraftMap.isEmpty()) {
                    cancelAreaDrawing();
                } else {
                    //noinspection SequencedCollectionMethodCanBeUsed
                    areaDraftMap.remove(areaDraftMap.size() - 1);
                    redraw();
                }
                return;
            }

            // Reset any drag state that may have leaked from a preceding
            // mousedown (e.g. if the mouseup landed on a dialog or toolbar
            // outside the canvas).
            isDragging = false;
            hasMoved = false;

            final int clientX = event.getNativeEvent().getClientX();
            final int clientY = event.getNativeEvent().getClientY();

            // Right-clicking an area vertex handle opens a vertex-specific menu
            // (offering "Delete Vertex") rather than the object/canvas menu.
            final String contextRole = handleRole(event.getNativeEvent().getEventTarget());
            if (contextRole != null && contextRole.startsWith("vertex-")) {
                final Fact area = selectedAreaFact();
                if (area != null) {
                    final Element panel = getView().getFocusPanel().getElement();
                    final double[] vertexMap = screenToMapCoords(
                            clientX - panel.getAbsoluteLeft(),
                            clientY - panel.getAbsoluteTop());
                    MapContextMenuEvent.fireVertex(this, area.getKey(),
                            vertexMap[0], vertexMap[1], clientX, clientY,
                            parseHandleIndex(contextRole, "vertex-"));
                }
                return;
            }

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
                        && !id.startsWith(FloorMapJsonKeys.SVG_GROUP_PREFIX)
                        && !id.startsWith(FloorMapJsonKeys.HANDLE_PREFIX)) {
                    // Selection handles (scale/rotate) carry a HANDLE_PREFIX id
                    // and are not objects — right-clicking one must not open an
                    // object menu targeting a nonexistent key.
                    objectId = id;
                }
            }

            // Convert element-relative position to map-space coordinates
            final double[] mapCoords = screenToMapCoords(elementX, elementY);

            MapContextMenuEvent.fire(this, objectId, mapCoords[0], mapCoords[1], clientX, clientY);
        }, ContextMenuEvent.getType()));

        // Escape clears the selection, so you can pan again after selecting the
        // full-canvas background (an unselected background press pans).
        registerHandler(getView().getFocusPanel().addKeyDownHandler(event -> {
            // While drawing an area: Escape discards the draft, Enter closes
            // the polygon. (No clash with Escape-clears-selection — starting a
            // draw clears the selection.)
            if (editMode && gesture == Gesture.DRAWING_AREA) {
                if (event.getNativeKeyCode() == KeyCodes.KEY_ESCAPE) {
                    cancelAreaDrawing();
                    return;
                }
                if (event.getNativeKeyCode() == KeyCodes.KEY_ENTER
                        && areaDraftMap.size() >= AREA_MIN_VERTICES) {
                    finishAreaDrawing();
                    return;
                }
            }
            if (editMode
                    && event.getNativeKeyCode() == KeyCodes.KEY_ESCAPE
                    && !selectedObjectIds.isEmpty()) {
                selectedObjectIds.clear();
                fireSelectionChanged();
            }
        }));

        // Double-click closes the in-progress area polygon. The dblclick's two
        // component clicks have already appended two near-coincident vertices,
        // so drop the duplicate before closing.
        registerHandler(getView().getFocusPanel().addDoubleClickHandler(event -> {
            if (gesture != Gesture.DRAWING_AREA) {
                return;
            }
            event.preventDefault();
            if (areaDraftMap.size() >= 2) {
                //noinspection SequencedCollectionMethodCanBeUsed
                final double[] last = mapToScreen(
                        areaDraftMap.get(areaDraftMap.size() - 1));
                final double[] prev = mapToScreen(
                        areaDraftMap.get(areaDraftMap.size() - 2));
                final double dx = last[0] - prev[0];
                final double dy = last[1] - prev[1];
                if (dx * dx + dy * dy
                        <= AREA_CLOSE_RADIUS_PX * AREA_CLOSE_RADIUS_PX) {
                    //noinspection SequencedCollectionMethodCanBeUsed
                    areaDraftMap.remove(areaDraftMap.size() - 1);
                }
            }
            if (areaDraftMap.size() >= AREA_MIN_VERTICES) {
                finishAreaDrawing();
            } else {
                redraw();
            }
        }));
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
        // Delegate to the shared, unit-tested viewport maths (Y_FLIP is the
        // Y-up→Y-down background); this is the inverse of the draw pipeline.
        return new FloorMapViewport(scale, offsetX, offsetY)
                .screenToMap(screenX, screenY, Y_FLIP);
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

    // =========================================================================
    // Drawing
    // =========================================================================

    private void redraw() {
        final List<FloorMapObject> overlay =
                buildAnimatedDrawList(/* nowMs — irrelevant when no animations */ 0.0);
        getView().draw(scale, offsetX, offsetY,
                FloorMapZOrder.sort(visibleFacts(factsExcludingOverlay(overlay)), typeStyles),
                visibleEvents(overlay), selectedObjectIds, typeStyles, showGrid, dimmedTypes,
                gesture == Gesture.MARQUEE ? currentMarqueeRect() : null,
                editMode && !selectedObjectIds.isEmpty() && gesture != Gesture.MARQUEE,
                selectionTransformable(),
                gesture == Gesture.DRAWING_AREA ? currentAreaDraftPx() : null);
    }

    /**
     * The current rubber-band rectangle in element-pixel space as
     * {@code {minX, minY, maxX, maxY}}. Only meaningful while a MARQUEE gesture
     * is in progress.
     */
    private double[] currentMarqueeRect() {
        return new double[]{
                Math.min(marqueeStartX, marqueeCurX),
                Math.min(marqueeStartY, marqueeCurY),
                Math.max(marqueeStartX, marqueeCurX),
                Math.max(marqueeStartY, marqueeCurY)};
    }

    /**
     * Resolves the id of the map object under an event target, or {@code null}
     * if the target is not a real object shape. Wrapper {@code <g>} groups
     * (prefixed {@link FloorMapJsonKeys#SVG_GROUP_PREFIX}) and transform handles
     * (prefixed {@link FloorMapJsonKeys#HANDLE_PREFIX}) are not objects.
     */
    private String hitObjectId(final EventTarget target) {
        if (Element.is(target)) {
            final String id = Element.as(target).getId();
            if (id != null && !id.isEmpty()
                    && !id.startsWith(FloorMapJsonKeys.SVG_GROUP_PREFIX)
                    && !id.startsWith(FloorMapJsonKeys.HANDLE_PREFIX)) {
                return id;
            }
        }
        return null;
    }

    /**
     * Returns the transform-handle role under an event target (the id suffix
     * after {@link FloorMapJsonKeys#HANDLE_PREFIX}, e.g. {@code "scale-nw"} or
     * {@code "rotate"}), or {@code null} if the target is not a handle.
     */
    private String handleRole(final EventTarget target) {
        if (Element.is(target)) {
            final String id = Element.as(target).getId();
            if (id != null && id.startsWith(FloorMapJsonKeys.HANDLE_PREFIX)) {
                return id.substring(FloorMapJsonKeys.HANDLE_PREFIX.length());
            }
        }
        return null;
    }

    /**
     * True if the current selection contains at least one fact that can be
     * meaningfully scaled or rotated — an image fact or an area (which has real
     * geometry). Bare point glyphs are drawn at a fixed screen size, so
     * transforming them has no visible effect; their handles are greyed and inert.
     */
    private boolean selectionTransformable() {
        for (final Fact fact : facts) {
            if (selectedObjectIds.contains(fact.getKey())
                    && (fact.hasImage() || fact.hasVertices())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Begins a scale or rotate gesture from the given handle, snapshotting the
     * pivot/centre (in map space) from the current selection frame.
     *
     * @param role the handle role ({@code "scale-*"} or {@code "rotate"})
     * @param px   the pointer X at gesture start (element pixels)
     * @param py   the pointer Y at gesture start (element pixels)
     */
    private void beginHandleGesture(final String role, final double px, final double py) {
        isDragging = true;
        hasMoved = false;
        pendingTransform = null;
        lastMouseX = px;
        lastMouseY = py;

        // Area vertex editing takes priority over the scale/rotate frame.
        if (role.startsWith("vertex-")) {
            beginVertexEdit(parseHandleIndex(role, "vertex-"), false);
            return;
        }
        if (role.startsWith("insert-")) {
            beginVertexEdit(parseHandleIndex(role, "insert-"), true);
            return;
        }

        final double[] frame = getView().getSelectionFrame();
        if (frame == null) {
            isDragging = false;
            gesture = Gesture.NONE;
            return;
        }
        final double minX = frame[0];
        final double minY = frame[1];
        final double maxX = frame[2];
        final double maxY = frame[3];
        final double cx = (minX + maxX) / 2;
        final double cy = (minY + maxY) / 2;

        if ("rotate".equals(role)) {
            gesture = Gesture.ROTATING;
            final double[] centre = screenToMapCoords(cx, cy);
            gestureCentreX = centre[0];
            gestureCentreY = centre[1];
            final double[] start = screenToMapCoords(px, py);
            gestureStartMapX = start[0];
            gestureStartMapY = start[1];
        } else {
            gesture = Gesture.SCALING;
            final String dir = role.startsWith("scale-")
                    ? role.substring("scale-".length())
                    : role;
            // Scale about the opposite corner/edge; the grabbed handle is the
            // moving reference point.
            final double[] pivotScreen = pointForDir(oppositeDir(dir), minX, minY, maxX, maxY, cx, cy);
            final double[] refScreen = pointForDir(dir, minX, minY, maxX, maxY, cx, cy);
            final double[] pivot = screenToMapCoords(pivotScreen[0], pivotScreen[1]);
            final double[] ref = screenToMapCoords(refScreen[0], refScreen[1]);
            gesturePivotX = pivot[0];
            gesturePivotY = pivot[1];
            gestureRefX = ref[0];
            gestureRefY = ref[1];
        }
    }

    /** Parses the integer index from a handle role like {@code "vertex-3"}. */
    private static int parseHandleIndex(final String role, final String prefix) {
        try {
            return Integer.parseInt(role.substring(prefix.length()));
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The single selected area fact (imageless, with vertices), or {@code null}
     * when the selection is empty, multiple, or not an area.
     */
    private Fact selectedAreaFact() {
        if (selectedObjectIds.size() != 1) {
            return null;
        }
        final String id = selectedObjectIds.iterator().next();
        for (final Fact fact : facts) {
            if (id.equals(fact.getKey()) && fact.hasVertices() && !fact.hasImage()) {
                return fact;
            }
        }
        return null;
    }

    /**
     * Begins a {@link Gesture#MOVING_VERTEX} edit. For {@code insert}, a new
     * vertex is spliced at the midpoint of edge {@code index} and immediately
     * dragged; otherwise the existing vertex {@code index} is dragged.
     */
    private void beginVertexEdit(final int index, final boolean insert) {
        final Fact area = selectedAreaFact();
        if (area == null || index < 0 || lockedKeys.contains(area.getKey())) {
            isDragging = false;
            gesture = Gesture.NONE;
            return;
        }
        final double[][] v = area.getVertices();
        editingAreaKey = area.getKey();
        editingWorldToMap = area.getWorldToMap();
        vertexInserted = insert;

        if (insert) {
            if (index >= v.length) {
                isDragging = false;
                gesture = Gesture.NONE;
                return;
            }
            final int n = v.length;
            final int j = (index + 1) % n;
            final double[] mid = {(v[index][0] + v[j][0]) / 2.0, (v[index][1] + v[j][1]) / 2.0};
            final double[][] nv = new double[n + 1][];
            for (int i = 0; i <= index; i++) {
                nv[i] = new double[]{v[i][0], v[i][1]};
            }
            nv[index + 1] = mid;
            for (int i = index + 1; i < n; i++) {
                nv[i + 1] = new double[]{v[i][0], v[i][1]};
            }
            workingVertices = nv;
            editingVertexIndex = index + 1;
        } else {
            if (index >= v.length) {
                isDragging = false;
                gesture = Gesture.NONE;
                return;
            }
            final double[][] nv = new double[v.length][];
            for (int i = 0; i < v.length; i++) {
                nv[i] = new double[]{v[i][0], v[i][1]};
            }
            workingVertices = nv;
            editingVertexIndex = index;
        }
        gesture = Gesture.MOVING_VERTEX;
        redraw();
    }

    /**
     * Deletes the vertex at {@code index} from the single selected area,
     * enforcing the 3-vertex minimum and skipping locked layers. Persists via
     * the geometry handler. No-op when there is no single-area selection.
     *
     * @param index the vertex index to delete
     */
    public void deleteVertex(final int index) {
        final Fact area = selectedAreaFact();
        if (area == null || geometryHandler == null || lockedKeys.contains(area.getKey())) {
            return;
        }
        final double[][] v = area.getVertices();
        if (index < 0 || index >= v.length || v.length <= AREA_MIN_VERTICES) {
            return;
        }
        final double[][] nv = new double[v.length - 1][];
        for (int i = 0, k = 0; i < v.length; i++) {
            if (i != index) {
                nv[k++] = new double[]{v[i][0], v[i][1]};
            }
        }
        geometryHandler.onGeometryEdited(area.getKey(), nv);
    }

    /**
     * Clears all transient vertex-editing state. Called on every path that ends
     * a {@code MOVING_VERTEX} gesture (normal mouseup and the lost-mouseup
     * recovery) so the {@link #factsForDraw()} live preview cannot outlive the
     * gesture.
     */
    private void clearVertexEditState() {
        editingAreaKey = null;
        editingWorldToMap = null;
        workingVertices = null;
        editingVertexIndex = -1;
        vertexInserted = false;
    }

    /**
     * Computes the map-space scale transform for the current SCALING gesture
     * given the current pointer position. Scaling is always uniform
     * (aspect-preserving): the factor is the projection of the pointer-from-pivot
     * vector onto the grabbed-corner-from-pivot vector. Uniform scale never
     * shears, so it is correct even for a rotated fact. The factor is clamped to
     * {@link #MIN_SCALE_FACTOR} to avoid zero/flip/singular.
     */
    private FloorMapTransformationMatrix computeScaleTransform(final double px, final double py) {
        final double[] cur = screenToMapCoords(px, py);
        final double dx0 = gestureRefX - gesturePivotX;
        final double dy0 = gestureRefY - gesturePivotY;
        final double denom = dx0 * dx0 + dy0 * dy0;
        final double s = denom > 1e-9
                ? ((cur[0] - gesturePivotX) * dx0 + (cur[1] - gesturePivotY) * dy0) / denom
                : 1;
        final double clamped = clampScale(s);
        return FloorMapTransformationMatrix.scaleAbout(
                clamped, clamped, gesturePivotX, gesturePivotY);
    }

    private static double clampScale(final double s) {
        return Math.max(s, MIN_SCALE_FACTOR);
    }

    /** Screen-space position of a corner frame handle by direction (nw/ne/se/sw). */
    private static double[] pointForDir(final String dir,
                                        final double minX, final double minY,
                                        final double maxX, final double maxY,
                                        final double cx, final double cy) {
        //noinspection EnhancedSwitchMigration
        switch (dir) {
            case "nw":
                return new double[]{minX, minY};
            case "ne":
                return new double[]{maxX, minY};
            case "se":
                return new double[]{maxX, maxY};
            case "sw":
                return new double[]{minX, maxY};
            default:
                return new double[]{cx, cy};
        }
    }

    private static String oppositeDir(final String dir) {
        //noinspection EnhancedSwitchMigration
        switch (dir) {
            case "nw":
                return "se";
            case "ne":
                return "sw";
            case "se":
                return "nw";
            case "sw":
                return "ne";
            default:
                return dir;
        }
    }

    /**
     * Filters the facts draw list down to those NOT currently owned by the
     * event overlay. An entity whose positions are recorded in the facts store
     * AND streamed as events would otherwise render twice — a static fact glyph
     * under the animated overlay glyph; the animated one wins.
     */
    private List<Fact> factsExcludingOverlay(final List<FloorMapObject> overlay) {
        final List<Fact> base = factsForDraw();
        if (overlay.isEmpty()) {
            return base;
        }
        final Set<String> overlayIds = new HashSet<>();
        for (final FloorMapObject obj : overlay) {
            overlayIds.add(obj.getId());
        }
        final List<Fact> out = new ArrayList<>(base.size());
        for (final Fact fact : base) {
            if (!overlayIds.contains(fact.getKey())) {
                out.add(fact);
            }
        }
        return out;
    }

    /**
     * Builds the event-overlay draw list, substituting animated entities at
     * their current interpolated positions and attaching trail data.
     *
     * @param nowMs Current wall-clock time in ms (used to compute trail alpha).
     *              Pass {@code 0.0} when there are no active animations.
     */
    private List<FloorMapObject> buildAnimatedDrawList(final double nowMs) {
        final List<FloorMapObject> combined = animator.buildDrawList(nowMs);

        // Decorate each entity with its image-bearing fact twin (if any) so the
        // view renders the entity's configured icon at the live position instead
        // of the generic type glyph. The static twin is suppressed by
        // factsExcludingOverlay, so without this the icon would vanish the moment
        // the entity appears in the events stream. This is a rendering concern
        // (it needs `facts`), so it stays in the presenter rather than the
        // shared animator. Set unconditionally (null clears).
        final Map<String, Fact> imageFactsByKey = new HashMap<>();
        for (final Fact fact : facts) {
            if (fact.hasImage()) {
                imageFactsByKey.put(fact.getKey(), fact);
            }
        }
        for (final FloorMapObject obj : combined) {
            obj.setImageFact(imageFactsByKey.get(obj.getId()));
        }
        return combined;
    }

    // =========================================================================
    // Animation loop
    // =========================================================================

    private final AnimationScheduler.AnimationCallback animationCallback =
            new AnimationScheduler.AnimationCallback() {
                @Override
                public void execute(final double timestamp) {
                    // Compute the time elapsed since the previous frame.  On the very first
                    // frame after the loop starts, lastAnimationTimestamp is 0 so we use a
                    // nominal 16 ms (one 60 fps frame) to avoid a stalled first step.
                    final double deltaMs = lastAnimationTimestamp > 0
                            ? timestamp - lastAnimationTimestamp
                            : 16.0;
                    lastAnimationTimestamp = timestamp;

                    // Advance entity animations + trail fades (shared animator),
                    // then glide the damped camera-follow (presenter-owned).
                    final boolean animActive = animator.advanceFrame(timestamp, deltaMs);
                    final boolean cameraMoved = followStep(deltaMs);

                    if (!cameraMoved && !animActive) {
                        // Nothing left to animate, fade, or glide — let the loop terminate.
                        animationLoopRunning = false;
                        lastAnimationTimestamp = 0;
                        return;
                    }

                    // Draw the current frame. No marquee/handles/draft during playback.
                    final List<FloorMapObject> overlay = buildAnimatedDrawList(timestamp);
                    getView().draw(scale, offsetX, offsetY,
                            FloorMapZOrder.sort(visibleFacts(factsExcludingOverlay(overlay)), typeStyles),
                            visibleEvents(overlay), selectedObjectIds, typeStyles, showGrid, dimmedTypes,
                            null, false, false, null);

                    // Keep looping.
                    AnimationScheduler.get().requestAnimationFrame(this);
                }
            };

    /** Starts the animation loop if it is not already running. */
    private void ensureAnimationLoop() {
        if (!animationLoopRunning) {
            animationLoopRunning = true;
            AnimationScheduler.get().requestAnimationFrame(animationCallback);
        }
    }

    // =========================================================================
    // Setters
    // =========================================================================

    /**
     * Single-select façade: highlights exactly one object (or clears the
     * highlight when {@code null}) and redraws.
     *
     * @param selectedObjectId the object ID to highlight, or {@code null} to clear
     */
    public void setSelectedObjectId(final String selectedObjectId) {
        selectedObjectIds.clear();
        if (selectedObjectId != null) {
            selectedObjectIds.add(selectedObjectId);
        }
        redraw();
    }

    /**
     * Replaces the highlighted selection with the given object ids (multi-select
     * facade beside {@link #setSelectedObjectId}) and redraws. Selection order is
     * preserved so the first id is treated as the primary. Does <em>not</em> fire
     * the {@link SelectionHandler} — this is the inbound path used by callers
     * (e.g. the editor) that already hold the selection.
     *
     * @param objectIds the ids to select; {@code null} clears the selection
     */
    public void setSelectedObjectIds(final Collection<String> objectIds) {
        selectedObjectIds.clear();
        if (objectIds != null) {
            selectedObjectIds.addAll(objectIds);
        }
        redraw();
    }

    /**
     * Starts tracking the given entity: highlights it via the selection
     * mechanism, immediately centres the camera on it, and follows it as it
     * moves. Passing {@code null} stops tracking and clears the highlight.
     * Calling this again with the same id re-centres and resumes following
     * after a manual pan paused it.
     *
     * @param trackedObjectId the entity's object id, or {@code null} to stop tracking
     */
    public void setTrackedObjectId(final String trackedObjectId) {
        this.trackedObjectId = trackedObjectId;
        this.followPaused = false;
        // Hard-centre on (re-)selection so tracking visibly engages even when
        // the entity is already on screen. If its position isn't known yet
        // (no events loaded), the flag holds until the first update that has one.
        this.centreOnNextFollow = trackedObjectId != null;
        followStep(0);
        setSelectedObjectId(trackedObjectId);
    }

    /**
     * Applies one damped camera-follow step: computes the pan needed to bring
     * the tracked entity back inside the view's central dead zone (via
     * {@link FloorMapViewport#followDelta}) and applies a time-proportional
     * fraction of it, so the camera glides after the entity instead of
     * snapping. The full correction is applied at once when hard-centring on
     * (re-)selection ({@link #centreOnNextFollow}) or when the remainder is
     * sub-pixel.
     *
     * <p>No-op when nothing is tracked, following is paused, or the entity's
     * position is unknown. Callers are responsible for redrawing afterwards.</p>
     *
     * @param deltaMs elapsed time since the previous step (ms); {@code 0}
     *                applies the full correction immediately
     * @return {@code true} if the camera moved (a glide is in progress), so
     *         the animation loop keeps running until the correction is spent
     */
    private boolean followStep(final double deltaMs) {
        if (trackedObjectId == null || followPaused) {
            return false;
        }
        final double[] pos = trackedPosition();
        if (pos == null) {
            // Keep centreOnNextFollow armed until we have a position fix.
            return false;
        }
        // Project the map-space position to the on-screen point via the shared
        // projection helper.
        final double[] screen = mapToScreen(pos);
        final double screenX = screen[0];
        final double screenY = screen[1];
        final Element panel = getView().getFocusPanel().getElement();
        // Margin 0.5 collapses the dead zone to the centre point (hard-centre).
        final boolean centre = centreOnNextFollow;
        centreOnNextFollow = false;
        final double margin = centre
                ? 0.5
                : FloorMapViewport.DEFAULT_FOLLOW_MARGIN;
        final double[] delta = FloorMapViewport.followDelta(screenX, screenY,
                panel.getOffsetWidth(), panel.getOffsetHeight(), margin);
        if (delta[0] == 0 && delta[1] == 0) {
            return false;
        }

        final boolean snap = centre
                || deltaMs <= 0
                || (Math.abs(delta[0]) < FOLLOW_SNAP_PX && Math.abs(delta[1]) < FOLLOW_SNAP_PX);
        final double factor = snap
                ? 1.0
                : FloorMapViewport.dampingFactor(deltaMs, FloorMapViewport.DEFAULT_FOLLOW_DAMPING_MS);
        offsetX += delta[0] * factor;
        offsetY += delta[1] * factor;
        return true;
    }

    /**
     * Resolves the tracked entity's current map-space position, preferring the
     * live interpolated animation position, then the last known rendered
     * position, then the event draw list, then — for static facts (objects,
     * backgrounds, areas), which never move — the fact's placement anchor.
     *
     * @return {@code {mapX, mapY}}, or {@code null} if the entity is unknown
     */
    private double[] trackedPosition() {
        // The animator knows live/animated/last-known and current-overlay positions.
        final double[] pos = animator.positionOf(trackedObjectId);
        if (pos != null) {
            return pos;
        }
        // Fall back to a static fact placed in the facts store.
        for (final Fact fact : facts) {
            if (trackedObjectId != null && trackedObjectId.equals(fact.getKey())) {
                // Image anchors need the aspect ratio, known only to the view.
                return getView().getFactMapAnchor(fact);
            }
        }
        return null;
    }

    /**
     * Returns the facts to render, applying any in-progress transform gesture:
     * each selected fact's world-to-map matrix is composed as
     * {@code pendingTransform · oldMatrix} so a move/scale/rotate is shown live.
     * On release the same transform is persisted via {@link DragHandler#onTransform}.
     */
    private List<Fact> factsForDraw() {
        final boolean editingVertices = editingAreaKey != null && workingVertices != null;
        if ((pendingTransform == null || selectedObjectIds.isEmpty()) && !editingVertices) {
            return facts;
        }
        final List<Fact> out = new ArrayList<>(facts.size());
        for (final Fact fact : facts) {
            if (editingVertices && editingAreaKey.equals(fact.getKey())) {
                // Live vertex-edit preview.
                out.add(fact.withVertices(workingVertices));
            } else if (pendingTransform != null && selectedObjectIds.contains(fact.getKey())) {
                out.add(fact.withWorldToMap(
                        pendingTransform.multiply(fact.getWorldToMap())));
            } else {
                out.add(fact);
            }
        }
        return out;
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
     * Converts a number of minor grid divisions into a map-space distance at
     * the current zoom level, using the same adaptive-decade sizing that draws
     * the grid ({@link FloorMapGrid}). Because the grid's spacing is chosen to
     * keep grid cells a comfortable on-screen size at any zoom, an offset
     * expressed this way stays visually consistent regardless of magnification.
     *
     * <p>The grid is drawn with an identity world-to-map matrix (see the
     * {@code appendGrid} call in the view), so its effective scale is simply
     * the user zoom and its world space coincides with map space.</p>
     *
     * @param minorDivisions the number of minor grid divisions
     * @return the equivalent distance in map-space units
     */
    public double minorGridDivisionsToMapUnits(final double minorDivisions) {
        return minorDivisions * FloorMapGrid.minorWorldSpacing(scale);
    }

    /**
     * Sets the event-driven entity overlays (events query result).
     * <p>
     * When the timeline is <em>not</em> playing, all objects are placed at their
     * target positions immediately (teleport behaviour).
     * <p>
     * When the timeline <em>is</em> playing, every entity is animated from its
     * last known position to the new one, regardless of type.
     */
    public void setEventObjects(final List<FloorMapObject> objects) {
        final boolean teleported = animator.onEventObjects(objects);
        // Run the loop for the animate path (it advances animations + glides the
        // camera and repaints); on the teleport path only if tracking, so the
        // damped camera-follow glides to the new position.
        if (!teleported || (trackedObjectId != null && !followPaused)) {
            ensureAnimationLoop();
        }
        // Force a paint so an update that starts no animation still repaints (the
        // loop returns without drawing when there is nothing to animate or glide).
        redraw();
    }

    /**
     * Toggles edit mode. When disabled, the object selection is cleared.
     *
     * @param editMode {@code true} to enter edit mode, {@code false} to leave
     */
    public void setEditMode(final boolean editMode) {
        this.editMode = editMode;
        if (!editMode) {
            selectedObjectIds.clear();
            if (gesture == Gesture.DRAWING_AREA) {
                cancelAreaDrawing();
            }
        }
        redraw();
    }

    /**
     * Enters the modal area-drawing gesture: subsequent clicks append polygon
     * vertices, a press-drag pans, right-click undoes the last vertex, Escape
     * cancels, and the polygon closes on Enter, double-click, or a click near
     * the first vertex (≥ 3 vertices). The finished polygon is delivered to
     * the {@link AreaHandler}.
     */
    public void startAreaDrawing() {
        selectedObjectIds.clear();
        fireSelectionChanged();
        areaDraftMap.clear();
        gesture = Gesture.DRAWING_AREA;
        // Make the modal mode visible immediately: crosshair cursor (the
        // instruction banner is drawn by the view). Without this the mode is
        // indistinguishable from nothing having happened.
        getView().getFocusPanel().getElement().getStyle()
                .setProperty("cursor", "crosshair");
        // The triggering context-menu click leaves keyboard focus on the popup;
        // without this, Enter/Escape are dead until the first canvas click.
        getView().getFocusPanel().setFocus(true);
        redraw();
    }

    /** Discards any in-progress area draft and leaves the drawing gesture. */
    public void cancelAreaDrawing() {
        areaDraftMap.clear();
        gesture = Gesture.NONE;
        getView().getFocusPanel().getElement().getStyle().clearProperty("cursor");
        redraw();
    }

    /**
     * Closes the in-progress polygon and delivers it to the
     * {@link AreaHandler}. No-ops (keeps drawing) below the vertex minimum.
     */
    private void finishAreaDrawing() {
        if (areaDraftMap.size() < AREA_MIN_VERTICES) {
            return;
        }
        final List<double[]> mapVertices = new ArrayList<>(areaDraftMap.size());
        for (final double[] v : areaDraftMap) {
            mapVertices.add(new double[]{v[0], v[1]});
        }
        areaDraftMap.clear();
        gesture = Gesture.NONE;
        getView().getFocusPanel().getElement().getStyle().clearProperty("cursor");
        redraw();
        if (areaHandler != null) {
            areaHandler.onAreaDrawn(mapVertices);
        }
    }

    /**
     * {@code true} if the given element-pixel position falls within the
     * close radius of the draft's first vertex.
     */
    private boolean isNearFirstDraftVertex(final double screenX, final double screenY) {
        if (areaDraftMap.isEmpty()) {
            return false;
        }
        //noinspection SequencedCollectionMethodCanBeUsed
        final double[] first = mapToScreen(areaDraftMap.get(0));
        final double dx = screenX - first[0];
        final double dy = screenY - first[1];
        return dx * dx + dy * dy <= AREA_CLOSE_RADIUS_PX * AREA_CLOSE_RADIUS_PX;
    }

    /**
     * Projects a map-space point to element-pixel coordinates — the inverse of
     * {@link #screenToMapCoords} (pan/zoom plus the Y-up flip).
     */
    private double[] mapToScreen(final double[] mapPoint) {
        return new FloorMapViewport(scale, offsetX, offsetY)
                .mapToScreen(mapPoint[0], mapPoint[1], Y_FLIP);
    }

    /**
     * The in-progress area draft as a flat element-pixel polyline
     * {@code [x0, y0, ..., xn, yn]} whose last point is the live cursor. An
     * empty draft yields just the cursor point — still non-null, so the view
     * shows the drawing-mode banner from the moment the mode starts.
     */
    private double[] currentAreaDraftPx() {
        final double[] draft = new double[(areaDraftMap.size() + 1) * 2];
        for (int i = 0; i < areaDraftMap.size(); i++) {
            final double[] px = mapToScreen(areaDraftMap.get(i));
            draft[i * 2] = px[0];
            draft[i * 2 + 1] = px[1];
        }
        draft[draft.length - 2] = areaCursorX;
        draft[draft.length - 1] = areaCursorY;
        return draft;
    }

    /**
     * Controls whether the grid overlay is drawn. The grid is a non-interactive
     * UI aid and is independent of edit mode and of whether a background image is
     * present.
     *
     * @param showGrid {@code true} to always draw the grid, {@code false} to hide it
     */
    public void setShowGrid(final boolean showGrid) {
        this.showGrid = showGrid;
        redraw();
    }

    /**
     * Sets the per-type presentation settings (z-order + default graphic shape
     * and colour). Used by the view to render imageless facts.
     *
     * @param typeStyles the ordered type styles, or {@code null}
     */
    public void setTypeStyles(final List<TypeStyle> typeStyles) {
        this.typeStyles = typeStyles;
        redraw();
    }

    /**
     * Sets per-type layer visibility from the Layers panel. Types in
     * {@code hidden} are neither drawn nor hit-tested; types in {@code dimmed}
     * render at reduced opacity.
     *
     * @param hidden types to hide; {@code null} treated as empty
     * @param dimmed types to render dimmed; {@code null} treated as empty
     */
    public void setLayerVisibility(final Set<String> hidden, final Set<String> dimmed) {
        hiddenTypes.clear();
        if (hidden != null) {
            hiddenTypes.addAll(hidden);
        }
        dimmedTypes.clear();
        if (dimmed != null) {
            dimmedTypes.addAll(dimmed);
        }
        redraw();
    }

    /**
     * Sets the types whose items are locked against movement (Editor Layers
     * panel). Locked items stay visible and selectable but cannot be dragged.
     *
     * @param locked the locked types; {@code null} treated as empty
     */
    public void setLockedTypes(final Set<String> locked) {
        lockedTypes.clear();
        if (locked != null) {
            lockedTypes.addAll(locked);
        }
        recomputeLockedKeys();
    }

    /** Recomputes {@link #lockedKeys} from the current facts and locked types. */
    private void recomputeLockedKeys() {
        lockedKeys.clear();
        if (lockedTypes.isEmpty()) {
            return;
        }
        for (final Fact fact : facts) {
            if (lockedTypes.contains(fact.getType())) {
                lockedKeys.add(fact.getKey());
            }
        }
    }

    /** Facts minus those whose type is a hidden layer. */
    private List<Fact> visibleFacts(final List<Fact> in) {
        if (in == null || hiddenTypes.isEmpty()) {
            return in;
        }
        final List<Fact> out = new ArrayList<>(in.size());
        for (final Fact fact : in) {
            if (!hiddenTypes.contains(fact.getType())) {
                out.add(fact);
            }
        }
        return out;
    }

    /** Event objects minus those whose type is a hidden layer. */
    private List<FloorMapObject> visibleEvents(final List<FloorMapObject> in) {
        if (in == null || hiddenTypes.isEmpty()) {
            return in;
        }
        final List<FloorMapObject> out = new ArrayList<>(in.size());
        for (final FloorMapObject ev : in) {
            if (!hiddenTypes.contains(ev.getType())) {
                out.add(ev);
            }
        }
        return out;
    }

    /**
     * Sets the facts to render (backgrounds + static facts) as produced by the
     * parser. Replaces the legacy background-image/matrix/objects inputs.
     *
     * @param facts the facts; {@code null} is treated as empty
     */
    public void setFacts(final List<Fact> facts) {
        this.facts = facts != null ? facts : new ArrayList<>();
        // Recompute which facts act as the background (plain drag over them
        // pans rather than moving them), keyed by the BACKGROUND key or type,
        // and which are areas (same pan-when-unselected press handling).
        backgroundKeys.clear();
        areaKeys.clear();
        for (final Fact fact : this.facts) {
            if (FloorMapJsonKeys.BACKGROUND.equals(fact.getKey())
                    || FloorMapJsonKeys.BACKGROUND.equals(fact.getType())) {
                backgroundKeys.add(fact.getKey());
            } else if (!fact.hasImage() && fact.hasVertices()) {
                areaKeys.add(fact.getKey());
            }
        }
        recomputeLockedKeys();
        // Draw first so the view's content bounds reflect these facts, THEN try
        // the initial fit — getContentMapBounds() reads the last-drawn facts.
        // Facts may arrive before or after first layout; this fits as soon as
        // both are available (no-op once the view has been applied).
        redraw();
        maybeApplyInitialView();
    }

    /**
     * Injects an initial view {@code {scale, offsetX, offsetY}} from another tab
     * (Map → Editor) so this canvas's first frame matches exactly and nothing
     * jumps on the tab switch. Only honoured before the initial view is applied;
     * user pan/zoom afterwards is independent per canvas.
     *
     * @param view the view state, or {@code null} to compute a fit locally
     */
    public void setInitialViewState(final double[] view) {
        this.injectedInitialView = view;
    }

    /**
     * Registers a listener notified once with this canvas's computed initial
     * view {@code {scale, offsetX, offsetY}}, so another tab can reuse it.
     *
     * @param listener the callback, or {@code null} to remove
     */
    public void setInitialViewListener(final Consumer<double[]> listener) {
        this.initialViewListener = listener;
    }


    /**
     * Sets the handler that is notified while an object is being dragged.
     *
     * @param dragHandler the callback, or {@code null} to remove
     */
    public void setDragHandler(final DragHandler dragHandler) {
        this.dragHandler = dragHandler;
    }

    /**
     * Sets the handler that persists an area's edited geometry.
     *
     * @param geometryHandler the callback, or {@code null} to remove
     */
    public void setGeometryHandler(final GeometryHandler geometryHandler) {
        this.geometryHandler = geometryHandler;
    }

    /**
     * Sets the handler that receives a finished area-drawing polygon.
     *
     * @param areaHandler the callback, or {@code null} to remove
     */
    public void setAreaHandler(final AreaHandler areaHandler) {
        this.areaHandler = areaHandler;
    }

    /**
     * Sets the handler notified when the selection changes as a result of a
     * canvas interaction (click, Shift-click toggle, or rubber-band marquee).
     *
     * @param selectionHandler the callback, or {@code null} to remove
     */
    public void setSelectionHandler(final SelectionHandler selectionHandler) {
        this.selectionHandler = selectionHandler;
    }

    /**
     * Notifies the {@link SelectionHandler} of the current selection, if one is
     * set. The primary key is the first-selected id, or {@code null} when empty.
     */
    private void fireSelectionChanged() {
        if (selectionHandler != null) {
            final String primary = selectedObjectIds.isEmpty()
                    ? null
                    : selectedObjectIds.iterator().next();
            selectionHandler.onSelectionChanged(
                    new ArrayList<>(selectedObjectIds), primary);
        }
    }

    /**
     * Callback invoked when a transform gesture (move/rotate/scale) completes,
     * to persist the change as a single map-space affine applied to the whole
     * selection. A plain move is just a translation transform.
     */
    public interface DragHandler {

        /**
         * Persists an arbitrary map-space transform applied to the whole
         * selection.
         *
         * @param keys the selected fact keys being transformed
         * @param mapSpaceTransform the accumulated map-space affine to compose
         *                          onto each fact ({@code newWorldToMap = T · old})
         */
        void onTransform(Collection<String> keys, FloorMapTransformationMatrix mapSpaceTransform);
    }

    /**
     * Callback invoked when an area's geometry is edited (a vertex moved,
     * inserted or deleted), to persist the new local-frame vertices.
     */
    public interface GeometryHandler {

        /**
         * Persists the area's new vertices (local frame).
         *
         * @param key      the area fact's key
         * @param vertices the new local-frame vertices ({@code >= 3})
         */
        void onGeometryEdited(String key, double[][] vertices);
    }

    /**
     * Callback invoked when the user finishes drawing an area polygon (see
     * {@link #startAreaDrawing()}).
     */
    public interface AreaHandler {

        /**
         * @param mapVertices the polygon vertices in map space, in click order;
         *                    always at least 3
         */
        void onAreaDrawn(List<double[]> mapVertices);
    }

    /**
     * Callback invoked when a canvas interaction changes the selection.
     */
    public interface SelectionHandler {

        /**
         * @param keys    the full selection in selection order
         * @param primary the first-selected id (the "primary"), or {@code null}
         */
        void onSelectionChanged(Collection<String> keys, String primary);
    }


    // =========================================================================
    // View interface
    // =========================================================================

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
         *   <li>Grid overlay (drawn when {@code showGrid} is set)</li>
         *   <li>Facts — image facts (incl. backgrounds) and imageless default graphics,
         *       in the supplied paint (z) order</li>
         *   <li>Events (people) drawn on top</li>
         * </ol>
         *
         * @param scale           the current zoom scale factor
         * @param x               the current pan offset X (pixels)
         * @param y               the current pan offset Y (pixels)
         * @param facts           the facts to render, already in paint (z) order
         * @param events          the event entity overlay objects (map coordinates)
         * @param selectedObjectIds the IDs of the currently selected objects (all
         *                         highlighted); empty if nothing is selected
         * @param typeStyles      per-type presentation settings (default graphic
         *                        shape/colour for imageless facts); may be {@code null}
         * @param showGrid        {@code true} to draw the (non-interactive) grid overlay
         * @param areaDraftPx     the in-progress area-drawing polyline in element
         *                        pixels ({@code [x0, y0, x1, y1, ...]}, last point =
         *                        live cursor), or {@code null} when not drawing
         */
        void draw(double scale, double x, double y, List<Fact> facts,
                List<FloorMapObject> events, Set<String> selectedObjectIds,
                List<TypeStyle> typeStyles, boolean showGrid, Set<String> dimmedTypes,
                double[] marqueeRectPx, boolean drawSelectionHandles, boolean scaleRotateEnabled,
                double[] areaDraftPx);

        /**
         * Returns the keys of facts whose on-screen bounds intersect the given
         * rubber-band rectangle (element-pixel space, {@code {minX, minY, maxX,
         * maxY}}). Uses the geometry of the last {@link #draw} call — including
         * image aspect ratios, which are known only to the view.
         *
         * @param rectPx the marquee rectangle in element pixels
         * @return the intersecting fact keys; never {@code null}
         */
        Set<String> hitTestScreenRect(double[] rectPx);

        /**
         * Returns the fact's map-space anchor point (the point the camera
         * centres on when the fact is tracked). Delegates to
         * {@link Fact#mapAnchor} with the view's image display width and the
         * fact's aspect ratio — which is known only to the view.
         *
         * @param fact the fact to anchor; must not be {@code null}
         * @return the anchor {@code {mapX, mapY}}; never {@code null}
         */
        double[] getFactMapAnchor(Fact fact);

        /**
         * Returns the screen-space bounding box {@code {minX, minY, maxX, maxY}}
         * of the current selection (from the last {@link #draw}), or {@code null}
         * if nothing is selected. Used to seed a scale/rotate gesture.
         *
         * @return the selection frame in element pixels, or {@code null}
         */
        double[] getSelectionFrame();

        /**
         * Returns the map-space bounding box {@code {minX, minY, maxX, maxY}} of
         * all facts from the last {@link #draw} call, or {@code null} if there is
         * no content. Used to compute the initial zoom-to-fit view. Independent
         * of the current scale/pan (unlike {@link #getSelectionFrame()}).
         *
         * @return the content bounds in map space, or {@code null}
         */
        double[] getContentMapBounds();

        /**
         * Registers a listener that is called whenever the view needs to
         * trigger a redraw from outside the normal presenter flow (e.g.
         * after an asynchronous image aspect-ratio calculation completes).
         *
         * @param redrawListener the callback to invoke, typically
         *                       {@code FloorMapCanvasPresenter::redraw}
         */
        void setRedrawListener(Runnable redrawListener);

        /**
         * Registers a listener that is called once the canvas has a real
         * (non-zero) on-screen size — i.e. after layout completes. The
         * presenter uses this to apply its size-dependent initial view (which
         * needs the canvas dimensions to fit or place the content).
         *
         * @param resizeListener the callback to invoke, typically
         *                       {@code FloorMapCanvasPresenter::maybeApplyInitialView}
         */
        void setResizeListener(Runnable resizeListener);
    }

}
