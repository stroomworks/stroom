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

import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.Template;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestAdvancedSettings {

    @Test
    void aNewLearningFlowHasNothingChangedUnderAdvancedOptions() {
        assertThat(AdvancedSettings.changed(doc().build())).isEmpty();
    }

    @Test
    void whatIsAlwaysInSightChangesNothingUnderAdvancedOptions() {
        final ShapeshifterAiDoc doc = doc()
                .learningMode(LearningMode.AUTOMATIC)
                .instructions("Prefer Network/Open")
                .attemptBudgetMs(120_000L)
                .tokenBudget(50_000L)
                .scorers(QualityPreset.STRICT.getSettings())
                .promotionFloor(0.75)
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
    void aSharedQuestionTextOfItsOwnOpensLearningSteps() {
        // Each of the texts every step shares, now under Learning steps' Advanced options
        for (final Template shared : LearnFlow.SHARED_TEXTS) {
            assertThat(AdvancedSettings.changed(doc().flow(withTexts(Map.of(shared, "Our own words"))).build()))
                    .as(shared.name())
                    .containsExactly(SetupSection.APPROACH);
        }
    }

    @Test
    void aStepsQuestionTextOfItsOwnLeavesLearningStepsFolded() {
        // A step's own text is shown with the step, not under Advanced options
        assertThat(AdvancedSettings.changed(doc().flow(withTexts(Map.of(Template.CHAIN, "Our own words"))).build()))
                .isEmpty();
    }

    @Test
    void checksOfItsOwnOrLinesKeptBackOpenQualityChecks() {
        final List<ScorerSetting> own = new ArrayList<>(QualityPreset.STANDARD.getSettings());
        own.set(0, new ScorerSetting(ScorerType.COMPILE, 0.5, 1.0, true, null));
        assertThat(AdvancedSettings.changed(doc().scorers(own).build()))
                .containsExactly(SetupSection.QUALITY);
        assertThat(AdvancedSettings.changed(doc().heldOutFraction(0.3).build()))
                .containsExactly(SetupSection.QUALITY);
    }

    @Test
    void noChecksAtAllLeavesQualityChecksFolded() {
        // The readiness check offers the standard set, which is in sight
        assertThat(AdvancedSettings.changed(doc().scorers(List.of()).build())).isEmpty();
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
                ApproachFlow.withFallback(FlowExample.DIRECT.steps(), false), null, null);
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
        final ShapeshifterAiDoc doc = doc()
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

    // A Learning Flow as new, to change one thing of
    private static ShapeshifterAiDoc.Builder doc() {
        return ShapeshifterAiDoc.builder().uuid("ss-1");
    }

    // A new Learning Flow's steps with its own words for some of the question texts
    private static LearningFlow withTexts(final Map<Template, String> texts) {
        return new LearningFlow(doc().build().getFlow().getSteps(), texts, null);
    }
}
