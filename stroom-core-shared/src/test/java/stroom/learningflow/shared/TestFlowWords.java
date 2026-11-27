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

class TestFlowWords {

    private static final List<FlowStep> STEPS = List.of(
            FlowStep.of(QuestionKind.CHAIN),
            FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.PARSER).build(),
            FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.TRANSFORM).build(),
            FlowStep.of(QuestionKind.ADMIT));

    @Test
    void stepsAreNamedAndNumbered() {
        assertThat(FlowWords.stepName(QuestionKind.SPLIT, null)).isEqualTo("Find what one record is");
        assertThat(FlowWords.stepName(QuestionKind.CONFIGURE, null)).isEqualTo("Write every element");
        assertThat(FlowWords.stepName(null, null)).isEqualTo("Step");
        assertThat(FlowWords.step(STEPS, 1)).isEqualTo("step 2 (Write the parser)");
        assertThat(FlowWords.stepName(List.of(FlowStep.of(QuestionKind.CHAIN),
                FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.PARSER).build(),
                FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.PARSER).build()), 2))
                .isEqualTo("Write the parser again");
    }

    @Test
    void aRuleReadsAsASentence() {
        assertThat(FlowWords.rule(STEPS, 2, new Transition(StepOutcome.PRESERVATION_SHORT, "parser")))
                .isEqualTo("if the records lose what the targets need, go back to step 2 (Write the parser)");
        assertThat(FlowWords.rule(STEPS, 1, new Transition(StepOutcome.PASSED, "transform")))
                .isEqualTo("if it passes, go on to step 3 (Write the transform)");
        assertThat(FlowWords.rule(STEPS, 0, new Transition(StepOutcome.PASSED, "transform")))
                .isEqualTo("if it passes, skip to step 3 (Write the transform)");
        assertThat(FlowWords.rule(STEPS, 2, new Transition(null, "admit")))
                .isEqualTo("if it runs out of tries, use the fallback");
        assertThat(FlowWords.rule(STEPS, 2, new Transition(StepOutcome.RUN_FAILED, "transform")))
                .isEqualTo("if the answer errors when run on the data, start step 3 (Write the transform) again");
    }

    @Test
    void theEndsOfAFlowAreSaidByWhatTheyDo() {
        assertThat(FlowWords.destination(STEPS, 1, new Transition(StepOutcome.PASSED, Transition.END)))
                .isEqualTo("finish");
        assertThat(FlowWords.destination(STEPS, 1, new Transition(null, Transition.PARK)))
                .isEqualTo("pause for a person");
        assertThat(FlowWords.destination(STEPS, 2, new Transition(null, Transition.GIVE_UP)))
                .isEqualTo("stop learning");
        assertThat(FlowWords.destination(STEPS, 1, new Transition(null, null)))
                .isEqualTo("leave by its usual exit (stop learning)");
        assertThat(FlowWords.destination(STEPS, 2, new Transition(null, null)))
                .isEqualTo("leave by its usual exit (pause for a person)");
    }

    @Test
    void aStepThatNamesNoStepIsSeenToBeWrong() {
        assertThat(FlowWords.destination(STEPS, 1, new Transition(StepOutcome.REFUSED, "nowhere")))
                .isEqualTo("go to 'nowhere', which isn't a step");
    }

    @Test
    void theUsualExitDependsOnWhatTheStepWrites() {
        assertThat(FlowWords.usualExit(STEPS.get(1))).isEqualTo("stop learning");
        assertThat(FlowWords.usualExit(STEPS.get(2))).isEqualTo("pause for a person");
        assertThat(FlowWords.usualExit(FlowStep.of(QuestionKind.SPLIT))).isEqualTo("pause for a person");
        // A step that writes every element stops learning where it is the parser that runs out, since a
        // stream that cannot be parsed is the one failure nothing can serve
        assertThat(FlowWords.usualExit(FlowStep.of(QuestionKind.CONFIGURE)))
                .isEqualTo("stop learning if the parser can't be written, or else pause for a person");
    }

    @Test
    void capitalise() {
        assertThat(FlowWords.capitalise("step 2")).isEqualTo("Step 2");
        assertThat(FlowWords.capitalise("")).isEmpty();
        assertThat(FlowWords.capitalise(null)).isNull();
    }
}
