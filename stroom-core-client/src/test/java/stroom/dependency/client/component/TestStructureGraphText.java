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
import stroom.dependency.client.layout.Instance;
import stroom.dependency.client.layout.InstanceKind;
import stroom.dependency.client.layout.LayoutResult;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestStructureGraphText {

    @Test
    void testTruncate() {
        assertThat(StructureGraphText.truncate("Event Indexing", 20))
                .isEqualTo("Event Indexing");
        assertThat(StructureGraphText.truncate("Event Indexing", 6))
                .isEqualTo("Event…");
        assertThat(StructureGraphText.truncate(null, 6))
                .isEmpty();
    }

    @Test
    void testNamesShortenAsTheGraphZoomsOut() {
        final LayoutResult layout = layout("events", DetailLevel.EVERYTHING, Set.of());
        final Instance feed = instance(layout, "fw_raw");

        // FW-LOGS:RAW_EVENTS is 18 characters, which fits at actual size.
        assertThat(StructureGraphText.name(layout, feed, 1))
                .isEqualTo("FW-LOGS:RAW_EVENTS");
        // At half size the counter-scaled label fits 11 characters: (296 - 94) * 0.5 / (0.6 * 15).
        assertThat(StructureGraphText.name(layout, feed, 0.5))
                .isEqualTo("FW-LOGS:RA…");
    }

    @Test
    void testNamesAreNeverCutBelowFourCharacters() {
        final LayoutResult layout = layout("events", DetailLevel.EVERYTHING, Set.of());

        assertThat(StructureGraphText.name(layout, instance(layout, "fw_raw"), 0.08))
                .isEqualTo("FW-…");
    }

    @Test
    void testNameVisibility() {
        // 8 characters need (8 * 0.6 * 15) / (296 - 94) = 0.356 zoom.
        assertThat(StructureGraphText.isNameVisible(0.36))
                .isTrue();
        assertThat(StructureGraphText.isNameVisible(0.35))
                .isFalse();
    }

    @Test
    void testMetaSaysWhatASummaryFolds() {
        final LayoutResult lanes = layout("deep", DetailLevel.LANES_COLLAPSED, Set.of());
        final Instance laneSummary = lanes.getInstances().getFirst();

        assertThat(laneSummary.getKind())
                .isEqualTo(InstanceKind.LANE_SUMMARY);
        assertThat(StructureGraphText.meta(lanes, laneSummary, 1))
                .isEqualTo(laneSummary.getCount() + " docs · collapsed lane");

        final LayoutResult refs = layout("deep", DetailLevel.REFERENCES_COLLAPSED, Set.of());
        final Instance refSummary = refs.getInstances().stream()
                .filter(instance -> instance.getKind() == InstanceKind.REFERENCE_SUMMARY)
                .findFirst()
                .orElseThrow();

        assertThat(StructureGraphText.meta(refs, refSummary, 1))
                .isEqualTo(refSummary.getCount() + " refs · collapsed");
    }

    @Test
    void testMetaIsTheTypeName() {
        final LayoutResult layout = layout("events", DetailLevel.EVERYTHING, Set.of());

        assertThat(StructureGraphText.meta(layout, instance(layout, "idx"), 1))
                .isEqualTo("Lucene index");
        final Instance schema = layout.getInstances().stream()
                .filter(instance -> "sch".equals(instance.getSrc()))
                .findFirst()
                .orElseThrow();
        assertThat(StructureGraphText.meta(layout, schema, 1))
                .isEqualTo("XML schema");
    }

    @Test
    void testExpanded() {
        final LayoutResult layout = layout("deep", DetailLevel.EVERYTHING, Set.of("p1"));

        assertThat(StructureGraphText.isExpanded(layout, instance(layout, "p1")))
                .isTrue();
        assertThat(StructureGraphText.isExpanded(layout, instance(layout, "p2")))
                .isFalse();
        assertThat(StructureGraphText.meta(layout, instance(layout, "p1"), 1))
                .isEqualTo("Pipeline");
    }

    @Test
    void testTypes() {
        assertThat(StructureGraphTypes.glyph("Feed"))
                .isEqualTo("F");
        // An unknown type: its first letter, its own name, no colour of its own
        assertThat(StructureGraphTypes.glyph("Script"))
                .isEqualTo("S");
        assertThat(StructureGraphTypes.glyph(null))
                .isEqualTo("?");
        assertThat(StructureGraphTypes.label("PlanB"))
                .isEqualTo("Plan B store");
        assertThat(StructureGraphTypes.label("Script"))
                .isEqualTo("Script");
        // Types the server draws on the flow row all have their own
        assertThat(StructureGraphTypes.label("StatisticStore"))
                .isEqualTo("Statistic store");
        assertThat(StructureGraphTypes.glyph("StatisticStore"))
                .isNotEqualTo(StructureGraphTypes.glyph("XMLSchema"));
        assertThat(StructureGraphTypes.modifier("XMLSchema"))
                .isEqualTo("xml-schema");
        assertThat(StructureGraphTypes.modifier("Script"))
                .isEqualTo("other");
        assertThat(StructureGraphTypes.modifier("AnalyticRule"))
                .isEqualTo("analytic-rule");
    }

    static LayoutResult layout(final String scenarioId, final DetailLevel detail, final Set<String> expanded) {
        return GraphLayout.layout(GraphScenarios.get(scenarioId).build(), expanded, detail, Map.of());
    }

    static Instance instance(final LayoutResult layout, final String id) {
        return layout.getInstances().stream()
                .filter(instance -> instance.getId().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
