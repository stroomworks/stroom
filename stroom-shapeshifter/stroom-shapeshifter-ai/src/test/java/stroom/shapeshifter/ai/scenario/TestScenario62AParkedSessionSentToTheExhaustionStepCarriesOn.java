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
import stroom.shapeshifter.ai.stage.Attempts.Turn;
import stroom.shapeshifter.ai.stage.Decision.Deferred;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.RegressionSet.Accepted;
import stroom.shapeshifter.ai.stage.RegressionSet.Kind;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Design 02 §5, scenario 62: **a parked session sent to the exhaustion step carries on** (A65, A76;
/// design 04 §4 and §6).
///
/// A session out of its token budget parks for a person with records of the held stream still short. A
/// person sends it to the exhaustion step: the worker carries it on from the turns already recorded, puts
/// no further transform question to the model, and takes the flow's spent transition instead — to the
/// `ADMIT` step, where what the stream still cannot translate is admitted as marked Unknown events. A
/// sending stands on the row until the attempt closes — through the park on the way for the budget the
/// exhaustion step's own question needs, and the grant that reopens it. A flow with nowhere to send it to
/// parks the session again, saying so. A sending to anything but a parked attempt is refused.
class TestScenario62AParkedSessionSentToTheExhaustionStepCarriesOn {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String LOGON_ONLY = Scenarios.resource("csv-logon-only.xsl");
    private static final String ADMITS_LOGOFF = Scenarios.resource("csv-logon-admits-logoff.xsl");
    private static final String BROKEN = "<xsl:stylesheet version=\"2.0\">not a stylesheet";
    private static final int LOGONS = 8;
    private static final int LOGOFFS = 4;
    private static final String STREAM = stream();

    @Test
    void theWorkerSkipsTheTransformsQuestionAndAdmitsAtTheExhaustionStep() {
        final Scenarios scenarios = new Scenarios();
        // Four tokens at a token a question, the budget being the attempt's across its rounds (A5): round
        // one's three questions and round two's chain, and the parser's second question is over it.
        final ShapeshifterAiDoc doc = doc(FlowStep.parse("CONFIGURE transform candidates 1 on spent goto admit"))
                .copy().tokenBudget(4L).build();
        scenarios.documents.put(doc);
        scenarios.inputs.put(streamed(1L));
        final Script silent = Script.of();
        assertThat(scenarios.stage(silent).decide(doc, Scenarios.LEARNER,
                streamed(1L)).decision()).isInstanceOf(Deferred.class);

        final Script learning = scenarios.script(CSV.configuration(), LOGON_ONLY).charging(1L)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(LOGON_ONLY))
                .expect(QuestionMatcher.chain().withFeedbackMentioning("of the held stream fell short"))
                .reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()));
        assertThat(scenarios.worker(learning).advance(10)).isEqualTo(1);
        learning.verifyExhausted();
        final Recorded parked = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(parked.status()).describedAs(String.valueOf(parked.decision())).isEqualTo(AttemptStatus.PARKED);
        assertThat(parked.decision()).contains("budget of 4 tokens is spent");
        assertThat(parked.exhaustNext()).isFalse();

        // Sent to the exhaustion step: the row says so, and the attempt is opened again for the worker.
        // A sending to an attempt that is not parked is refused.
        scenarios.stage(silent).exhaust(doc, parked.id());
        final Recorded sent = scenarios.attempts.byId(parked.id()).orElseThrow();
        assertThat(sent.status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(sent.exhaustNext()).isTrue();
        assertThatThrownBy(() -> scenarios.stage(silent).exhaust(doc, parked.id()))
                .describedAs("not parked any more").isInstanceOf(IllegalStateException.class);

        // Carried on: the budget is spent before the first question, replayed or not, so the session
        // parks again at once — with the sending standing, since nothing has taken it yet.
        final Script stillOver = scenarios.script(CSV.configuration(), LOGON_ONLY).charging(1L);
        assertThat(scenarios.worker(stillOver).advance(10)).isEqualTo(1);
        assertThat(stillOver.asked()).isEmpty();
        final Recorded parkedAgain = scenarios.attempts.byId(parked.id()).orElseThrow();
        assertThat(parkedAgain.status()).isEqualTo(AttemptStatus.PARKED);
        assertThat(parkedAgain.exhaustNext()).describedAs("stands through the park").isTrue();

        // Granted enough to finish, the sending stands through that too: the recorded turns — round
        // one, and round two's chain and parser, whose answers were recorded before the budget refused
        // them — are replayed for nothing, the transform's question is not put, and the flow goes to the
        // exhaustion step, which asks for the admitted form and gets it. The sending is taken, and the
        // closed row no longer carries it.
        scenarios.stage(silent).grant(doc, parked.id(), 10L, 0L);
        assertThat(scenarios.attempts.byId(parked.id()).orElseThrow().exhaustNext())
                .describedAs("stands through the grant").isTrue();
        final Script carryingOn = scenarios.script(CSV.configuration(), LOGON_ONLY).charging(1L)
                .expect(QuestionMatcher.admission("XSLTFilter")).reply(Scenarios.fenced(ADMITS_LOGOFF));
        assertThat(scenarios.worker(carryingOn).advance(10)).isEqualTo(1);
        carryingOn.verifyExhausted();
        final Recorded finished = scenarios.attempts.byId(parked.id()).orElseThrow();
        assertThat(finished.status())
                .describedAs(String.valueOf(finished.decision())).isEqualTo(AttemptStatus.PROMOTED);
        assertThat(finished.exhaustNext()).describedAs("taken, and the row closed").isFalse();
        assertThat(finished.turns()).extracting(Turn::kind).contains(QuestionKind.ADMIT);
        assertThat(finished.turns()).filteredOn(turn -> turn.kind() == QuestionKind.CONFIGURE
                                                        && turn.question().contains("XSLTFilter"))
                .describedAs("the transform was asked once, in round one").hasSize(1);
        final Binding binding = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        final List<Accepted> corpus = scenarios.regressionSet.accepted(binding.getUuid());
        assertThat(corpus).filteredOn(entry -> entry.kind() == Kind.UNKNOWN).hasSize(LOGOFFS);
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId).contains(1L);
    }

    @Test
    void aFlowWithNoSpentTransitionParksTheSessionAgainAndSaysSo() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc(FlowStep.parse("CONFIGURE transform candidates 1"))
                .copy().tokenBudget(4L).build();
        scenarios.documents.put(doc);
        scenarios.inputs.put(streamed(1L));
        final Script silent = Script.of();
        scenarios.stage(silent).decide(doc, Scenarios.LEARNER, streamed(1L));
        final Script learning = scenarios.script(CSV.configuration(), LOGON_ONLY).charging(1L)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(LOGON_ONLY))
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()));
        assertThat(scenarios.worker(learning).advance(10)).isEqualTo(1);
        final Recorded parked = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(parked.status()).isEqualTo(AttemptStatus.PARKED);

        // Sent, and parked again for the budget on the way; then granted enough to go on.
        scenarios.stage(silent).exhaust(doc, parked.id());
        final Script stillOver = scenarios.script(CSV.configuration(), LOGON_ONLY).charging(1L);
        assertThat(scenarios.worker(stillOver).advance(10)).isEqualTo(1);
        assertThat(scenarios.attempts.byId(parked.id()).orElseThrow().status()).isEqualTo(AttemptStatus.PARKED);
        scenarios.stage(silent).grant(doc, parked.id(), 10L, 0L);
        final Script nothing = scenarios.script(CSV.configuration(), LOGON_ONLY).charging(1L);
        assertThat(scenarios.worker(nothing).advance(10)).isEqualTo(1);
        final Recorded again = scenarios.attempts.byId(parked.id()).orElseThrow();
        assertThat(again.status()).isEqualTo(AttemptStatus.PARKED);
        assertThat(again.decision()).contains("Sent to the exhaustion step by a person")
                .contains("no 'on spent' transition");
        assertThat(nothing.asked()).describedAs("no transform's question").isEmpty();
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("round one's fragment stays bound")
                .hasSize(1);
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 1L)).describedAs("and the stream waits").isPresent();
    }

    @Test
    void aSendingCutsTheTransformsCandidatesShortWhereverTheSessionParkedAmongThem() {
        // Two candidates for the transform, and a budget of two tokens: the first candidate's answer is
        // recorded before the budget refuses it, so the session parks with the transform's first
        // question answered and its second never put. A sending is taken at the second — not before the
        // step, where a replayed first would hide it — and the exhaustion step admits what the first
        // candidate, which does not compile, could not.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc(FlowStep.parse("CONFIGURE transform candidates 2 on spent goto admit"))
                .copy().tokenBudget(2L).build();
        scenarios.documents.put(doc);
        scenarios.inputs.put(streamed(1L));
        final Script silent = Script.of();
        scenarios.stage(silent).decide(doc, Scenarios.LEARNER, streamed(1L));
        final Script learning = scenarios.script(CSV.configuration(), LOGON_ONLY).charging(1L)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(BROKEN));
        assertThat(scenarios.worker(learning).advance(10)).isEqualTo(1);
        learning.verifyExhausted();
        final Recorded parked = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(parked.status()).describedAs(String.valueOf(parked.decision())).isEqualTo(AttemptStatus.PARKED);
        assertThat(parked.turns()).filteredOn(turn -> turn.kind() == QuestionKind.CONFIGURE
                                                      && turn.question().contains("XSLTFilter"))
                .describedAs("the first candidate's turn is on the record").hasSize(1);

        scenarios.stage(silent).exhaust(doc, parked.id());
        assertThat(scenarios.worker(Script.of()).advance(10)).describedAs("parked again for the budget").isEqualTo(1);
        scenarios.stage(silent).grant(doc, parked.id(), 10L, 0L);
        final Script carryingOn = scenarios.script(CSV.configuration(), LOGON_ONLY).charging(1L)
                .expect(QuestionMatcher.admission("XSLTFilter")).reply(Scenarios.fenced(ADMITS_LOGOFF));
        assertThat(scenarios.worker(carryingOn).advance(10)).isEqualTo(1);
        carryingOn.verifyExhausted();
        final Recorded finished = scenarios.attempts.byId(parked.id()).orElseThrow();
        assertThat(finished.status())
                .describedAs(String.valueOf(finished.decision())).isEqualTo(AttemptStatus.PROMOTED);
        assertThat(finished.turns()).filteredOn(turn -> turn.kind() == QuestionKind.CONFIGURE
                                                        && turn.question().contains("XSLTFilter"))
                .describedAs("the second candidate was never asked for").hasSize(1);
        assertThat(finished.turns()).extracting(Turn::kind).contains(QuestionKind.ADMIT);
    }

    /// The demo document under a flow whose transform step is as given, and whose sample holds the header
    /// and a few logons.
    private static ShapeshifterAiDoc doc(final FlowStep transform) {
        return DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door")
                .heldOutFraction(0.0)
                .sampleSizeLimit(200)
                .flow(new LearningFlow(List.of(
                        FlowStep.parse("CHAIN"),
                        FlowStep.parse("CONFIGURE parser"),
                        transform,
                        FlowStep.parse("ADMIT candidates 1")), null, null))
                .build();
    }

    private static String stream() {
        final StringBuilder text = new StringBuilder("dt,who,where,what\n");
        for (int i = 0; i < LOGONS + LOGOFFS; i++) {
            text.append("2020-06-17T08:").append(String.format("%02d", i)).append(":00.000Z,user").append(i)
                    .append(",office,").append(i < LOGONS
                            ? "logon"
                            : "logoff").append('\n');
        }
        return text.toString();
    }

    /// The stream as a task has it: the logons as the prefix, the whole to be read as a fragment runs.
    private static Input streamed(final long id) {
        final int prefix = STREAM.indexOf("logoff") - "2020-06-17T08:08:00.000Z,user8,office,".length();
        return Input.streamed(id, FEED, "Raw Events", Map.of(), STREAM.substring(0, prefix), "pipeline-1",
                () -> new ByteArrayInputStream(STREAM.getBytes(StandardCharsets.UTF_8)));
    }
}
