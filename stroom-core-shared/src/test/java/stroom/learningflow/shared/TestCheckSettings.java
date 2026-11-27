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

import stroom.util.json.JsonUtil;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests the check settings: every check has exactly one setting, a new Learning Flow document has the
/// *Standard* defaults, settings round-trip, and invalid sets are reported.
class TestCheckSettings {

    @Test
    void theDefaultsAreTheDecidedTable() {
        final List<CheckSetting> defaults = CheckSettings.defaults();
        assertThat(defaults).extracting(CheckSetting::getCheck).containsExactly(Check.values());
        assertThat(state(defaults, Check.COMPILE)).isEqualTo(CheckState.GATE);
        assertThat(state(defaults, Check.CONFORMANCE)).isEqualTo(CheckState.GATE);
        assertThat(state(defaults, Check.PRESERVATION)).isEqualTo(CheckState.GATE);
        assertThat(state(defaults, Check.FIDELITY)).isEqualTo(CheckState.GATE);
        assertThat(state(defaults, Check.REGRESSION)).isEqualTo(CheckState.GATE);
        assertThat(state(defaults, Check.YIELD)).isEqualTo(CheckState.OFF);
        assertThat(state(defaults, Check.QUALITY)).isEqualTo(CheckState.OFF);
        assertThat(state(defaults, Check.RULES)).isEqualTo(CheckState.OFF);
        assertThat(CheckSettings.of(defaults, Check.COVERAGE))
                .describedAs("D4: input coverage is a gate at 90%")
                .isEqualTo(new CheckSetting(Check.COVERAGE, CheckState.GATE, 0.9, null));
        assertThat(CheckSettings.of(defaults, Check.WHOLENESS).getThreshold()).isEqualTo(0.9);
    }

    @Test
    void aNewDocumentHasTheDefaults() {
        assertThat(LearningFlowDoc.builder().uuid("uuid").build().getChecks()).isEqualTo(CheckSettings.defaults());
    }

    @Test
    void aCheckNotNamedHasItsDefaultAndTheFirstOfTwoIsKept() {
        final List<CheckSetting> complete = CheckSettings.complete(List.of(
                CheckSetting.of(Check.YIELD, CheckState.GATE),
                CheckSetting.of(Check.YIELD, CheckState.OFF)));
        assertThat(complete).hasSize(Check.values().length);
        assertThat(state(complete, Check.YIELD)).isEqualTo(CheckState.GATE);
        assertThat(state(complete, Check.CONFORMANCE)).isEqualTo(CheckState.GATE);
    }

    @Test
    void aDocumentWithScorersHasItsChecksReadFromThem() {
        final LearningFlowDoc doc = LearningFlowDoc.builder()
                .uuid("uuid")
                .checks(List.of(
                        CheckSetting.of(Check.COMPILE, CheckState.GATE),
                        CheckSetting.of(Check.COVERAGE, CheckState.OFF),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.6, null),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        new CheckSetting(Check.CONFORMANCE, CheckState.GATE, 1.0, null),
                        CheckSetting.of(Check.QUALITY, CheckState.OFF),
                        CheckSetting.of(Check.RULES, CheckState.OFF),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
                .build();
        assertThat(doc.getCheck(Check.YIELD))
                .describedAs("listed but not a gate: advice, its weight dropped")
                .isEqualTo(new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.6, null));
        assertThat(doc.getCheck(Check.CONFORMANCE).getState()).isEqualTo(CheckState.GATE);
        assertThat(doc.getCheck(Check.QUALITY).getState()).describedAs("not listed").isEqualTo(CheckState.OFF);
        assertThat(doc.getCheck(Check.COMPILE).getState()).describedAs("always").isEqualTo(CheckState.GATE);
        assertThat(doc.getCheck(Check.WHOLENESS).getState())
                .describedAs("a check with no scorer always judged where it applied")
                .isEqualTo(CheckState.GATE);
    }

    @Test
    void checksSetOnTheBuilderReplaceAnyScorers() {
        final LearningFlowDoc doc = LearningFlowDoc.builder()
                .uuid("uuid")
                .checks(List.of(
                        CheckSetting.of(Check.COMPILE, CheckState.GATE),
                        CheckSetting.of(Check.COVERAGE, CheckState.OFF),
                        new CheckSetting(Check.YIELD, CheckState.GATE, 0.6, null),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        CheckSetting.of(Check.CONFORMANCE, CheckState.OFF),
                        CheckSetting.of(Check.QUALITY, CheckState.OFF),
                        CheckSetting.of(Check.RULES, CheckState.OFF),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
                .checks(List.of(CheckSetting.of(Check.YIELD, CheckState.ADVICE)))
                .build();
        assertThat(doc.getCheck(Check.YIELD).getState()).isEqualTo(CheckState.ADVICE);
    }

    @Test
    void aDocumentsChecksRoundTrip() {
        final LearningFlowDoc doc = LearningFlowDoc.builder()
                .uuid("uuid")
                .checks(CheckPreset.STRICT.settings())
                .build();
        final LearningFlowDoc read = JsonUtil.readValue(JsonUtil.writeValueAsString(doc), LearningFlowDoc.class);
        assertThat(read).isEqualTo(doc);
        assertThat(read.getChecks()).isEqualTo(CheckPreset.STRICT.settings());
    }

    @Test
    void theProblemsAreNamed() {
        assertThat(CheckSettings.problems(CheckSettings.defaults())).isEmpty();
        assertThat(CheckSettings.problems(List.of(
                CheckSetting.of(Check.COMPILE, CheckState.ADVICE),
                new CheckSetting(Check.YIELD, CheckState.GATE, 1.5, null),
                CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                CheckSetting.of(Check.FIDELITY, CheckState.OFF))))
                .containsExactly(
                        "The compile check is always a gate.",
                        "The yield check's threshold must be between 0 and 1.",
                        "The fidelity check is set more than once.");
        assertThat(CheckSettings.problems(null)).isEmpty();
    }

    @Test
    void aSettingKeepsAThresholdOnlyWhereItsCheckHasOne() {
        assertThat(new CheckSetting(Check.PRESERVATION, CheckState.GATE, 0.4, null).getThreshold()).isNull();
        assertThat(new CheckSetting(Check.PRESERVATION, CheckState.GATE, 0.4, null).getPassingValue()).isEqualTo(1.0);
        assertThat(new CheckSetting(Check.YIELD, null, null, null).getState()).isEqualTo(CheckState.OFF);
        assertThat(new CheckSetting(Check.YIELD, null, null, null).getThreshold()).isEqualTo(0.5);
        assertThatThrownBy(() -> new CheckSetting(Check.WHOLENESS, CheckState.GATE, null,
                new YieldParameters(null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CheckState state(final List<CheckSetting> settings, final Check check) {
        return CheckSettings.of(settings, check).getState();
    }
}
