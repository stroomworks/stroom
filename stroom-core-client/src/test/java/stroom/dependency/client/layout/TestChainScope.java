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
import stroom.dependency.shared.GraphNode;
import stroom.dependency.shared.NodeRole;
import stroom.docref.DocRef;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TestChainScope {

    /// The scope of every instance, in every scenario the harness recorded scopes for, is the same as the
    /// harness's.
    @TestFactory
    Stream<DynamicTest> testScopeMatchesHarness() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Scenario scenario : GraphScenarios.ALL) {
            final JsonNode scopes = TestGraphLayout.readGolden(scenario.id()).get("scopes");
            if (scopes == null || scopes.isNull()) {
                // Recorded for every scenario but the large and the empty ones (generate-layout-golden.mjs)
                assertThat(scenario.id()).isIn("wide12", "wide40", "refheavy", "empty");
                continue;
            }
            tests.add(DynamicTest.dynamicTest(scenario.id(), () -> {
                final LayoutResult layout = layout(scenario.id());
                for (final JsonNode expected : scopes) {
                    final String id = expected.get("id").asString();
                    assertThat(ChainScope.of(layout, id))
                            .describedAs("scope of %s", id)
                            .containsExactlyInAnyOrderElementsOf(TestGraphLayout.texts(expected.get("scope")));
                }
            }));
        }
        return tests.stream();
    }

    @Test
    void testSiblingsSharingADownstreamNodeAreNotInScope() {
        // In the fan-in scenario every chain converges on one index. Selecting one feed must light up its
        // own chain and everything downstream, but not the other feeds.
        final LayoutResult layout = layout("fanin");

        final Set<String> scope = ChainScope.of(layout, "f0");

        assertThat(scope)
                .contains("f0", "p0", "idx", "d0")
                .doesNotContain("f1", "p1");
    }

    @Test
    void testScopeTakesInEveryCopyOfADocument() {
        // The schema is drawn once per lane in the wide scenario; selecting one copy selects them all.
        final LayoutResult layout = layout("refheavy");

        final Set<String> scope = ChainScope.of(layout, "ref:0:sch");

        assertThat(scope)
                .contains("ref:0:sch", "ref:1:sch", "ref:2:sch");
    }

    @Test
    void testSelectingASummaryDoesNotTakeInOtherSummaries() {
        // Two unconnected feeds, each its own lane, both collapsed into summaries
        final DependencyGraph graph = new DependencyGraph(
                null,
                List.of(new GraphNode("a", new DocRef("Feed", "a", "A"), NodeRole.FLOW, null, true, null, null, null),
                        new GraphNode("b", new DocRef("Feed", "b", "B"), NodeRole.FLOW, null, true, null, null, null)),
                null,
                null,
                null,
                null,
                null);
        final LayoutResult layout = GraphLayout.layout(graph, Set.of(), DetailLevel.LANES_COLLAPSED, Map.of());

        assertThat(ChainScope.of(layout, GraphLayout.lanePinKey(0)))
                .containsExactly(GraphLayout.lanePinKey(0));
    }

    @Test
    void testUnknownOrNullIdHasNoScope() {
        final LayoutResult layout = layout("events");

        assertThat(ChainScope.of(layout, null))
                .isNull();
        assertThat(ChainScope.of(layout, "no-such-instance"))
                .isNull();
    }

    private static LayoutResult layout(final String scenarioId) {
        return GraphLayout.layout(GraphScenarios.get(scenarioId).build(),
                Set.of(),
                DetailLevel.EVERYTHING,
                Map.of());
    }
}
