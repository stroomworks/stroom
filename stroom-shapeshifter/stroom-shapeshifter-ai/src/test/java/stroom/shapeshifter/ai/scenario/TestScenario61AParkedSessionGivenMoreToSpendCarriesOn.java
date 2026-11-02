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
import stroom.shapeshifter.ai.pack.DemoDocument;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Decision.Deferred;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Design 02 §5, scenario 61: **a parked session given more to spend carries on** (A65, A77; design 04
/// §4 and §6).
///
/// A session out of its token budget parks for a person. A person grants it more — against the attempt
/// alone, the document untouched — and the worker carries it on from the turns already recorded, spending
/// only what the grant allows, and finishes it. The grant stands on the row. A grant to anything but a
/// parked attempt is refused.
class TestScenario61AParkedSessionGivenMoreToSpendCarriesOn {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");

    @Test
    void theGrantIsTheAttemptsAloneAndTheWorkerFinishesWithIt() {
        final Scenarios scenarios = new Scenarios();
        // Two tokens to spend, at a token a question: the chain and the parser, and the transform's
        // question is over the budget.
        final ShapeshifterAiDoc doc = DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door")
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
        assertThat(parked.decision()).contains("budget of 2 tokens is spent");
        assertThat(parked.grant().tokens()).isZero();

        // A grant to a running or finished attempt is refused; to the parked one it is recorded and the
        // attempt opened again.
        assertThatThrownBy(() -> scenarios.stage(silent).grant(doc, parked.id(), 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        scenarios.stage(silent).grant(doc, parked.id(), 10L, 0L);
        final Recorded reopened = scenarios.attempts.byId(parked.id()).orElseThrow();
        assertThat(reopened.status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(reopened.grant().tokens()).isEqualTo(10L);
        assertThat(scenarios.documents.byUuid(DOC).orElseThrow().getTokenBudget())
                .describedAs("the document's budget is as it was").isEqualTo(2L);
        assertThatThrownBy(() -> scenarios.stage(silent).grant(doc, parked.id(), 1L, 0L))
                .describedAs("not parked any more").isInstanceOf(IllegalStateException.class);

        // Carried on: the recorded turns are replayed for nothing, the transform is asked, and the
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
