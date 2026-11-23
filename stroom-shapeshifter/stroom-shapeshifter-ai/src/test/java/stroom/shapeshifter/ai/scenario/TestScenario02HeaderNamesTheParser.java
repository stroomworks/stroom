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

import stroom.shapeshifter.ai.learning.Question.Configuration;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.CheckSetting;
import stroom.shapeshifter.shared.CheckState;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.XPathAssertion;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 02 §5, scenario 2, as design 06 leaves it: the model chooses a JSONParser, no configuration
/// question is asked for it, and the fragment holds it with no document. The scenario was first written for
/// a learning key holding the `Format` header, which the chain question carried and the binding bound on
/// (A29); since design 06 phase J slice 2a a learner is one element in one pipeline, a pipeline being unable
/// to select streams by a receipt header (A119), so the header is neither shown nor bound.
class TestScenario02HeaderNamesTheParser {

    private static final String XSLT = Scenarios.resource("records.xsl");
    private static final String LINES = Scenarios.resource("records.jsonl");
    private static final String EVENTS = Scenarios.resource("records.events.xml");

    private static ShapeshifterAiDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("api-gateway")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "JSONParser", "XSLTFilter"))
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
    void scenario2TheHeaderNamesTheParser() {
        final Scenarios scenarios = new Scenarios();
        final Script script = Script.of()
                .expect(QuestionMatcher.chain()
                        .allowing("DSParser", "JSONParser", "XSLTFilter"))
                .reply("JSONParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback()).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(),
                new Input(1, "API-GATEWAY", "Raw Events", Map.of("Format", "JSON"), LINES)).learned();

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        script.verifyExhausted();
        assertThat(script.asked()).hasSize(2);
        // The transform question carried the parser's real output, not a description of it.
        final Configuration transform = (Configuration) script.asked().get(1);
        assertThat(transform.input()).startsWith("<?xml").contains("http://www.w3.org/2013/XSL/json");
        assertThat(run.output()).isEqualTo(EVENTS);
        assertThat(run.learner()).isEqualTo(Scenarios.LEARNER);
        // The fragment holds the parser with no document: its one property-bearing element is the transform.
        assertThat(scenarios.stores.pipelines.list()).hasSize(1);
        assertThat(scenarios.stores.textConverters.list()).isEmpty();
        assertThat(scenarios.stores.xslts.list()).hasSize(1);
    }
}
