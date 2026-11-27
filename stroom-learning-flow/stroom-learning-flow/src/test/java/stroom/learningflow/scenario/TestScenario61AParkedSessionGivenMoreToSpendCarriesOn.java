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
import stroom.learningflow.pack.DemoDocument;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Decision.Deferred;
import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.Input;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests that a session parked for running out of budget carries on when a person grants it more.
///
/// A session that runs out of its token budget is parked for a person. The person grants it more tokens,
/// for that attempt alone and without changing the document, and the worker resumes it from the turns
/// already recorded, spending only what the grant allows, and finishes it. The grant stays recorded on the
/// attempt. A grant to an attempt that is not parked is refused.
class TestScenario61AParkedSessionGivenMoreToSpendCarriesOn {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");

    @Test
    void theGrantIsTheAttemptsAloneAndTheWorkerFinishesWithIt() {
        final Scenarios scenarios = new Scenarios();
        // Two tokens to spend, at one token per question: enough for the chain and the parser, so the
        // transform question goes over the budget.
        final LearningFlowDoc doc = DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door")
                .heldOutFraction(0.0)
                .tokenBudget(2L)
                .build();
        scenarios.documents.put(doc);
        scenarios.inputs.put(input(1L));
        final Script silent = Script.of();
        assertThat(scenarios.stage(silent).decide(doc, Scenarios.LEARNER,
                input(1L)).decision()).isInstanceOf(Deferred.class);

        final Script learning = scenarios.script(CSV.configuration(), XSLT).charging(1L)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        assertThat(scenarios.worker(learning).advance(10)).isEqualTo(1);
        final Recorded parked = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(parked.status()).describedAs(String.valueOf(parked.decision())).isEqualTo(AttemptStatus.PARKED);
        assertThat(parked.decision()).startsWith("Ran out of tokens at ");
        assertThat(parked.report().getDetails()).first().asString().startsWith("A run may spend 2 tokens;");
        assertThat(parked.grant().tokens()).isZero();

        // A grant of nothing is refused. A real grant to the parked attempt is recorded and reopens it, after
        // which a further grant is refused because it is no longer parked.
        assertThatThrownBy(() -> scenarios.supervisorActions().grant(doc, parked.id(), 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        scenarios.supervisorActions().grant(doc, parked.id(), 10L, 0L);
        final Recorded reopened = scenarios.attempts.byId(parked.id()).orElseThrow();
        assertThat(reopened.status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(reopened.grant().tokens()).isEqualTo(10L);
        assertThat(scenarios.documents.byUuid(DOC).orElseThrow().getTokenBudget())
                .describedAs("the document's budget is as it was").isEqualTo(2L);
        assertThatThrownBy(() -> scenarios.supervisorActions().grant(doc, parked.id(), 1L, 0L))
                .describedAs("not parked any more").isInstanceOf(IllegalStateException.class);

        // The worker resumes it: the recorded turns are replayed at no cost, the transform is asked, and the
        // session finishes within what was granted.
        final Script carryingOn = scenarios.script(CSV.configuration(), XSLT).charging(1L)
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        assertThat(scenarios.worker(carryingOn).advance(10)).isEqualTo(1);
        carryingOn.verifyExhausted();
        final Recorded finished = scenarios.attempts.byId(parked.id()).orElseThrow();
        assertThat(finished.status())
                .describedAs(String.valueOf(finished.decision())).isEqualTo(AttemptStatus.PROMOTED);
        assertThat(finished.grant().tokens()).describedAs("the grant stands on the row").isEqualTo(10L);
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).hasSize(1);
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId).contains(1L);
    }

    private static Input input(final long id) {
        return new Input(id, FEED, "Raw Events", Map.of(), CSV.input(), "pipeline-1");
    }
}
