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

package stroom.learningflow.learning;

import stroom.learningflow.shared.BuiltInFlow;
import stroom.learningflow.shared.ConfigureRole;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StepOutcome;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests the built-in example flows, which are JSON resources beside `FlowExamples`, read with the same mapper
/// a stored document is read with.
///
/// Because the examples are data rather than code, these tests are what pin down each example's steps and
/// transitions.
class TestFlowExamples {

    @ParameterizedTest
    @EnumSource(FlowExample.class)
    void everySetupReadsAndIsAFlowTheStageCanHold(final FlowExample example) {
        final List<FlowStep> steps = FlowExamples.steps(example);

        assertThat(steps).isNotEmpty();
        assertThat(new LearningFlow(steps, null, null).problems())
                .describedAs("a built-in setup that the stage could not hold would break every new "
                             + "document made from it (A33)")
                .isEmpty();
    }

    @Test
    void directIsTheChainTheTwoConfiguresAndTheExhaustionStep() {
        final List<FlowStep> steps = FlowExamples.steps(FlowExample.DIRECT);

        assertThat(steps).extracting(FlowStep::getKind).containsExactly(
                QuestionKind.CHAIN, QuestionKind.CONFIGURE, QuestionKind.CONFIGURE, QuestionKind.ADMIT);
        assertThat(steps.get(1).getRole()).isEqualTo(ConfigureRole.PARSER);
        assertThat(steps.get(2).getRole()).isEqualTo(ConfigureRole.TRANSFORM);
        assertThat(steps.get(2).getTransitions()).singleElement()
                .describedAs("the transform's fallback is the exhaustion step, which is the one step that "
                             + "may ask for the admitted form (A53)")
                .satisfies(transition -> {
                    assertThat(transition.getOn()).isNull();
                    assertThat(transition.getGoTo()).isEqualTo("admit");
                });
    }

    @Test
    void escalatingSettlesTheSplitGoesDirectThenAsksForATarget() {
        // The Escalating example asks the split question of every input, sends a parser that is short on yield
        // back to the split, and asks for a target only when the transform stays short.
        final List<FlowStep> steps = FlowExamples.steps(FlowExample.ESCALATING);

        assertThat(steps).extracting(FlowStep::getKind).containsExactly(
                QuestionKind.CHAIN, QuestionKind.SPLIT, QuestionKind.CONFIGURE, QuestionKind.CONFIGURE,
                QuestionKind.TARGET, QuestionKind.CONFIGURE, QuestionKind.CONFIGURE, QuestionKind.ADMIT);
        assertThat(steps.get(2).getTransitions()).singleElement().satisfies(transition -> {
            assertThat(transition.getOn()).isEqualTo(StepOutcome.YIELD_SHORT);
            assertThat(transition.getGoTo()).isEqualTo("split");
        });
        final FlowStep first = steps.get(3);
        assertThat(first.getId()).isEqualTo("first");
        assertThat(first.getCandidates()).isEqualTo(2);
        assertThat(first.getTransitions()).extracting(transition -> transition.getGoTo())
                .describedAs("it ends where it passes, and escalates to the target where it does not")
                .containsExactly("end", "target");
        assertThat(steps.get(5).getId()).isEqualTo("again");
    }

    @Test
    void targetFirstSettlesTheBoundaryAndTheEventsBeforeAnyConfiguration() {
        final List<FlowStep> steps = FlowExamples.steps(FlowExample.TARGET_FIRST);

        assertThat(steps).extracting(FlowStep::getKind).containsExactly(
                QuestionKind.CHAIN, QuestionKind.SPLIT, QuestionKind.TARGET, QuestionKind.CONFIGURE,
                QuestionKind.CONFIGURE, QuestionKind.ADMIT);
        assertThat(steps.get(2).getKinds()).isEqualTo(FlowStep.DEFAULT_KINDS);
        assertThat(steps.get(4).getTransitions()).extracting(transition -> transition.getGoTo())
                .describedAs("a value the records lack sends the parser back (A31)")
                .containsExactly("parser", "admit");
    }

    @Test
    void everySetupIsOfferedToTheClientWithItsStepsAndItsName() {
        final List<BuiltInFlow> all = FlowExamples.all();

        assertThat(all).extracting(BuiltInFlow::getExample).containsExactly(FlowExample.values());
        assertThat(all).allSatisfy(flow -> {
            assertThat(flow.getDisplayValue()).isEqualTo(flow.getExample().getDisplayValue());
            assertThat(flow.getSteps()).isEqualTo(FlowExamples.steps(flow.getExample()));
        });
    }
}
