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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.learning.Question.Configuration;
import stroom.shapeshifter.ai.learning.Question.Split;
import stroom.shapeshifter.ai.learning.Question.TargetFor;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.XPathAssertion;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 02 §5, scenario 47 (design 03 §3): a sign-on log of six columns by position and no delimiter, under
/// the escalating flow. A parser that captures four columns and matches the rest of the line consumes every
/// character, so coverage is full and says nothing; the transform written over it cannot state an outcome,
/// the business rule refuses it twice, and the flow escalates to a target. Asked again against the targets,
/// the four-column parser is caught by preservation — the records carry no value for the reason the target
/// event states — and the six-column parser is promoted.
class TestScenario47FixedWidth {

    private static final String SIX = Scenarios.resource("fixed-width.ds3.xml");
    private static final String FOUR = Scenarios.resource("fixed-width-four.ds3.xml");
    private static final String XSLT = Scenarios.resource("fixed-width.xsl");
    private static final String FOUR_XSLT = Scenarios.resource("fixed-width-four.xsl");
    private static final String LOG = Scenarios.resource("fixed-width.log");
    private static final String EVENTS = Scenarios.resource("fixed-width.events.xml");

    private static ShapeshifterAiDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("mainframe-sign-on")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExample.ESCALATING)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.9, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true,
                                new SchemaConformanceParameters("EVENTS")),
                        new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, true,
                                new ExtractionQualityParameters(false, List.of("EventSource/User/Id"))),
                        // The rule the four-column transform cannot meet: a sign-on decision states its outcome.
                        new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 1.0, false,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                        "a sign-on decision states its outcome",
                                        "not(EventDetail/Authenticate) "
                                        + "or EventDetail/Authenticate/Outcome/Success[. = 'true' or . = 'false']")),
                                        true))))
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
                // The transform's candidates are spent; targets are proposed (the structure answers), and the
                // parser is asked again against them: four columns, then six.
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
        // The split is asked of every input (A39): one record per line here, answered by the structure.
        assertThat(script.asked()).filteredOn(Split.class::isInstance).hasSize(1);
        // Two kinds of line — a reason of two words and of one — and a target for each.
        assertThat(script.asked()).filteredOn(TargetFor.class::isInstance).hasSize(2);
        final List<Exchange> turns = run.transcript();
        assertThat(turns.stream().map(Exchange::step))
                .containsExactly("chain", "split", "parser", "first", "first", "target", "target", "again", "again",
                        "transform");
        assertThat(turns.stream().map(Exchange::outcome)).containsExactly(
                StepOutcome.PASSED, StepOutcome.PASSED, StepOutcome.PASSED, StepOutcome.RULES_SHORT,
                StepOutcome.RULES_SHORT, StepOutcome.PASSED, StepOutcome.PASSED, StepOutcome.PRESERVATION_SHORT,
                StepOutcome.PASSED, StepOutcome.PASSED);
        // Coverage never spoke: the four-column parser consumed every character of every line.
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
