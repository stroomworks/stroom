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

import stroom.floormap.shared.TypeStyle.Shape;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestFloorMapShapes {

    @Test
    void testTriangle_pointsUp() {
        // apex at top (0,-h), base corners bottom-right and bottom-left.
        assertThat(FloorMapShapes.polygonPoints(Shape.TRIANGLE, 10))
                .isEqualTo("0.0,-10.0 10.0,10.0 -10.0,10.0");
    }

    @Test
    void testDiamond_fourPoints() {
        assertThat(FloorMapShapes.polygonPoints(Shape.DIAMOND, 10))
                .isEqualTo("0.0,-10.0 10.0,0.0 0.0,10.0 -10.0,0.0");
    }

    @Test
    void testNativeShapes_haveNoPolygon() {
        assertThat(FloorMapShapes.polygonPoints(Shape.CIRCLE, 10)).isNull();
        assertThat(FloorMapShapes.polygonPoints(Shape.SQUARE, 10)).isNull();
        assertThat(FloorMapShapes.polygonPoints(Shape.PIN, 10)).isNull();
        assertThat(FloorMapShapes.polygonPoints(null, 10)).isNull();
    }
}
