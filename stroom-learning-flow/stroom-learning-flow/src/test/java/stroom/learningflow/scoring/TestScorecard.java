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

package stroom.learningflow.scoring;

import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.ConfigureRole;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.TurnScore;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests how a scorecard turns the checks' scores into a step's outcome.
///
/// Every check is either a gate or advice. A gate that falls short decides a step's outcome; advice that falls
/// short decides it only where the step has a rule for that outcome.
class TestScorecard {

    private static final FlowStep NO_RULES = FlowStep.builder(QuestionKind.CONFIGURE)
            .role(ConfigureRole.TRANSFORM)
            .build();
    private static final FlowStep ROUTES_ON_YIELD = FlowStep.builder(QuestionKind.CONFIGURE)
            .role(ConfigureRole.TRANSFORM)
            .goTo(StepOutcome.YIELD_SHORT, "split")
            .build();

    private static Judgement judgement(final Check check, final CheckState state, final double value) {
        return new Judgement(new CheckSetting(check, state, 0.5, null),
                new Score(check, value, List.of()));
    }

    private static Verdict verdict(final Judgement... judgements) {
        return Verdict.of(List.of(judgements));
    }

    @Test
    void aFailedGateDecidesBeforeAnyAdvice() {
        final Verdict verdict = verdict(
                judgement(Check.YIELD, CheckState.ADVICE, 0.4),
                judgement(Check.QUALITY, CheckState.GATE, 0.2));
        assertThat(Scorecard.outcome(verdict, ROUTES_ON_YIELD)).isEqualTo(StepOutcome.QUALITY_SHORT);
        assertThat(Scorecard.outcome(verdict(judgement(Check.COMPILE, CheckState.GATE, 0.0)), NO_RULES))
                .isEqualTo(StepOutcome.COMPILE_FAILED);
    }

    @Test
    void adviceThatFallsShortDecidesOnlyWhereTheStepHasARuleForIt() {
        final Verdict verdict = verdict(
                judgement(Check.YIELD, CheckState.ADVICE, 0.4),
                judgement(Check.QUALITY, CheckState.ADVICE, 0.2));
        assertThat(Scorecard.outcome(verdict, ROUTES_ON_YIELD)).isEqualTo(StepOutcome.YIELD_SHORT);
        assertThat(Scorecard.outcome(verdict, NO_RULES))
                .describedAs("advice never refuses a candidate on its own")
                .isEqualTo(StepOutcome.PASSED);
        assertThat(Scorecard.outcome(verdict))
                .describedAs("a step with no rules")
                .isEqualTo(StepOutcome.PASSED);
    }

    @Test
    void aCheckThatMeetsItsThresholdPasses() {
        assertThat(Scorecard.outcome(verdict(judgement(Check.YIELD, CheckState.GATE, 0.5)), NO_RULES))
                .isEqualTo(StepOutcome.PASSED);
    }

    @Test
    void aVerdictsScoreIsTheAverageOfTheChecksThatRanGatesAndAdviceAlike() {
        assertThat(verdict(
                judgement(Check.YIELD, CheckState.ADVICE, 0.4),
                judgement(Check.QUALITY, CheckState.GATE, 0.8)).score()).isEqualTo(0.6, within());
        assertThat(verdict().score()).describedAs("no check ran").isEqualTo(1.0);
    }

    @Test
    void whetherAConversationCheckDecidesFollowsItsStateAndTheStepsRules() {
        final Scorecard scorecard = Scorecard.of(List.of(
                CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                CheckSetting.of(Check.PRESERVATION, CheckState.ADVICE),
                CheckSetting.of(Check.FIDELITY, CheckState.OFF)), List.of());
        final FlowStep routesOnPreservation = FlowStep.builder(QuestionKind.CONFIGURE)
                .role(ConfigureRole.PARSER)
                .goTo(StepOutcome.PRESERVATION_SHORT, "split")
                .build();
        assertThat(scorecard.decides(Check.WHOLENESS, NO_RULES)).isTrue();
        assertThat(scorecard.decides(Check.PRESERVATION, NO_RULES)).isFalse();
        assertThat(scorecard.decides(Check.PRESERVATION, routesOnPreservation)).isTrue();
        assertThat(scorecard.decides(Check.FIDELITY, NO_RULES)).describedAs("off").isFalse();
        assertThat(scorecard.threshold(Check.WHOLENESS)).isEqualTo(0.9);
    }

    /// A turn that fell short is given the score of the check that decided its outcome, so the score shown
    /// matches the outcome.
    @Test
    void aShortfallIsScoredByTheCheckThatDecidedIt() {
        assertThat(Scorecard.deciding(verdict(
                judgement(Check.YIELD, CheckState.ADVICE, 0.4),
                judgement(Check.QUALITY, CheckState.GATE, 0.2)), NO_RULES))
                .contains(new TurnScore(Check.QUALITY, 0.2, 0.5));
        assertThat(Scorecard.deciding(verdict(judgement(Check.COMPILE, CheckState.GATE, 0.0)), NO_RULES))
                .describedAs("the compile gate is the floor, and its outcome says what its score would")
                .isEmpty();
    }

    /// A turn that passed is given the score of the gate that passed by the smallest margin or, where no gate
    /// applied, of the check that did. The compile gate is never used.
    @Test
    void aPassIsScoredByTheCheckThatPassedByTheLeast() {
        assertThat(Scorecard.deciding(verdict(
                judgement(Check.COMPILE, CheckState.GATE, 1.0),
                judgement(Check.YIELD, CheckState.ADVICE, 0.55),
                judgement(Check.COVERAGE, CheckState.GATE, 0.9),
                judgement(Check.QUALITY, CheckState.GATE, 0.7)), NO_RULES))
                .describedAs("a gate before a closer check that is not one")
                .contains(new TurnScore(Check.QUALITY, 0.7, 0.5));
        assertThat(Scorecard.deciding(verdict(
                judgement(Check.YIELD, CheckState.ADVICE, 0.9),
                judgement(Check.COVERAGE, CheckState.ADVICE, 0.6)), NO_RULES))
                .contains(new TurnScore(Check.COVERAGE, 0.6, 0.5));
        assertThat(Scorecard.deciding(verdict(), NO_RULES)).isEmpty();
    }

    @Test
    void aTurnScoreReadsAsTheCheckItsScoreAndItsThreshold() {
        assertThat(new TurnScore(Check.COVERAGE, 0.41234, 0.8).format())
                .isEqualTo("Input coverage 0.412 < 0.8");
        assertThat(new TurnScore(Check.YIELD, 0.8, 0.8).format()).isEqualTo("Yield 0.8 ≥ 0.8");
    }

    private static Offset<Double> within() {
        return Offset.offset(1e-9);
    }
}
