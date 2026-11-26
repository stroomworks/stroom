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

package stroom.learningflow.client.component;

import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckPreset;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.PromotionMode;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestAdvancedSettings {

    @Test
    void aNewLearningFlowHasNothingChangedUnderAdvancedOptions() {
        assertThat(AdvancedSettings.changed(doc().build())).isEmpty();
    }

    @Test
    void whatIsAlwaysInSightChangesNothingUnderAdvancedOptions() {
        final LearningFlowDoc doc = doc()
                .learningMode(LearningMode.AUTOMATIC)
                .instructions("Prefer Network/Open")
                .attemptBudgetMs(120_000L)
                .tokenBudget(50_000L)
                .checks(CheckPreset.STRICT.settings())
                .promotionMode(PromotionMode.REVIEW)
                .relearnThreshold(0.6)
                .build();
        assertThat(AdvancedSettings.changed(doc)).isEmpty();
    }

    @Test
    void triesOrTheSampleOpenLearningSteps() {
        assertThat(AdvancedSettings.changed(doc().maxAttempts(9).build()))
                .containsExactly(SetupSection.APPROACH);
        assertThat(AdvancedSettings.changed(doc().sampleSizeLimit(1024).build()))
                .containsExactly(SetupSection.APPROACH);
    }

    @Test
    void checksOfItsOwnOrLinesKeptBackOpenQualityChecks() {
        final List<CheckSetting> own = new ArrayList<>(CheckPreset.STANDARD.settings());
        own.replaceAll(setting -> setting.getCheck() == Check.YIELD
                ? setting.withState(CheckState.ADVICE)
                : setting);
        assertThat(AdvancedSettings.changed(doc().checks(own).build()))
                .containsExactly(SetupSection.QUALITY);
        assertThat(AdvancedSettings.changed(doc().heldOutFraction(0.3).build()))
                .containsExactly(SetupSection.QUALITY);
    }

    @Test
    void checksNotNamedAreTheDefaultsAndLeaveQualityChecksFolded() {
        assertThat(AdvancedSettings.changed(doc().checks(List.of()).build())).isEmpty();
    }

    @Test
    void aFractionReadBackAsTheSamePercentIsNoChange() {
        final double held = doc().build().getHeldOutFraction();
        assertThat(AdvancedSettings.changed(doc().heldOutFraction(held + 0.000_001).build()))
                .isEmpty();
    }

    @Test
    void recordsBeforeTrustOrTheFallbackOpenApprovingNewTranslations() {
        assertThat(AdvancedSettings.changed(doc().minRecordsToLearn(50).build()))
                .containsExactly(SetupSection.GOING_LIVE);
        final LearningFlow withoutFallback = new LearningFlow(
                ApproachFlow.withFallback(FlowExampleFixtures.steps(FlowExample.DIRECT), false), null, null);
        assertThat(AdvancedSettings.changed(doc().flow(withoutFallback).build()))
                .containsExactly(SetupSection.GOING_LIVE);
    }

    @Test
    void recordsKeptOrOverdueOpenMonitoring() {
        assertThat(AdvancedSettings.changed(doc().regressionCap(10).build()))
                .containsExactly(SetupSection.IN_SERVICE);
        assertThat(AdvancedSettings.changed(doc().maxDeferralAgeMs(3_600_000L).build()))
                .containsExactly(SetupSection.IN_SERVICE);
    }

    @Test
    void theLearningSectionHasNoAdvancedOptions() {
        final LearningFlowDoc doc = doc()
                .maxAttempts(9)
                .heldOutFraction(0.3)
                .minRecordsToLearn(50)
                .regressionCap(10)
                .build();
        assertThat(AdvancedSettings.changed(doc))
                .containsExactlyInAnyOrder(SetupSection.APPROACH, SetupSection.QUALITY, SetupSection.GOING_LIVE,
                        SetupSection.IN_SERVICE)
                .doesNotContain(SetupSection.LEARNING);
    }

    /// Makes a builder for a document as it is when new, for a test to change one setting of.
    /// A new document is given the `Direct` flow's steps by the store. A document built here would otherwise
    /// have no steps, and every check that reads the flow would report that as a problem.
    ///
    /// @return The builder.
    private static LearningFlowDoc.Builder doc() {
        return LearningFlowDoc.builder()
                .uuid("ss-1")
                .flow(new LearningFlow(FlowExampleFixtures.steps(FlowExample.DIRECT), null, null));
    }
}
