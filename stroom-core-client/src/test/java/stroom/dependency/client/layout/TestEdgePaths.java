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

import stroom.dependency.shared.DependencyGraph;
import stroom.dependency.shared.EdgeCertainty;
import stroom.dependency.shared.EdgeKind;
import stroom.dependency.shared.GraphEdge;
import stroom.dependency.shared.GraphElement;
import stroom.dependency.shared.GraphNode;
import stroom.dependency.shared.NodeRole;
import stroom.docref.DocRef;
import stroom.test.common.TestUtil;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
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

    // An expanded pipeline's reference leaves from the right of the element that uses the document, out to
    // the right, and still meets the document's top
    @Test
    void testAnExpandedPipelinesReferenceLeavesTheElementThatUsesIt() {
        final LayoutResult layout = GraphLayout.layout(pipelineUsingAnXslt(), Set.of("p"), DetailLevel.EVERYTHING,
                Map.of());

        final LayoutEdge edge = referenceFrom(layout, "translationFilter");
        final double[] points = EdgePaths.points(layout, edge);
        final Instance pipeline = edge.a();
        final Instance xslt = edge.b();
        // The second element's box: below the header and the first box
        assertThat(points[0]).isEqualTo((double) (pipeline.getX() + LayoutConstants.EXPANDED_CHIP_X
                                                  + LayoutConstants.EW));
        assertThat(points[1]).isEqualTo(pipeline.getY() + LayoutConstants.EXPANDED_HEADER
                                        + LayoutConstants.EH + LayoutConstants.EXPANDED_CHIP_GAP
                                        + LayoutConstants.EH / 2.0);
        // Out to the right first
        assertThat(points[2]).isGreaterThan(points[0]);
        assertThat(points[3]).isEqualTo(points[1]);
        // To the top centre of the XSLT, in the row below
        assertThat(points[6]).isEqualTo(xslt.getX() + layout.dims(xslt).w() / 2.0);
        assertThat(points[7]).isEqualTo((double) xslt.getY());
    }

    // Collapsed, the pipeline is one box, so its references to a document leave from it as a whole, as one
    // edge, as any node's do; expanded, there is one from each element that uses the document
    @Test
    void testACollapsedPipelinesReferencesLeaveItAsAWhole() {
        final LayoutResult layout = GraphLayout.layout(pipelineUsingAnXslt(), Set.of(), DetailLevel.EVERYTHING,
                Map.of());
        final LayoutResult expanded = GraphLayout.layout(pipelineUsingAnXslt(), Set.of("p"),
                DetailLevel.EVERYTHING, Map.of());

        assertThat(layout.getEdges()).hasSize(1);
        assertThat(expanded.getEdges()).extracting(LayoutEdge::element)
                .containsExactlyInAnyOrder("translationFilter", "decorationFilter");
        final LayoutEdge edge = layout.getEdges().getFirst();
        final double[] points = EdgePaths.points(layout, edge);
        assertThat(points[0]).isEqualTo(edge.a().getX() + layout.dims(edge.a()).w() / 2.0);
        assertThat(points[1]).isEqualTo(edge.a().getY() + layout.dims(edge.a()).h());
    }

    // A graph that counts a pipeline's elements but doesn't name them can't say which box an element is,
    // so the reference leaves the pipeline as a whole
    @Test
    void testAReferenceFromAnUnnamedElementLeavesThePipelineAsAWhole() {
        final DependencyGraph graph = graph(null);
        final LayoutResult layout = GraphLayout.layout(graph, Set.of("p"), DetailLevel.EVERYTHING, Map.of());

        final LayoutEdge edge = referenceFrom(layout, "translationFilter");
        final double[] points = EdgePaths.points(layout, edge);
        assertThat(points[0]).isEqualTo(edge.a().getX() + layout.dims(edge.a()).w() / 2.0);
    }

    @Test
    void testPointAtRejectsWrongLength() {
        assertThatThrownBy(() -> EdgePaths.pointAt(new double[]{1, 2}, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // A pipeline of three elements, two of which use one XSLT
    private static DependencyGraph pipelineUsingAnXslt() {
        return graph(List.of(
                new GraphElement("dsParser", null, "DSParser"),
                new GraphElement("translationFilter", null, "XSLTFilter"),
                new GraphElement("decorationFilter", null, "XSLTFilter")));
    }

    private static DependencyGraph graph(final List<GraphElement> elements) {
        return new DependencyGraph(
                null,
                List.of(new GraphNode("p", new DocRef("Pipeline", "p", "Parser"), NodeRole.FLOW, null, true, null,
                                3, elements, null),
                        new GraphNode("x", new DocRef("XSLT", "x", "Translation"), NodeRole.REFERENCE, null, true,
                                null, null, null)),
                List.of(new GraphEdge("p", "x", EdgeKind.REFERENCE, EdgeCertainty.STATIC, "translationFilter",
                                false),
                        new GraphEdge("p", "x", EdgeKind.REFERENCE, EdgeCertainty.STATIC, "decorationFilter",
                                false)),
                null,
                null,
                null,
                null);
    }

    private static LayoutEdge referenceFrom(final LayoutResult layout, final String element) {
        return layout.getEdges().stream()
                .filter(edge -> element.equals(edge.element()))
                .findFirst()
                .orElseThrow();
    }
}
