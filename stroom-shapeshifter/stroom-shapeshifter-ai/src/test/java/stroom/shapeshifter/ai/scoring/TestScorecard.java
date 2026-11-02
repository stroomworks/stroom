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

package stroom.shapeshifter.ai.scoring;

import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.TurnScore;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestScorecard {

    private static Judgement judgement(final ScorerType type, final boolean gate, final double value) {
        return new Judgement(new ScorerSetting(type, 1.0, 0.5, gate, null), new Score(type, value, List.of()));
    }

    @Test
    void theOutcomeIsTheFailedGateBeforeAnyOtherShortfall() {
        // A document lists yield before extraction quality; both fall short; the gate is what decided it.
        final Verdict verdict = new Verdict(List.of(
                judgement(ScorerType.YIELD, false, 0.4),
                judgement(ScorerType.EXTRACTION_QUALITY, true, 0.2)), 0.3);
        assertThat(Scorecard.outcome(verdict)).isEqualTo(StepOutcome.QUALITY_SHORT);
        assertThat(Scorecard.outcome(new Verdict(List.of(
                judgement(ScorerType.YIELD, false, 0.4),
                judgement(ScorerType.EXTRACTION_QUALITY, false, 0.2)), 0.3))).isEqualTo(StepOutcome.YIELD_SHORT);
        assertThat(Scorecard.outcome(new Verdict(List.of(
                judgement(ScorerType.COMPILE, true, 0.0)), 0.0))).isEqualTo(StepOutcome.COMPILE_FAILED);
        assertThat(Scorecard.outcome(new Verdict(List.of(judgement(ScorerType.YIELD, false, 1.0)), 1.0)))
                .isEqualTo(StepOutcome.PASSED);
    }

    /// Design 06 A144: a shortfall's score is the check that decided it, as the outcome names it.
    @Test
    void aShortfallIsScoredByTheCheckThatDecidedIt() {
        assertThat(Scorecard.deciding(new Verdict(List.of(
                judgement(ScorerType.YIELD, false, 0.4),
                judgement(ScorerType.EXTRACTION_QUALITY, true, 0.2)), 0.3)))
                .contains(new TurnScore(ScorerType.EXTRACTION_QUALITY, 0.2, 0.5));
        assertThat(Scorecard.deciding(new Verdict(List.of(judgement(ScorerType.COMPILE, true, 0.0)), 0.0)))
                .describedAs("the compile gate is the floor, and its outcome says what its score would")
                .isEmpty();
    }

    /// A pass is scored by the gate that passed by the least, and where no gate applied, by the check that
    /// did; the compile gate, met at 1.0 against a threshold of 1.0, is never it.
    @Test
    void aPassIsScoredByTheCheckThatPassedByTheLeast() {
        assertThat(Scorecard.deciding(new Verdict(List.of(
                judgement(ScorerType.COMPILE, true, 0.5),
                judgement(ScorerType.YIELD, false, 0.55),
                judgement(ScorerType.INPUT_COVERAGE, true, 0.9),
                judgement(ScorerType.EXTRACTION_QUALITY, true, 0.7)), 0.8)))
                .describedAs("a gate before a closer check that is not one")
                .contains(new TurnScore(ScorerType.EXTRACTION_QUALITY, 0.7, 0.5));
        assertThat(Scorecard.deciding(new Verdict(List.of(
                judgement(ScorerType.YIELD, false, 0.9),
                judgement(ScorerType.INPUT_COVERAGE, false, 0.6)), 0.75)))
                .contains(new TurnScore(ScorerType.INPUT_COVERAGE, 0.6, 0.5));
        assertThat(Scorecard.deciding(new Verdict(List.of(), 1.0))).isEmpty();
    }

    @Test
    void aTurnScoreReadsAsTheCheckItsScoreAndItsThreshold() {
        assertThat(new TurnScore(ScorerType.INPUT_COVERAGE, 0.41234, 0.8).format())
                .isEqualTo("Input coverage 0.412 < 0.8");
        assertThat(new TurnScore(ScorerType.YIELD, 0.8, 0.8).format()).isEqualTo("Yield 0.8 ≥ 0.8");
    }
}
