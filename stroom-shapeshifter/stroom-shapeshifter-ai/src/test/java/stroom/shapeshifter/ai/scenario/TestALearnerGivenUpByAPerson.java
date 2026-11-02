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

import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Design 06 A115: a person gives a learner up from its row in the learning pipelines list. It learns no
/// more; its streams with nothing serving them error with the reason, and its pipeline is woken so that
/// those waiting come to their end now; what serves it goes on serving; and sending it back undoes it.
class TestALearnerGivenUpByAPerson {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");

    @Test
    void aLearnerNothingServesErrorsItsWaitingStreamsWithTheReason() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        scenarios.deferrals.deferred(Scenarios.LEARNER, DOC, 1L, FEED, "Raw Events", null, "Learning.", 0);

        scenarios.stage(Script.of()).giveUp(doc, Scenarios.LEARNER, "The feed is retired", "jo");

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
        final ShapeshifterAiDoc doc = doc();
        final StageRun learned = scenarios.process(script(scenarios), doc, stream(1L)).learned();
        assertThat(learned.decision()).isInstanceOf(Promoted.class);

        scenarios.stage(Script.of()).giveUp(doc, Scenarios.LEARNER, "Leave it as it is", "jo");

        final Script silent = Script.of();
        assertThat(scenarios.stage(silent).decide(doc, Scenarios.LEARNER, stream(2L)).decision())
                .describedAs("served as before: giving up stops the learning, not the serving")
                .isInstanceOf(Bound.class);
        assertThat(silent.asked()).isEmpty();

        scenarios.stage(Script.of()).relearn(doc, Scenarios.LEARNER, "Sent back to be learned again by jo");
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER)).describedAs("sent back").isEmpty();
    }

    /// A draft is decided before a give-up is read, so a learner with one awaiting review is given up by
    /// rejecting the draft, which discards it; giving it up from its row is refused and says so.
    @Test
    void aLearnerWithADraftAwaitingReviewIsGivenUpByRejectingIt() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        scenarios.states.awaitReview(Scenarios.LEARNER, DOC, "a-draft");

        assertThatThrownBy(() -> scenarios.stage(Script.of()).giveUp(doc, Scenarios.LEARNER, "Retired", "jo"))
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

    private static ShapeshifterAiDoc doc() {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(1)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build();
    }

    private static Input stream(final long id) {
        return new Input(id, FEED, "Raw Events", Map.of("Format", "CSV"), CSV.input(), "pipeline-1");
    }
}
