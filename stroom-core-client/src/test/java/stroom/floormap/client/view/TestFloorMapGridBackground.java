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

package stroom.floormap.client.view;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestFloorMapGridBackground {


    @Test
    void majorSpacingStaysInComfortableRange() {
        // Simulate various effective scales (matrixScale × userZoom)
        // and verify major grid spacing produces screen pixels within
        // the [TARGET_MIN_PX, TARGET_MAX_PX] comfort range.
        for (final double effectiveScale : new double[]{0.5, 1, 5, 20, 50, 200, 1000}) {
            final double[] params = FloorMapGridBackground.computeGridParams(effectiveScale);
            final double majorWorldSpacing = params[0];
            final double screenPx = majorWorldSpacing * effectiveScale;

            assertThat(screenPx)
                    .as("effectiveScale=%s → majorWorldSpacing=%s → screenPx=%s",
                            effectiveScale, majorWorldSpacing, screenPx)
                    .isGreaterThanOrEqualTo(FloorMapGridBackground.TARGET_MIN_PX)
                    .isLessThanOrEqualTo(FloorMapGridBackground.TARGET_MAX_PX);
        }
    }

    @Test
    void minorOpacityIsZeroAtDecadeFloor() {
        // At exactly TARGET_MIN_PX screen spacing, minor opacity should be ~0.
        // effectiveScale = 4.0 → majorWorldSpacing = 10 → screenPx = 40 = TARGET_MIN_PX
        final double[] params = FloorMapGridBackground.computeGridParams(4.0);
        final double minorOpacity = params[1];
        assertThat(minorOpacity).isLessThan(0.01);
    }

    @Test
    void minorOpacityIncreasesWithZoomWithinSameDecade() {
        // Two effective scales that produce the same decade but different
        // positions within it. Higher scale → larger screenPx → higher opacity.
        // effectiveScale=5 → majorWorldSpacing=10 → screenPx=50 (low in decade)
        // effectiveScale=30 → majorWorldSpacing=10 → screenPx=300 (high in decade)
        final double[] paramsLow = FloorMapGridBackground.computeGridParams(5.0);
        final double[] paramsHigh = FloorMapGridBackground.computeGridParams(30.0);

        // Both should select majorWorldSpacing = 10
        assertThat(paramsLow[0])
                .as("Low-zoom major spacing")
                .isEqualTo(10.0);
        assertThat(paramsHigh[0])
                .as("High-zoom major spacing")
                .isEqualTo(10.0);

        // Higher zoom should have higher minor opacity
        assertThat(paramsHigh[1])
                .as("Minor opacity at higher zoom")
                .isGreaterThan(paramsLow[1]);
    }

    @Test
    void decadesAreAlwaysPowerOf10() {
        for (final double effectiveScale : new double[]{0.1, 1, 10, 100, 1000}) {
            final double[] params = FloorMapGridBackground.computeGridParams(effectiveScale);
            final double majorWorldSpacing = params[0];
            final double log = Math.log10(majorWorldSpacing);
            assertThat(Math.abs(log - Math.round(log)))
                    .as("majorWorldSpacing=%s should be a power of 10", majorWorldSpacing)
                    .isLessThan(1e-9);
        }
    }

    @Test
    void minorOpacityNeverExceedsMaximum() {
        // At very high zoom the minor opacity should be clamped
        for (final double effectiveScale : new double[]{1000, 5000, 100000}) {
            final double[] params = FloorMapGridBackground.computeGridParams(effectiveScale);
            final double minorOpacity = params[1];
            assertThat(minorOpacity)
                    .as("Minor opacity at effectiveScale=%s", effectiveScale)
                    .isLessThanOrEqualTo(0.25 + 1e-9);  // MINOR_MAX_OPACITY = 0.25
        }
    }

    @Test
    void veryLowScaleStillProducesValidSpacing() {
        // Extremely zoomed out — should still produce a valid power-of-10 spacing
        final double[] params = FloorMapGridBackground.computeGridParams(0.001);
        final double majorWorldSpacing = params[0];
        assertThat(majorWorldSpacing).isGreaterThan(0);
        final double screenPx = majorWorldSpacing * 0.001;
        assertThat(screenPx)
                .isGreaterThanOrEqualTo(FloorMapGridBackground.TARGET_MIN_PX)
                .isLessThanOrEqualTo(FloorMapGridBackground.TARGET_MAX_PX);
    }

    @Test
    void extremeScalesProduceValidResults() {
        // The algorithm should work across the full zoom clamp range (1e-12 to 1e12)
        // without producing NaN or Infinity.
        for (final double effectiveScale : new double[]{1e-12, 1e-8, 1e-4, 1e4, 1e8, 1e12}) {
            final double[] params = FloorMapGridBackground.computeGridParams(effectiveScale);
            final double majorWorldSpacing = params[0];
            final double minorOpacity = params[1];

            assertThat(majorWorldSpacing)
                    .as("majorWorldSpacing at effectiveScale=%s", effectiveScale)
                    .isGreaterThan(0)
                    .isFinite();
            assertThat(minorOpacity)
                    .as("minorOpacity at effectiveScale=%s", effectiveScale)
                    .isGreaterThanOrEqualTo(0.0)
                    .isFinite();

            // Screen pixels should still be in the comfort range
            final double screenPx = majorWorldSpacing * effectiveScale;
            assertThat(screenPx)
                    .as("screenPx at effectiveScale=%s", effectiveScale)
                    .isGreaterThanOrEqualTo(FloorMapGridBackground.TARGET_MIN_PX)
                    .isLessThanOrEqualTo(FloorMapGridBackground.TARGET_MAX_PX);
        }
    }

    @Test
    void zeroScaleReturnsDefaults() {
        final double[] params = FloorMapGridBackground.computeGridParams(0.0);
        assertThat(params[0]).isEqualTo(1.0);
        assertThat(params[1]).isEqualTo(0.0);
    }

    @Test
    void negativeScaleReturnsDefaults() {
        final double[] params = FloorMapGridBackground.computeGridParams(-5.0);
        assertThat(params[0]).isEqualTo(1.0);
        assertThat(params[1]).isEqualTo(0.0);
    }

    @Test
    void nanScaleReturnsDefaults() {
        final double[] params = FloorMapGridBackground.computeGridParams(Double.NaN);
        assertThat(params[0]).isEqualTo(1.0);
        assertThat(params[1]).isEqualTo(0.0);
    }

    @Test
    void infiniteScaleReturnsDefaults() {
        final double[] params = FloorMapGridBackground.computeGridParams(Double.POSITIVE_INFINITY);
        assertThat(params[0]).isEqualTo(1.0);
        assertThat(params[1]).isEqualTo(0.0);
    }

    @Test
    void gridAdaptsCorrectlyAcrossMultipleDecades() {
        // Walk through 6 orders of magnitude and verify each decade transition
        // produces a new power-of-10 major spacing.
        double previousSpacing = Double.MAX_VALUE;
        for (int exp = -3; exp <= 3; exp++) {
            final double effectiveScale = Math.pow(10, exp);
            final double[] params = FloorMapGridBackground.computeGridParams(effectiveScale);
            final double majorWorldSpacing = params[0];

            // As effectiveScale increases, majorWorldSpacing should decrease
            // (finer grid at higher zoom)
            if (exp > -3) {
                assertThat(majorWorldSpacing)
                        .as("Spacing should decrease as zoom increases (exp=%s)", exp)
                        .isLessThanOrEqualTo(previousSpacing);
            }
            previousSpacing = majorWorldSpacing;
        }
    }
}
