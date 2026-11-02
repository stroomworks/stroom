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
import stroom.shapeshifter.shared.ConfigureRole;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowProblem;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.StepGuard;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.Template;
import stroom.shapeshifter.shared.Transition;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestLearnFlow {

    @Test
    void limitEmptyIsTheDefault() {
        assertThat(LearnFlow.limitProblem("")).isNull();
        assertThat(LearnFlow.limitProblem("  ")).isNull();
        assertThat(LearnFlow.limitProblem(null)).isNull();
        assertThat(LearnFlow.parseLimit(" ")).isNull();
    }

    @Test
    void limitMustBeAWholeNumberOfOneOrMore() {
        assertThat(LearnFlow.limitProblem("3")).isNull();
        assertThat(LearnFlow.parseLimit(" 3 ")).isEqualTo(3);
        assertThat(LearnFlow.limitProblem("0")).isNotNull();
        assertThat(LearnFlow.limitProblem("-1")).isNotNull();
        assertThat(LearnFlow.limitProblem("1.5")).isNotNull();
        assertThat(LearnFlow.limitProblem("abc")).isNotNull();
        assertThat(LearnFlow.parseLimit("abc")).isNull();
        assertThat(LearnFlow.limitText(null)).isEmpty();
        assertThat(LearnFlow.limitText(4)).isEqualTo("4");
    }

    @Test
    void elementsSkipBlankLines() {
        assertThat(LearnFlow.elements(" XMLParser \n\n XSLTFilter")).containsExactly("XMLParser", "XSLTFilter");
        assertThat(LearnFlow.elements(null)).isEmpty();
    }

    @Test
    void titleNamesTheRoleOfAConfigureStep() {
        final FlowStep parser = new FlowStep(null, QuestionKind.CONFIGURE, ConfigureRole.PARSER, null, null, null,
                null, null);
        assertThat(LearnFlow.title(parser)).isEqualTo("Configure " + ConfigureRole.PARSER.getDisplayValue());
        assertThat(LearnFlow.title(FlowStep.of(QuestionKind.SPLIT)))
                .isEqualTo(QuestionKind.SPLIT.getDisplayValue());
        assertThat(LearnFlow.heading(2, 5, FlowStep.of(QuestionKind.SPLIT)))
                .isEqualTo("Step 2 of 5  ·  " + QuestionKind.SPLIT.getDisplayValue());
    }

    @Test
    void defaultsSayWhatTheFlowLeavesUnsaid() {
        final FlowStep parser = new FlowStep(null, QuestionKind.CONFIGURE, ConfigureRole.PARSER, null, null, null,
                null, null);
        assertThat(LearnFlow.defaults(parser))
                .startsWith("By default: passed goes to the next step; a shortfall re-asks")
                .contains("gives the learner up")
                .endsWith("judged by its kind's own.");

        final FlowStep full = new FlowStep(null, QuestionKind.TARGET, null, null, 1, null,
                List.of(Check.values()[0]),
                List.of(new Transition(StepOutcome.PASSED, Transition.END), new Transition(null, Transition.PARK)));
        assertThat(LearnFlow.defaults(full)).isEqualTo("Every outcome has a transition.");
    }

    @Test
    void textsFollowTheKindAndGuard() {
        assertThat(LearnFlow.textsOf(FlowStep.of(QuestionKind.CHAIN))).containsExactly(Template.CHAIN);
        assertThat(LearnFlow.textsOf(FlowStep.of(QuestionKind.SPLIT, StepGuard.XML)))
                .containsExactly(Template.SPLIT_XML);
        assertThat(LearnFlow.textsOf(FlowStep.of(QuestionKind.SPLIT, StepGuard.JSON)))
                .containsExactly(Template.SPLIT_JSON);
        assertThat(LearnFlow.textsOf(FlowStep.of(QuestionKind.SPLIT, StepGuard.TEXT)))
                .containsExactly(Template.SPLIT);
        assertThat(LearnFlow.textsOf(FlowStep.of(QuestionKind.SPLIT)))
                .containsExactly(Template.SPLIT, Template.SPLIT_XML, Template.SPLIT_JSON);
        assertThat(LearnFlow.textsOf(FlowStep.of(QuestionKind.ADMIT))).containsExactly(Template.ADMISSION);
        assertThat(LearnFlow.textsOf(FlowStep.of(QuestionKind.CONFIGURE)))
                .containsExactly(Template.CONFIGURATION);
    }

    @Test
    void suitableBuiltInsPutTheSlotFirst() {
        final List<Template> fits = LearnFlow.suitable(Template.CONFIGURATION);
        assertThat(fits.get(0)).isEqualTo(Template.CONFIGURATION);
        // Rules passages have no variables, so fit any text
        assertThat(fits).contains(Template.SPLIT_RULES);
        // The admission question uses variables the configuration question lacks
        assertThat(fits).doesNotContain(Template.ADMISSION);
    }

    @Test
    void variablesTitle() {
        assertThat(LearnFlow.variablesTitle(Template.SPLIT_RULES)).isEqualTo("Variables: none");
        assertThat(LearnFlow.variablesTitle(Template.SYSTEM))
                .isEqualTo("Variables: ${instructions}, ${demands}");
    }

    @Test
    void textStatusSaysWhereTheWordsComeFrom() {
        assertThat(LearnFlow.textStatus(Template.SYSTEM, false, false, null, null))
                .isEqualTo("The built-in text has not been fetched.");
        assertThat(LearnFlow.textStatus(Template.SYSTEM, false, true, 3, 3))
                .isEqualTo("The built-in text, built-in version 3.");
        assertThat(LearnFlow.textStatus(Template.CONFIGURATION, true, true, 3, 2))
                .isEqualTo("This document's own text, built-in version 3, this document last saved against 2. "
                           + "Asked at every Configure step, parser and transform alike.");
    }

    @Test
    void textRoundTrips() {
        final List<FlowStep> steps = FlowExample.DIRECT.steps();
        final String text = LearnFlow.format(steps);
        assertThat(LearnFlow.parse(text + "\n\n")).isEqualTo(steps);
        assertThat(LearnFlow.parse("")).isEmpty();
    }

    @Test
    void aLineThatDoesNotParseIsNamed() {
        assertThatThrownBy(() -> LearnFlow.parse("CHAIN\nNOT_A_KIND"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Line 2: ");
    }

    @Test
    void problems() {
        final List<FlowProblem> problems = List.of(new FlowProblem(1, "a"), new FlowProblem(1, "b"),
                new FlowProblem(null, "c"));
        assertThat(LearnFlow.problemsOf(problems, 1)).isEqualTo("a; b");
        assertThat(LearnFlow.problemsOf(problems, 0)).isNull();
        assertThat(LearnFlow.flowProblem(List.of())).isNull();
        assertThat(LearnFlow.flowProblem(problems)).isNotNull();
    }

    @Test
    void pointingAtAndNameClash() {
        final FlowStep target = new FlowStep("target", QuestionKind.TARGET, null, null, null, null, null, null);
        final FlowStep split = new FlowStep("split", QuestionKind.SPLIT, null, null, null, null, null,
                List.of(new Transition(StepOutcome.PASSED, "target")));
        final List<FlowStep> steps = List.of(split, target);
        assertThat(LearnFlow.pointingAt(steps, 1)).containsExactly("split");
        assertThat(LearnFlow.pointingAt(steps, 0)).isEmpty();
        assertThat(LearnFlow.nameClash(steps, 0, split)).isNull();
        assertThat(LearnFlow.nameClash(steps, 0, target)).contains("'target'");
    }

    @Test
    void copyDropsOnlyTheId() {
        final FlowStep chain = new FlowStep("pick", QuestionKind.CHAIN, null, null, 2, null, null, null,
                List.of("XMLParser"));
        final FlowStep copy = LearnFlow.copyWithoutId(chain);
        assertThat(copy.getId()).isNull();
        assertThat(copy.getCandidates()).isEqualTo(2);
        assertThat(copy.getElements()).containsExactly("XMLParser");
    }
}
