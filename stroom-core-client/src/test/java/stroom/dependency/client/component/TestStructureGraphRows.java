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
import stroom.dependency.client.layout.LayoutResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestStructureGraphRows {

    @Test
    void testEveryInstanceHasARow() {
        final LayoutResult layout = layout("events");

        final List<StructureGraphRow> rows = StructureGraphRows.of(layout);

        assertThat(rows)
                .extracting(StructureGraphRow::id)
                .containsExactlyInAnyOrderElementsOf(layout.getInstances().stream()
                        .map(instance -> instance.getId())
                        .toList());
    }

    @Test
    void testRowsFollowTheFlowLaneByLane() {
        // One chain, seven stages deep: the flow row in column order, then its references.
        final List<StructureGraphRow> rows = StructureGraphRows.of(layout("deep"));

        assertThat(rows.subList(0, 4))
                .extracting(StructureGraphRow::id)
                .containsExactly("f0", "p1", "f1", "p2");
        assertThat(rows)
                .extracting(StructureGraphRow::lane)
                .isSorted();
        final int firstReference = rows.indexOf(row(rows, "ref:0:x1"));
        assertThat(rows.subList(0, firstReference))
                .extracting(StructureGraphRow::type)
                .doesNotContain("XSLT");
    }

    @Test
    void testConnectionsAreNamed() {
        final List<StructureGraphRow> rows = StructureGraphRows.of(layout("deep"));

        final StructureGraphRow pipeline = row(rows, "p1");
        assertThat(pipeline.upstream()).isEqualTo("STAGE-00:RAW_EVENTS");
        assertThat(pipeline.downstream()).isEqualTo("STAGE-01:EVENTS");
        assertThat(pipeline.references()).isEqualTo("Stage 1 Translation");
        assertThat(pipeline.referencedBy()).isEqualTo(StructureGraphRows.NONE);
        assertThat(pipeline.type()).isEqualTo("Pipeline");
        assertThat(pipeline.docRef().getUuid()).isEqualTo("u1");

        final StructureGraphRow xslt = row(rows, "ref:0:x1");
        assertThat(xslt.referencedBy()).isEqualTo("Stage 1");
        assertThat(xslt.upstream()).isEqualTo(StructureGraphRows.NONE);
    }

    @Test
    void testNotesSayWhatTheHoverCardDoes() {
        final List<StructureGraphRow> rows = StructureGraphRows.of(layout("events"));

        assertThat(row(rows, "fw_raw").notes()).isEqualTo("Stream type: RAW_EVENTS");
        assertThat(row(rows, "fw_par").notes()).isEqualTo("7 elements");
        assertThat(row(rows, "idx").notes()).isEmpty();
    }

    @Test
    void testLanesAreNamedByTheirFirstDocument() {
        final List<StructureGraphRow> rows = StructureGraphRows.of(layout("deep"));

        assertThat(row(rows, "idx").laneName()).isEqualTo("Lane 1: STAGE-00:RAW_EVENTS");
    }

    @Test
    void testALaneOfReferencesOnlyIsNumbered() {
        final List<StructureGraphRow> rows = StructureGraphRows.of(layout("single"));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().laneName()).isEqualTo("Lane 1");
    }

    @Test
    void testEmpty() {
        assertThat(StructureGraphRows.of(layout("empty"))).isEmpty();
    }

    private static LayoutResult layout(final String scenarioId) {
        return GraphLayout.layout(GraphScenarios.get(scenarioId).build(), Set.of(), DetailLevel.EVERYTHING, Map.of());
    }

    private static StructureGraphRow row(final List<StructureGraphRow> rows, final String id) {
        return rows.stream()
                .filter(row -> row.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
