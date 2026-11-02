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
import stroom.shapeshifter.ai.learning.InputKind;
import stroom.shapeshifter.ai.learning.Question.Configuration;
import stroom.shapeshifter.ai.learning.Question.Split;
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
import stroom.shapeshifter.shared.XPathAssertion;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 02 §5, scenario 49 (design 01 §12 item 26): a stream of XML fragments — one `<Event>` per
/// line, no root. It is not a document, so until the `XMLFragmentParser` existed as a step the stage
/// read it as text, could not answer the split question over it, and abandoned the attempt.
///
/// The parser wraps the fragments in a root, which makes them one document whose children are the
/// records. Nothing is asked about the wrapper — it is the element's own business, not a candidate
/// spent — and the split question is the XML one, naming the element that is one record, because what
/// the stream carries is markup.
///
/// The fixture is the Windows security export with its root removed, and it learns the same stylesheet
/// and produces the same events as scenario 45: a stream is the records it carries, whether or not
/// anything wrapped them.
class TestScenario49XmlFragments {

    private static final String FRAGMENTS = Scenarios.resource("events-fragments.xml");
    private static final String TYPED = Scenarios.resource("windows-security.xsl");
    private static final String EVENTS = Scenarios.resource("windows-security.events.xml");

    private static ShapeshifterAiDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("windows-fragments")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExample.TARGET_FIRST)
                .allowedElements(List.of("XMLFragmentParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
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

        // Nothing was asked about the wrapper: the fragment parser's configuration is its own.
        assertThat(script.asked()).noneMatch(question -> question instanceof Configuration configuration
                                                         && "XMLFragmentParser".equals(
                configuration.elementType()));
        // And the split question was the XML one, over what the parser made of the stream.
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
