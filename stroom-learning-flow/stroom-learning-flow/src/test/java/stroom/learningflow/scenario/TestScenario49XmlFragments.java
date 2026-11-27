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
import stroom.learningflow.learning.InputKind;
import stroom.learningflow.learning.Question.Configuration;
import stroom.learningflow.learning.Question.Split;
import stroom.learningflow.shared.BusinessRulesParameters;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.ExtractionQualityParameters;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
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

/// Tests learning a stream of XML fragments, one `<Event>` per line with no root element.
///
/// Such a stream is not a well-formed document, so it is learned through the `XMLFragmentParser`, which wraps
/// the fragments in a root and makes them one document whose children are the records. Nothing is asked
/// about the wrapper, since the parser handles it and it costs no candidate. The split question is the XML
/// one, naming the element that is one record.
///
/// The fixture is the Windows Security export with its root removed. It learns the same stylesheet and
/// produces the same events as `TestScenario45WindowsSecurity`, because the records are the same whether or
/// not anything wraps them.
class TestScenario49XmlFragments {

    private static final String FRAGMENTS = Scenarios.resource("events-fragments.xml");
    private static final String TYPED = Scenarios.resource("windows-security.xsl");
    private static final String EVENTS = Scenarios.resource("windows-security.events.xml");

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("windows-fragments")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST))
                .allowedElements(List.of("XMLFragmentParser", "XSLTFilter"))
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
    void scenario49FragmentsAreWrappedAndLearnedAsRecords() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.xmlScript("Event", TYPED)
                .expect(QuestionMatcher.chain()).reply("XMLFragmentParser -> XSLTFilter")
                .expect(QuestionMatcher.split().withoutFeedback()).reply("Event")
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(TYPED));

        final StageRun run = scenarios.process(script, doc(),
                new Input(1, "WINDOWS-FRAGMENTS", "Raw Events", Map.of("Format", "XML"), FRAGMENTS)).learned();

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        script.verifyExhausted();
        assertThat(Scenarios.canonical(run.output()))
                .describedAs("the same events as the same stream with a root around it")
                .isEqualTo(Scenarios.canonical(EVENTS));

        // Nothing was asked about the wrapper, because the fragment parser needs no configuration.
        assertThat(script.asked()).noneMatch(question -> question instanceof Configuration configuration
                                                         && "XMLFragmentParser".equals(
                configuration.elementType()));
        // The split question was the XML one, over the parser's output.
        final Split split = script.asked().stream()
                .filter(Split.class::isInstance).map(Split.class::cast).findFirst().orElseThrow();
        assertThat(split.kind()).isEqualTo(InputKind.XML);
        assertThat(split.documentType()).isNull();
        final List<Exchange> turns = run.transcript();
        assertThat(turns.get(0).step()).isEqualTo("chain");
    }

    @Test
    void theBindingCarriesTheRecordElementAndTheStreamIsCountedByIt() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.xmlScript("Event", TYPED)
                .expect(QuestionMatcher.chain()).reply("XMLFragmentParser -> XSLTFilter")
                .expect(QuestionMatcher.split().withoutFeedback()).reply("Event")
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(TYPED));

        scenarios.process(script, doc(),
                new Input(1, "WINDOWS-FRAGMENTS", "Raw Events", Map.of("Format", "XML"), FRAGMENTS)).learned();

        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).hasSize(1);
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER).getFirst().getRecordBoundary().getElement())
                .isEqualTo("Event");
    }
}
