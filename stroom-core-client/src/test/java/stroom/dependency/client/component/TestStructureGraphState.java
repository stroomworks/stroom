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
import stroom.dependency.client.layout.GraphViewport.FitMode;
import stroom.dependency.client.layout.Instance;
import stroom.dependency.client.layout.InstanceKind;
import stroom.dependency.shared.DependencyGraph;
import stroom.dependency.shared.EdgeKind;
import stroom.dependency.shared.GraphEdge;
import stroom.dependency.shared.GraphNode;
import stroom.dependency.shared.NodeRole;
import stroom.docref.DocRef;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestStructureGraphState {

    @Test
    void testNewGraphForgetsWhatWasChanged() {
        final StructureGraphState state = state("deep");
        state.toggleExpanded("p1");
        state.collapseLane(0);
        state.search("stage");

        state.setGraph(GraphScenarios.get("deep").build());

        assertThat(state.getLayout().getExpanded()).isEmpty();
        assertThat(state.getLayout().getPins()).isEmpty();
        assertThat(state.getMatches()).isEmpty();
        assertThat(state.getViewport().getZoom()).isEqualTo(1);
    }

    @Test
    void testNullGraphIsEmpty() {
        final StructureGraphState state = new StructureGraphState();

        state.setGraph(null);

        assertThat(state.getLayout().getInstances()).isEmpty();
        assertThat(state.getLayout().getGhosts()).isNotEmpty();
        assertThat(state.summary()).isEqualTo("Structure of the folder: it has no documents");
    }

    @Test
    void testToggleExpanded() {
        final StructureGraphState state = state("deep");

        state.toggleExpanded("p1");
        assertThat(state.getLayout().getExpanded()).containsExactly("p1");

        state.toggleExpanded("p1");
        assertThat(state.getLayout().getExpanded()).isEmpty();
    }

    @Test
    void testCollapseLaneThenOpenItsSummary() {
        final StructureGraphState state = state("deep");
        state.setSelectedId("p1");

        state.collapseLane(0);

        final Instance summary = state.instance(GraphLayout.lanePinKey(0));
        assertThat(summary.getKind()).isEqualTo(InstanceKind.LANE_SUMMARY);
        assertThat(state.getSelectedId())
                .describedAs("the selected node was folded into the summary")
                .isNull();

        assertThat(state.openSummary(summary.getId())).isTrue();
        assertThat(state.instance("p1")).isNotNull();
        assertThat(state.getLayout().getPins()).containsEntry(GraphLayout.lanePinKey(0), false);
    }

    @Test
    void testOpenReferenceSummaryPinsTheReferenceRowOpen() {
        final StructureGraphState state = state("deep");
        // Zoomed out far enough to fold the reference rows, but not the lanes.
        state.zoomBy(0, 0, 0.5);
        assertThat(state.getLayout().getDetail()).isEqualTo(DetailLevel.REFERENCES_COLLAPSED);

        assertThat(state.openSummary("refsum:0")).isTrue();

        assertThat(state.getLayout().getPins()).containsEntry(GraphLayout.refsPinKey(0), false);
        assertThat(state.instance("refsum:0")).isNull();
    }

    @Test
    void testOpenSummaryIgnoresOtherNodes() {
        final StructureGraphState state = state("deep");

        assertThat(state.openSummary("p1")).isFalse();
        assertThat(state.openSummary("no-such-node")).isFalse();
    }

    @Test
    void testSelectKeepsTheSelectionAndIgnoresUnknownIds() {
        final StructureGraphState state = state("fanin");

        state.setSelectedId("f0");
        assertThat(state.getSelectedId()).isEqualTo("f0");
        assertThat(state.getScope()).contains("f0", "p0", "idx").doesNotContain("f1");

        // Again, as the second click of a double click does: still selected
        state.setSelectedId("f0");
        assertThat(state.getSelectedId()).isEqualTo("f0");

        state.setSelectedId(null);
        assertThat(state.getSelectedId()).isNull();
        assertThat(state.getScope()).isNull();

        state.setSelectedId("no-such-node");
        assertThat(state.getSelectedId()).isNull();
    }

    @Test
    void testSearchIgnoresCaseAndBlankClears() {
        final StructureGraphState state = state("fanin");

        assertThat(state.search("indexer 3")).containsExactly("p3");
        assertThat(state.search("VIEW")).hasSize(6);
        assertThat(state.search("nothing like this")).isEmpty();
        assertThat(state.search("  ")).isEmpty();
        assertThat(state.search(null)).isEmpty();
    }

    @Test
    void testWheelCrossingAThresholdLaysOutAgain() {
        final StructureGraphState state = state("deep");

        // A small zoom out stays above 0.62: only the view moves.
        assertThat(state.zoomByWheel(0, 0, 100)).isFalse();
        // Far enough out to cross it.
        assertThat(state.zoomByWheel(0, 0, 400)).isTrue();
        assertThat(state.getLayout().getDetail()).isEqualTo(DetailLevel.REFERENCES_COLLAPSED);
    }

    @Test
    void testFitSettlesTheDetailLevel() {
        final StructureGraphState state = state("wide40");

        final boolean laidOutAgain = state.fit(800, 400, FitMode.ALL);

        assertThat(laidOutAgain).isTrue();
        assertThat(state.getLayout().getDetail()).isEqualTo(state.getViewport().getDetailLevel());
    }

    @Test
    void testDescribe() {
        final StructureGraphState state = state("events");

        assertThat(state.describe(state.instance("fw_raw")))
                .containsExactly("Feed", "Stream type: RAW_EVENTS");
        assertThat(state.describe(state.instance("fw_par")))
                .containsExactly("Pipeline", "7 elements");
        final Instance schema = state.getLayout().getInstances().stream()
                .filter(instance -> "sch".equals(instance.getSrc()))
                .findFirst()
                .orElseThrow();
        assertThat(state.describe(schema))
                .containsExactly("XML schema", "Used in 4 lanes");
    }

    @Test
    void testDescribeSummaries() {
        final StructureGraphState state = state("deep");
        state.collapseLane(0);

        assertThat(state.describe(state.instance(GraphLayout.lanePinKey(0))))
                .first()
                .asString()
                .startsWith("A collapsed lane of ");
    }

    @Test
    void testDescribeNamesAmbiguityAndContext() {
        final StructureGraphState state = new StructureGraphState();
        state.setGraph(new DependencyGraph(
                null,
                List.of(new GraphNode("p", new DocRef("Pipeline", "p", "P"), NodeRole.FLOW,
                                null, true, null, null, null),
                        new GraphNode("x", new DocRef("XSLT", "x", "X"), NodeRole.REFERENCE,
                                null, false, 1, null, null)),
                List.of(new GraphEdge("p", "x", EdgeKind.REFERENCE, null, null, true)),
                null,
                null,
                null,
                null));

        assertThat(state.describe(state.instance("p")))
                .contains("Refers to a name that more than one document has");
        assertThat(state.describe(state.instance("ref:0:x")))
                .contains("Outside this folder",
                        "Shares its name with another document, so a reference to it is ambiguous");
    }

    @Test
    void testSummaryAndNote() {
        final StructureGraphState state = state("fanin");

        assertThat(state.summary()).isEqualTo("Structure of the folder: 23 documents in 8 lanes");
        assertThat(state.note()).isNull();

        state.setGraph(new DependencyGraph(null, null, null, null, true, null, null));
        assertThat(state.note()).startsWith("This folder has too many documents");
    }

    @Test
    void testRowsListEveryDocumentWhateverTheCanvasCollapses() {
        final StructureGraphState state = state("deep");
        final int rows = state.getRows().size();

        state.zoomBy(0, 0, 0.3);
        assertThat(state.getLayout().getDetail()).isEqualTo(DetailLevel.LANES_COLLAPSED);

        assertThat(state.getRows()).hasSize(rows);
    }

    @Test
    void testCanvasIdForARowFollowsCollapse() {
        final StructureGraphState state = state("deep");
        assertThat(state.canvasIdFor("p1")).isEqualTo("p1");
        assertThat(state.canvasIdFor("ref:0:x1")).isEqualTo("ref:0:x1");

        // Reference rows folded: the flow row is still drawn, its references are in the summary.
        state.zoomBy(0, 0, 0.5);
        assertThat(state.canvasIdFor("p1")).isEqualTo("p1");
        assertThat(state.canvasIdFor("ref:0:x1")).isEqualTo("refsum:0");

        // Lanes folded: everything is in the lane's summary.
        state.zoomBy(0, 0, 0.5);
        assertThat(state.canvasIdFor("p1")).isEqualTo(GraphLayout.lanePinKey(0));
        assertThat(state.canvasIdFor("ref:0:x1")).isEqualTo(GraphLayout.lanePinKey(0));

        assertThat(state.canvasIdFor("no-such-row")).isNull();
    }

    @Test
    void testRowIdForASummaryIsNull() {
        final StructureGraphState state = state("deep");
        state.collapseLane(0);

        assertThat(state.rowIdFor(GraphLayout.lanePinKey(0))).isNull();
        assertThat(state.rowIdFor("p1")).isEqualTo("p1");
    }

    @Test
    void testSetSelectedIdDoesNotToggle() {
        final StructureGraphState state = state("deep");

        state.setSelectedId("p1");
        state.setSelectedId("p1");
        assertThat(state.getSelectedId()).isEqualTo("p1");

        state.setSelectedId("no-such-node");
        assertThat(state.getSelectedId()).isNull();
    }

    @Test
    void testNotesSayWhatTheHoverCardSays() {
        final StructureGraphState state = new StructureGraphState();
        state.setGraph(new DependencyGraph(
                null,
                List.of(new GraphNode("p", new DocRef("Pipeline", "p", "P"), NodeRole.FLOW,
                        null, true, null, 6, 2)),
                null,
                null,
                null,
                null,
                null));

        final Instance pipeline = state.instance("p");
        assertThat(new StructureGraphNotes(state.getLayout()).of(pipeline))
                .containsExactly("6 elements", "Refers to 2 documents that cannot be found");
        assertThat(state.describe(pipeline))
                .containsExactly("Pipeline", "6 elements", "Refers to 2 documents that cannot be found");
        assertThat(state.getRows().getFirst().notes())
                .isEqualTo("6 elements. Refers to 2 documents that cannot be found");
    }

    private static StructureGraphState state(final String scenarioId) {
        final StructureGraphState state = new StructureGraphState();
        state.setGraph(GraphScenarios.get(scenarioId).build());
        return state;
    }
}
