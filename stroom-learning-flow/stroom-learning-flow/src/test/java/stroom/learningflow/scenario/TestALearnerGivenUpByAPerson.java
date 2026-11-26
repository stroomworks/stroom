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

import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Decision.Sentinel;
import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests a person giving up a learner from the Supervisor, for example because its feed is retired.
///
/// A learner that has been given up learns no more. Streams that nothing serves are errored with the person's
/// reason, and the learner's pipeline is woken so that streams already waiting are errored now rather than
/// waiting indefinitely. A binding that already serves the learner carries on serving, and sending the
/// learner back to learn again clears the give-up.
class TestALearnerGivenUpByAPerson {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");

    @Test
    void aLearnerNothingServesErrorsItsWaitingStreamsWithTheReason() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        scenarios.deferrals.deferred(Scenarios.LEARNER, DOC, 1L, FEED, "Raw Events", null, "Learning.", 0);

        scenarios.supervisorActions().giveUp(doc, Scenarios.LEARNER, "The feed is retired", "jo");

        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER))
                .hasValue("Given up by jo: The feed is retired");
        assertThat(scenarios.wake.woken())
                .describedAs("its pipeline woken, so the waiting stream comes to its end now")
                .extracting(Deferral::inputId)
                .containsExactly(1L);
        final Script silent = Script.of();
        final StageRun errored = scenarios.stage(silent).decide(doc, Scenarios.LEARNER, stream(1L));
        assertThat(errored.decision()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) errored.decision()).reason()).contains("Given up by jo: The feed is retired");
        assertThat(silent.asked()).describedAs("and nothing asked").isEmpty();
    }

    @Test
    void whatServesALearnerGivenUpGoesOnServingUntilItIsSentBack() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        final StageRun learned = scenarios.process(script(scenarios), doc, stream(1L)).learned();
        assertThat(learned.decision()).isInstanceOf(Promoted.class);

        scenarios.supervisorActions().giveUp(doc, Scenarios.LEARNER, "Leave it as it is", "jo");

        final Script silent = Script.of();
        assertThat(scenarios.stage(silent).decide(doc, Scenarios.LEARNER, stream(2L)).decision())
                .describedAs("served as before: giving up stops the learning, not the serving")
                .isInstanceOf(Bound.class);
        assertThat(silent.asked()).isEmpty();

        scenarios.supervisorActions().relearn(doc, Scenarios.LEARNER, "Sent back to be learned again by jo");
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER)).describedAs("sent back").isEmpty();
    }

    /// A draft awaiting review takes precedence over a give-up, so the way to give up a learner with a draft is
    /// to reject the draft. Giving it up directly is refused, with a message telling the person to reject it.
    @Test
    void aLearnerWithADraftAwaitingReviewIsGivenUpByRejectingIt() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        scenarios.states.awaitReview(Scenarios.LEARNER, DOC, "a-draft");

        assertThatThrownBy(() -> scenarios.supervisorActions().giveUp(doc, Scenarios.LEARNER, "Retired", "jo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reject it");
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER)).isEmpty();
    }

    private static Script script(final Scenarios scenarios) {
        return scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(1)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        CheckSetting.of(Check.COVERAGE, CheckState.OFF),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.5,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        CheckSetting.of(Check.CONFORMANCE, CheckState.OFF),
                        CheckSetting.of(Check.QUALITY, CheckState.OFF),
                        CheckSetting.of(Check.RULES, CheckState.OFF),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
                .build();
    }

    private static Input stream(final long id) {
        return new Input(id, FEED, "Raw Events", Map.of("Format", "CSV"), CSV.input(), "pipeline-1");
    }
}
