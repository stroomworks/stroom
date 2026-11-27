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

package stroom.learningflow.scenario;

import stroom.learningflow.learning.Exchange;
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.learning.Question.Configuration;
import stroom.learningflow.learning.Question.Split;
import stroom.learningflow.learning.Question.TargetFor;
import stroom.learningflow.shared.BusinessRulesParameters;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.ExtractionQualityParameters;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.XPathAssertion;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests learning a fixed-width sign-on log, with six columns by position and no delimiter, under the
/// escalating flow.
///
/// A parser that captures four columns and matches the rest of the line consumes every character, so
/// coverage is full and does not catch the dropped columns. The transform written over its output cannot
/// state an outcome, so the business rule refuses it twice and the flow escalates to proposing targets. When
/// asked again against the targets, the four-column parser is caught by the preservation check, because the
/// records carry no value for the reason the target event states. The six-column parser is then promoted.
class TestScenario47FixedWidth {

    private static final String SIX = Scenarios.resource("fixed-width.ds3.xml");
    private static final String FOUR = Scenarios.resource("fixed-width-four.ds3.xml");
    private static final String XSLT = Scenarios.resource("fixed-width.xsl");
    private static final String FOUR_XSLT = Scenarios.resource("fixed-width-four.xsl");
    private static final String LOG = Scenarios.resource("fixed-width.log");
    private static final String EVENTS = Scenarios.resource("fixed-width.events.xml");

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("mainframe-sign-on")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.ESCALATING))
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.COVERAGE, CheckState.GATE, 0.9, null),
                        new CheckSetting(Check.YIELD, CheckState.GATE, 0.5,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        new CheckSetting(Check.CONFORMANCE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.QUALITY, CheckState.GATE, 0.7,
                                new ExtractionQualityParameters(false, List.of("EventSource/User/Id"))),
                        new CheckSetting(Check.RULES, CheckState.GATE, 1.0,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                        "a sign-on decision states its outcome",
                                        "not(EventDetail/Authenticate) or EventDetail/Authenticate"
                                        + "/Outcome/Success[. = 'true' or . = 'false']")),
                                        true)),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
                .build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, "MAINFRAME-SIGNON", "Raw Events", Map.of(), data);
    }

    @Test
    void scenario47AColumnDroppedAtTheParserIsCaughtByPreservationNotCoverage() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(SIX, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withTargets(0).withoutFeedback())
                .reply(Scenarios.fenced(FOUR))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(FOUR_XSLT))
                .expect(QuestionMatcher.configuration("XSLTFilter")
                        .withFeedbackMentioning("a sign-on decision states its outcome")
                        .withFeedbackMentioning("fails for 24 of 24 records"))
                .reply(Scenarios.fenced(FOUR_XSLT))
                // The transform's candidates are spent, so targets are proposed (answered automatically by the
                // script) and the parser is asked again against them: first four columns, then six.
                .expect(QuestionMatcher.configuration("DSParser").withTargets(2)).reply(Scenarios.fenced(FOUR))
                .expect(QuestionMatcher.configuration("DSParser")
                        .withFeedbackMentioning("the records carry no value for")
                        .withFeedbackMentioning("PASSWORD OK"))
                .reply(Scenarios.fenced(SIX))
                .expect(QuestionMatcher.configuration("XSLTFilter").withTargets(2)).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(), stream(1, LOG)).learned();

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        script.verifyExhausted();
        assertThat(run.output()).isEqualTo(EVENTS);
        // The split is asked for every input. Here it is one record per line, answered automatically by the
        // script.
        assertThat(script.asked()).filteredOn(Split.class::isInstance).hasSize(1);
        // There are two kinds of line, with a reason of two words and of one, and a target for each.
        assertThat(script.asked()).filteredOn(TargetFor.class::isInstance).hasSize(2);
        final List<Exchange> turns = run.transcript();
        assertThat(turns.stream().map(Exchange::step))
                .containsExactly("chain", "split", "parser", "first", "first", "target", "target", "again", "again",
                        "transform");
        assertThat(turns.stream().map(Exchange::outcome)).containsExactly(
                StepOutcome.PASSED, StepOutcome.PASSED, StepOutcome.PASSED, StepOutcome.RULES_SHORT,
                StepOutcome.RULES_SHORT, StepOutcome.PASSED, StepOutcome.PASSED, StepOutcome.PRESERVATION_SHORT,
                StepOutcome.PASSED, StepOutcome.PASSED);
        // Coverage never gave feedback, because the four-column parser consumed every character of every line.
        assertThat(turns.stream().flatMap(turn -> turn.question().feedback().stream()))
                .extracting(error -> error.getMessage())
                .noneMatch(message -> message.startsWith("Input coverage")
                                      || message.startsWith("The split consumed"));
        final Configuration parserAgain = (Configuration) turns.get(8).question();
        assertThat(parserAgain.feedback()).extracting(error -> error.getMessage())
                .anyMatch(message -> message.contains("Record kind 1: the records carry no value for "
                                                      + "[PASSWORD OK]"))
                .anyMatch(message -> message.contains("Record kind 2: the records carry no value for [REVOKED]"));
    }
}
