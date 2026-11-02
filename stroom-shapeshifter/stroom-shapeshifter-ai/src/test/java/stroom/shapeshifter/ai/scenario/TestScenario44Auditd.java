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

/// Design 02 §5, scenario 44 (design 03 §3): Linux audit records, where one event is the run of consecutive
/// lines sharing a serial and nothing else marks the boundary. A split of one record per line consumes every
/// character and is caught by yield against the input's own structure — the document says a record is
/// several lines — before any target is proposed; the split that joins by serial passes; targets are proposed
/// for whole events; and a parser that drops the EXECVE's quoted arguments is caught by preservation, the
/// target having the command and its arguments.
class TestScenario44Auditd {

    private static final String BY_EVENT = Scenarios.resource("auditd.ds3.xml");
    private static final String BY_SERIAL = Scenarios.resource("auditd-split.ds3.xml");
    private static final String NO_ARGS = Scenarios.resource("auditd-no-args.ds3.xml");
    private static final String XSLT = Scenarios.resource("auditd.xsl");
    private static final String LOG = Scenarios.resource("auditd.log");
    private static final String EVENTS = Scenarios.resource("auditd.events.xml");

    private static ShapeshifterAiDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("linux-auditd")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExample.TARGET_FIRST)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.9, false, null),
                        // A record is about three lines: the expected yield per line says so, and a split
                        // of one record per line scores a third of it.
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.6, false,
                                new YieldParameters(0.35, YieldBasis.LINES)),
                        new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true,
                                new SchemaConformanceParameters("EVENTS")),
                        new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, true,
                                new ExtractionQualityParameters(false, List.of("EventSource/User/Id"))),
                        new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 1.0, false,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                        "logons name the user",
                                        "not(EventDetail/Authenticate) "
                                        + "or EventDetail/Authenticate/User/Id[normalize-space(.) != '']")),
                                        true))))
                .build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, "LINUX-AUDITD", "Raw Events", Map.of(), data);
    }

    @Test
    void scenario44ARecordIsSeveralLines() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(BY_EVENT, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.split().withoutFeedback()).reply(Scenarios.fenced(Structure.LINE_SPLIT))
                .expect(QuestionMatcher.split()
                        .withFeedbackMentioning("Yield scored")
                        .withFeedbackMentioning("per unit against an expected 0.35"))
                .reply(Scenarios.fenced(BY_SERIAL))
                .expect(QuestionMatcher.configuration("DSParser").withoutFeedback()).reply(Scenarios.fenced(NO_ARGS))
                .expect(QuestionMatcher.configuration("DSParser")
                        .withFeedbackMentioning("the records carry no value for")
                        .withFeedbackMentioning("/etc/hosts"))
                .reply(Scenarios.fenced(BY_EVENT))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(), stream(1, LOG)).learned();

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        script.verifyExhausted();
        assertThat(run.output()).isEqualTo(EVENTS);
        // The line split consumed everything and was refused on yield alone, before any target was asked.
        final List<Exchange> turns = run.transcript();
        assertThat(turns.get(1).question()).isInstanceOf(Split.class);
        assertThat(turns.get(1).outcome()).isEqualTo(StepOutcome.YIELD_SHORT);
        assertThat(turns.get(1).question().feedback()).isEmpty();
        assertThat(turns.get(2).outcome()).isEqualTo(StepOutcome.PASSED);
        // Design 06 A144: each turn carries the score of the check that decided it — the yield that fell
        // short, and on the pass the check that passed by the least — and a chain chosen carries none.
        assertThat(turns.get(0).score()).isNull();
        assertThat(turns.get(1).score()).satisfies(score -> {
            assertThat(score.getScorer()).isEqualTo(ScorerType.YIELD);
            assertThat(score.isMet()).isFalse();
        });
        assertThat(turns.get(2).score()).satisfies(score -> assertThat(score.isMet()).isTrue());
        final long attemptId = scenarios.attempts.all().getFirst().id();
        assertThat(scenarios.attempts.byId(attemptId).orElseThrow().turns().get(1).score())
                .describedAs("and the attempt keeps it")
                .isEqualTo(turns.get(1).score());
        assertThat(turns.stream().filter(turn -> turn.question() instanceof TargetFor).findFirst().orElseThrow()
                .question()).isInstanceOf(TargetFor.class);
        // Targets were proposed for whole events: the first kind's record spans lines that share a serial.
        final TargetFor target = (TargetFor) script.asked().stream().filter(TargetFor.class::isInstance).findFirst()
                .orElseThrow();
        assertThat(target.record().lines().count()).isGreaterThan(1);
        assertThat(target.record().lines().map(line -> line.replaceAll(".*msg=audit\\([^:]+:(\\d+)\\).*", "$1"))
                .distinct()).hasSize(1);
        // The parser dropping the arguments was caught by preservation: coverage was full.
        final Exchange parserAgain = turns.stream()
                .filter(turn -> turn.question() instanceof Configuration && turn.candidate() == 2)
                .findFirst().orElseThrow();
        assertThat(parserAgain.question().feedback()).extracting(error -> error.getMessage())
                .noneMatch(message -> message.startsWith("Input coverage"));
        assertThat(turns.stream().filter(turn -> turn.question() instanceof Configuration).findFirst().orElseThrow()
                .outcome()).isEqualTo(StepOutcome.PRESERVATION_SHORT);
    }

    @Test
    void underTheEscalatingFlowAParserThatCutsPerLineGoesBackToTheSplit() {
        // Run 7 (design 02 §6.3): with no split step the escalating flow's parser had to cut and extract at
        // once and gave up. Now the split is asked first (A39), and a parser refused on yield — one record
        // per line against the 0.35 the document states — goes back to the split rather than being re-asked
        // blind (A40); the split holds, and the parser asked again is held to it.
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(BY_EVENT, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.split().withoutFeedback()).reply(Scenarios.fenced(BY_SERIAL))
                .expect(QuestionMatcher.configuration("DSParser").withoutFeedback())
                .reply(Scenarios.fenced(Structure.LINE_SPLIT))
                .expect(QuestionMatcher.split().withFeedbackMentioning("Yield scored"))
                .reply(Scenarios.fenced(BY_SERIAL))
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(BY_EVENT))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback()).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc().copy().flow(FlowExample.ESCALATING).build(),
                stream(1, LOG)).learned();

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        script.verifyExhausted();
        assertThat(run.output()).isEqualTo(EVENTS);
        assertThat(run.transcript().stream().map(Exchange::step))
                .containsExactly("chain", "split", "parser", "split", "parser", "first");
        assertThat(run.transcript().stream().map(Exchange::outcome))
                .containsExactly(StepOutcome.PASSED, StepOutcome.PASSED, StepOutcome.YIELD_SHORT, StepOutcome.PASSED,
                        StepOutcome.PASSED, StepOutcome.PASSED);
        assertThat(script.asked()).noneMatch(TargetFor.class::isInstance);
    }
}
