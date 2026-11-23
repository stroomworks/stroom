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
import stroom.dependency.client.layout.Instance;
import stroom.dependency.client.layout.LayoutResult;
import stroom.dependency.shared.DependencyGraph;
import stroom.dependency.shared.GraphElement;
import stroom.dependency.shared.GraphNode;
import stroom.dependency.shared.NodeRole;
import stroom.docref.DocRef;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestStructureGraphNotes {

    // What an expanded pipeline draws is said in words too, for the table's Notes column and a screen
    // reader: each element's name (or id) and type, in order
    @Test
    void testAPipelinesElementsAreListed() {
        final List<GraphElement> elements = List.of(
                new GraphElement("dsParser", null, "DSParser"),
                new GraphElement("translationFilter", "FW Translation", "XSLTFilter"));

        assertThat(notesOfPipeline(2, elements))
                .containsExactly("2 elements: dsParser (DSParser), FW Translation (XSLTFilter)");
    }

    // A graph that only counts a pipeline's elements says how many there are
    @Test
    void testAPipelinesElementsAreCountedWhenNotNamed() {
        assertThat(notesOfPipeline(1, null)).containsExactly("1 element");
        assertThat(notesOfPipeline(3, List.of())).containsExactly("3 elements");
    }

    @Test
    void testAPipelineWithNoElementsSaysNothingOfThem() {
        assertThat(notesOfPipeline(0, List.of())).isEmpty();
    }

    private static List<String> notesOfPipeline(final int elementCount, final List<GraphElement> elements) {
        final DependencyGraph graph = new DependencyGraph(
                null,
                List.of(new GraphNode("p", new DocRef("Pipeline", "p", "Parser"), NodeRole.FLOW, null, true,
                        null, elementCount, elements, null)),
                null,
                null,
                null,
                null,
                null);
        final LayoutResult layout = GraphLayout.layout(graph, Set.of(), DetailLevel.EVERYTHING, Map.of());
        final Instance pipeline = layout.getInstances().stream()
                .filter(instance -> "p".equals(instance.getId()))
                .findFirst()
                .orElseThrow();
        return new StructureGraphNotes(layout).of(pipeline);
    }
}
