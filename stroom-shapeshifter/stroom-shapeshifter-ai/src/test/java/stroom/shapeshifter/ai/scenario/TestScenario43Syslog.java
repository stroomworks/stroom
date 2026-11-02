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
import stroom.shapeshifter.shared.XPathAssertion;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 02 §5, scenario 43 (design 03 §3): syslog in both forms — RFC 3164 and RFC 5424 — from two senders on
/// one feed. That is one learner, so one transform must take both forms, and a splitter that handles one is sent
/// back naming the lines it dropped. The scenario also put the record signature in the learning key, making
/// each form its own shape; design 06 retired that, a pipeline learning one transform with a match per kind
/// of record (A49, A104).
class TestScenario43Syslog {

    private static final String BOTH_FORMS = Scenarios.resource("syslog.ds3.xml");
    private static final String BSD_ONLY = Scenarios.resource("syslog-3164-only.ds3.xml");
    private static final String XSLT = Scenarios.resource("syslog.xsl");
    private static final String LOG = Scenarios.resource("syslog.log");
    private static final String EVENTS = Scenarios.resource("syslog.events.xml");
    private static final String FEED = "GATEWAY-SSH";

    private static ShapeshifterAiDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("gateway-ssh")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExample.TARGET_FIRST)
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
                                new ExtractionQualityParameters(false,
                                        List.of("EventSource/User/Id", "EventSource/Client/IPAddress"))),
                        new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 1.0, false,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                        "logons name the user",
                                        "EventDetail/Authenticate/User/Id[normalize-space(.) != '']")), true))))
                .build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, FEED, "Raw Events", Map.of("Format", "syslog"), data);
    }


    @Test
    void scenario43OneTransformMustTakeBothForms() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(BOTH_FORMS, XSLT)
                .expect(QuestionMatcher.chain().withKey("Feed", FEED)).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withoutFeedback()).reply(Scenarios.fenced(BSD_ONLY))
                .expect(QuestionMatcher.configuration("DSParser")
                        .withFeedbackMentioning("Input coverage scored")
                        .withFeedbackMentioning("Lines not consumed: 2, 4, 6")
                        .withPreviousConfiguration(BSD_ONLY))
                .reply(Scenarios.fenced(BOTH_FORMS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(), stream(1, LOG)).learned();

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        script.verifyExhausted();
        assertThat(run.output()).isEqualTo(EVENTS);
        // One learner, so one binding, and one variant that handles both forms.
        assertThat(run.learner()).isEqualTo(Scenarios.LEARNER);
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).hasSize(1);
        // The two forms are two kinds of record, so two targets were proposed and both configurations carried them.
        assertThat(script.asked()).filteredOn(TargetFor.class::isInstance).hasSize(2);
        assertThat(script.scripted()).hasSize(4);
    }
}
