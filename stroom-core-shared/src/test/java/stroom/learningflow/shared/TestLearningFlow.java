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

import stroom.learningflow.shared.FlowProblem.Severity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestLearningFlow {

    @Test
    void aRuleFiringOnACheckThatIsOffIsWarnedAbout() {
        final LearningFlow flow = ruleOnConformanceShort();

        final List<FlowProblem> warned = flow.check(List.of(
                CheckSetting.of(Check.COMPILE, CheckState.GATE),
                CheckSetting.of(Check.CONFORMANCE, CheckState.OFF)));

        assertThat(warned).hasSize(1);
        assertThat(warned.get(0).getSeverity()).isEqualTo(Severity.WARNING);
        assertThat(warned.get(0).isProblem()).isFalse();
        // The warning names the step the rule is on, so that the step's card can show it
        assertThat(warned.get(0).getStep()).isEqualTo(1);
        assertThat(warned.get(0).getMessage()).contains("never fires").contains("conformance");
    }

    @Test
    void aRuleFiringOnACheckThatIsOnIsNotWarnedAbout() {
        final LearningFlow flow = ruleOnConformanceShort();

        assertThat(flow.check(List.of(CheckSetting.of(Check.CONFORMANCE, CheckState.GATE)))).isEmpty();
        assertThat(flow.check(List.of(CheckSetting.of(Check.CONFORMANCE, CheckState.ADVICE)))).isEmpty();
    }

    @Test
    void checksThatAreNotGivenRaiseNoWarning() {
        final LearningFlow flow = ruleOnConformanceShort();

        assertThat(flow.check()).isEmpty();
        assertThat(flow.check(null)).isEmpty();
        assertThat(flow.check(List.of())).isEmpty();
        // A list that doesn't name this rule's check says nothing about it, so raises no warning
        assertThat(flow.check(List.of(CheckSetting.of(Check.YIELD, CheckState.OFF)))).isEmpty();
    }

    @Test
    void aWarningDoesNotStopTheFlowBeingHeld() {
        final LearningFlow flow = ruleOnConformanceShort();

        // A save is refused on problems() alone, which uses check(), which raises no warnings, so a flow
        // whose only fault is a warning can be saved however its checks are set
        assertThat(flow.problems()).isEmpty();
        assertThat(flow.check()).isEmpty();

        // If a warning ever reached that path, the severity filter would still not refuse the save for it
        final List<FlowProblem> warned = flow.check(List.of(
                CheckSetting.of(Check.CONFORMANCE, CheckState.OFF)));
        assertThat(warned).isNotEmpty();
        assertThat(FlowProblem.messages(warned, Severity.PROBLEM)).isEmpty();
        assertThat(FlowProblem.messages(warned, Severity.WARNING)).hasSize(1);
    }

    @Test
    void realProblemsAreStillProblems() {
        final LearningFlow broken = flowOf(List.of(
                FlowStep.of(QuestionKind.CONFIGURE), FlowStep.of(QuestionKind.CHAIN)));

        final List<FlowProblem> problems = broken.check(List.of(
                CheckSetting.of(Check.CONFORMANCE, CheckState.OFF)));

        assertThat(problems).isNotEmpty();
        assertThat(problems).allMatch(FlowProblem::isProblem);
        assertThat(broken.problems()).isNotEmpty();
    }

    @Test
    void everyStepCheckCanBeReadBackFromItsShortfall() {
        for (final Check check : Check.values()) {
            if (check.isStepCheck()) {
                assertThat(Check.ofShortfall(check.shortfall())).isEqualTo(check);
            }
        }
        assertThat(Check.ofShortfall(StepOutcome.COMPILE_FAILED)).isEqualTo(Check.COMPILE);
        assertThat(Check.ofShortfall(null)).isNull();
        assertThat(Check.ofShortfall(StepOutcome.PASSED)).isNull();
    }

    // A flow whose second step has a rule firing on the conformance check falling short
    private static LearningFlow ruleOnConformanceShort() {
        return flowOf(List.of(
                FlowStep.of(QuestionKind.CHAIN),
                new FlowStep(null, QuestionKind.CONFIGURE, null, null, null, null,
                        List.of(new Transition(StepOutcome.CONFORMANCE_SHORT, Transition.END)))));
    }

    /// Builds a flow of the given steps, with no template overrides. The example flows are defined on the
    /// server, so a test in this module builds the flow it means rather than starting from one of them.
    ///
    /// @param steps Its steps.
    /// @return The flow.
    private static LearningFlow flowOf(final List<FlowStep> steps) {
        return new LearningFlow(steps, null, null);
    }
}
