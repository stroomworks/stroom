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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TestFloorMapEntityAnimator {

    private static final double TOL = 1e-6;
    private static final double DURATION_MS = 800.0;

    private FloorMapEntityAnimator animator;

    @BeforeEach
    void setUp() {
        animator = new FloorMapEntityAnimator();
    }

    private static FloorMapObject obj(final String id, final double x, final double y) {
        return new FloorMapObject(id, "person", x, y);
    }

    // -----------------------------------------------------------------------

    /** Not playing: entities teleport, positions are recorded, and it's inactive. */
    @Test
    void testTeleportRecordsPositions() {
        final boolean teleported = animator.onEventObjects(List.of(obj("a", 1, 2), obj("b", 3, 4)));
        assertThat(teleported).isTrue();
        assertThat(animator.isActive()).isFalse();
        assertThat(animator.positionOf("a")).containsExactly(1.0, 2.0);
        assertThat(animator.positionOf("b")).containsExactly(3.0, 4.0);
    }

    /**
     * The type is answerable for any entity the animator knows — including one
     * mid-animation, which is when the hover panel most needs it — and null for
     * one it has never seen.
     */
    @Test
    void testTypeOfMatchesPositionOf() {
        assertThat(animator.typeOf("a")).isNull();

        animator.onEventObjects(List.of(obj("a", 1, 2)));
        assertThat(animator.typeOf("a")).isEqualTo("person");
        assertThat(animator.typeOf("nobody")).isNull();
        assertThat(animator.typeOf(null)).isNull();

        // Mid-animation the position comes from the in-flight animation, so the
        // type must come from there too.
        animator.setPlaying(true);
        animator.onEventObjects(List.of(new FloorMapObject("a", "vehicle", 9, 9)));
        animator.advanceFrame(DURATION_MS / 2, DURATION_MS / 2);
        assertThat(animator.isActive()).isTrue();
        assertThat(animator.typeOf("a")).isEqualTo("vehicle");
    }

    /** A teleport prunes state for entities that are no longer present. */
    @Test
    void testTeleportPrunesVanishedEntities() {
        animator.onEventObjects(List.of(obj("a", 1, 1), obj("b", 2, 2)));
        animator.onEventObjects(List.of(obj("a", 5, 5)));
        assertThat(animator.positionOf("a")).containsExactly(5.0, 5.0);
        assertThat(animator.positionOf("b")).isNull();
    }

    /** Playing: a moved entity animates and, on frame completion, lands at the target. */
    @Test
    void testPlayingAnimatesToTarget() {
        animator.setPlaying(true);
        animator.onEventObjects(List.of(obj("a", 0, 0)));   // first appearance, recorded
        final boolean teleported = animator.onEventObjects(List.of(obj("a", 10, 0)));
        assertThat(teleported).isFalse();
        assertThat(animator.isActive()).isTrue();

        // Halfway through the animation the entity is at the midpoint.
        animator.advanceFrame(DURATION_MS / 2, DURATION_MS / 2);
        final FloorMapObject mid = drawn(animator.buildDrawList(0));
        assertThat(mid.getX()).isCloseTo(5.0, within(0.001));

        // Completing the animation lands it on the target.
        animator.advanceFrame(DURATION_MS, DURATION_MS / 2);
        assertThat(animator.positionOf("a")).containsExactly(10.0, 0.0);
        // A trail fade starts on completion, so it stays active until that ends...
        assertThat(animator.isActive()).isTrue();
        // ...and advancing past the fade duration returns it to idle (fade GC).
        animator.advanceFrame(DURATION_MS + 2000 + 1, 16);
        assertThat(animator.isActive()).isFalse();
    }

    /**
     * While animating A→B, an update back to A must NOT be dropped as unchanged
     * (regression for the return-to-previous-target bug).
     */
    @Test
    void testReturnToPreviousTargetWhileAnimating() {
        animator.setPlaying(true);
        animator.onEventObjects(List.of(obj("a", 0, 0)));
        animator.onEventObjects(List.of(obj("a", 10, 0)));   // animate 0 → 10
        animator.advanceFrame(DURATION_MS / 2, DURATION_MS / 2); // at x=5

        animator.onEventObjects(List.of(obj("a", 0, 0)));    // back to 0 — must re-animate
        assertThat(animator.isActive()).isTrue();
        animator.advanceFrame(DURATION_MS * 2, DURATION_MS);  // finish
        assertThat(animator.positionOf("a")).containsExactly(0.0, 0.0);
    }

    /** buildDrawList includes stationary (last-known) entities. */
    @Test
    void testBuildDrawListIncludesStationary() {
        animator.onEventObjects(List.of(obj("a", 2, 3)));
        final FloorMapObject a = drawn(animator.buildDrawList(0));
        assertThat(a.getX()).isCloseTo(2.0, within(TOL));
        assertThat(a.getY()).isCloseTo(3.0, within(TOL));
    }

    /** clear() arms a teleport: the next update is instant even while playing. */
    @Test
    void testClearArmsTeleport() {
        animator.setPlaying(true);
        animator.onEventObjects(List.of(obj("a", 0, 0)));
        animator.clear();
        final boolean teleported = animator.onEventObjects(List.of(obj("a", 99, 99)));
        assertThat(teleported).isTrue();
        assertThat(animator.positionOf("a")).containsExactly(99.0, 99.0);
    }

    private static FloorMapObject drawn(final List<FloorMapObject> list) {
        return list.stream().filter(o -> o.getId().equals("a")).findFirst().orElseThrow();
    }

    // -----------------------------------------------------------------------
    // Trail recording and decimation
    // -----------------------------------------------------------------------

    /**
     * Drives one entity along a straight line for {@code frames} frames, returning the trail the
     * renderer would be handed. Each frame advances the animation a little, so a trail point is
     * recorded per frame.
     */
    private List<double[]> trailAfterFrames(final int frames) {
        animator.setPlaying(true);
        animator.onEventObjects(List.of(obj("a", 0, 0)));
        animator.onEventObjects(List.of(obj("a", 10000, 0)));
        // A long animation so it stays in flight for every frame, recording a point each time.
        for (int i = 1; i <= frames; i++) {
            animator.advanceFrame(i, 1);
        }
        return drawn(animator.buildDrawList(0)).getTrail();
    }

    /** A short trail is passed through point-for-point - decimation must not kick in early. */
    @Test
    void testShortTrailIsNotDecimated() {
        final List<double[]> trail = trailAfterFrames(50);

        assertThat(trail).hasSize(50);
        // Alpha runs 0 (oldest) to 1 (newest).
        assertThat(trail.get(0)[2]).isCloseTo(0.0, within(TOL));
        assertThat(trail.get(trail.size() - 1)[2]).isCloseTo(1.0, within(TOL));
    }

    /**
     * Past the render cap the trail is decimated, not truncated: the point count is bounded but
     * the oldest point is still the start of the journey, so the trail keeps its full extent.
     */
    @Test
    void testLongTrailIsDecimatedNotTruncated() {
        final int frames = 2000;
        final List<double[]> trail = trailAfterFrames(frames);

        // Bounded well below the number of recorded points...
        assertThat(trail.size()).isLessThanOrEqualTo(401);
        assertThat(trail.size()).isGreaterThan(100);
        // ...but still spans the whole journey rather than just its tail.
        final double firstX = trail.get(0)[0];
        final double lastX = trail.get(trail.size() - 1)[0];
        assertThat(firstX).isLessThan(50.0);
        assertThat(lastX).isGreaterThan(firstX * 10);
        // Alpha still runs the full 0 -> 1 range across the decimated points.
        assertThat(trail.get(0)[2]).isCloseTo(0.0, within(TOL));
        assertThat(trail.get(trail.size() - 1)[2]).isCloseTo(1.0, within(TOL));
    }

    /**
     * The newest recorded point is the entity's current position, so it must always survive
     * decimation - otherwise the trail visibly lags behind the glyph it belongs to.
     */
    @Test
    void testDecimationAlwaysKeepsTheNewestPoint() {
        // 999 is deliberately not a multiple of the stride, so the newest point is only present
        // if it is explicitly appended.
        final List<double[]> trail = trailAfterFrames(999);
        final double[] newest = trail.get(trail.size() - 1);

        assertThat(newest[2]).isCloseTo(1.0, within(TOL));
        assertThat(newest[0]).isEqualTo(animator.positionOf("a")[0]);
        assertThat(newest[1]).isEqualTo(animator.positionOf("a")[1]);
    }

    /**
     * The ring buffer must wrap correctly: past its capacity the oldest points are overwritten
     * and the trail still reads oldest-first, with x strictly increasing along a straight run.
     */
    @Test
    void testTrailStaysOrderedOldestFirstAfterWrapping() {
        final List<double[]> trail = trailAfterFrames(300);

        for (int i = 1; i < trail.size(); i++) {
            assertThat(trail.get(i)[0])
                    .as("x at %d must exceed x at %d", i, i - 1)
                    .isGreaterThan(trail.get(i - 1)[0]);
            assertThat(trail.get(i)[2])
                    .as("alpha at %d must not decrease", i)
                    .isGreaterThanOrEqualTo(trail.get(i - 1)[2]);
        }
    }
}
