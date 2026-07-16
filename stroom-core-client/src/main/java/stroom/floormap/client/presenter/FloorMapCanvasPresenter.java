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
import stroom.floormap.shared.FloorMapJsonKeys;
import stroom.floormap.shared.FloorMapObject;
import stroom.floormap.shared.FloorMapTransformationMatrix;
import stroom.floormap.shared.FloorMapViewport;
import stroom.floormap.shared.FloorMapZOrder;
import stroom.floormap.shared.TypeStyle;

import com.google.gwt.animation.client.AnimationScheduler;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    // Animation constants
    // -------------------------------------------------------------------------

    /** How long (wall-clock ms) a single position-change animation lasts. */
    private static final double ANIMATION_DURATION_MS = 800.0;

    /**
     * Maximum number of recorded trail points per entity.  Set high enough
     * that the trail covers the full journey during normal playback (~83 s
     * at 60 fps).  The single SVG {@code <path>} rendering makes this
     * inexpensive; trail data is cleaned up on fade completion and
     * discontinuous time jumps.
     */
    private static final int TRAIL_MAX_PTS = 5000;

    /** How long (wall-clock ms) trails take to fade out after the entity stops moving. */
    private static final double TRAIL_FADE_DURATION_MS = 2000.0;

    // -------------------------------------------------------------------------
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

    /** The zoom level a freshly opened map starts at (100 %). */
    private static final double DEFAULT_SCALE = 1.0;

    /**
     * How far the origin (0,0) is inset from the bottom-left corner in the
     * default view, expressed in major grid divisions. Half a division places
     * the axis indicator comfortably clear of the corner (e.g. the bottom-left
     * of the screen reads as (-50,-50) when the major division is 100).
     */
    private static final double ORIGIN_INSET_MAJOR_DIVISIONS = 0.5;

    // Zoom and pan state
    private double scale = DEFAULT_SCALE;
    private double offsetX = 0;
    private double offsetY = 0;
    /**
     * Whether the size-dependent default view has been applied yet. Applied
     * once, the first time the canvas has a real height (see
     * {@link #applyDefaultView()}); user pan/zoom afterwards is left untouched.
     */
    private boolean defaultViewApplied = false;

    // Dragging state
    private boolean isDraggingEnabled = false;
    private DragHandler dragHandler;
    private boolean isDragging = false;
    /** True only if the mouse actually moved while dragging an object (distinguishes click-to-select from drag). */
    private boolean hasMoved = false;
    private double lastMouseX;
    private double lastMouseY;
    /** Accumulated drag delta in map space (Y-up) for the current drag gesture. */
    private double dragDxMap;
    private double dragDyMap;

    /**
     * Event entities that are not currently animated. When playing, moving
     * entities are built dynamically in {@link #buildAnimatedDrawList}.
     */
    private List<FloorMapObject> eventObjects = new ArrayList<>();

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

    /** {@code true} while the timeline is actively playing. */
    private boolean isPlaying = false;

    /**
     * When {@code true}, the next call to {@link #setEventObjects} will teleport
     * entities directly to their new positions without creating animations, even if
     * {@link #isPlaying} is {@code true}.  Set by {@link #clearAnimationState()} so
     * that a scrub/skip/loop-around places users instantly rather than replaying a
     * batch of movements.
     */
    private boolean pendingTeleport = false;

    /**
     * In-flight animations keyed by entity id.  Only populated while playing.
     */
    private final Map<String, EntityAnimation> activeAnimations = new HashMap<>();

    /**
     * Last known rendered state (id, type, map-space position) for each event
     * entity, used as the start point for the next animation and to keep the
     * entity drawn (with its correct type styling) between event refreshes.
     */
    private final Map<String, FloorMapObject> lastEntityPositions = new HashMap<>();

    /**
     * Trail points for each entity.  Each entry is {@code [mapX, mapY]}.
     * Points are appended during animation; oldest are at the front.
     * The list is bounded by {@link #TRAIL_MAX_PTS}.
     */
    private final Map<String, List<double[]>> entityTrails = new HashMap<>();

    /**
     * AnimationScheduler timestamp when each entity's last animation completed,
     * initiating the trail fade-out.  Entries are removed once the fade finishes
     * or if the entity starts a new animation.
     */
    private final Map<String, Double> trailFadeStartTimes = new HashMap<>();

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
            getView().setResizeListener(this::applyDefaultView);
            getView().onResize();
        }

        // Perform initial draw
        redraw();
    }

    /**
     * Positions the initial view so the map origin (0,0) sits near the
     * bottom-left corner of the canvas, inset by
     * {@link #ORIGIN_INSET_MAJOR_DIVISIONS} of a major grid division at the
     * default zoom.
     *
     * <p>Runs once, the first time the canvas has a real height, so the result
     * is correct at any window size and any default zoom; the inset is derived
     * from the grid's own adaptive-decade sizing so it always matches the drawn
     * grid. Subsequent user pan/zoom is left untouched.</p>
     */
    private void applyDefaultView() {
        if (defaultViewApplied) {
            return;
        }
        final int height = getView().getFocusPanel().getElement().getOffsetHeight();
        if (height <= 0) {
            // Canvas not laid out yet — a later onResize will call back.
            return;
        }

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
        defaultViewApplied = true;
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
        this.isPlaying = playing;
    }

    /**
     * Discards all in-flight movement animations and trail data.  Call this
     * whenever the timeline time jumps non-continuously (scrub, step, loop-around,
     * stop-at-end) so stale animation state does not carry over.
     * <p>
     * Also sets {@link #pendingTeleport} so that the <em>next</em> call to
     * {@link #setEventObjects} places entities at their new positions instantly
     * (teleport) rather than animating them from stale positions, even when
     * {@link #isPlaying} is {@code true}.
     */
    public void clearAnimationState() {
        activeAnimations.clear();
        entityTrails.clear();
        trailFadeStartTimes.clear();
        pendingTeleport = true;
        animationLoopRunning = false;
        lastAnimationTimestamp = 0;
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
                        // Holding Ctrl or Shift lets the user pan even when the
                        // click lands on an object — needed when a full-canvas
                        // background fact would otherwise intercept every drag.
                        if (!(event.getNativeEvent().getCtrlKey()
                                || event.getNativeEvent().getShiftKey())) {
                            // Single-select today: replace the whole selection.
                            selectedObjectIds.clear();
                            selectedObjectIds.add(id);

                            // Fire an event to tell the parent presenter to show the edit menu
                            MapObjectSelectedEvent.fire(this, id);
                            isDragging = true;
                            hasMoved = false;
                            dragDxMap = 0;
                            dragDyMap = 0;
                            lastMouseX = event.getX();
                            lastMouseY = event.getY();

                            // Stop panning
                            return;
                        }
                    }
                }

                // Clicked on background/empty space, clear selection and allow panning
                selectedObjectIds.clear();
            } else {
                // Read-only (Map tab) mode: clicking an object shape announces
                // it so the parent presenter can select/track it (people only —
                // the parent filters). No event fires for background/empty
                // clicks: mousedown on empty space is how panning starts, and
                // deselection is handled by the tracking panel instead.
                final EventTarget target = event.getNativeEvent().getEventTarget();
                if (Element.is(target)) {
                    final String id = Element.as(target).getId();
                    if (id != null && !id.isEmpty()
                            && !id.startsWith(FloorMapJsonKeys.SVG_GROUP_PREFIX)
                            && !FloorMapJsonKeys.BACKGROUND.equals(id)) {
                        MapObjectSelectedEvent.fire(this, id);
                    }
                }
            }

            // Normal panning logic
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
                isDragging = false;
                hasMoved = false;
                return;
            }

            if (isDragging) {
                final double deltaX = event.getX() - lastMouseX;
                final double deltaY = event.getY() - lastMouseY;

                if (editMode && isDraggingEnabled && !selectedObjectIds.isEmpty()) {
                    // Accumulate the drag in map space (Y-up) for live feedback and a
                    // single translateFacts() applied to the whole selection on drop.
                    dragDxMap += deltaX / scale;
                    //noinspection UnnecessaryUnaryMinus
                    dragDyMap += -(deltaY / scale);
                    hasMoved = true;
                    redraw();
                } else {
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
                }

                lastMouseX = event.getX();
                lastMouseY = event.getY();
            }
        }));

        //noinspection unused event
        registerHandler(getView().getMouseUpHandlers().addMouseUpHandler(event -> {
            // Persist the drag as a single translate of the whole selection.
            final boolean moved = isDragging && hasMoved && editMode
                    && !selectedObjectIds.isEmpty();
            final double dx = dragDxMap;
            final double dy = dragDyMap;
            dragDxMap = 0;
            dragDyMap = 0;
            isDragging = false;
            hasMoved = false;
            if (moved && dragHandler != null) {
                dragHandler.onTranslate(new ArrayList<>(selectedObjectIds), dx, dy);
            }
        }));

        // Mouse Wheel (Zoom toward cursor)
        registerHandler(getView().getMouseWheelHandlers().addMouseWheelHandler(event -> {
            event.preventDefault();

            double zoomFactor = 1.1;
            if (event.getNativeDeltaY() > 0) {
                zoomFactor = 1 / zoomFactor; // Zoom out
            }

            // Note: zooming deliberately does NOT pause following — zooming in
            // on a tracked entity is the natural way to watch it, and the
            // dead-zone follow simply keeps it in view at the new zoom level.

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
        // Remove the zoom/pan offset and scale, then undo the Y-up render flip
        // (map space is Y-up; SVG is Y-down) — the inverse of the draw pipeline.
        final double mapX = (screenX - offsetX) / scale;
        final double mapY = -((screenY - offsetY) / scale);
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

    // =========================================================================
    // Drawing
    // =========================================================================

    private void redraw() {
        final List<FloorMapObject> overlay =
                buildAnimatedDrawList(/* nowMs — irrelevant when no animations */ 0.0);
        getView().draw(scale, offsetX, offsetY,
                FloorMapZOrder.sort(factsExcludingOverlay(overlay), typeStyles),
                overlay, selectedObjectIds, typeStyles, showGrid);
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
        // Facts are rendered separately from the facts list; this list is the
        // event overlay only.
        final List<FloorMapObject> combined = new ArrayList<>(eventObjects);

        // Entities currently mid-animation — add at their interpolated position.
        for (final Map.Entry<String, EntityAnimation> entry : activeAnimations.entrySet()) {
            final EntityAnimation anim = entry.getValue();
            final FloorMapObject obj = new FloorMapObject(
                    anim.id, anim.type, anim.currentX(), anim.currentY());
            attachTrail(obj, anim.id, nowMs);
            combined.add(obj);
        }

        // Stationary entities (animation finished or idle during playback) —
        // draw at their last known position and attach any fading trail.  A set
        // of already-drawn IDs prevents duplicates when the same entity is also
        // present in eventObjects (e.g. after play stops).
        final Set<String> drawnIds = new HashSet<>();
        for (final FloorMapObject obj : combined) {
            drawnIds.add(obj.getId());
        }
        for (final Map.Entry<String, FloorMapObject> entry : lastEntityPositions.entrySet()) {
            final String id = entry.getKey();
            if (!drawnIds.contains(id)) {
                final FloorMapObject last = entry.getValue();
                // Fresh copy each draw so per-frame trail data never leaks
                // between frames via a shared instance.
                final FloorMapObject obj = new FloorMapObject(
                        id, last.getType(), last.getX(), last.getY());
                attachTrail(obj, id, nowMs);
                combined.add(obj);
            }
        }

        return combined;
    }

    /**
     * Computes per-point alpha values using a positional (index-based) gradient
     * and attaches the resulting {@code [x, y, alpha]} list to {@code obj}.
     * <p>
     * Alpha runs from {@code 0.0} at the oldest point (index 0) to {@code 1.0}
     * at the newest point (last index).  This ensures the <em>entire</em> spatial
     * path from the start of a journey to the user's current position is always
     * drawn — the tail fades to transparent but the leading edge always meets the
     * user's circle, so the trail visually grows to the full journey length.
     * <p>
     * When the entity has stopped moving, a global fade factor is applied on top
     * of the index-based gradient so the trail fades out over
     * {@link #TRAIL_FADE_DURATION_MS} and then disappears.
     *
     * @param nowMs current AnimationScheduler timestamp in ms, used to compute
     *              the fade factor for stopped entities.  Pass {@code 0.0} when
     *              the animation loop is not running.
     */
    private void attachTrail(final FloorMapObject obj, final String id, final double nowMs) {
        final List<double[]> raw = entityTrails.get(id);
        if (raw == null || raw.isEmpty()) {
            return;
        }

        // Compute a global fade multiplier for trails of stopped people.
        double fadeFactor = 1.0;
        final Double fadeStart = trailFadeStartTimes.get(id);
        if (fadeStart != null && nowMs > 0) {
            final double elapsed = nowMs - fadeStart;
            fadeFactor = Math.max(0.0, 1.0 - elapsed / TRAIL_FADE_DURATION_MS);
        }
        if (fadeFactor <= 0.0) {
            return; // Fully faded — nothing to render.
        }

        final int size = raw.size();
        final List<double[]> trailWithAlpha = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            final double[] pt = raw.get(i);
            // Oldest point → alpha 0, newest → alpha 1, scaled by fade factor.
            final double alpha = (size == 1 ? 1.0 : (double) i / (size - 1)) * fadeFactor;
            trailWithAlpha.add(new double[]{pt[0], pt[1], alpha});
        }
        obj.setTrail(trailWithAlpha);
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

                    if (activeAnimations.isEmpty() && trailFadeStartTimes.isEmpty()) {
                        // Nothing left to animate or fade — let the loop terminate.
                        animationLoopRunning = false;
                        lastAnimationTimestamp = 0;
                        return;
                    }

                    // Advance each active animation by the fraction of ANIMATION_DURATION_MS
                    // that elapsed since the last frame.  This is independent of any absolute
                    // clock, so it works correctly regardless of the time-base used by the
                    // AnimationScheduler (performance.now() vs Date.now()).
                    final List<String> finished = new ArrayList<>();
                    for (final Map.Entry<String, EntityAnimation> entry : activeAnimations.entrySet()) {
                        final EntityAnimation anim = entry.getValue();
                        anim.progress = Math.min(1.0, anim.progress + deltaMs / ANIMATION_DURATION_MS);

                        // Record the current interpolated position into the trail.
                        recordTrailPoint(anim.id, anim.currentX(), anim.currentY());

                        if (anim.progress >= 1.0) {
                            // Snap to the destination and record final position.
                            lastEntityPositions.put(anim.id, new FloorMapObject(
                                    anim.id, anim.type, anim.toX, anim.toY));
                            finished.add(anim.id);
                            // Start fading the trail for this entity.
                            trailFadeStartTimes.put(anim.id, timestamp);
                        }
                    }
                    for (final String id : finished) {
                        activeAnimations.remove(id);
                    }

                    // Process fading trails: remove entries that are fully faded or
                    // whose entity has started a new animation.
                    final List<String> doneFading = new ArrayList<>();
                    for (final Map.Entry<String, Double> fade : trailFadeStartTimes.entrySet()) {
                        final String id = fade.getKey();
                        if (activeAnimations.containsKey(id)) {
                            // Entity started moving again — cancel the fade.
                            doneFading.add(id);
                        } else if (timestamp - fade.getValue() >= TRAIL_FADE_DURATION_MS) {
                            // Fully faded — remove trail data.
                            entityTrails.remove(id);
                            doneFading.add(id);
                        }
                    }
                    for (final String id : doneFading) {
                        trailFadeStartTimes.remove(id);
                    }

                    // Keep the camera on the tracked entity as it interpolates.
                    applyFollow();

                    // Draw the current frame.
                    final List<FloorMapObject> overlay = buildAnimatedDrawList(timestamp);
                    getView().draw(scale, offsetX, offsetY,
                            FloorMapZOrder.sort(factsExcludingOverlay(overlay), typeStyles),
                            overlay, selectedObjectIds, typeStyles, showGrid);

                    // Keep looping.
                    AnimationScheduler.get().requestAnimationFrame(this);
                }
            };

    /**
     * Handles an entity position update from the events query
     * ({@link #setEventObjects}).
     * <p>
     * When not playing: records the position in {@link #lastEntityPositions} so
     * play-start has a valid "from" anchor.  The caller is responsible for
     * placing the object in the appropriate draw list for immediate display.
     * <p>
     * When playing: creates an animation if the position has changed and there
     * is not already an animation running to the same destination.
     *
     * @return {@code true} if the caller should add the object to its draw list
     *         (i.e., it was NOT animated and should be shown at its current position),
     *         {@code false} if the animation system has taken ownership.
     */
    private boolean handleEntityUpdate(final FloorMapObject obj) {
        if (!isPlaying || pendingTeleport) {
            // Teleport: record position for later play-start animation anchor.
            lastEntityPositions.put(obj.getId(), new FloorMapObject(
                    obj.getId(), obj.getType(), obj.getX(), obj.getY()));
            return true; // caller adds to draw list
        }

        final FloorMapObject last = lastEntityPositions.get(obj.getId());
        if (last == null) {
            // First appearance while playing — place without animation.
            lastEntityPositions.put(obj.getId(), new FloorMapObject(
                    obj.getId(), obj.getType(), obj.getX(), obj.getY()));
            return true;
        }

        // Check whether we already have an animation running toward this exact destination.
        final EntityAnimation existing = activeAnimations.get(obj.getId());
        final boolean alreadyAnimatingToTarget = existing != null
                && Math.abs(existing.toX - obj.getX()) < 0.001
                && Math.abs(existing.toY - obj.getY()) < 0.001;

        if (!alreadyAnimatingToTarget) {
            final double dx = last.getX() - obj.getX();
            final double dy = last.getY() - obj.getY();
            if (dx * dx + dy * dy > 0.0001) {
                final double fromX = existing != null ? existing.currentX() : last.getX();
                final double fromY = existing != null ? existing.currentY() : last.getY();
                activeAnimations.put(obj.getId(), new EntityAnimation(
                        obj.getId(), obj.getType(),
                        fromX, fromY,
                        obj.getX(), obj.getY()));
                // lastEntityPositions updated by animation loop when progress ≥ 1.0
                return false; // animation system owns this entity
            }
        }

        // Position unchanged or already animating to target — animation loop owns it.
        return false;
    }

    /** Starts the animation loop if it is not already running. */
    private void ensureAnimationLoop() {
        if (!animationLoopRunning) {
            animationLoopRunning = true;
            AnimationScheduler.get().requestAnimationFrame(animationCallback);
        }
    }

    /**
     * Appends {@code [x, y]} to the trail for {@code id}, enforcing the maximum
     * trail length by dropping the oldest point when the cap is exceeded.
     * <p>
     * Trail points no longer carry a wall-clock timestamp; fading is now
     * index-based (see {@link #attachTrail}) so the full spatial path is always
     * visible regardless of how long the journey took.
     */
    private void recordTrailPoint(final String id,
                                  final double x,
                                  final double y) {

        //noinspection unused k
        final List<double[]> trail = entityTrails.computeIfAbsent(id, k -> new ArrayList<>());
        trail.add(new double[]{x, y});

        // Hard cap to avoid unbounded growth.
        while (trail.size() > TRAIL_MAX_PTS) {
            //noinspection SequencedCollectionMethodCanBeUsed GWT does not support
            trail.remove(0);
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
        applyFollow();
        setSelectedObjectId(trackedObjectId);
    }

    /**
     * Pans the camera (via {@link FloorMapViewport#followDelta}) so the tracked
     * entity stays within the view's central dead zone — or hard-centres it
     * when {@link #centreOnNextFollow} is set. No-op when nothing is tracked,
     * following is paused, or the entity's position is unknown. Callers are
     * responsible for redrawing afterwards.
     */
    private void applyFollow() {
        if (trackedObjectId == null || followPaused) {
            return;
        }
        final double[] pos = trackedPosition();
        if (pos == null) {
            return;
        }
        // Project the map-space position through the draw transform (map space
        // is Y-up; SVG is Y-down, hence the flip) to get the on-screen point.
        final double screenX = offsetX + scale * pos[0];
        final double screenY = offsetY - scale * pos[1];
        final Element panel = getView().getFocusPanel().getElement();
        // Margin 0.5 collapses the dead zone to the centre point (hard-centre).
        final double margin = centreOnNextFollow
                ? 0.5
                : FloorMapViewport.DEFAULT_FOLLOW_MARGIN;
        centreOnNextFollow = false;
        final double[] delta = FloorMapViewport.followDelta(screenX, screenY,
                panel.getOffsetWidth(), panel.getOffsetHeight(), margin);
        offsetX += delta[0];
        offsetY += delta[1];
    }

    /**
     * Resolves the tracked entity's current map-space position, preferring the
     * live interpolated animation position, then the last known rendered
     * position, then the event draw list.
     *
     * @return {@code {mapX, mapY}}, or {@code null} if the entity is unknown
     */
    private double[] trackedPosition() {
        final EntityAnimation animation = activeAnimations.get(trackedObjectId);
        if (animation != null) {
            return new double[]{animation.currentX(), animation.currentY()};
        }
        final FloorMapObject last = lastEntityPositions.get(trackedObjectId);
        if (last != null) {
            return new double[]{last.getX(), last.getY()};
        }
        for (final FloorMapObject obj : eventObjects) {
            if (trackedObjectId.equals(obj.getId())) {
                return new double[]{obj.getX(), obj.getY()};
            }
        }
        return null;
    }

    /**
     * Returns the facts to render, applying any in-progress drag: selected facts
     * have their world-to-map translated by the accumulated map-space delta so the
     * drag is shown live. On drop the delta is persisted via
     * {@link DragHandler#onTranslate}.
     */
    private List<Fact> factsForDraw() {
        if (selectedObjectIds.isEmpty() || (dragDxMap == 0 && dragDyMap == 0)) {
            return facts;
        }
        final List<Fact> out = new ArrayList<>(facts.size());
        for (final Fact fact : facts) {
            if (selectedObjectIds.contains(fact.getKey())) {
                final FloorMapTransformationMatrix m = fact.getWorldToMap();
                out.add(new Fact(fact.getKey(), fact.getType(), fact.getImage(),
                        new FloorMapTransformationMatrix(m.getA(), m.getB(), m.getC(), m.getD(),
                                m.getE() + dragDxMap, m.getF() + dragDyMap),
                        fact.getPosition()));
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
     * Sets the event-driven entity overlays (events query result).
     * <p>
     * When the timeline is <em>not</em> playing, all objects are placed at their
     * target positions immediately (teleport behaviour).
     * <p>
     * When the timeline <em>is</em> playing, every entity is animated from its
     * last known position to the new one, regardless of type.
     */
    public void setEventObjects(final List<FloorMapObject> objects) {
        if (!isPlaying || pendingTeleport) {
            // Not playing, or a discontinuous time jump just occurred — teleport all entities.
            this.eventObjects = objects != null ? objects : new ArrayList<>();
            activeAnimations.clear();
            // Record positions for play-start animation anchor.
            if (objects != null) {
                for (final FloorMapObject obj : objects) {
                    lastEntityPositions.put(obj.getId(), new FloorMapObject(
                            obj.getId(), obj.getType(), obj.getX(), obj.getY()));
                }
            }
            // One-shot: clear the teleport flag now that positions are committed.
            pendingTeleport = false;
            // Keep the tracked entity in view after a scrub/paused refresh.
            applyFollow();
            redraw();
            return;
        }

        final List<FloorMapObject> unanimated = new ArrayList<>();

        if (objects != null) {
            for (final FloorMapObject obj : objects) {
                if (handleEntityUpdate(obj)) {
                    // Entity placed without animation (first appearance, etc.) —
                    // add to draw list so it's visible.
                    unanimated.add(obj);
                }
            }
        }

        this.eventObjects = unanimated;
        ensureAnimationLoop();
        // Covers the tracked entity's first appearance while playing (placed
        // without an animation) — subsequent frames follow via the loop.
        applyFollow();
        // Force a paint so updates that don't start an animation still repaint
        // during playback — the animation loop returns without drawing when
        // there is nothing to animate.
        redraw();
    }

    /**
     * Toggles edit mode. When disabled, the object selection is cleared and
     * object dragging is turned off.
     *
     * @param editMode {@code true} to enter edit mode, {@code false} to leave
     */
    public void setEditMode(final boolean editMode) {
        this.editMode = editMode;
        if (!editMode) {
            selectedObjectIds.clear();
        }

        isDraggingEnabled = false;
        redraw();
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
     * Sets the facts to render (backgrounds + static facts) as produced by the
     * parser. Replaces the legacy background-image/matrix/objects inputs.
     *
     * @param facts the facts; {@code null} is treated as empty
     */
    public void setFacts(final List<Fact> facts) {
        this.facts = facts != null ? facts : new ArrayList<>();
        redraw();
    }

    /**
     * Enables or disables object dragging within edit mode.
     *
     * @param isDraggingEnabled {@code true} to allow dragging selected objects
     */
    public void setIsDraggingEnabled(final boolean isDraggingEnabled) {
        this.isDraggingEnabled = isDraggingEnabled;
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
     * Callback invoked when a drag gesture completes, to persist the move as a
     * single translation of the whole selection in map space.
     */
    public interface DragHandler {

        /**
         * @param keys  the selected fact keys that were dragged
         * @param dxMap the accumulated X translation in map space
         * @param dyMap the accumulated Y translation in map space (Y-up)
         */
        void onTranslate(Collection<String> keys, double dxMap, double dyMap);
    }

    // =========================================================================
    // Inner classes
    // =========================================================================

    /**
     * Tracks a single in-progress movement animation for a person entity.
     * <p>
     * Progress is advanced externally each animation frame by adding
     * {@code deltaMs / ANIMATION_DURATION_MS}, so no absolute start timestamp is
     * stored — the animation is insulated from any time-base differences between
     * {@link com.google.gwt.core.client.Duration#currentTimeMillis()} and the
     * {@link AnimationScheduler} callback timestamp.
     * <p>
     * Interpolation is deliberately <strong>linear</strong>: for timeline playback
     * of historical data the destination is just the last recorded position, not a
     * physical stopping point, so ease-in-out deceleration looks unnatural.
     */
    private static class EntityAnimation {

        final String id;
        final String type;
        final double fromX;
        final double fromY;
        final double toX;
        final double toY;
        double progress; // 0.0 → 1.0, advanced per frame by the animation loop

        EntityAnimation(final String id,
                      final String type,
                      final double fromX,
                      final double fromY,
                      final double toX,
                      final double toY) {
            this.id = id;
            this.type = type;
            this.fromX = fromX;
            this.fromY = fromY;
            this.toX = toX;
            this.toY = toY;
            this.progress = 0.0;
        }

        /** Current interpolated X position in map space (linear). */
        double currentX() {
            return fromX + (toX - fromX) * progress;
        }

        /** Current interpolated Y position in map space (linear). */
        double currentY() {
            return fromY + (toY - fromY) * progress;
        }
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
         */
        void draw(double scale, double x, double y, List<Fact> facts,
                List<FloorMapObject> events, Set<String> selectedObjectIds,
                List<TypeStyle> typeStyles, boolean showGrid);

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
         * presenter uses this to apply its size-dependent default view (which
         * needs the canvas height to place the origin at the bottom-left).
         *
         * @param resizeListener the callback to invoke, typically
         *                       {@code FloorMapCanvasPresenter::applyDefaultView}
         */
        void setResizeListener(Runnable resizeListener);
    }

}
