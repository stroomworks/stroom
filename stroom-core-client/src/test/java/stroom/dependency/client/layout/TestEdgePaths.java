/*
 * Copyright 2026 Crown Copyright
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

package stroom.dependency.client.layout;

import stroom.dependency.shared.EdgeKind;
import stroom.test.common.TestUtil;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class TestEdgePaths {

    /// Numbers are written as JavaScript writes them.
    @TestFactory
    Stream<DynamicTest> testFormat() {
        return TestUtil.buildDynamicTestStream()
                .withInputType(Double.class)
                .withOutputType(String.class)
                .withSingleArgTestFunction(EdgePaths::format)
                .withSimpleEqualityAssertion()
                .addCase(0.0, "0")
                .addCase(-0.0, "0")
                .addCase(97.0, "97")
                .addCase(-12.0, "-12")
                .addCase(37.5, "37.5")
                .addCase(85.05000000000001, "85.05000000000001")
                .build();
    }

    @Test
    void testEdgesAttachWhereTheyShould() {
        final LayoutResult layout = GraphLayout.layout(GraphScenarios.get("deep").build(),
                Set.of(),
                DetailLevel.EVERYTHING,
                Map.of());

        for (final LayoutEdge edge : layout.getEdges()) {
            final double[] points = EdgePaths.points(layout, edge);
            final Instance a = edge.a();
            final Instance b = edge.b();
            final Dims da = layout.dims(a);
            final Dims db = layout.dims(b);
            if (edge.kind() == EdgeKind.FLOW) {
                // From the middle of the source's right side to the middle of the target's left side
                assertThat(points[0]).isEqualTo((double) (a.getX() + da.w()));
                assertThat(points[1]).isEqualTo(a.getY() + da.h() / 2.0);
                assertThat(points[6]).isEqualTo((double) b.getX());
                assertThat(points[7]).isEqualTo(b.getY() + db.h() / 2.0);
            } else {
                // A pipeline's translation is in the row below: bottom centre to top centre
                assertThat(points[0]).isEqualTo(a.getX() + da.w() / 2.0);
                assertThat(points[1]).isEqualTo(a.getY() + da.h());
                assertThat(points[6]).isEqualTo(b.getX() + db.w() / 2.0);
                assertThat(points[7]).isEqualTo((double) b.getY());
            }
        }
        // The curve's midpoint, by the cubic Bézier formula
        assertThat(EdgePaths.pointAt(new double[]{0, 0, 0, 10, 10, 10, 10, 0}, 0.5))
                .containsExactly(new double[]{5, 7.5}, within(1e-9));
    }

    @Test
    void testPointAtRejectsWrongLength() {
        assertThatThrownBy(() -> EdgePaths.pointAt(new double[]{1, 2}, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
