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

package stroom.shapeshifter.client.component;

import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestQualityPreset {

    @Test
    void aPresetsOwnChecksAreRecognised() {
        for (final QualityPreset preset : QualityPreset.values()) {
            assertThat(QualityPreset.of(new ArrayList<>(preset.getSettings()))).isEqualTo(preset);
            assertThat(preset.getDescription()).isNotBlank();
            // Every preset compiles and validates against the schema, as gates
            assertThat(preset.getSettings()).anyMatch(s -> s.getType() == ScorerType.COMPILE && s.isGate());
            assertThat(preset.getSettings()).anyMatch(s -> s.getType() == ScorerType.SCHEMA_CONFORMANCE
                                                          && s.isGate());
            // Only scorers that exist
            assertThat(preset.getSettings()).allMatch(s -> s.getType().isBuilt());
        }
    }

    @Test
    void anEditedSetIsTheDocumentsOwn() {
        final List<ScorerSetting> edited = new ArrayList<>(QualityPreset.STANDARD.getSettings());
        edited.set(1, new ScorerSetting(ScorerType.YIELD, 1.0, 0.6, false, null));
        assertThat(QualityPreset.of(edited)).isNull();
        assertThat(QualityPreset.of(List.of())).isNull();
        assertThat(QualityPreset.of(null)).isNull();
    }
}
