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

import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.learning.Question.TargetFor;
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

/// Tests learning syslog in both its forms, RFC 3164 and RFC 5424, sent by two senders on one feed.
///
/// One feed through one pipeline is one learner, so a single configuration must handle both forms, with a
/// template match per kind of record. A splitter that handles only one form is sent back to the model with
/// feedback naming the lines it dropped.
class TestScenario43Syslog {

    private static final String BOTH_FORMS = Scenarios.resource("syslog.ds3.xml");
    private static final String BSD_ONLY = Scenarios.resource("syslog-3164-only.ds3.xml");
    private static final String XSLT = Scenarios.resource("syslog.xsl");
    private static final String LOG = Scenarios.resource("syslog.log");
    private static final String EVENTS = Scenarios.resource("syslog.events.xml");
    private static final String FEED = "GATEWAY-SSH";

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("gateway-ssh")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST))
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
                                new ExtractionQualityParameters(false,
                                                List.of("EventSource/User/Id", "EventSource/Client/IPAddress"))),
                        new CheckSetting(Check.RULES, CheckState.GATE, 1.0,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                                "logons name the user",
                                                "EventDetail/Authenticate/User/Id[normalize-space(.) != '']")), true)),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
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
        // One learner, so one binding, which handles both forms.
        assertThat(run.learner()).isEqualTo(Scenarios.LEARNER);
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).hasSize(1);
        // The two forms are two kinds of record, so two target events were proposed.
        assertThat(script.asked()).filteredOn(TargetFor.class::isInstance).hasSize(2);
        assertThat(script.scripted()).hasSize(4);
    }
}
