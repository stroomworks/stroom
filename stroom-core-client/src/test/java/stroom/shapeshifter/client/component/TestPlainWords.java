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

import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.StepGuard;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.TurnScore;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestPlainWords {

    @Test
    void stepsAreNamedForWhatTheyDoAndARepeatSaysAgain() {
        final List<FlowStep> steps = FlowExample.ESCALATING.steps();
        assertThat(PlainWords.stepName(steps, 0)).isEqualTo("Choose the elements");
        assertThat(PlainWords.stepName(steps, 1)).isEqualTo("Find what one record is");
        assertThat(PlainWords.stepName(steps, 2)).isEqualTo("Write the parser");
        assertThat(PlainWords.stepName(steps, 3)).isEqualTo("Write the transform");
        assertThat(PlainWords.stepName(steps, 4)).isEqualTo("Agree the target events");
        assertThat(PlainWords.stepName(steps, 5)).isEqualTo("Write the parser again");
        assertThat(PlainWords.stepName(steps, 6)).isEqualTo("Write the transform again");
        assertThat(PlainWords.stepName(steps, 7)).isEqualTo("Fallback: marked Unknowns");
    }

    @Test
    void everyOutcomeHasAResultAndACondition() {
        for (final StepOutcome outcome : StepOutcome.values()) {
            assertThat(PlainWords.result(outcome)).isNotBlank().doesNotContain("-short");
            assertThat(PlainWords.condition(outcome)).isNotBlank().doesNotContain("-short");
        }
        assertThat(PlainWords.condition(null)).isEqualTo("it runs out of tries");
        assertThat(PlainWords.result(StepOutcome.COVERAGE_SHORT)).isEqualTo("Didn't read all the input");
        assertThat(PlainWords.result(null)).isEqualTo("Not judged");
    }

    @Test
    void everyCheckAndScorerHasAName() {
        for (final Check check : Check.values()) {
            assertThat(PlainWords.check(check)).isNotBlank();
        }
        for (final ScorerType type : ScorerType.values()) {
            assertThat(PlainWords.scorer(type)).isNotBlank();
        }
        assertThat(PlainWords.scorer(ScorerType.ERROR_LOAD)).endsWith("(not built)");
    }

    @Test
    void aScoreIsSaidAgainstWhatItNeeds() {
        assertThat(PlainWords.score(new TurnScore(ScorerType.INPUT_COVERAGE, 0.42, 0.9)))
                .isEqualTo("Reading the input scored 42%. It needs 90%.");
        assertThat(PlainWords.score(new TurnScore(ScorerType.COMPILE, 1.0, 1.0)))
                .isEqualTo("Compiling scored 100%. It needed 100%.");
    }

    @Test
    void theExamplesHaveNamesAndDescriptions() {
        for (final FlowExample example : FlowExample.values()) {
            assertThat(PlainWords.example(example)).isNotBlank();
            assertThat(PlainWords.exampleDescription(example)).isNotBlank();
        }
        assertThat(PlainWords.example(FlowExample.ESCALATING)).isEqualTo("Quick, then careful");
    }

    @Test
    void numbersCountsAndDurations() {
        assertThat(PlainWords.number(12000)).isEqualTo("12,000");
        assertThat(PlainWords.number(999)).isEqualTo("999");
        assertThat(PlainWords.number(1234567)).isEqualTo("1,234,567");
        assertThat(PlainWords.number(-1500)).isEqualTo("-1,500");
        assertThat(PlainWords.count(1, "stream")).isEqualTo("1 stream");
        assertThat(PlainWords.count(2, "stream")).isEqualTo("2 streams");
        assertThat(PlainWords.percent(0.425)).isEqualTo("43%");
        assertThat(PlainWords.duration(30_000)).isEqualTo("less than a minute");
        assertThat(PlainWords.duration(5 * 60_000)).isEqualTo("5 minutes");
        assertThat(PlainWords.duration(3 * 3_600_000)).isEqualTo("3 hours");
        assertThat(PlainWords.duration(26 * 3_600_000L)).isEqualTo("1 day");
    }

    @Test
    void listsAndSentences() {
        assertThat(PlainWords.list(List.of())).isEmpty();
        assertThat(PlainWords.list(List.of("a"))).isEqualTo("a");
        assertThat(PlainWords.list(List.of("a", "b", "c"))).isEqualTo("a, b and c");
        assertThat(PlainWords.lowerFirst("Out of time")).isEqualTo("out of time");
        assertThat(PlainWords.lowerFirst(null)).isEmpty();
    }

    @Test
    void whenAStepIsAskedIsSaidInWords() {
        assertThat(PlainWords.guard(StepGuard.ALWAYS)).isEqualTo("for every input");
        assertThat(PlainWords.guard(StepGuard.TEXT)).isEqualTo("only for raw text input");
        assertThat(PlainWords.guard(StepGuard.JSON)).isEqualTo("only for JSON input");
        assertThat(PlainWords.guard(null)).isEmpty();
    }

    @Test
    void aNameThatBeginsWithCapitalsIsNotLowerCased() {
        assertThat(PlainWords.lowerFirst("Out of time")).isEqualTo("out of time");
        assertThat(PlainWords.lowerFirst("XMLParser failed")).isEqualTo("XMLParser failed");
        assertThat(PlainWords.lowerFirst("A")).isEqualTo("a");
    }
}
