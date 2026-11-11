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

package stroom.dependency.client.component;

import stroom.dependency.client.layout.DetailLevel;
import stroom.dependency.client.layout.GraphLayout;
import stroom.dependency.client.layout.GraphScenarios;
import stroom.dependency.client.layout.GraphScenarios.Scenario;
import stroom.dependency.client.layout.Instance;
import stroom.dependency.client.layout.InstanceKind;
import stroom.dependency.client.layout.LayoutEdge;
import stroom.dependency.client.layout.LayoutResult;
import stroom.dependency.shared.DependencyGraph;
import stroom.dependency.shared.EdgeKind;
import stroom.dependency.shared.GraphEdge;
import stroom.dependency.shared.GraphNode;
import stroom.dependency.shared.NodeRole;
import stroom.docref.DocRef;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;

class TestStructureGraphSvg {

    private static final String MARKER = "structure-graph-arrow-1";

    /// Every scenario at every detail level draws well-formed SVG with one group per placed instance and
    /// one path per edge, each carrying the ids the component finds them by.
    @TestFactory
    Stream<DynamicTest> testEveryInstanceAndEdgeIsDrawnOnce() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Scenario scenario : GraphScenarios.ALL) {
            for (final DetailLevel detail : DetailLevel.values()) {
                tests.add(DynamicTest.dynamicTest(scenario.id() + " / " + detail, () -> {
                    final LayoutResult layout = GraphLayout.layout(scenario.build(), Set.of(), detail, Map.of());
                    final Document svg = parse(StructureGraphSvg.build(layout, MARKER, 1).asString());

                    final List<String> nodeIds = attributeValues(svg, "g", StructureGraphSvg.DATA_ID);
                    final List<String> expectedIds = new ArrayList<>();
                    for (final Instance instance : layout.getInstances()) {
                        expectedIds.add(instance.getId());
                    }
                    assertThat(nodeIds)
                            .containsExactlyElementsOf(expectedIds);

                    final List<String> edgeEnds = new ArrayList<>();
                    final NodeList paths = svg.getElementsByTagName("path");
                    for (int i = 0; i < paths.getLength(); i++) {
                        final Element path = (Element) paths.item(i);
                        if (path.hasAttribute(StructureGraphSvg.DATA_A)) {
                            edgeEnds.add(path.getAttribute(StructureGraphSvg.DATA_A)
                                         + ">" + path.getAttribute(StructureGraphSvg.DATA_B));
                        }
                    }
                    final List<String> expectedEnds = new ArrayList<>();
                    for (final LayoutEdge edge : layout.getEdges()) {
                        expectedEnds.add(edge.a().getId() + ">" + edge.b().getId());
                    }
                    assertThat(edgeEnds)
                            .containsExactlyElementsOf(expectedEnds);
                }));
            }
        }
        return tests.stream();
    }

    @Test
    void testSvgIsHiddenFromAssistiveTechnology() {
        final Element root = parse(build("events", DetailLevel.EVERYTHING, Set.of())).getDocumentElement();

        assertThat(root.getAttribute("aria-hidden"))
                .isEqualTo("true");
        assertThat(root.getAttribute("focusable"))
                .isEqualTo("false");
    }

    @Test
    void testNamesAreEscaped() {
        final DependencyGraph graph = new DependencyGraph(
                null,
                List.of(new GraphNode("a",
                        new DocRef("Feed", "u0", "<b>\"Odd\" & 'name'</b>"),
                        NodeRole.FLOW,
                        null,
                        true,
                        null,
                        null,
                        null)),
                null,
                null,
                null,
                null,
                null);
        final LayoutResult layout = GraphLayout.layout(graph, Set.of(), DetailLevel.EVERYTHING, Map.of());

        final String markup = StructureGraphSvg.build(layout, MARKER, 2).asString();

        assertThat(markup)
                .doesNotContain("<b>");
        assertThat(textOfClass(parse(markup), "structure-graph__name"))
                .isEqualTo("<b>\"Odd\" & 'name'</b>");
    }

    @Test
    void testAmbiguousAndBackEdgesAreMarked() {
        final DependencyGraph graph = new DependencyGraph(
                null,
                List.of(node("p", "Pipeline", NodeRole.FLOW),
                        node("x1", "XSLT", NodeRole.REFERENCE),
                        node("x2", "XSLT", NodeRole.REFERENCE)),
                List.of(new GraphEdge("p", "x1", EdgeKind.REFERENCE, null, null, true),
                        new GraphEdge("p", "x2", EdgeKind.REFERENCE, null, null, false)),
                null,
                null,
                null,
                null);
        final String markup = StructureGraphSvg.build(
                GraphLayout.layout(graph, Set.of(), DetailLevel.EVERYTHING, Map.of()), MARKER, 1).asString();

        assertThat(classOfEdge(parse(markup), "ref:0:x1"))
                .contains("structure-graph__edge--ambiguous");
        assertThat(classOfEdge(parse(markup), "ref:0:x2"))
                .doesNotContain("structure-graph__edge--ambiguous");

        final Document cycle = parse(build("cycle", DetailLevel.EVERYTHING, Set.of()));
        assertThat(classOfEdge(cycle, "a"))
                .contains("structure-graph__edge--back");
    }

    @Test
    void testNodeWithABrokenReferenceIsMarked() {
        final DependencyGraph graph = new DependencyGraph(
                null,
                List.of(new GraphNode("x", new DocRef("XSLT", "x", "X"), NodeRole.REFERENCE,
                                null, true, null, null, 1),
                        node("y", "XSLT", NodeRole.REFERENCE)),
                null,
                null,
                null,
                null,
                null);
        final Document svg = parse(StructureGraphSvg.build(
                GraphLayout.layout(graph, Set.of(), DetailLevel.EVERYTHING, Map.of()), MARKER, 1).asString());

        assertThat(nodeGroup(svg, "ref:0:x").getAttribute("class")).contains("structure-graph__node--broken");
        assertThat(nodeGroup(svg, "ref:1:y").getAttribute("class")).doesNotContain("structure-graph__node--broken");
    }

    @Test
    void testExpandedPipelineShowsItsElements() {
        final Document svg = parse(build("deep", DetailLevel.EVERYTHING, Set.of("p1")));

        final Element p1 = nodeGroup(svg, "p1");
        assertThat(p1.getAttribute("class"))
                .contains("structure-graph__node--expanded");
        // Stage 1 has 4 + (1 % 3) = 5 elements.
        assertThat(countByClass(p1, "structure-graph__chip"))
                .isEqualTo(5);
        assertThat(countByClass(nodeGroup(svg, "p2"), "structure-graph__chip"))
                .isZero();
    }

    @Test
    void testSummariesAreControlsAndCollapsedLanesHaveNoToggle() {
        final Document svg = parse(build("deep", DetailLevel.LANES_COLLAPSED, Set.of()));

        final Element summary = nodeGroup(svg, GraphLayout.lanePinKey(0));
        assertThat(summary.getAttribute(StructureGraphSvg.DATA_ACTION))
                .isEqualTo(StructureGraphSvg.ACTION_OPEN_SUMMARY);
        assertThat(attributeValues(svg, "g", StructureGraphSvg.DATA_LANE))
                .isEmpty();

        final Document open = parse(build("deep", DetailLevel.EVERYTHING, Set.of()));
        assertThat(attributeValues(open, "g", StructureGraphSvg.DATA_LANE))
                .containsExactly("0");
    }

    @Test
    void testEmptyFolderDrawsCreateSlots() {
        final Document svg = parse(build("empty", DetailLevel.EVERYTHING, Set.of()));

        assertThat(countByClass(svg.getDocumentElement(), "structure-graph__ghost"))
                .isEqualTo(7);
        assertThat(countByClass(svg.getDocumentElement(), "structure-graph__ghost-line"))
                .isOne();
    }

    @Test
    void testKindModifier() {
        assertThat(StructureGraphSvg.kindModifier(InstanceKind.REFERENCE_SUMMARY))
                .isEqualTo("reference-summary");
    }

    private static String build(final String scenarioId, final DetailLevel detail, final Set<String> expanded) {
        final LayoutResult layout = GraphLayout.layout(
                GraphScenarios.get(scenarioId).build(), expanded, detail, Map.of());
        return StructureGraphSvg.build(layout, MARKER, 1).asString();
    }

    private static GraphNode node(final String id, final String type, final NodeRole role) {
        return new GraphNode(id, new DocRef(type, id, id), role, null, true, null, null, null);
    }

    private static Document parse(final String markup) {
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(markup)));
        } catch (final Exception e) {
            throw new AssertionError("Not well-formed SVG: " + e.getMessage(), e);
        }
    }

    private static List<String> attributeValues(final Document document, final String tag, final String name) {
        final List<String> values = new ArrayList<>();
        final NodeList elements = document.getElementsByTagName(tag);
        for (int i = 0; i < elements.getLength(); i++) {
            final Element element = (Element) elements.item(i);
            if (element.hasAttribute(name)) {
                values.add(element.getAttribute(name));
            }
        }
        return values;
    }

    private static Element nodeGroup(final Document document, final String id) {
        final NodeList groups = document.getElementsByTagName("g");
        for (int i = 0; i < groups.getLength(); i++) {
            final Element group = (Element) groups.item(i);
            if (id.equals(group.getAttribute(StructureGraphSvg.DATA_ID))) {
                return group;
            }
        }
        throw new AssertionError("No node " + id);
    }

    private static String classOfEdge(final Document document, final String to) {
        final NodeList paths = document.getElementsByTagName("path");
        for (int i = 0; i < paths.getLength(); i++) {
            final Element path = (Element) paths.item(i);
            if (to.equals(path.getAttribute(StructureGraphSvg.DATA_B))) {
                return path.getAttribute("class");
            }
        }
        throw new AssertionError("No edge to " + to);
    }

    private static String textOfClass(final Document document, final String className) {
        final NodeList texts = document.getElementsByTagName("text");
        for (int i = 0; i < texts.getLength(); i++) {
            final Element text = (Element) texts.item(i);
            if (className.equals(text.getAttribute("class"))) {
                return text.getTextContent();
            }
        }
        throw new AssertionError("No text of class " + className);
    }

    private static int countByClass(final Element root, final String className) {
        int count = 0;
        final NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            final String classes = ((Element) all.item(i)).getAttribute("class");
            if (List.of(classes.split(" ")).contains(className)) {
                count++;
            }
        }
        return count;
    }
}
