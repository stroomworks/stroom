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

/// Tests learning the Windows Security event log exported as XML.
///
/// The split step names the element that is one record. `EventData`, which holds most of an event but not
/// its `System` block, is refused by the wholeness check. The test also covers a transform that produces
/// events that validate but extract nothing: one that copies every named `Data` to a `Data` element under
/// `Unknown` is refused by the extraction quality check. The transform that maps each `EventID` to a typed
/// event is then promoted, handling three kinds of event that are told apart by the names of their `Data`
/// elements.
class TestScenario45WindowsSecurity {

    private static final String TYPED = Scenarios.resource("windows-security.xsl");
    private static final String DEGENERATE = Scenarios.resource("windows-security-degenerate.xsl");
    private static final String LOG = Scenarios.resource("windows-security.xml");
    private static final String EVENTS = Scenarios.resource("windows-security.events.xml");

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("windows-security")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST))
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        CheckSetting.of(Check.COVERAGE, CheckState.OFF),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.5,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        new CheckSetting(Check.CONFORMANCE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.QUALITY, CheckState.GATE, 0.7,
                                new ExtractionQualityParameters(false, List.of("EventSource/User/Id"))),
                        new CheckSetting(Check.RULES, CheckState.ADVICE, 1.0,
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

    @Test
    void scenario45NamedDataBecomeTypedFields() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.xmlScript("Event", TYPED)
                .expect(QuestionMatcher.chain()).reply("XSLTFilter")
                // Most of an event, but not its System block, so it is refused on wholeness.
                .expect(QuestionMatcher.split().withoutFeedback()).reply("EventData")
                .expect(QuestionMatcher.split().withFeedbackMentioning("of the document's")).reply("Event")
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(DEGENERATE))
                .expect(QuestionMatcher.configuration("XSLTFilter")
                        .withFeedbackMentioning("Extraction quality scored")
                        .withFeedbackMentioning("Typed-element ratio")
                        .withFeedbackMentioning("records name Unknown in EventDetail"))
                .reply(Scenarios.fenced(TYPED));

        final StageRun run = scenarios.process(script, doc(),
                new Input(1, "WINDOWS-SECURITY", "Raw Events", Map.of("Format", "XML"), LOG)).learned();

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        script.verifyExhausted();
        assertThat(Scenarios.canonical(run.output())).isEqualTo(Scenarios.canonical(EVENTS));
        // Each record is a child of the root element, so the number of events matches the number of records
        // and the candidate scores 1.0.
        assertThat(((Promoted) run.decision()).score()).isEqualTo(1.0);
        final List<Exchange> turns = run.transcript();
        assertThat(turns.get(1).question()).isInstanceOf(Split.class);
        assertThat(turns.get(1).outcome()).isEqualTo(StepOutcome.WHOLENESS_SHORT);
        // Kinds of record are told apart by their structure, and the structural summary includes the values of
        // naming attributes such as Data/@Name. A logon, a logoff and a process creation, each a System block
        // and its own set of named Data elements, are therefore three kinds, and a target is asked for each.
        final List<TargetFor> targets = script.asked().stream()
                .filter(TargetFor.class::isInstance).map(TargetFor.class::cast).toList();
        assertThat(targets).hasSize(3);
        assertThat(targets).allSatisfy(target -> assertThat(target.record())
                .startsWith("<Event ").contains("<EventData>"));
        assertThat(targets.stream()
                .map(target -> target.record().replaceAll("(?s).*<EventID>(\\d+)</EventID>.*", "$1")))
                .containsExactlyInAnyOrder("4624", "4634", "4688");
        assertThat(run.output()).contains("<TypeId>4624</TypeId>", "<TypeId>4634</TypeId>", "<TypeId>4688</TypeId>");
        // The degenerate transform validated; only extraction quality refused it.
        final Exchange degenerate = turns.stream()
                .filter(turn -> turn.question() instanceof Configuration).findFirst().orElseThrow();
        assertThat(degenerate.outcome()).isEqualTo(StepOutcome.QUALITY_SHORT);
        final Configuration reAsk = (Configuration) script.scripted().get(script.scripted().size() - 1);
        assertThat(reAsk.feedback()).extracting(error -> error.getMessage())
                .noneMatch(message -> message.startsWith("Schema conformance scored"));
        assertThat(reAsk.split().element()).isEqualTo("Event");
    }
}
