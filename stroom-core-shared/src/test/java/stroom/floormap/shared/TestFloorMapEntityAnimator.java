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
}
