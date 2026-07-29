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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestFact {

    private static final double IMAGE_DISPLAY_WIDTH = 1000;

    // -----------------------------------------------------------------------
    // mapAnchor — the camera-centre point for a tracked fact
    // -----------------------------------------------------------------------

    /**
     * A point fact anchors at its position pushed through the placement
     * matrix.
     */
    @Test
    void testMapAnchorPointFact() {
        final Fact fact = new Fact("gate-1", "gate", null,
                FloorMapTransformationMatrix.translate(1, 2),
                new double[]{5, 7});

        assertThat(fact.mapAnchor(IMAGE_DISPLAY_WIDTH, null))
                .containsExactly(6, 9);
    }

    /**
     * A point fact without a position anchors at the placement matrix's
     * origin.
     */
    @Test
    void testMapAnchorPositionlessFact() {
        final Fact fact = new Fact("gate-1", "gate", null,
                FloorMapTransformationMatrix.translate(3, 4),
                null);

        assertThat(fact.mapAnchor(IMAGE_DISPLAY_WIDTH, null))
                .containsExactly(3, 4);
    }

    /**
     * An area fact anchors at its local vertex centroid pushed through the
     * placement matrix. (Area vertices are stored centroid-local, so the
     * centroid of a symmetric polygon is the local origin.)
     */
    @Test
    void testMapAnchorAreaFact() {
        final Fact fact = new Fact("zone-a", "area", null,
                FloorMapTransformationMatrix.translate(50, 60),
                null,
                new double[][]{{-10, -10}, {10, -10}, {10, 10}, {-10, 10}},
                null, null);

        assertThat(fact.mapAnchor(IMAGE_DISPLAY_WIDTH, null))
                .containsExactly(50, 60);
    }

    /**
     * An off-centre polygon anchors at its true centroid, not the local
     * origin.
     */
    @Test
    void testMapAnchorAreaFactOffCentre() {
        final Fact fact = new Fact("zone-b", "area", null,
                FloorMapTransformationMatrix.identity(),
                null,
                new double[][]{{0, 0}, {12, 0}, {12, 6}, {0, 6}},
                null, null);

        assertThat(fact.mapAnchor(IMAGE_DISPLAY_WIDTH, null))
                .containsExactly(6, 3);
    }

    /**
     * An image fact anchors at the centre of the placed image rectangle:
     * width is the fixed display width, height follows the aspect ratio, and
     * the render wrapper transform reduces to {@code worldToMap · (w/2, h/2)}.
     */
    @Test
    void testMapAnchorImageFact() {
        final Fact fact = new Fact("background", "background", "img.png",
                FloorMapTransformationMatrix.translate(100, 200),
                null);

        // Aspect 2.0 → h = 500 → local centre (500, 250).
        assertThat(fact.mapAnchor(IMAGE_DISPLAY_WIDTH, 2.0))
                .containsExactly(600, 450);
    }

    /**
     * An image whose aspect ratio is not yet known is treated as square,
     * matching the renderer's pre-load fallback.
     */
    @Test
    void testMapAnchorImageFactDefaultAspect() {
        final Fact fact = new Fact("background", "background", "img.png",
                FloorMapTransformationMatrix.translate(100, 200),
                null);

        assertThat(fact.mapAnchor(IMAGE_DISPLAY_WIDTH, null))
                .containsExactly(600, 700);
    }

    /**
     * Dispatch matches the renderer: a fact carrying both an image and
     * vertices anchors as an image.
     */
    @Test
    void testMapAnchorImageWinsOverVertices() {
        final Fact fact = new Fact("hybrid", "object", "img.png",
                FloorMapTransformationMatrix.identity(),
                null,
                new double[][]{{-10, -10}, {10, -10}, {10, 10}},
                null, null);

        assertThat(fact.mapAnchor(IMAGE_DISPLAY_WIDTH, 1.0))
                .containsExactly(500, 500);
    }

    // -----------------------------------------------------------------------
    // label
    // -----------------------------------------------------------------------

    /** The narrower constructors leave the label unset. */
    @Test
    void testLabelDefaultsToNull() {
        assertThat(new Fact("k", "gate", null,
                FloorMapTransformationMatrix.identity(), null).getLabel()).isNull();
        assertThat(new Fact("k", "gate", null,
                FloorMapTransformationMatrix.identity(), null,
                null, null, null).getLabel()).isNull();
    }

    @Test
    void testLabelRoundTrips() {
        final Fact fact = new Fact("k", "area", null,
                FloorMapTransformationMatrix.identity(), null,
                null, null, null, "Loading Bay");

        assertThat(fact.getLabel()).isEqualTo("Loading Bay");
        assertThat(fact.getLabelOrNull()).isEqualTo("Loading Bay");
    }

    /** A blank label is treated as absent, so callers fall back to the key. */
    @Test
    void testGetLabelOrNullTreatsBlankAsAbsent() {
        assertThat(new Fact("k", "area", null,
                FloorMapTransformationMatrix.identity(), null,
                null, null, null, "   ").getLabelOrNull()).isNull();
        assertThat(new Fact("k", "area", null,
                FloorMapTransformationMatrix.identity(), null,
                null, null, null, "").getLabelOrNull()).isNull();
    }

    /**
     * The transform/vertex-preview copies carry the label — a live drag must not
     * silently rename an area in the tracking panel.
     */
    @Test
    void testLabelSurvivesCopies() {
        final Fact fact = new Fact("k", "area", null,
                FloorMapTransformationMatrix.identity(), null,
                new double[][]{{-5, -5}, {5, -5}, {5, 5}}, null, null, "Loading Bay");

        assertThat(fact.withWorldToMap(FloorMapTransformationMatrix.translate(10, 10))
                .getLabel()).isEqualTo("Loading Bay");
        assertThat(fact.withVertices(new double[][]{{-1, -1}, {1, -1}, {1, 1}})
                .getLabel()).isEqualTo("Loading Bay");
    }
}
