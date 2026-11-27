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

package stroom.learningflow.shared;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests the check presets: *Standard* is the defaults, and *Lenient* and *Strict* loosen or tighten it.
class TestCheckPreset {

    @Test
    void standardIsTheDefaults() {
        assertThat(CheckPreset.STANDARD.settings()).isEqualTo(CheckSettings.defaults());
    }

    @Test
    void lenientLoosensTheGatesThatAreAJudgementCall() {
        final List<CheckSetting> lenient = CheckPreset.LENIENT.settings();
        assertThat(CheckSettings.of(lenient, Check.COVERAGE))
                .isEqualTo(new CheckSetting(Check.COVERAGE, CheckState.ADVICE, 0.8, null));
        assertThat(CheckSettings.of(lenient, Check.WHOLENESS))
                .isEqualTo(new CheckSetting(Check.WHOLENESS, CheckState.GATE, 0.8, null));
        assertThat(CheckSettings.of(lenient, Check.FIDELITY).getState()).isEqualTo(CheckState.ADVICE);
        assertThat(CheckSettings.of(lenient, Check.CONFORMANCE).getState()).isEqualTo(CheckState.GATE);
        assertThat(CheckSettings.of(lenient, Check.PRESERVATION).getState()).isEqualTo(CheckState.GATE);
    }

    @Test
    void strictTightensTheGatesAndAdvisesOnTheHeuristics() {
        final List<CheckSetting> strict = CheckPreset.STRICT.settings();
        assertThat(CheckSettings.of(strict, Check.COVERAGE).getThreshold()).isEqualTo(0.95);
        assertThat(CheckSettings.of(strict, Check.WHOLENESS).getThreshold()).isEqualTo(0.95);
        assertThat(CheckSettings.of(strict, Check.YIELD))
                .isEqualTo(new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.8, null));
        assertThat(CheckSettings.of(strict, Check.QUALITY))
                .isEqualTo(new CheckSetting(Check.QUALITY, CheckState.ADVICE, 0.9, null));
        assertThat(CheckSettings.of(strict, Check.RULES).getState()).isEqualTo(CheckState.GATE);
    }

    @Test
    void aSetIsKnownAsItsPresetOrAsItsOwn() {
        for (final CheckPreset preset : CheckPreset.values()) {
            assertThat(CheckPreset.of(preset.settings())).isEqualTo(preset);
        }
        assertThat(CheckPreset.of(List.of(CheckSetting.of(Check.YIELD, CheckState.GATE)))).isNull();
        assertThat(CheckPreset.of(List.of())).describedAs("nothing named is every default").isEqualTo(
                CheckPreset.STANDARD);
    }

    @Test
    void everyPresetKeepsToTheRules() {
        for (final CheckPreset preset : CheckPreset.values()) {
            assertThat(CheckSettings.problems(preset.settings())).isEmpty();
        }
    }
}
