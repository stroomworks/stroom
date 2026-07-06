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
import stroom.floormap.client.event.MapObjectMovedEvent;
import stroom.floormap.client.event.MapObjectSelectedEvent;
import stroom.floormap.client.presenter.FloorMapCanvasPresenter.FloorMapCanvasView;
import stroom.floormap.shared.FloorMapObject;
import stroom.floormap.shared.FloorMapTransformationMatrix;

import com.google.gwt.animation.client.AnimationScheduler;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;

/**
 * Presenter for the interactive SVG map canvas.
 *
 * <p>Manages zoom, pan, object selection and drag-move in edit mode, and
 * smooth person-movement animations with fading trails during timeline
 * playback.  Two separate object lists are maintained — {@code factObjects}
 * for static floor-plan items and {@code eventObjects} for event-driven
 * entities — so that facts and events never overwrite each other.</p>
 *
 * <p>The canvas view renders the combined draw list produced by
 * {@link #buildAnimatedDrawList(double)} via its
 * {@link FloorMapCanvasView#draw draw()} method.</p>
 */
public class FloorMapCanvasPresenter extends MyPresenterWidget<FloorMapCanvasView> {

    // -------------------------------------------------------------------------
    // Animation constants
    // -------------------------------------------------------------------------

    /** How long (wall-clock ms) a single position-change animation lasts. */
    private static final double ANIMATION_DURATION_MS = 800.0;

    /**
     * Maximum number of recorded trail points per person.  Set high enough
     * that the trail covers the full journey during normal playback (~83 s
     * at 60 fps).  The single SVG {@code <path>} rendering makes this
     * inexpensive; trail data is cleaned up on fade completion and
     * discontinuous time jumps.
     */
    private static final int TRAIL_MAX_PTS = 5000;

    /** How long (wall-clock ms) trails take to fade out after the person stops moving. */
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

    // Zoom and pan state
    private double scale = 1.0;
    private double offsetX = 0;
    private double offsetY = 0;
    private String backgroundImage;
    private FloorMapTransformationMatrix matrix;

    // Dragging state
    private boolean isDraggingEnabled = false;
    private DragHandler dragHandler;
    private boolean isDragging = false;
    /** True only if the mouse actually moved while dragging an object (distinguishes click-to-select from drag). */
    private boolean hasMoved = false;
    private double lastMouseX;
    private double lastMouseY;

    // Objects on the map — kept in two separate lists so facts and events never overwrite each other.
    private List<FloorMapObject> factObjects = new ArrayList<>();

    /**
     * Non-person event objects (and people when NOT playing) set here directly.
     * When playing, animated people are built dynamically in {@link #buildAnimatedDrawList}.
     */
    private List<FloorMapObject> eventObjects = new ArrayList<>();

    // Edit mode
    private boolean editMode = false;
    private String selectedObjectId = null;

    // -------------------------------------------------------------------------
    // Playback / animation state
    // -------------------------------------------------------------------------

    /** {@code true} while the timeline is actively playing. */
    private boolean isPlaying = false;

    /**
     * When {@code true}, the next call to {@link #setEventObjects} will teleport
     * persons directly to their new positions without creating animations, even if
     * {@link #isPlaying} is {@code true}.  Set by {@link #clearAnimationState()} so
     * that a scrub/skip/loop-around places users instantly rather than replaying a
     * batch of movements.
     */
    private boolean pendingTeleport = false;

    /**
     * In-flight animations keyed by person id.  Only populated while playing.
     */
    private final Map<String, UserAnimation> activeAnimations = new HashMap<>();

    /**
     * Last known rendered position (map-space) for each person, used as the
     * start point for the next animation.
     */
    private final Map<String, double[]> lastPersonPositions = new HashMap<>();

    /**
     * Trail points for each person.  Each entry is {@code [mapX, mapY]}.
     * Points are appended during animation; oldest are at the front.
     * The list is bounded by {@link #TRAIL_MAX_PTS}.
     */
    private final Map<String, List<double[]>> personTrails = new HashMap<>();

    /**
     * AnimationScheduler timestamp when each person's last animation completed,
     * initiating the trail fade-out.  Entries are removed once the fade finishes
     * or if the person starts a new animation.
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
            getView().onResize();
            getView().setRedrawListener(this::redraw);
        }

        // Perform initial draw
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
     * {@link #setEventObjects} places persons at their new positions instantly
     * (teleport) rather than animating them from stale positions, even when
     * {@link #isPlaying} is {@code true}.
     */
    public void clearAnimationState() {
        activeAnimations.clear();
        personTrails.clear();
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
    }

    // =========================================================================
    // Drawing
    // =========================================================================

    private void redraw() {
        getView().draw(scale, offsetX, offsetY, backgroundImage, matrix,
                buildAnimatedDrawList(/* nowMs — irrelevant when no animations */ 0.0),
                selectedObjectId);
    }

    /**
     * Builds the combined list of objects to draw, substituting animated people
     * at their current interpolated positions and attaching trail data.
     *
     * @param nowMs Current wall-clock time in ms (used to compute trail alpha).
     *              Pass {@code 0.0} when there are no active animations.
     */
    private List<FloorMapObject> buildAnimatedDrawList(final double nowMs) {
        final List<FloorMapObject> combined = new ArrayList<>(factObjects);
        combined.addAll(eventObjects); // non-person events are already in eventObjects

        // People currently mid-animation — add at their interpolated position.
        for (final Map.Entry<String, UserAnimation> entry : activeAnimations.entrySet()) {
            final UserAnimation anim = entry.getValue();
            final FloorMapObject obj = new FloorMapObject(
                    anim.id, anim.type, anim.currentX(), anim.currentY());
            attachTrail(obj, anim.id, nowMs);
            combined.add(obj);
        }

        // Stationary people (animation finished or idle during playback) — draw
        // at their last known position and attach any fading trail.  A set of
        // already-drawn person IDs prevents duplicates when the same person is
        // also present in factObjects or eventObjects (e.g. after play stops).
        final Set<String> drawnPersonIds = new HashSet<>();
        for (final FloorMapObject obj : combined) {
            if ("person".equalsIgnoreCase(obj.getType())) {
                drawnPersonIds.add(obj.getId());
            }
        }
        for (final Map.Entry<String, double[]> entry : lastPersonPositions.entrySet()) {
            final String id = entry.getKey();
            if (!activeAnimations.containsKey(id) && !drawnPersonIds.contains(id)) {
                final FloorMapObject obj = new FloorMapObject(
                        id, "person", entry.getValue()[0], entry.getValue()[1]);
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
     * When the person has stopped moving, a global fade factor is applied on top
     * of the index-based gradient so the trail fades out over
     * {@link #TRAIL_FADE_DURATION_MS} and then disappears.
     *
     * @param nowMs current AnimationScheduler timestamp in ms, used to compute
     *              the fade factor for stopped people.  Pass {@code 0.0} when
     *              the animation loop is not running.
     */
    private void attachTrail(final FloorMapObject obj, final String id, final double nowMs) {
        final List<double[]> raw = personTrails.get(id);
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
                    for (final Map.Entry<String, UserAnimation> entry : activeAnimations.entrySet()) {
                        final UserAnimation anim = entry.getValue();
                        anim.progress = Math.min(1.0, anim.progress + deltaMs / ANIMATION_DURATION_MS);

                        // Record the current interpolated position into the trail.
                        recordTrailPoint(anim.id, anim.currentX(), anim.currentY());

                        if (anim.progress >= 1.0) {
                            // Snap to the destination and record final position.
                            lastPersonPositions.put(anim.id, new double[]{anim.toX, anim.toY});
                            finished.add(anim.id);
                            // Start fading the trail for this person.
                            trailFadeStartTimes.put(anim.id, timestamp);
                        }
                    }
                    for (final String id : finished) {
                        activeAnimations.remove(id);
                    }

                    // Process fading trails: remove entries that are fully faded or
                    // whose person has started a new animation.
                    final List<String> doneFading = new ArrayList<>();
                    for (final Map.Entry<String, Double> fade : trailFadeStartTimes.entrySet()) {
                        final String id = fade.getKey();
                        if (activeAnimations.containsKey(id)) {
                            // Person started moving again — cancel the fade.
                            doneFading.add(id);
                        } else if (timestamp - fade.getValue() >= TRAIL_FADE_DURATION_MS) {
                            // Fully faded — remove trail data.
                            personTrails.remove(id);
                            doneFading.add(id);
                        }
                    }
                    for (final String id : doneFading) {
                        trailFadeStartTimes.remove(id);
                    }

                    // Draw the current frame.
                    getView().draw(scale, offsetX, offsetY, backgroundImage, matrix,
                            buildAnimatedDrawList(timestamp), selectedObjectId);

                    // Keep looping.
                    AnimationScheduler.get().requestAnimationFrame(this);
                }
            };

    /**
     * Shared logic for handling a person position update from either the facts
     * query ({@link #setFactObjects}) or the events query ({@link #setEventObjects}).
     * <p>
     * When not playing: records the position in {@link #lastPersonPositions} so
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
    private boolean handlePersonUpdate(final FloorMapObject obj) {
        if (!isPlaying || pendingTeleport) {
            // Teleport: record position for later play-start animation anchor.
            lastPersonPositions.put(obj.getId(), new double[]{obj.getX(), obj.getY()});
            return true; // caller adds to draw list
        }

        final double[] last = lastPersonPositions.get(obj.getId());
        if (last == null) {
            // First appearance while playing — place without animation.
            lastPersonPositions.put(obj.getId(), new double[]{obj.getX(), obj.getY()});
            return true;
        }

        // Check whether we already have an animation running toward this exact destination.
        final UserAnimation existing = activeAnimations.get(obj.getId());
        final boolean alreadyAnimatingToTarget = existing != null
                && Math.abs(existing.toX - obj.getX()) < 0.001
                && Math.abs(existing.toY - obj.getY()) < 0.001;

        if (!alreadyAnimatingToTarget) {
            final double dx = last[0] - obj.getX();
            final double dy = last[1] - obj.getY();
            if (dx * dx + dy * dy > 0.0001) {
                final double fromX = existing != null ? existing.currentX() : last[0];
                final double fromY = existing != null ? existing.currentY() : last[1];
                activeAnimations.put(obj.getId(), new UserAnimation(
                        obj.getId(), obj.getType(),
                        fromX, fromY,
                        obj.getX(), obj.getY()));
                // lastPersonPositions updated by animation loop when progress ≥ 1.0
                return false; // animation system owns this person
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
        final List<double[]> trail = personTrails.computeIfAbsent(id, k -> new ArrayList<>());
        trail.add(new double[]{x, y});

        // Hard cap to avoid unbounded growth.
        while (trail.size() > TRAIL_MAX_PTS) {
            trail.remove(0);
        }
    }

    // =========================================================================
    // Setters
    // =========================================================================

    /**
     * Sets the currently selected (highlighted) object on the canvas and redraws.
     *
     * @param selectedObjectId the object ID to highlight, or {@code null} to clear
     */
    public void setSelectedObjectId(final String selectedObjectId) {
        this.selectedObjectId = selectedObjectId;
        redraw();
    }

    /**
     * Sets the background's map-to-screen transformation matrix and redraws.
     *
     * @param matrix the new transformation matrix
     */
    public void setMatrix(final FloorMapTransformationMatrix matrix) {
        this.matrix = matrix;
        redraw();
    }

    /**
     * Returns the current background map-to-screen transformation matrix.
     *
     * @return the current matrix, or {@code null} if not yet set
     */
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
     * Gates, doors, desks etc. teleport; people with a {@code "person"} type
     * are routed through the same animation machinery as event-driven people.
     */
    public void setFactObjects(final List<FloorMapObject> objects) {
        final List<FloorMapObject> nonPersonFacts = new ArrayList<>();

        if (objects != null) {
            for (final FloorMapObject obj : objects) {
                if ("person".equalsIgnoreCase(obj.getType())) {
                    if (handlePersonUpdate(obj)) {
                        // Person placed without animation (not playing, first appearance, etc.) —
                        // add to draw list so it's visible.
                        nonPersonFacts.add(obj);
                    }
                } else {
                    nonPersonFacts.add(obj);
                }
            }
        }

        // One-shot: clear the teleport flag now that person positions have been committed.
        // Without this, a pendingTeleport set by clearAnimationState() (loop-around, scrub,
        // step, stop-at-end) would stick forever because setEventObjects — the only other
        // place that clears it — may never be called for fact-sourced person objects.
        pendingTeleport = false;

        this.factObjects = nonPersonFacts;

        if (isPlaying) {
            ensureAnimationLoop();
            // The animation loop calls draw(); avoid double-paint.
        } else {
            redraw();
        }
    }

    /**
     * Sets the event-driven entity overlays (events query result).
     * <p>
     * When the timeline is <em>not</em> playing, all objects are placed at their
     * target positions immediately (teleport behaviour).
     * <p>
     * When the timeline <em>is</em> playing, objects whose {@code type} is
     * {@code "person"} are animated from their last known position to the new one;
     * every other type continues to teleport.
     */
    public void setEventObjects(final List<FloorMapObject> objects) {
        if (!isPlaying || pendingTeleport) {
            // Not playing, or a discontinuous time jump just occurred — teleport all persons.
            this.eventObjects = objects != null ? objects : new ArrayList<>();
            activeAnimations.clear();
            // Record positions for play-start animation anchor.
            if (objects != null) {
                for (final FloorMapObject obj : objects) {
                    if ("person".equalsIgnoreCase(obj.getType())) {
                        lastPersonPositions.put(obj.getId(),
                                new double[]{obj.getX(), obj.getY()});
                    }
                }
            }
            // One-shot: clear the teleport flag now that positions are committed.
            pendingTeleport = false;
            redraw();
            return;
        }

        final List<FloorMapObject> nonPersons = new ArrayList<>();

        if (objects != null) {
            for (final FloorMapObject obj : objects) {
                if ("person".equalsIgnoreCase(obj.getType())) {
                    if (handlePersonUpdate(obj)) {
                        // Person placed without animation (first appearance, etc.) —
                        // add to draw list so it's visible.
                        nonPersons.add(obj);
                    }
                } else {
                    nonPersons.add(obj);
                }
            }
        }

        this.eventObjects = nonPersons;
        ensureAnimationLoop();
    }

    /**
     * Legacy convenience alias — routes to {@link #setFactObjects} so existing
     * edit-mode code paths (which only deal with facts) continue to work.
     */
    public void setObjects(final List<FloorMapObject> objects) {
        setFactObjects(objects);
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
            selectedObjectId = null;
        }

        isDraggingEnabled = false;
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
     * Callback interface for drag notifications.
     */
    public interface DragHandler {

        void onDrag(String objectId, double x, double y, FloorMapTransformationMatrix matrix);
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
    private static class UserAnimation {

        final String id;
        final String type;
        final double fromX;
        final double fromY;
        final double toX;
        final double toY;
        double progress; // 0.0 → 1.0, advanced per frame by the animation loop

        UserAnimation(final String id,
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

    public interface FloorMapCanvasView extends View, RequiresResize {

        HasMouseDownHandlers getFocusPanel();

        HasMouseMoveHandlers getMouseMoveHandlers();

        HasMouseUpHandlers getMouseUpHandlers();

        HasMouseWheelHandlers getMouseWheelHandlers();

        void draw(double scale, double x, double y, String backgroundImage,
                FloorMapTransformationMatrix matrix, List<FloorMapObject> objects,
                String selectedObjectId);

        void setRedrawListener(Runnable redrawListener);
    }

}
