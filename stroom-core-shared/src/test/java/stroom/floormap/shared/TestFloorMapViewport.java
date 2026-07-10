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

import stroom.test.common.TestUtil;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link FloorMapViewport} — the pure pan/zoom/drag maths extracted
 * from the canvas presenter. Verifies coordinate conversions, the
 * zoom-toward-cursor invariant, scale clamping, and Edit-Mode drag deltas.
 */
class TestFloorMapViewport {

    private static final double TOLERANCE = 1e-9;

    // A non-trivial background matrix (scale + rotation + translation) so that a
    // wrong inverse produces a visibly wrong answer.
    private static final FloorMapTransformationMatrix BACKGROUND =
            new FloorMapTransformationMatrix(1.2, 0.9, -0.9, 1.2, 100, 50);

    // -----------------------------------------------------------------------
    // Round-trip: screenToMap and mapToScreen are exact inverses
    // -----------------------------------------------------------------------

    /**
     * For any viewport state and invertible background, mapping a screen point
     * to map space and back must recover the original screen point.
     */
    @TestFactory
    Stream<DynamicTest> testScreenToMapRoundTrip() {
        final double screenX = 640;
        final double screenY = 360;
        return TestUtil.buildDynamicTestStream()
                .withInputType(FloorMapViewport.class)
                .withOutputType(double[].class)
                .withTestFunction(testCase -> {
                    final FloorMapViewport viewport = testCase.getInput();
                    final double[] map = viewport.screenToMap(screenX, screenY, BACKGROUND);
                    return viewport.mapToScreen(map[0], map[1], BACKGROUND);
                })
                .withAssertions(outcome -> {
                    assertThat(outcome.getActualOutput()[0])
                            .isCloseTo(outcome.getExpectedOutput()[0], within(TOLERANCE));
                    assertThat(outcome.getActualOutput()[1])
                            .isCloseTo(outcome.getExpectedOutput()[1], within(TOLERANCE));
                })
                .addNamedCase("Default state",
                        new FloorMapViewport(), new double[]{screenX, screenY})
                .addNamedCase("Panned",
                        new FloorMapViewport(1.0, 200, -120), new double[]{screenX, screenY})
                .addNamedCase("Zoomed in",
                        new FloorMapViewport(3.5, 0, 0), new double[]{screenX, screenY})
                .addNamedCase("Zoomed out and panned",
                        new FloorMapViewport(0.25, -60, 40), new double[]{screenX, screenY})
                .build();
    }

    @Test
    void testScreenToMap_nullBackgroundTreatedAsIdentity() {
        final FloorMapViewport viewport = new FloorMapViewport(2.0, 100, 50);

        // With identity background, screenToMap is just the inverse zoom/pan.
        final double[] map = viewport.screenToMap(300, 250, null);

        assertThat(map[0]).isCloseTo((300 - 100) / 2.0, within(TOLERANCE));
        assertThat(map[1]).isCloseTo((250 - 50) / 2.0, within(TOLERANCE));
    }

    // -----------------------------------------------------------------------
    // Zoom toward cursor
    // -----------------------------------------------------------------------

    /**
     * The defining property of zoom-toward-cursor: the map point directly under
     * the cursor must not move on screen when zooming.
     */
    @Test
    void testZoom_keepsMapPointUnderCursorFixed() {
        final FloorMapViewport viewport = new FloorMapViewport(1.0, 30, 70);
        final double cursorX = 400;
        final double cursorY = 300;

        final double[] before = viewport.screenToMap(cursorX, cursorY, BACKGROUND);
        viewport.zoom(cursorX, cursorY, true);
        final double[] after = viewport.screenToMap(cursorX, cursorY, BACKGROUND);

        assertThat(viewport.getScale()).isCloseTo(FloorMapViewport.ZOOM_STEP, within(TOLERANCE));
        assertThat(after[0]).isCloseTo(before[0], within(TOLERANCE));
        assertThat(after[1]).isCloseTo(before[1], within(TOLERANCE));
    }

    @Test
    void testZoom_inThenOutRestoresState() {
        final FloorMapViewport viewport = new FloorMapViewport(1.0, 30, 70);

        viewport.zoom(400, 300, true);
        viewport.zoom(400, 300, false);

        assertThat(viewport.getScale()).isCloseTo(1.0, within(TOLERANCE));
        assertThat(viewport.getOffsetX()).isCloseTo(30, within(TOLERANCE));
        assertThat(viewport.getOffsetY()).isCloseTo(70, within(TOLERANCE));
    }

    @Test
    void testZoom_clampsAtMaxScale() {
        final FloorMapViewport viewport = new FloorMapViewport(
                FloorMapViewport.MAX_SCALE, 0, 0);
        viewport.zoom(0, 0, true);
        assertThat(viewport.getScale()).isEqualTo(FloorMapViewport.MAX_SCALE);
    }

    @Test
    void testZoom_clampsAtMinScale() {
        final FloorMapViewport viewport = new FloorMapViewport(
                FloorMapViewport.MIN_SCALE, 0, 0);
        viewport.zoom(0, 0, false);
        assertThat(viewport.getScale()).isEqualTo(FloorMapViewport.MIN_SCALE);
    }

    // -----------------------------------------------------------------------
    // Pan
    // -----------------------------------------------------------------------

    @Test
    void testPan_accumulatesOffset() {
        final FloorMapViewport viewport = new FloorMapViewport(2.0, 10, 20);
        viewport.pan(5, -8);
        viewport.pan(3, 4);

        // Pan is a raw screen delta and is independent of scale.
        assertThat(viewport.getOffsetX()).isEqualTo(18);
        assertThat(viewport.getOffsetY()).isEqualTo(16);
        assertThat(viewport.getScale()).isEqualTo(2.0);
    }

    // -----------------------------------------------------------------------
    // Background drag
    // -----------------------------------------------------------------------

    /**
     * Dragging the background moves only its translation components, reduced by
     * the zoom scale; the linear part (a, b, c, d) is untouched.
     */
    @Test
    void testDragBackground_translatesOnlyByDeltaOverScale() {
        final FloorMapViewport viewport = new FloorMapViewport(2.0, 0, 0);
        final FloorMapTransformationMatrix result =
                viewport.dragBackground(BACKGROUND, 40, -20);

        assertThat(result.getA()).isEqualTo(BACKGROUND.getA());
        assertThat(result.getB()).isEqualTo(BACKGROUND.getB());
        assertThat(result.getC()).isEqualTo(BACKGROUND.getC());
        assertThat(result.getD()).isEqualTo(BACKGROUND.getD());
        assertThat(result.getE()).isCloseTo(BACKGROUND.getE() + 40 / 2.0, within(TOLERANCE));
        assertThat(result.getF()).isCloseTo(BACKGROUND.getF() + -20 / 2.0, within(TOLERANCE));
    }

    @Test
    void testDragBackground_nullMatrixStartsFromIdentity() {
        final FloorMapViewport viewport = new FloorMapViewport(1.0, 0, 0);
        final FloorMapTransformationMatrix result =
                viewport.dragBackground(null, 15, 25);

        assertThat(result).isEqualTo(new FloorMapTransformationMatrix(1, 0, 0, 1, 15, 25));
    }

    // -----------------------------------------------------------------------
    // Item drag
    // -----------------------------------------------------------------------

    /**
     * Dragging a plotted item: a screen delta must become the map-space delta
     * that, added to the item's map coordinates, lands it exactly under the
     * cursor. We verify this against the round-trip: the map position of the
     * cursor start and end points should differ by exactly the returned delta.
     */
    @Test
    void testDragItemMapDelta_matchesScreenToMapDifference() {
        final FloorMapViewport viewport = new FloorMapViewport(1.5, 40, -30);
        final double startX = 200;
        final double startY = 150;
        final double screenDeltaX = 60;
        final double screenDeltaY = -25;

        final double[] mapStart = viewport.screenToMap(startX, startY, BACKGROUND);
        final double[] mapEnd = viewport.screenToMap(
                startX + screenDeltaX, startY + screenDeltaY, BACKGROUND);
        final double[] delta = viewport.dragItemMapDelta(BACKGROUND, screenDeltaX, screenDeltaY);

        assertThat(delta[0]).isCloseTo(mapEnd[0] - mapStart[0], within(TOLERANCE));
        assertThat(delta[1]).isCloseTo(mapEnd[1] - mapStart[1], within(TOLERANCE));
    }

    @Test
    void testDragItemMapDelta_identityBackgroundIsJustUnzoomedDelta() {
        final FloorMapViewport viewport = new FloorMapViewport(4.0, 0, 0);
        final double[] delta = viewport.dragItemMapDelta(null, 80, -40);

        assertThat(delta[0]).isCloseTo(80 / 4.0, within(TOLERANCE));
        assertThat(delta[1]).isCloseTo(-40 / 4.0, within(TOLERANCE));
    }
}
