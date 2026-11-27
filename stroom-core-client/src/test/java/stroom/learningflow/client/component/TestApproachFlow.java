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

import stroom.learningflow.client.component.ApproachFlow.AddOption;
import stroom.learningflow.client.component.ApproachFlow.Addable;
import stroom.learningflow.client.component.ApproachFlow.Destination;
import stroom.learningflow.shared.BuiltInFlow;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StepGuard;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.Transition;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TestApproachFlow {

    private static final List<BuiltInFlow> EXAMPLES = FlowExampleFixtures.all();
    private static final List<FlowStep> DIRECT = FlowExampleFixtures.steps(FlowExample.DIRECT);
    private static final List<FlowStep> ESCALATING = FlowExampleFixtures.steps(FlowExample.ESCALATING);

    @Test
    void theExamplesAreRecognisedAndAnEditIsNot() {
        assertThat(ApproachFlow.example(EXAMPLES, DIRECT)).isEqualTo(FlowExample.DIRECT);
        assertThat(ApproachFlow.example(EXAMPLES, ESCALATING)).isEqualTo(FlowExample.ESCALATING);
        final List<FlowStep> edited = ApproachFlow.replace(DIRECT, 1,
                ApproachFlow.withTries(DIRECT.get(1), 2));
        assertThat(ApproachFlow.example(EXAMPLES, edited)).isNull();
        assertThat(ApproachFlow.example(List.of(), DIRECT))
                .describedAs("before the setups are fetched nothing matches, so a document reads as "
                             + "having its own approach rather than a wrong name")
                .isNull();
    }

    @Test
    void chooseTheElementsStaysFirst() {
        assertThat(ApproachFlow.canMove(DIRECT, 0, 1)).isFalse();
        assertThat(ApproachFlow.canMove(DIRECT, 1, -1)).isFalse();
        assertThat(ApproachFlow.canMove(DIRECT, 1, 1)).isTrue();
        assertThat(ApproachFlow.canMove(DIRECT, DIRECT.size() - 1, 1)).isFalse();
        assertThat(ApproachFlow.move(DIRECT, 1, -1)).isEqualTo(DIRECT);
        final List<FlowStep> moved = ApproachFlow.move(DIRECT, 1, 1);
        assertThat(moved.get(2)).isEqualTo(DIRECT.get(1));
        assertThat(moved.get(1)).isEqualTo(DIRECT.get(2));
        assertThat(ApproachFlow.canDelete(DIRECT.get(0))).isFalse();
        assertThat(ApproachFlow.takesRules(DIRECT.get(0))).isFalse();
    }

    @Test
    void addingAStepNeverGoesBeforeChooseTheElements() {
        final List<FlowStep> added = ApproachFlow.insert(DIRECT, 0, AddOption.SPLIT);
        assertThat(added.get(0).getKind()).isEqualTo(QuestionKind.CHAIN);
        assertThat(added.get(1).getKind()).isEqualTo(QuestionKind.SPLIT);
        assertThat(new LearningFlow(added, null, null).check()).isEmpty();
    }

    @Test
    void aSecondStepOfOneNameIsGivenAnIdOfItsOwn() {
        final List<FlowStep> added = ApproachFlow.insert(DIRECT, 2, AddOption.PARSER);
        assertThat(added.get(2).getId()).isEqualTo("parser-2");
        assertThat(new LearningFlow(added, null, null).check()).isEmpty();
        final List<FlowStep> copied = ApproachFlow.copy(added, 2);
        assertThat(copied.get(3).getId()).isEqualTo("parser-3");
        assertThat(new LearningFlow(copied, null, null).check()).isEmpty();
    }

    @Test
    void splitAndTargetCanBeAddedOnce() {
        final List<FlowStep> withSplit = ApproachFlow.insert(DIRECT, 1, AddOption.SPLIT);
        final List<Addable> addable = ApproachFlow.addable(withSplit);
        final Addable split = addable.stream().filter(a -> a.getOption() == AddOption.SPLIT).findFirst()
                .orElseThrow();
        assertThat(split.getReason()).isEqualTo("Already step 2. A flow has it once.");
        assertThat(addable.stream().filter(a -> a.getOption() == AddOption.TARGET).findFirst().orElseThrow()
                .getReason()).isNull();
        assertThat(addable.stream().filter(a -> a.getOption() == AddOption.PARSER).findFirst().orElseThrow()
                .getReason()).isNull();
    }

    @Test
    void deletingAStepTakesTheRulesThatWentToIt() {
        // In the Escalating flow, the parser step has a rule going back to the split step on yield-short
        final int split = indexOf(ESCALATING, QuestionKind.SPLIT);
        final List<FlowStep> deleted = ApproachFlow.delete(ESCALATING, split);
        assertThat(deleted).hasSize(ESCALATING.size() - 1);
        assertThat(deleted.stream().flatMap(step -> step.getTransitions().stream())
                .map(Transition::getGoTo)).doesNotContain("split");
        assertThat(new LearningFlow(deleted, null, null).check()).isEmpty();
    }

    @Test
    void theFallbackIsASwitch() {
        final List<FlowStep> without = ApproachFlow.withFallback(DIRECT, false);
        assertThat(ApproachFlow.hasFallback(without)).isFalse();
        assertThat(without.stream().flatMap(step -> step.getTransitions().stream())).isEmpty();
        assertThat(new LearningFlow(without, null, null).check()).isEmpty();

        final List<FlowStep> back = ApproachFlow.withFallback(without, true);
        assertThat(back).isEqualTo(DIRECT);
        assertThat(ApproachFlow.withFallback(DIRECT, true)).isEqualTo(DIRECT);
    }

    @Test
    void conditionsAreWhatTheStepsChecksCanRaise() {
        final FlowStep parser = DIRECT.get(1);
        final List<StepOutcome> parserConditions = ApproachFlow.conditions(parser);
        assertThat(parserConditions).contains(StepOutcome.COVERAGE_SHORT, StepOutcome.YIELD_SHORT,
                StepOutcome.PRESERVATION_SHORT, null);
        assertThat(parserConditions).doesNotContain(StepOutcome.CONFORMANCE_SHORT,
                StepOutcome.PERMITTED_SHORT);
        // Running out of tries (null) is always the last condition
        assertThat(parserConditions.get(parserConditions.size() - 1)).isNull();

        final FlowStep transform = DIRECT.get(2);
        assertThat(ApproachFlow.conditions(transform)).contains(StepOutcome.CONFORMANCE_SHORT,
                StepOutcome.FIDELITY_SHORT).doesNotContain(StepOutcome.COVERAGE_SHORT);

        // The conditions are those of the checks for the step's kind, plus any outcome an existing rule
        // already uses, so that no rule's condition disappears from its menu
        final FlowStep named = ApproachFlow.withRules(transform,
                List.of(new Transition(StepOutcome.RUN_FAILED, "parser")));
        assertThat(ApproachFlow.conditions(named)).contains(StepOutcome.CONFORMANCE_SHORT,
                StepOutcome.FIDELITY_SHORT, StepOutcome.RUN_FAILED);
    }

    @Test
    void aStepsChecksAreItsKindsWhateverTheStepSays() {
        // A check applies to every step it can measure: a parser step is judged on its records, a transform
        // step on the events it makes, and a Configure step that writes both on either
        assertThat(ApproachFlow.checksFor(FlowStep.of(QuestionKind.SPLIT)))
                .containsExactly(Check.COVERAGE, Check.YIELD, Check.WHOLENESS);
        assertThat(ApproachFlow.checksFor(FlowStep.of(QuestionKind.TARGET)))
                .containsExactly(Check.CONFORMANCE, Check.QUALITY, Check.RULES, Check.PERMITTED);
        assertThat(ApproachFlow.checksFor(DIRECT.get(2)))
                .contains(Check.CONFORMANCE, Check.FIDELITY)
                .doesNotContain(Check.COVERAGE);
        // The step that chooses the elements is not judged by any quality check
        assertThat(ApproachFlow.checksFor(FlowStep.of(QuestionKind.CHAIN))).isEmpty();

        // A step's checks depend only on its kind, even if a rule names another check's outcome
        final FlowStep rules = new FlowStep(null, QuestionKind.SPLIT, null, null, null, null,
                List.of(new Transition(StepOutcome.RULES_SHORT, Transition.END)));
        assertThat(ApproachFlow.checksFor(rules))
                .containsExactly(Check.COVERAGE, Check.YIELD, Check.WHOLENESS);
    }

    @Test
    void theFallbackIsOfferedOnlyOnRunningOutOfTries() {
        final int transform = 2;
        final List<String> onSpent = goTos(ApproachFlow.destinations(DIRECT, transform, null, "admit"));
        assertThat(onSpent).contains("admit", "parser", "chain", Transition.END, Transition.PARK,
                Transition.GIVE_UP, null).doesNotContain("transform");
        final List<String> onShort = goTos(ApproachFlow.destinations(DIRECT, transform,
                StepOutcome.CONFORMANCE_SHORT, "parser"));
        assertThat(onShort).doesNotContain("admit");
    }

    @Test
    void destinationsSayWhichWayTheyGo() {
        final List<Destination> destinations = ApproachFlow.destinations(ESCALATING, 2, StepOutcome.YIELD_SHORT,
                "split");
        assertThat(destinations.stream().map(Destination::getDisplayValue).collect(Collectors.toList()))
                .contains("go back to 2 · Find what one record is",
                        "go on to 4 · Write the transform",
                        "skip to 5 · Agree the target events",
                        "leave by its usual exit (stop learning)");
    }

    @Test
    void aRuleToAStepThatIsNotThereIsOfferedSoItIsSeen() {
        final List<Destination> destinations = ApproachFlow.destinations(DIRECT, 1, StepOutcome.COVERAGE_SHORT,
                "nowhere");
        assertThat(goTos(destinations)).contains("nowhere");
    }

    @Test
    void aNewRuleTakesAFreeShortfallAndGoesBack() {
        final List<FlowStep> added = ApproachFlow.addRule(ESCALATING, 2);
        final FlowStep parser = added.get(2);
        assertThat(parser.getTransitions()).hasSize(2);
        final Transition rule = parser.getTransitions().get(1);
        assertThat(rule.getOn()).isEqualTo(StepOutcome.COVERAGE_SHORT);
        assertThat(rule.getGoTo()).isEqualTo("split");
        // Conditions used by either rule are not offered to a new rule, but each rule's own condition is
        // still offered to that rule
        assertThat(ApproachFlow.unusedConditions(parser, null)).doesNotContain(StepOutcome.YIELD_SHORT,
                StepOutcome.COVERAGE_SHORT);
        assertThat(ApproachFlow.unusedConditions(parser, parser.getTransitions().get(0)))
                .contains(StepOutcome.YIELD_SHORT);
    }

    @Test
    void rulesAreChangedAndRemoved() {
        final List<FlowStep> set = ApproachFlow.setRule(ESCALATING, 2, 0, new Transition(null, Transition.PARK));
        assertThat(set.get(2).getTransitions()).containsExactly(new Transition(null, Transition.PARK));
        final List<FlowStep> removed = ApproachFlow.removeRule(set, 2, 0);
        assertThat(removed.get(2).getTransitions()).isEmpty();
    }

    @Test
    void theUsualBehaviourIsSaid() {
        assertThat(ApproachFlow.usual(DIRECT, 1)).contains("If it passes, it goes on to the next step.",
                "If it runs out of tries, it will stop learning.");
        // The transform step has its own rule for running out of tries, and is the last step before the
        // fallback, so passing finishes the run
        assertThat(ApproachFlow.usual(DIRECT, 2)).contains("If it passes, the run finishes.")
                .noneMatch(line -> line.startsWith("If it runs out"));
        assertThat(ApproachFlow.usual(DIRECT, 0)).hasSize(1);
        assertThat(ApproachFlow.usual(DIRECT, 3)).isEmpty();
    }

    @Test
    void everyLearnableElementTickedIsWrittenAsNone() {
        assertThat(ApproachFlow.elements(FlowStep.LEARNABLE_ELEMENTS)).isEmpty();
        assertThat(ApproachFlow.elements(List.of("XSLTFilter", "XMLFragmentParser", "JSONParser", "DSParser")))
                .isEmpty();
        // Elements are written in the order they are shown, whatever order they were ticked in
        assertThat(ApproachFlow.elements(List.of("XSLTFilter", "DSParser")))
                .containsExactly("DSParser", "XSLTFilter");
        // Elements the AI cannot learn are not written
        assertThat(ApproachFlow.elements(List.of("XMLParser", "XSLTFilter"))).containsExactly("XSLTFilter");
    }

    @Test
    void theDefaultIsEveryLearnableElement() {
        assertThat(FlowStep.LEARNABLE_ELEMENTS)
                .containsExactly("DSParser", "JSONParser", "XMLFragmentParser", "XSLTFilter");
        final FlowStep chain = FlowStep.of(QuestionKind.CHAIN);
        assertThat(ApproachFlow.tickedElements(chain)).isEqualTo(FlowStep.LEARNABLE_ELEMENTS);
        assertThat(ApproachFlow.unlearnableElements(chain)).isEmpty();
    }

    @Test
    void aStepNamingElementsTicksThoseThatCanBeLearnedAndSaysWhichCannot() {
        final FlowStep chain = FlowStep.of(QuestionKind.CHAIN)
                .withElements(List.of("XSLTFilter", "XMLParser", "XMLFragmentParser", "IndexingFilter"));
        assertThat(ApproachFlow.tickedElements(chain)).containsExactly("XMLFragmentParser", "XSLTFilter");
        assertThat(ApproachFlow.unlearnableElements(chain)).containsExactly("XMLParser", "IndexingFilter");
    }

    @Test
    void aStepNamingOnlyElementsThatCannotBeLearnedTicksNone() {
        final FlowStep chain = FlowStep.of(QuestionKind.CHAIN).withElements(List.of("XMLParser"));
        assertThat(ApproachFlow.tickedElements(chain)).isEmpty();
        assertThat(ApproachFlow.unlearnableElements(chain)).containsExactly("XMLParser");
        // Removing it writes no elements, which means every learnable element
        assertThat(ApproachFlow.elements(ApproachFlow.tickedElements(chain))).isEmpty();
    }

    @Test
    void aChooseTheElementsStepsTagNamesTheElementsItChoosesFrom() {
        assertThat(ApproachFlow.tuning(FlowStep.of(QuestionKind.CHAIN))).isEmpty();
        assertThat(ApproachFlow.tuning(FlowStep.of(QuestionKind.CHAIN)
                .withElements(List.of("JSONParser", "XSLTFilter"))))
                .containsExactly("chooses from JSONParser and XSLTFilter");
        assertThat(ApproachFlow.tuning(FlowStep.of(QuestionKind.CHAIN).withElements(List.of("DSParser"))))
                .containsExactly("chooses from DSParser");
    }

    @Test
    void stepFieldsAreChangedOneAtATime() {
        final FlowStep step = DIRECT.get(1);
        assertThat(ApproachFlow.withWhen(step, StepGuard.TEXT).getWhen()).isEqualTo(StepGuard.TEXT);
        assertThat(ApproachFlow.withTries(step, 3).getCandidates()).isEqualTo(3);
        assertThat(ApproachFlow.withTries(step, null).getCandidates()).isNull();
        assertThat(ApproachFlow.withKinds(FlowStep.of(QuestionKind.TARGET), 2).getKinds()).isEqualTo(2);
    }

    @Test
    void aStepsTagsSayWhatItSetsOfItsOwn() {
        final FlowStep step = DIRECT.get(1);
        assertThat(ApproachFlow.tuning(step)).isEmpty();
        assertThat(ApproachFlow.tuning(ApproachFlow.withWhen(step, StepGuard.ALWAYS))).isEmpty();
        assertThat(ApproachFlow.tuning(ApproachFlow.withTries(ApproachFlow.withWhen(step, StepGuard.XML), 5)))
                .containsExactly("only for XML input", "5 tries");
        assertThat(ApproachFlow.tuning(ApproachFlow.withTries(step, 1))).containsExactly("1 try");
        assertThat(ApproachFlow.tuning(ApproachFlow.withKinds(FlowStep.of(QuestionKind.TARGET), 1)))
                .containsExactly("1 kind of record");
        assertThat(ApproachFlow.tuning(ApproachFlow.withKinds(FlowStep.of(QuestionKind.TARGET), 4)))
                .containsExactly("4 kinds of record");
    }

    private static int indexOf(final List<FlowStep> steps, final QuestionKind kind) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).getKind() == kind) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> goTos(final List<Destination> destinations) {
        return destinations.stream().map(Destination::getGoTo).collect(Collectors.toList());
    }
}
