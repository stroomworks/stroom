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
import stroom.util.json.JsonUtil;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link FloorMapTransformationMatrix} — construction, rotation,
 * inversion (including the singular-matrix fallback), SVG formatting,
 * equality and JSON serialisation round-trip.
 */
class TestFloorMapTransformationMatrix {

    private static final double TOLERANCE = 1e-9;

    @Test
    void testConstructionAndGetters() {
        final FloorMapTransformationMatrix matrix =
                new FloorMapTransformationMatrix(1.5, 2.5, 3.5, 4.5, 5.5, 6.5);

        assertThat(matrix.getA()).isEqualTo(1.5);
        assertThat(matrix.getB()).isEqualTo(2.5);
        assertThat(matrix.getC()).isEqualTo(3.5);
        assertThat(matrix.getD()).isEqualTo(4.5);
        assertThat(matrix.getE()).isEqualTo(5.5);
        assertThat(matrix.getF()).isEqualTo(6.5);
    }

    @Test
    void testIdentity() {
        final FloorMapTransformationMatrix identity = FloorMapTransformationMatrix.identity();

        // Applying the identity must leave a point unchanged.
        assertThat(apply(identity, 12.5, -7.25)).containsExactly(12.5, -7.25);
    }

    @Test
    void testToSvgMatrix() {
        final FloorMapTransformationMatrix matrix =
                new FloorMapTransformationMatrix(1.5, 2.5, 3.5, 4.5, 5.5, 6.5);

        assertThat(matrix.toSvgMatrix())
                .isEqualTo("matrix(1.5,2.5,3.5,4.5,5.5,6.5)");
    }

    @Test
    void testEqualsAndHashCode() {
        final FloorMapTransformationMatrix matrix1a =
                new FloorMapTransformationMatrix(1, 0, 0, 1, 10, 20);
        final FloorMapTransformationMatrix matrix1b =
                new FloorMapTransformationMatrix(1, 0, 0, 1, 10, 20);
        final FloorMapTransformationMatrix matrix2 =
                new FloorMapTransformationMatrix(1, 0, 0, 1, 10, 21);

        assertThat(matrix1a).isEqualTo(matrix1b);
        assertThat(matrix1a.hashCode()).isEqualTo(matrix1b.hashCode());
        assertThat(matrix1a).isNotEqualTo(matrix2);
    }

    // -----------------------------------------------------------------------
    // Rotation
    // -----------------------------------------------------------------------

    @Test
    void testRotateZeroDegrees() {
        final FloorMapTransformationMatrix rotation = FloorMapTransformationMatrix.rotate(0);

        // Component-wise rather than equals() — rotate(0) produces c = -0.0,
        // which Double.compare treats as distinct from 0.0.
        assertThat(rotation.getA()).isCloseTo(1, within(TOLERANCE));
        assertThat(rotation.getB()).isCloseTo(0, within(TOLERANCE));
        assertThat(rotation.getC()).isCloseTo(0, within(TOLERANCE));
        assertThat(rotation.getD()).isCloseTo(1, within(TOLERANCE));
        assertThat(rotation.getE()).isCloseTo(0, within(TOLERANCE));
        assertThat(rotation.getF()).isCloseTo(0, within(TOLERANCE));
    }

    @Test
    void testRotateNinetyDegrees() {
        final FloorMapTransformationMatrix rotation = FloorMapTransformationMatrix.rotate(90);

        // Counter-clockwise positive: (1, 0) maps to (0, 1).
        final double[] point = apply(rotation, 1, 0);
        assertThat(point[0]).isCloseTo(0, within(TOLERANCE));
        assertThat(point[1]).isCloseTo(1, within(TOLERANCE));
    }

    @Test
    void testRotateInverseMatchesNegativeRotation() {
        final FloorMapTransformationMatrix inverse =
                FloorMapTransformationMatrix.rotate(37).inverse();
        final FloorMapTransformationMatrix negativeRotation =
                FloorMapTransformationMatrix.rotate(-37);

        assertMatrixCloseTo(inverse, negativeRotation);
    }

    // -----------------------------------------------------------------------
    // Inversion
    // -----------------------------------------------------------------------

    @Test
    void testInverseOfTranslation() {
        final FloorMapTransformationMatrix inverse =
                new FloorMapTransformationMatrix(1, 0, 0, 1, 10, 20).inverse();

        assertMatrixCloseTo(inverse, new FloorMapTransformationMatrix(1, 0, 0, 1, -10, -20));
    }

    @Test
    void testInverseOfScale() {
        final FloorMapTransformationMatrix inverse =
                new FloorMapTransformationMatrix(4, 0, 0, 5, 0, 0).inverse();

        assertMatrixCloseTo(inverse, new FloorMapTransformationMatrix(0.25, 0, 0, 0.2, 0, 0));
    }

    /**
     * The property the drag-and-drop maths relies on (FloorMapNotes.md
     * section 3): transforming a point and then applying the inverse must
     * recover the original point exactly (within floating-point tolerance).
     */
    @TestFactory
    Stream<DynamicTest> testInverseRecoversOriginalPoint() {
        final double[] originalPoint = {12.5, -7.25};
        return TestUtil.buildDynamicTestStream()
                .withInputType(FloorMapTransformationMatrix.class)
                .withOutputType(double[].class)
                .withTestFunction(testCase -> {
                    final FloorMapTransformationMatrix matrix = testCase.getInput();
                    final double[] transformed = apply(matrix, originalPoint[0], originalPoint[1]);
                    return apply(matrix.inverse(), transformed[0], transformed[1]);
                })
                .withAssertions(outcome -> {
                    assertThat(outcome.getActualOutput()[0])
                            .isCloseTo(outcome.getExpectedOutput()[0], within(TOLERANCE));
                    assertThat(outcome.getActualOutput()[1])
                            .isCloseTo(outcome.getExpectedOutput()[1], within(TOLERANCE));
                })
                .addNamedCase("Identity",
                        FloorMapTransformationMatrix.identity(), originalPoint)
                .addNamedCase("Translation",
                        new FloorMapTransformationMatrix(1, 0, 0, 1, 100, -50), originalPoint)
                .addNamedCase("Uniform scale",
                        new FloorMapTransformationMatrix(2.5, 0, 0, 2.5, 0, 0), originalPoint)
                .addNamedCase("Rotation",
                        FloorMapTransformationMatrix.rotate(37), originalPoint)
                .addNamedCase("Shear",
                        new FloorMapTransformationMatrix(1, 0.5, 0.3, 1, 0, 0), originalPoint)
                .addNamedCase("Scale, rotate and translate",
                        new FloorMapTransformationMatrix(1.2, 0.9, -0.9, 1.2, 100, 50), originalPoint)
                .build();
    }

    /**
     * A singular matrix (determinant ≈ 0) cannot be inverted; {@code inverse()}
     * must fall back to the identity rather than dividing by zero.
     */
    @TestFactory
    Stream<DynamicTest> testInverseOfSingularMatrixFallsBackToIdentity() {
        return TestUtil.buildDynamicTestStream()
                .withInputType(FloorMapTransformationMatrix.class)
                .withOutputType(FloorMapTransformationMatrix.class)
                .withTestFunction(testCase -> testCase.getInput().inverse())
                .withSimpleEqualityAssertion()
                .addNamedCase("Zero matrix",
                        new FloorMapTransformationMatrix(0, 0, 0, 0, 0, 0),
                        FloorMapTransformationMatrix.identity())
                .addNamedCase("Linearly dependent rows",
                        new FloorMapTransformationMatrix(2, 4, 1, 2, 5, 6),
                        FloorMapTransformationMatrix.identity())
                .addNamedCase("Determinant below tolerance",
                        new FloorMapTransformationMatrix(1e-6, 0, 0, 1e-6, 5, 6),
                        FloorMapTransformationMatrix.identity())
                .build();
    }

    // -----------------------------------------------------------------------
    // Serialisation
    // -----------------------------------------------------------------------

    @Test
    void testJsonRoundTrip() {
        final FloorMapTransformationMatrix original =
                new FloorMapTransformationMatrix(1.2, 0.9, -0.9, 1.2, 100.5, 50.25);

        final String json = JsonUtil.writeValueAsString(original);
        final FloorMapTransformationMatrix deserialized =
                JsonUtil.readValue(json, FloorMapTransformationMatrix.class);

        assertThat(deserialized).isEqualTo(original);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Applies the affine transform to a point, mirroring the rendering maths
     * in FloorMapCanvasPresenter:
     * <pre>
     *   x' = a * x + c * y + e
     *   y' = b * x + d * y + f
     * </pre>
     */
    private static double[] apply(final FloorMapTransformationMatrix matrix,
                                  final double x,
                                  final double y) {
        return new double[]{
                matrix.getA() * x + matrix.getC() * y + matrix.getE(),
                matrix.getB() * x + matrix.getD() * y + matrix.getF()};
    }

    private static void assertMatrixCloseTo(final FloorMapTransformationMatrix actual,
                                            final FloorMapTransformationMatrix expected) {
        assertThat(actual.getA()).isCloseTo(expected.getA(), within(TOLERANCE));
        assertThat(actual.getB()).isCloseTo(expected.getB(), within(TOLERANCE));
        assertThat(actual.getC()).isCloseTo(expected.getC(), within(TOLERANCE));
        assertThat(actual.getD()).isCloseTo(expected.getD(), within(TOLERANCE));
        assertThat(actual.getE()).isCloseTo(expected.getE(), within(TOLERANCE));
        assertThat(actual.getF()).isCloseTo(expected.getF(), within(TOLERANCE));
    }
}
