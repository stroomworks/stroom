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

import stroom.dependency.client.layout.GraphScenarios.Scenario;
import stroom.dependency.client.layout.GraphViewport.FitMode;
import stroom.dependency.shared.DependencyGraph;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TestGraphViewport {

    private static final double TOLERANCE = 1e-9;
    private static final double VIEW_WIDTH = 1280;
    private static final double VIEW_HEIGHT = 720;

    @Test
    void testScreenToGraphRoundTrip() {
        final GraphViewport viewport = new GraphViewport(0.73, 112.5, -40);

        final double[] graph = viewport.screenToGraph(640, 360);
        final double[] screen = viewport.graphToScreen(graph[0], graph[1]);

        assertThat(screen)
                .containsExactly(new double[]{640, 360}, within(TOLERANCE));
    }

    @Test
    void testZoomAboutKeepsThePointFixed() {
        final GraphViewport viewport = new GraphViewport(1, 30, 50);
        final double[] before = viewport.screenToGraph(400, 300);

        viewport.zoomAbout(400, 300, 0.5);

        assertThat(viewport.getZoom())
                .isEqualTo(0.5);
        assertThat(viewport.screenToGraph(400, 300))
                .containsExactly(before, within(TOLERANCE));
    }

    @Test
    void testZoomAboutKeepsThePointFixedAtTheClamp() {
        final GraphViewport viewport = new GraphViewport(2, 30, 50);
        final double[] before = viewport.screenToGraph(400, 300);

        viewport.zoomAbout(400, 300, 50);

        assertThat(viewport.getZoom())
                .isEqualTo(GraphViewport.MAX_ZOOM);
        assertThat(viewport.screenToGraph(400, 300))
                .containsExactly(before, within(TOLERANCE));
    }

    @Test
    void testZoomIsClamped() {
        final GraphViewport viewport = new GraphViewport();

        viewport.zoomAbout(0, 0, 0.001);
        assertThat(viewport.getZoom())
                .isEqualTo(GraphViewport.MIN_ZOOM);

        viewport.zoomAbout(0, 0, 1000);
        assertThat(viewport.getZoom())
                .isEqualTo(GraphViewport.MAX_ZOOM);

        assertThat(new GraphViewport(0, 0, 0).getZoom())
                .isEqualTo(GraphViewport.MIN_ZOOM);
    }

    @Test
    void testZoomThatIsNotANumberIsIgnored() {
        final GraphViewport viewport = new GraphViewport(0.5, 10, 20);

        viewport.zoomAbout(100, 100, Double.NaN);
        viewport.zoomByWheel(100, 100, Double.NaN);

        assertThat(viewport.getZoom()).isEqualTo(0.5);
        assertThat(viewport.toSvgTransform()).doesNotContain("NaN");
    }

    @Test
    void testWheelZoomsOutOnPositiveDelta() {
        final GraphViewport viewport = new GraphViewport();

        viewport.zoomByWheel(100, 100, 120);
        assertThat(viewport.getZoom())
                .isCloseTo(Math.exp(-120 * GraphViewport.WHEEL_SENSITIVITY), within(TOLERANCE));

        viewport.zoomByWheel(100, 100, -120);
        assertThat(viewport.getZoom())
                .isCloseTo(1, within(TOLERANCE));
    }

    @Test
    void testCentreOn() {
        final GraphViewport viewport = new GraphViewport(0.5, 0, 0);

        viewport.centreOn(300, 200, 800, 600);

        assertThat(viewport.graphToScreen(300, 200))
                .containsExactly(new double[]{400, 300}, within(TOLERANCE));
        assertThat(viewport.getZoom())
                .isEqualTo(0.5);
    }

    @Test
    void testPan() {
        final GraphViewport viewport = new GraphViewport(1, 10, 20);

        viewport.pan(5, -7);

        assertThat(viewport.getTx())
                .isEqualTo(15);
        assertThat(viewport.getTy())
                .isEqualTo(13);
    }

    /// Fitting everything must put every placed instance inside the canvas, for every scenario that can
    /// fit. The harness's fit anchored a graph that fitted exactly 28 pixels in, which pushed its far edge
    /// off the canvas below about 70% zoom; this is the regression test for the port's fix.
    @TestFactory
    Stream<DynamicTest> testFitAllContainsEveryInstance() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Scenario scenario : GraphScenarios.ALL) {
            tests.add(DynamicTest.dynamicTest(scenario.id(), () -> {
                final LayoutResult layout = layout(scenario.build(), DetailLevel.EVERYTHING);
                final GraphViewport viewport = new GraphViewport();
                final boolean fitted = viewport.fit(layout, VIEW_WIDTH, VIEW_HEIGHT, FitMode.ALL);
                if (!fitted) {
                    assertThat(layout.getInstances()).isEmpty();
                    return;
                }
                if (viewport.getZoom() == FitMode.ALL.getFloor()) {
                    // Too big to fit even at the smallest zoom, so it is anchored instead: forty lanes.
                    assertThat(scenario.id()).isEqualTo("wide40");
                    return;
                }
                for (final Instance instance : layout.getInstances()) {
                    final Dims dims = layout.dims(instance);
                    final double[] topLeft = viewport.graphToScreen(instance.getX(), instance.getY());
                    final double[] bottomRight = viewport.graphToScreen(
                            instance.getX() + dims.w(), instance.getY() + dims.h());
                    assertThat(topLeft[0]).isGreaterThanOrEqualTo(-TOLERANCE);
                    assertThat(topLeft[1]).isGreaterThanOrEqualTo(-TOLERANCE);
                    assertThat(bottomRight[0]).isLessThanOrEqualTo(VIEW_WIDTH + TOLERANCE);
                    assertThat(bottomRight[1]).isLessThanOrEqualTo(VIEW_HEIGHT + TOLERANCE);
                }
            }));
        }
        return tests.stream();
    }

    @Test
    void testFitReadKeepsLabelsLegible() {
        // Forty lanes do not fit at a legible zoom, so reading mode stops at its floor and anchors the
        // top-left corner.
        final LayoutResult layout = layout(GraphScenarios.get("wide40").build(), DetailLevel.EVERYTHING);
        final GraphViewport viewport = new GraphViewport();

        viewport.fit(layout, VIEW_WIDTH, VIEW_HEIGHT, FitMode.READ);

        assertThat(viewport.getZoom())
                .isEqualTo(FitMode.READ.getFloor());
        final double[] origin = viewport.graphToScreen(LayoutConstants.PAD - 40, LayoutConstants.PAD - 40);
        assertThat(origin)
                .containsExactly(new double[]{28, 28}, within(TOLERANCE));
    }

    @Test
    void testFitNeverZoomsInBeyondTheCap() {
        final LayoutResult layout = layout(GraphScenarios.get("single").build(), DetailLevel.EVERYTHING);
        final GraphViewport viewport = new GraphViewport();

        viewport.fit(layout, VIEW_WIDTH, VIEW_HEIGHT, FitMode.ALL);

        assertThat(viewport.getZoom())
                .isEqualTo(1.1);
    }

    @Test
    void testFitEmptyResetsToActualSize() {
        final LayoutResult layout = layout(GraphScenarios.get("empty").build(), DetailLevel.EVERYTHING);
        final GraphViewport viewport = new GraphViewport(0.3, 500, 500);

        assertThat(viewport.fit(layout, VIEW_WIDTH, VIEW_HEIGHT, FitMode.READ))
                .isFalse();
        assertThat(viewport.getZoom())
                .isEqualTo(1);
    }

    @Test
    void testFitAndSettleLaysOutAgainAtMostOnce() {
        // Fitting forty lanes into a small canvas zooms out past both thresholds, so the detail drops.
        final DependencyGraph graph = GraphScenarios.get("wide40").build();
        final LayoutResult first = layout(graph, DetailLevel.EVERYTHING);
        final AtomicInteger relayouts = new AtomicInteger();
        final GraphViewport viewport = new GraphViewport();

        final LayoutResult settled = viewport.fitAndSettle(first,
                detail -> {
                    relayouts.incrementAndGet();
                    return layout(graph, detail);
                },
                800,
                400,
                FitMode.ALL);

        assertThat(relayouts)
                .hasValue(1);
        assertThat(settled)
                .isNotSameAs(first);
        assertThat(settled.getDetail())
                .isNotEqualTo(DetailLevel.EVERYTHING);
    }

    @Test
    void testFitAndSettleKeepsALayoutAtTheRightDetail() {
        final DependencyGraph graph = GraphScenarios.get("single").build();
        final LayoutResult first = layout(graph, DetailLevel.EVERYTHING);
        final GraphViewport viewport = new GraphViewport();

        final LayoutResult settled = viewport.fitAndSettle(first,
                detail -> {
                    throw new AssertionError("Should not lay out again");
                },
                VIEW_WIDTH,
                VIEW_HEIGHT,
                FitMode.READ);

        assertThat(settled)
                .isSameAs(first);
    }

    @Test
    void testLabelScaleAndMeta() {
        assertThat(new GraphViewport(2, 0, 0).getLabelScale())
                .isEqualTo(1);
        assertThat(new GraphViewport(0.8, 0, 0).getLabelScale())
                .isCloseTo(1.25, within(TOLERANCE));
        assertThat(new GraphViewport(0.1, 0, 0).getLabelScale())
                .isEqualTo(2);
        assertThat(new GraphViewport(0.63, 0, 0).isMetaVisible())
                .isTrue();
        assertThat(new GraphViewport(0.62, 0, 0).isMetaVisible())
                .isFalse();
    }

    @Test
    void testSvgTransform() {
        assertThat(new GraphViewport(0.5, 28, 30.25).toSvgTransform())
                .isEqualTo("translate(28,30.25) scale(0.5)");
    }

    private static LayoutResult layout(final DependencyGraph graph, final DetailLevel detail) {
        return GraphLayout.layout(graph, Set.of(), detail, Map.of());
    }
}
