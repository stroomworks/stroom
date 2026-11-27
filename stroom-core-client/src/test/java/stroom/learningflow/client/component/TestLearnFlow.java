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

import stroom.learningflow.shared.FlowProblem;
import stroom.learningflow.shared.FlowProblem.Severity;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StepGuard;
import stroom.learningflow.shared.Template;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        // Rules passages have no variables, so they suit any text
        assertThat(fits).contains(Template.SPLIT_RULES);
        // The admission question uses variables the configuration question doesn't have
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
    void problems() {
        final List<FlowProblem> problems = List.of(new FlowProblem(1, "a"), new FlowProblem(1, "b"),
                new FlowProblem(null, "c"));
        assertThat(LearnFlow.problemsOf(problems, 1)).isEqualTo("a; b");
        assertThat(LearnFlow.problemsOf(problems, 0)).isNull();
    }

    @Test
    void aStepsWarningsAreKeptApartFromItsProblems() {
        final List<FlowProblem> problems = List.of(new FlowProblem(1, "a"),
                new FlowProblem(1, "w", Severity.WARNING), new FlowProblem(1, "x", Severity.WARNING),
                new FlowProblem(0, "y", Severity.WARNING));

        // A step card shows problems and warnings in different styles, so each severity can be asked for
        // separately
        assertThat(LearnFlow.problemsOf(problems, 1)).isEqualTo("a");
        assertThat(LearnFlow.problemsOf(problems, 1, Severity.PROBLEM)).isEqualTo("a");
        assertThat(LearnFlow.problemsOf(problems, 1, Severity.WARNING)).isEqualTo("w; x");
        assertThat(LearnFlow.problemsOf(problems, 0, Severity.PROBLEM)).isNull();
        assertThat(LearnFlow.problemsOf(problems, 0, Severity.WARNING)).isEqualTo("y");
    }
}
