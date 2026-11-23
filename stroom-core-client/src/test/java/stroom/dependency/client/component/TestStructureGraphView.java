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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class TestStructureGraphView {

    @Test
    void testGraphIsTheDefault() {
        assertThat(StructureGraphView.DEFAULT).isEqualTo(StructureGraphView.GRAPH);
    }

    @Test
    void testWhatEachViewShows() {
        assertThat(StructureGraphView.GRAPH.isGraphShown()).isTrue();
        assertThat(StructureGraphView.GRAPH.isTableShown()).isFalse();

        assertThat(StructureGraphView.TABLE.isGraphShown()).isFalse();
        assertThat(StructureGraphView.TABLE.isTableShown()).isTrue();

        assertThat(StructureGraphView.BOTH.isGraphShown()).isTrue();
        assertThat(StructureGraphView.BOTH.isTableShown()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(StructureGraphView.class)
    void testFromNameReadsWhatIsStored(final StructureGraphView view) {
        assertThat(StructureGraphView.fromName(view.name())).isEqualTo(view);
    }

    // A missing, empty or unknown value (e.g. from an older or newer Stroom) opens on the default
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"graph", "Table", "SPLIT", " BOTH"})
    void testFromNameFallsBackToTheDefault(final String name) {
        assertThat(StructureGraphView.fromName(name)).isEqualTo(StructureGraphView.DEFAULT);
    }

    // Each view's option is named, and has a tooltip saying what it shows
    @Test
    void testEveryViewHasALabelAndTitle() {
        for (final StructureGraphView view : StructureGraphView.values()) {
            assertThat(view.getLabel()).isNotBlank();
            assertThat(view.getTitle()).isNotBlank();
        }
    }
}
