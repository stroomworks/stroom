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

package stroom.floormap.shared;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The floor-map entity animation "data machine": tracks event entities as they
 * move over time, interpolating between positions, recording fading movement
 * trails, and teleporting on discontinuous time jumps.
 *
 * <p>Kept out of the GWT canvas presenter so this logic — the animate-vs-teleport
 * decision, the return-to-previous-target case, trail capping and fade timing — is
 * unit-testable on the JVM. It holds no GWT/DOM
 * types and knows nothing about rendering or scheduling: the presenter owns the
 * {@code AnimationScheduler} loop, camera-follow and the SVG draw, and drives
 * this class one frame at a time via {@link #advanceFrame}.</p>
 *
 * <p>All positions are in map space. The presenter decorates the returned draw
 * list with image "twins" (a view concern) before rendering.</p>
 */
public final class FloorMapEntityAnimator {

    /** Duration of a single entity move animation, in ms. */
    private static final double ANIMATION_DURATION_MS = 800.0;

    /** Maximum recorded trail points per entity (bounds memory during long playback). */
    private static final int TRAIL_MAX_PTS = 5000;

    /** How long (wall-clock ms) a trail takes to fade out after the entity stops. */
    private static final double TRAIL_FADE_DURATION_MS = 2000.0;

    /** {@code true} while the timeline is actively playing. */
    private boolean isPlaying = false;

    /**
     * When {@code true}, the next {@link #onEventObjects} teleports entities to
     * their new positions rather than animating, even while playing. Set by
     * {@link #clear()} so a scrub/skip places entities instantly.
     */
    private boolean pendingTeleport = false;

    /** In-flight animations keyed by entity id. */
    private final Map<String, EntityAnimation> activeAnimations = new HashMap<>();

    /** Last known rendered state (id, type, map position) per entity. */
    private final Map<String, FloorMapObject> lastEntityPositions = new HashMap<>();

    /** Trail points per entity, each {@code [mapX, mapY]}; oldest first, capped. */
    private final Map<String, List<double[]>> entityTrails = new HashMap<>();

    /** Timestamp each entity's last animation finished, initiating the trail fade. */
    private final Map<String, Double> trailFadeStartTimes = new HashMap<>();

    /** The current non-animated event overlay (set by {@link #onEventObjects}). */
    private List<FloorMapObject> eventObjects = new ArrayList<>();

    /** Sets whether the timeline is playing (drives animate-vs-teleport). */
    public void setPlaying(final boolean playing) {
        this.isPlaying = playing;
    }

    /**
     * Discards all in-flight animations and trail data and arms a teleport for
     * the next {@link #onEventObjects}. Call on a discontinuous time jump
     * (scrub/skip/loop-around). Does not touch {@link #eventObjects} (the last
     * drawn overlay stays until the next update).
     */
    public void clear() {
        activeAnimations.clear();
        entityTrails.clear();
        trailFadeStartTimes.clear();
        pendingTeleport = true;
    }

    /**
     * Applies a fresh set of event entities.
     *
     * <p>Teleport path (not playing, or a pending teleport): entities jump to
     * their new positions, stale per-entity state for vanished entities is
     * pruned, and trails are dropped. Animate path (playing): each changed
     * entity starts an animation from its current position; unchanged/animating
     * ones are owned by the loop.</p>
     *
     * @param objects the new entities (may be {@code null})
     * @return {@code true} if this was a teleport (instant), {@code false} if it
     *         started/continued animations (the caller should run the loop)
     */
    public boolean onEventObjects(final List<FloorMapObject> objects) {
        final List<FloorMapObject> objs = objects != null ? objects : new ArrayList<>();

        if (!isPlaying || pendingTeleport) {
            this.eventObjects = objs;
            // Prune per-entity state for entities no longer present so a vanished
            // entity can't linger as a ghost. Only on the teleport path — not on
            // every event — so partial playback result batches don't strip
            // anchors and make a still-present entity teleport instead of animate.
            final Set<String> currentIds = new HashSet<>();
            for (final FloorMapObject obj : objs) {
                currentIds.add(obj.getId());
            }
            lastEntityPositions.keySet().retainAll(currentIds);
            activeAnimations.clear();
            // Teleport = instant placement, so no in-progress journeys/trails.
            entityTrails.clear();
            trailFadeStartTimes.clear();
            for (final FloorMapObject obj : objs) {
                lastEntityPositions.put(obj.getId(), new FloorMapObject(
                        obj.getId(), obj.getType(), obj.getX(), obj.getY()));
            }
            pendingTeleport = false;
            return true;
        }

        final List<FloorMapObject> unanimated = new ArrayList<>();
        for (final FloorMapObject obj : objs) {
            if (handleEntityUpdate(obj)) {
                unanimated.add(obj);
            }
        }
        this.eventObjects = unanimated;
        return false;
    }

    /**
     * Advances all in-flight animations and trail fades by one frame.
     *
     * @param timestampMs the current scheduler timestamp (ms), for trail fade timing
     * @param deltaMs      elapsed time since the previous frame (ms), for progress
     * @return {@code true} if anything is still animating or fading (the caller
     *         should keep the loop running)
     */
    public boolean advanceFrame(final double timestampMs, final double deltaMs) {
        final List<String> finished = new ArrayList<>();
        for (final Map.Entry<String, EntityAnimation> entry : activeAnimations.entrySet()) {
            final EntityAnimation anim = entry.getValue();
            anim.progress = Math.min(1.0, anim.progress + deltaMs / ANIMATION_DURATION_MS);
            recordTrailPoint(anim.id, anim.currentX(), anim.currentY());
            if (anim.progress >= 1.0) {
                lastEntityPositions.put(anim.id, new FloorMapObject(
                        anim.id, anim.type, anim.toX, anim.toY));
                finished.add(anim.id);
                trailFadeStartTimes.put(anim.id, timestampMs);
            }
        }
        for (final String id : finished) {
            activeAnimations.remove(id);
        }

        final List<String> doneFading = new ArrayList<>();
        for (final Map.Entry<String, Double> fade : trailFadeStartTimes.entrySet()) {
            final String id = fade.getKey();
            if (activeAnimations.containsKey(id)) {
                doneFading.add(id); // moving again — cancel the fade
            } else if (timestampMs - fade.getValue() >= TRAIL_FADE_DURATION_MS) {
                entityTrails.remove(id); // fully faded
                doneFading.add(id);
            }
        }
        for (final String id : doneFading) {
            trailFadeStartTimes.remove(id);
        }

        return isActive();
    }

    /**
     * Builds the event-overlay draw list: the non-animated entities plus each
     * animated entity at its interpolated position and each stationary entity at
     * its last position, with trail data attached. Does <em>not</em> decorate
     * with image twins — that is a rendering concern the caller handles.
     *
     * @param nowMs current scheduler timestamp (ms) for trail alpha; {@code 0}
     *              when the loop is not running (no fade applied)
     * @return the overlay entities to draw
     */
    public List<FloorMapObject> buildDrawList(final double nowMs) {
        final List<FloorMapObject> combined = new ArrayList<>(eventObjects);

        for (final Map.Entry<String, EntityAnimation> entry : activeAnimations.entrySet()) {
            final EntityAnimation anim = entry.getValue();
            final FloorMapObject obj = new FloorMapObject(
                    anim.id, anim.type, anim.currentX(), anim.currentY());
            attachTrail(obj, anim.id, nowMs);
            combined.add(obj);
        }

        final Set<String> drawnIds = new HashSet<>();
        for (final FloorMapObject obj : combined) {
            drawnIds.add(obj.getId());
        }
        for (final Map.Entry<String, FloorMapObject> entry : lastEntityPositions.entrySet()) {
            final String id = entry.getKey();
            if (!drawnIds.contains(id)) {
                final FloorMapObject last = entry.getValue();
                final FloorMapObject obj = new FloorMapObject(
                        id, last.getType(), last.getX(), last.getY());
                attachTrail(obj, id, nowMs);
                combined.add(obj);
            }
        }
        return combined;
    }

    /**
     * Returns the current map-space position of the entity: its live interpolated
     * animation position, else its last committed position, else its position in
     * the current overlay. {@code null} if the animator doesn't know it (the
     * caller may fall back to a static fact).
     *
     * @param id the entity id
     * @return {@code {mapX, mapY}}, or {@code null}
     */
    public double[] positionOf(final String id) {
        if (id == null) {
            return null;
        }
        final EntityAnimation animation = activeAnimations.get(id);
        if (animation != null) {
            return new double[]{animation.currentX(), animation.currentY()};
        }
        final FloorMapObject last = lastEntityPositions.get(id);
        if (last != null) {
            return new double[]{last.getX(), last.getY()};
        }
        for (final FloorMapObject obj : eventObjects) {
            if (id.equals(obj.getId())) {
                return new double[]{obj.getX(), obj.getY()};
            }
        }
        return null;
    }

    /** {@code true} if any animation is in flight or any trail is still fading. */
    public boolean isActive() {
        return !activeAnimations.isEmpty() || !trailFadeStartTimes.isEmpty();
    }

    // -----------------------------------------------------------------------

    /**
     * Records/updates one entity from an event refresh. When not playing (or a
     * teleport is pending) it just records the anchor position. When playing it
     * starts an animation from the entity's current position to the new one,
     * unless it is already heading there. Returns {@code true} if the caller
     * should draw the entity itself (not animated), {@code false} if the animator
     * now owns it.
     */
    private boolean handleEntityUpdate(final FloorMapObject obj) {
        if (!isPlaying || pendingTeleport) {
            lastEntityPositions.put(obj.getId(), new FloorMapObject(
                    obj.getId(), obj.getType(), obj.getX(), obj.getY()));
            return true;
        }

        final FloorMapObject last = lastEntityPositions.get(obj.getId());
        if (last == null) {
            lastEntityPositions.put(obj.getId(), new FloorMapObject(
                    obj.getId(), obj.getType(), obj.getX(), obj.getY()));
            return true;
        }

        final EntityAnimation existing = activeAnimations.get(obj.getId());
        final boolean alreadyAnimatingToTarget = existing != null
                && Math.abs(existing.toX - obj.getX()) < 0.001
                && Math.abs(existing.toY - obj.getY()) < 0.001;

        if (!alreadyAnimatingToTarget) {
            // Compare the new target against the CURRENT destination (the
            // in-flight animation's endpoint if animating, else the last
            // committed position) so a "return to A" update while animating A→B
            // isn't dropped as unchanged.
            final double refX = existing != null ? existing.toX : last.getX();
            final double refY = existing != null ? existing.toY : last.getY();
            final double dx = refX - obj.getX();
            final double dy = refY - obj.getY();
            if (dx * dx + dy * dy > 0.0001) {
                final double fromX = existing != null ? existing.currentX() : last.getX();
                final double fromY = existing != null ? existing.currentY() : last.getY();
                activeAnimations.put(obj.getId(), new EntityAnimation(
                        obj.getId(), obj.getType(), fromX, fromY, obj.getX(), obj.getY()));
                return false;
            }
        }
        return false;
    }

    /**
     * Attaches an {@code [x, y, alpha]} trail to {@code obj}: alpha runs 0 (oldest)
     * → 1 (newest), scaled by a global fade factor once the entity has stopped.
     */
    private void attachTrail(final FloorMapObject obj, final String id, final double nowMs) {
        final List<double[]> raw = entityTrails.get(id);
        if (raw == null || raw.isEmpty()) {
            return;
        }
        double fadeFactor = 1.0;
        final Double fadeStart = trailFadeStartTimes.get(id);
        if (fadeStart != null && nowMs > 0) {
            final double elapsed = nowMs - fadeStart;
            fadeFactor = Math.max(0.0, 1.0 - elapsed / TRAIL_FADE_DURATION_MS);
        }
        if (fadeFactor <= 0.0) {
            return;
        }
        final int size = raw.size();
        final List<double[]> trailWithAlpha = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            final double[] pt = raw.get(i);
            final double alpha = (size == 1 ? 1.0 : (double) i / (size - 1)) * fadeFactor;
            trailWithAlpha.add(new double[]{pt[0], pt[1], alpha});
        }
        obj.setTrail(trailWithAlpha);
    }

    /** Appends {@code [x, y]} to the entity's trail, dropping the oldest past the cap. */
    private void recordTrailPoint(final String id, final double x, final double y) {
        //noinspection unused k
        final List<double[]> trail = entityTrails.computeIfAbsent(id, k -> new ArrayList<>());
        trail.add(new double[]{x, y});
        while (trail.size() > TRAIL_MAX_PTS) {
            //noinspection SequencedCollectionMethodCanBeUsed GWT does not support removeFirst
            trail.remove(0);
        }
    }

    // -----------------------------------------------------------------------

    /** A single in-flight entity move, interpolated linearly by {@link #progress}. */
    private static final class EntityAnimation {

        private final String id;
        private final String type;
        private final double fromX;
        private final double fromY;
        private final double toX;
        private final double toY;
        private double progress; // 0.0 → 1.0

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

        double currentX() {
            return fromX + (toX - fromX) * progress;
        }

        double currentY() {
            return fromY + (toY - fromY) * progress;
        }
    }
}
