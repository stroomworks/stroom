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
import stroom.dependency.shared.DependencyGraph;
import stroom.dependency.shared.EdgeKind;
import stroom.dependency.shared.GraphEdge;
import stroom.dependency.shared.GraphNode;
import stroom.dependency.shared.NodeRole;
import stroom.docref.DocRef;
import stroom.util.json.JsonUtil;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests the layout against the reference harness it was ported from, and against the harness's own
/// regression suite.
///
/// The reference layouts under `src/test/resources/stroom/dependency/client/layout/golden` were produced
/// by running the harness's `layout()` on every fixture scenario, at every detail level, with every pin
/// state, and with every pipeline expanded. Regenerate them with
/// `docs/references/folder-data-flow/generate-layout-golden.mjs` if the harness changes.
class TestGraphLayout {

    private static final String GOLDEN_PATH = "/stroom/dependency/client/layout/golden/";
    private static final List<String> PIN_MODES = List.of("none", "open", "shut");

    /// The Java scenarios must build exactly the graphs the harness's fixtures do, or the comparisons
    /// below prove nothing.
    @TestFactory
    Stream<DynamicTest> testScenariosMatchHarnessFixtures() {
        return GraphScenarios.ALL.stream()
                .map(scenario -> DynamicTest.dynamicTest(scenario.id(), () -> {
                    final JsonNode golden = readGolden(scenario.id());
                    final DependencyGraph graph = scenario.build();

                    assertThat(describeNodes(graph))
                            .containsExactlyElementsOf(describeGoldenNodes(golden.get("graph")));
                    assertThat(describeEdges(graph))
                            .containsExactlyElementsOf(describeGoldenEdges(golden.get("graph")));
                }));
    }

    /// Every layout the harness produced, reproduced exactly: every instance's position and size, every
    /// edge and its path, lane boxes, ghosts, back edges, counts and the audit.
    @TestFactory
    Stream<DynamicTest> testLayoutMatchesHarness() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Scenario scenario : GraphScenarios.ALL) {
            final JsonNode golden = readGolden(scenario.id());
            for (final JsonNode expected : golden.get("layouts")) {
                final String name = scenario.id()
                                    + " / detail " + expected.get("detail").asInt()
                                    + " / " + expected.get("pinMode").asString()
                                    + (expected.get("expanded").isEmpty()
                        ? ""
                        : " / expanded");
                tests.add(DynamicTest.dynamicTest(name, () -> assertMatches(scenario, expected)));
            }
        }
        return tests.stream();
    }

    /// The harness's regression matrix: every scenario at every detail level with every pin state. Nothing
    /// may overlap, land off the canvas or go unplaced.
    @TestFactory
    Stream<DynamicTest> testRegressionMatrix() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Scenario scenario : GraphScenarios.ALL) {
            for (final DetailLevel detail : DetailLevel.values()) {
                for (final String pinMode : PIN_MODES) {
                    tests.add(DynamicTest.dynamicTest(
                            scenario.id() + " / " + detail + " / " + pinMode,
                            () -> {
                                final LayoutAudit audit = LayoutAudit.audit(build(scenario, detail, pinMode));
                                assertThat(audit.getOverlap())
                                        .describedAs("nodes must not overlap")
                                        .isEmpty();
                                assertThat(audit.getOffCanvas())
                                        .describedAs("nodes must not land off the canvas")
                                        .isEmpty();
                                assertThat(audit.getMissing())
                                        .describedAs("every node must be placed")
                                        .isEmpty();
                            }));
                }
            }
        }
        return tests.stream();
    }

    @Test
    void testExpandingEveryPipelineKeepsTheLayoutClean() {
        for (final Scenario scenario : GraphScenarios.ALL) {
            final DependencyGraph graph = scenario.build();
            final LayoutResult first = GraphLayout.layout(graph, Set.of(), DetailLevel.EVERYTHING, Map.of());
            final Set<String> expanded = new HashSet<>();
            for (final Instance instance : first.getInstances()) {
                if (instance.isExpandable()) {
                    expanded.add(instance.getId());
                }
            }
            final LayoutAudit audit = LayoutAudit.audit(
                    GraphLayout.layout(graph, expanded, DetailLevel.EVERYTHING, Map.of()));

            assertThat(audit.getOverlap())
                    .describedAs("%s: overlaps when fully expanded", scenario.id())
                    .isEmpty();
            assertThat(audit.getMissing())
                    .describedAs("%s: unplaced when fully expanded", scenario.id())
                    .isEmpty();
        }
    }

    @Test
    void testDuplicationHappensClientSide() {
        final LayoutResult layout = GraphLayout.layout(
                GraphScenarios.get("events").build(), Set.of(), DetailLevel.EVERYTHING, Map.of());

        assertThat(layout.getStats().nodes())
                .describedAs("reference duplication happens client-side, so laid out >= served")
                .isGreaterThanOrEqualTo(layout.getStats().servedNodes());
    }

    @Test
    void testCyclesDoNotPreventALayout() {
        final LayoutResult layout = GraphLayout.layout(
                GraphScenarios.get("cycle").build(), Set.of(), DetailLevel.EVERYTHING, Map.of());

        assertThat(layout.getBackEdges())
                .containsExactly("p2>a");
        assertThat(LayoutAudit.audit(layout).getMissing())
                .isEmpty();
    }

    @Test
    void testPipelineLibraryLaysOutByInheritance() {
        final LayoutResult layout = GraphLayout.layout(
                GraphScenarios.get("library").build(), Set.of(), DetailLevel.EVERYTHING, Map.of());

        assertThat(layout.getStats().cols())
                .describedAs("inheritance must drive columns when there is no flow")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void testEmptyFolderYieldsCreateSlots() {
        final LayoutResult layout = GraphLayout.layout(
                GraphScenarios.get("empty").build(), Set.of(), DetailLevel.EVERYTHING, Map.of());

        assertThat(layout.getInstances())
                .isEmpty();
        assertThat(layout.getGhosts())
                .isNotEmpty()
                .allSatisfy(ghost -> {
                    assertThat(ghost.x()).isNotNegative();
                    assertThat(ghost.y()).isNotNegative();
                });
    }

    @Test
    void testNullInputsAreTreatedAsEmpty() {
        final LayoutResult layout = GraphLayout.layout(null, null, null, null);

        assertThat(layout.getInstances())
                .isEmpty();
        assertThat(layout.getDetail())
                .isEqualTo(DetailLevel.EVERYTHING);
        assertThat(layout.getGhosts())
                .hasSize(7);
    }

    @Test
    void testEdgesToNodesNotSentAreDropped() {
        final DependencyGraph graph = new DependencyGraph(
                null,
                List.of(new GraphNode("a", new DocRef("Feed", "u0", "A"), NodeRole.FLOW,
                        null, true, null, null, null)),
                List.of(new GraphEdge("a", "gone", EdgeKind.FLOW, null, null, null),
                        new GraphEdge("gone", "a", EdgeKind.REFERENCE, null, null, null)),
                null,
                null,
                null,
                null);

        final LayoutResult layout = GraphLayout.layout(graph, Set.of(), DetailLevel.EVERYTHING, Map.of());

        assertThat(layout.getInstances())
                .extracting(Instance::getId)
                .containsExactly("a");
        assertThat(layout.getEdges())
                .isEmpty();
    }

    @Test
    void testPinOverridesDetailLevel() {
        final DependencyGraph graph = GraphScenarios.get("deep").build();
        final LayoutResult collapsed = GraphLayout.layout(
                graph, Set.of(), DetailLevel.LANES_COLLAPSED, Map.of());
        final int firstLane = collapsed.getLanes().getFirst();

        final LayoutResult pinnedOpen = GraphLayout.layout(
                graph, Set.of(), DetailLevel.LANES_COLLAPSED, Map.of(GraphLayout.lanePinKey(firstLane), false));

        assertThat(collapsed.getInstances())
                .extracting(Instance::getKind)
                .containsOnly(InstanceKind.LANE_SUMMARY);
        assertThat(pinnedOpen.getInstances())
                .extracting(Instance::getKind)
                .contains(InstanceKind.FLOW);
    }

    @Test
    void testCompareNames() {
        assertThat(GraphLayout.compareNames("apple", "Banana"))
                .isNegative();
        assertThat(GraphLayout.compareNames("Banana", "apple"))
                .isPositive();
        assertThat(GraphLayout.compareNames("same", "same"))
                .isZero();
        assertThat(GraphLayout.compareNames(null, "a"))
                .isNegative();
    }

    // Builds a layout as the harness's test does: the pin modes pin the first lane of an unpinned layout.
    private static LayoutResult build(final Scenario scenario, final DetailLevel detail, final String pinMode) {
        final DependencyGraph graph = scenario.build();
        final LayoutResult base = GraphLayout.layout(graph, Set.of(), detail, Map.of());
        final Map<String, Boolean> pins = new HashMap<>();
        if (!base.getLanes().isEmpty()) {
            final String key = GraphLayout.lanePinKey(base.getLanes().getFirst());
            if ("open".equals(pinMode)) {
                pins.put(key, false);
            } else if ("shut".equals(pinMode)) {
                pins.put(key, true);
            }
        }
        return GraphLayout.layout(graph, Set.of(), detail, pins);
    }

    private static void assertMatches(final Scenario scenario, final JsonNode expected) {
        final Map<String, Boolean> pins = new LinkedHashMap<>();
        for (final JsonNode pin : expected.get("pins")) {
            pins.put(pin.get("key").asString(), pin.get("value").asBoolean());
        }
        final Set<String> expanded = new HashSet<>();
        for (final JsonNode id : expected.get("expanded")) {
            expanded.add(id.asString());
        }
        final DetailLevel detail = DetailLevel.fromLevel(expected.get("detail").asInt());

        final LayoutResult layout = GraphLayout.layout(scenario.build(), expanded, detail, pins);

        assertThat(describeInstances(layout))
                .describedAs("instances")
                .containsExactlyElementsOf(strings(expected.get("instances"),
                        "id", "kind", "type", "name", "lane", "col", "x", "y", "w", "h",
                        "shared", "count", "host", "pinned"));
        assertThat(describeLayoutEdges(layout))
                .describedAs("edges")
                .containsExactlyElementsOf(strings(expected.get("edges"),
                        "a", "b", "kind", "back", "cert", "element", "path"));
        assertThat(describeLaneBoxes(layout))
                .describedAs("lane boxes")
                .containsExactlyElementsOf(strings(expected.get("laneBoxes"), "lane", "y0", "y1"));
        assertThat(describeGhosts(layout))
                .describedAs("ghosts")
                .containsExactlyElementsOf(strings(expected.get("ghosts"), "label", "row", "x", "y", "w", "h"));
        assertThat(layout.getLanes())
                .describedAs("lanes")
                .containsExactlyElementsOf(ints(expected.get("lanes")));
        assertThat(layout.getBackEdges())
                .describedAs("back edges")
                .containsExactlyElementsOf(texts(expected.get("backEdges")));

        final JsonNode stats = expected.get("stats");
        assertThat(layout.getStats())
                .describedAs("stats")
                .isEqualTo(new LayoutStats(
                        stats.get("nodes").asInt(),
                        stats.get("servedNodes").asInt(),
                        stats.get("edges").asInt(),
                        stats.get("lanes").asInt(),
                        stats.get("cols").asInt(),
                        stats.get("duplicated").asInt(),
                        stats.get("collapsed").asInt()));

        final LayoutAudit audit = LayoutAudit.audit(layout);
        final JsonNode expectedAudit = expected.get("audit");
        assertThat(audit.getOverlap())
                .describedAs("audit overlap")
                .containsExactlyElementsOf(texts(expectedAudit.get("overlap")));
        assertThat(audit.getOffCanvas())
                .describedAs("audit off canvas")
                .containsExactlyElementsOf(texts(expectedAudit.get("offCanvas")));
        assertThat(audit.getMissing())
                .describedAs("audit missing")
                .containsExactlyElementsOf(texts(expectedAudit.get("missing")));
        assertThat(audit.getThrough())
                .describedAs("audit through")
                .containsExactlyElementsOf(texts(expectedAudit.get("through")));
    }

    private static List<String> describeNodes(final DependencyGraph graph) {
        final List<String> lines = new ArrayList<>();
        for (final GraphNode node : graph.getNodes()) {
            lines.add(join(node.getId(),
                    node.getDocRef().getType(),
                    node.getDocRef().getUuid(),
                    node.getDocRef().getName(),
                    node.getRole(),
                    node.getStreamType(),
                    node.getElementCount()));
        }
        return lines;
    }

    private static List<String> describeGoldenNodes(final JsonNode graph) {
        final List<String> lines = new ArrayList<>();
        for (final JsonNode node : graph.get("nodes")) {
            final JsonNode docRef = node.get("docRef");
            lines.add(join(text(node.get("id")),
                    text(docRef.get("type")),
                    text(docRef.get("uuid")),
                    text(docRef.get("name")),
                    text(node.get("role")),
                    text(node.get("streamType")),
                    text(node.get("elementCount"))));
        }
        return lines;
    }

    private static List<String> describeEdges(final DependencyGraph graph) {
        final List<String> lines = new ArrayList<>();
        for (final GraphEdge edge : graph.getEdges()) {
            lines.add(join(edge.getFrom(), edge.getTo(), edge.getKind(), edge.getCertainty(), edge.getElement()));
        }
        return lines;
    }

    private static List<String> describeGoldenEdges(final JsonNode graph) {
        return strings(graph.get("edges"), "from", "to", "kind", "certainty", "element");
    }

    private static List<String> describeInstances(final LayoutResult layout) {
        final List<String> lines = new ArrayList<>();
        for (final Instance instance : layout.getInstances()) {
            final Dims dims = layout.dims(instance);
            lines.add(join(instance.getId(),
                    harnessKind(instance.getKind()),
                    instance.getType(),
                    instance.getName(),
                    instance.getLane(),
                    instance.getCol(),
                    instance.getX(),
                    instance.getY(),
                    dims.w(),
                    dims.h(),
                    instance.getShared(),
                    instance.getCount(),
                    instance.getHost(),
                    instance.getPinned()));
        }
        return lines;
    }

    private static List<String> describeLayoutEdges(final LayoutResult layout) {
        final List<String> lines = new ArrayList<>();
        for (final LayoutEdge edge : layout.getEdges()) {
            lines.add(join(edge.a().getId(),
                    edge.b().getId(),
                    edge.kind(),
                    edge.back(),
                    edge.certainty(),
                    edge.element(),
                    EdgePaths.path(layout, edge)));
        }
        return lines;
    }

    private static List<String> describeLaneBoxes(final LayoutResult layout) {
        final List<String> lines = new ArrayList<>();
        for (final Map.Entry<Integer, LaneBox> entry : layout.getLaneBoxes().entrySet()) {
            lines.add(join(entry.getKey(), entry.getValue().y0(), entry.getValue().y1()));
        }
        return lines;
    }

    private static List<String> describeGhosts(final LayoutResult layout) {
        final List<String> lines = new ArrayList<>();
        for (final Ghost ghost : layout.getGhosts()) {
            lines.add(join(ghost.label(),
                    ghost.referenceRow()
                            ? "ref"
                            : "flow",
                    ghost.x(),
                    ghost.y(),
                    ghost.w(),
                    ghost.h()));
        }
        return lines;
    }

    // The harness's names for instance kinds.
    private static String harnessKind(final InstanceKind kind) {
        return switch (kind) {
            case FLOW -> "flow";
            case REFERENCE -> "ref";
            case ENDPOINT -> "ep";
            case LANE_SUMMARY -> "laneSum";
            case REFERENCE_SUMMARY -> "refSum";
        };
    }

    static JsonNode readGolden(final String scenarioId) {
        try (final InputStream inputStream = TestGraphLayout.class.getResourceAsStream(
                GOLDEN_PATH + scenarioId + ".json")) {
            assertThat(inputStream)
                    .describedAs("reference layouts for %s", scenarioId)
                    .isNotNull();
            return JsonUtil.getMapper().readTree(inputStream);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> strings(final JsonNode array, final String... fields) {
        final List<String> lines = new ArrayList<>();
        for (final JsonNode element : array) {
            final Object[] values = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) {
                values[i] = text(element.get(fields[i]));
            }
            lines.add(join(values));
        }
        return lines;
    }

    static List<String> texts(final JsonNode array) {
        final List<String> values = new ArrayList<>();
        for (final JsonNode element : array) {
            values.add(text(element));
        }
        return values;
    }

    private static List<Integer> ints(final JsonNode array) {
        final List<Integer> values = new ArrayList<>();
        for (final JsonNode element : array) {
            values.add(element.asInt());
        }
        return values;
    }

    // A JSON value as text, with an absent or null value as "null", matching String.valueOf on the Java side.
    private static String text(final JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        } else if (node.isString()) {
            return node.asString();
        } else {
            return node.toString();
        }
    }

    private static String join(final Object... values) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }
}
