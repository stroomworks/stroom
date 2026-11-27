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

/// Tests learning Linux audit records, where one event is a run of consecutive lines sharing a serial number
/// and nothing else marks the record boundary.
///
/// A split of one record per line consumes every character, so coverage cannot catch it. It is caught by the
/// yield check instead, because the document states an expected yield of fewer than one event per line, and
/// it is refused before any target is proposed. The split that joins lines by serial passes, and targets are
/// proposed for whole events. A parser that drops the EXECVE record's quoted arguments is then caught by the
/// preservation check, because the target event has the command and its arguments.
class TestScenario44Auditd {

    private static final String BY_EVENT = Scenarios.resource("auditd.ds3.xml");
    private static final String BY_SERIAL = Scenarios.resource("auditd-split.ds3.xml");
    private static final String NO_ARGS = Scenarios.resource("auditd-no-args.ds3.xml");
    private static final String XSLT = Scenarios.resource("auditd.xsl");
    private static final String LOG = Scenarios.resource("auditd.log");
    private static final String EVENTS = Scenarios.resource("auditd.events.xml");

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("linux-auditd")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST))
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.COVERAGE, CheckState.GATE, 0.9, null),
                        new CheckSetting(Check.YIELD, CheckState.GATE, 0.6,
                                new YieldParameters(0.35, YieldBasis.LINES)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        new CheckSetting(Check.CONFORMANCE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.QUALITY, CheckState.GATE, 0.7,
                                new ExtractionQualityParameters(false, List.of("EventSource/User/Id"))),
                        new CheckSetting(Check.RULES, CheckState.GATE, 1.0,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                                "logons name the user",
                                                "not(EventDetail/Authenticate) "
                                                + "or EventDetail/Authenticate/User/Id[normalize-space(.) != '']")),
                                                true)),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
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
        // The line split consumed everything and was refused on yield alone, before any target was asked for.
        final List<Exchange> turns = run.transcript();
        assertThat(turns.get(1).question()).isInstanceOf(Split.class);
        assertThat(turns.get(1).outcome()).isEqualTo(StepOutcome.YIELD_SHORT);
        assertThat(turns.get(1).question().feedback()).isEmpty();
        assertThat(turns.get(2).outcome()).isEqualTo(StepOutcome.PASSED);
        // Each turn carries the score of the check that decided it: for the refused split, the yield that fell
        // short, and for the split that passed, the check that passed by the smallest margin. The chain turn
        // carries no score.
        assertThat(turns.get(0).score()).isNull();
        assertThat(turns.get(1).score()).satisfies(score -> {
            assertThat(score.getCheck()).isEqualTo(Check.YIELD);
            assertThat(score.isMet()).isFalse();
        });
        assertThat(turns.get(2).score()).satisfies(score -> assertThat(score.isMet()).isTrue());
        final long attemptId = scenarios.attempts.all().getFirst().id();
        assertThat(scenarios.attempts.byId(attemptId).orElseThrow().turns().get(1).score())
                .describedAs("and the attempt keeps it")
                .isEqualTo(turns.get(1).score());
        assertThat(turns.stream().filter(turn -> turn.question() instanceof TargetFor).findFirst().orElseThrow()
                .question()).isInstanceOf(TargetFor.class);
        // Targets were proposed for whole events: the first kind's record spans several lines sharing a serial.
        final TargetFor target = (TargetFor) script.asked().stream().filter(TargetFor.class::isInstance).findFirst()
                .orElseThrow();
        assertThat(target.record().lines().count()).isGreaterThan(1);
        assertThat(target.record().lines().map(line -> line.replaceAll(".*msg=audit\\([^:]+:(\\d+)\\).*", "$1"))
                .distinct()).hasSize(1);
        // The parser that dropped the arguments was caught by preservation, since its coverage was full.
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
        // In the escalating flow the split is asked for before the parser, so the parser does not have to find
        // the record boundary and extract fields at the same time. A parser refused on yield (one record per
        // line against the 0.35 per line the document states) goes back to the split step rather than being
        // asked again without guidance. The split is confirmed, and the parser asked again is held to it.
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

        final StageRun run = scenarios.process(script,
                doc().copy().flow(FlowExamples.flow(FlowExample.ESCALATING)).build(),
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
