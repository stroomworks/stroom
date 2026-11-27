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
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.ConfigureRole;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StopCause;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Attempts.Turn;
import stroom.learningflow.stage.Decision.Deferred;
import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.RegressionSet.Accepted;
import stroom.learningflow.stage.RegressionSet.Kind;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests a person sending a parked session straight to the flow's exhaustion step.
///
/// A session that runs out of its token budget with records of the held stream still short is parked for a
/// person. The person sends it to the exhaustion step: the worker resumes it from the turns already recorded,
/// asks the model no further transform question, and follows the transform step's "when spent" transition
/// instead, to the `ADMIT` step, where what the stream still cannot translate is admitted as marked Unknown
/// events. The request stays recorded on the attempt until the attempt closes, including through a further
/// park for the budget the exhaustion step's own question needs and the grant that reopens it. If the flow
/// has no such transition, the session is parked again with a reason. Sending an attempt that is not parked
/// is refused.
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
        // Four tokens at one token per question, the budget covering all of the attempt's rounds: enough for
        // round one's three questions and round two's chain question, so round two's parser question goes
        // over it.
        final LearningFlowDoc doc = doc(FlowStep.builder(QuestionKind.CONFIGURE)
                .role(ConfigureRole.TRANSFORM)
                .candidates(1)
                .whenSpentGoTo("admit")
                .build())
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
        assertThat(parked.decision()).startsWith("Ran out of tokens at ");
        assertThat(parked.report().getDetails()).first().asString().startsWith("A run may spend 4 tokens;");
        assertThat(parked.exhaustNext()).isFalse();

        // Sent to the exhaustion step, the attempt records the request and is reopened for the worker.
        // Sending an attempt that is not parked is refused.
        scenarios.supervisorActions().exhaust(doc, parked.id());
        final Recorded sent = scenarios.attempts.byId(parked.id()).orElseThrow();
        assertThat(sent.status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(sent.exhaustNext()).isTrue();
        assertThatThrownBy(() -> scenarios.supervisorActions().exhaust(doc, parked.id()))
                .describedAs("not parked any more").isInstanceOf(IllegalStateException.class);

        // When resumed, the budget is already spent before the first question, replayed or not, so the
        // session parks again at once. The request still stands, since nothing has acted on it yet.
        final Script stillOver = scenarios.script(CSV.configuration(), LOGON_ONLY).charging(1L);
        assertThat(scenarios.worker(stillOver).advance(10)).isEqualTo(1);
        assertThat(stillOver.asked()).isEmpty();
        final Recorded parkedAgain = scenarios.attempts.byId(parked.id()).orElseThrow();
        assertThat(parkedAgain.status()).isEqualTo(AttemptStatus.PARKED);
        assertThat(parkedAgain.exhaustNext()).describedAs("stands through the park").isTrue();

        // Granted enough to finish, the request still stands. The recorded turns (round one, and round two's
        // chain and parser, whose answers were recorded before the budget stopped the session) are replayed at
        // no cost, the transform question is not asked, and the flow goes to the exhaustion step, which asks
        // for the admitted form and gets it. The request is then used up, and the closed attempt no longer
        // carries it.
        scenarios.supervisorActions().grant(doc, parked.id(), 10L, 0L);
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
        final LearningFlowDoc doc = doc(FlowStep.builder(QuestionKind.CONFIGURE)
                .role(ConfigureRole.TRANSFORM)
                .candidates(1)
                .build())
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

        // Sent to the exhaustion step, parked again for the budget, then granted enough to carry on.
        scenarios.supervisorActions().exhaust(doc, parked.id());
        final Script stillOver = scenarios.script(CSV.configuration(), LOGON_ONLY).charging(1L);
        assertThat(scenarios.worker(stillOver).advance(10)).isEqualTo(1);
        assertThat(scenarios.attempts.byId(parked.id()).orElseThrow().status()).isEqualTo(AttemptStatus.PARKED);
        scenarios.supervisorActions().grant(doc, parked.id(), 10L, 0L);
        final Script nothing = scenarios.script(CSV.configuration(), LOGON_ONLY).charging(1L);
        assertThat(scenarios.worker(nothing).advance(10)).isEqualTo(1);
        final Recorded again = scenarios.attempts.byId(parked.id()).orElseThrow();
        assertThat(again.status()).isEqualTo(AttemptStatus.PARKED);
        assertThat(again.decision()).startsWith("Couldn't finish with Unknowns: ")
                .endsWith("has no rule to use the fallback.");
        assertThat(again.report().getCause()).isEqualTo(StopCause.FALLBACK_MISSING);
        assertThat(nothing.asked()).describedAs("no transform's question").isEmpty();
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("round one's fragment stays bound")
                .hasSize(1);
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 1L)).describedAs("and the stream waits").isPresent();
    }

    @Test
    void aSendingCutsTheTransformsCandidatesShortWhereverTheSessionParkedAmongThem() {
        // Two candidates for the transform, and a budget of two tokens. The first candidate's answer is
        // recorded before the budget stops the session, so it parks with the transform's first question
        // answered and its second never asked. The request to go to the exhaustion step is acted on at the
        // second question, not before the transform step, where the replayed first answer would hide it. The
        // exhaustion step then admits what the first candidate, which does not compile, could not translate.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc(FlowStep.builder(QuestionKind.CONFIGURE)
                .role(ConfigureRole.TRANSFORM)
                .candidates(2)
                .whenSpentGoTo("admit")
                .build())
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

        scenarios.supervisorActions().exhaust(doc, parked.id());
        assertThat(scenarios.worker(Script.of()).advance(10)).describedAs("parked again for the budget").isEqualTo(1);
        scenarios.supervisorActions().grant(doc, parked.id(), 10L, 0L);
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

    /// Builds the demo document with a flow using the given transform step, and a sample holding the header
    /// and a few logons.
    private static LearningFlowDoc doc(final FlowStep transform) {
        return DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door")
                .heldOutFraction(0.0)
                .sampleSizeLimit(200)
                .flow(new LearningFlow(List.of(
                        FlowStep.of(QuestionKind.CHAIN),
                        FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.PARSER).build(),
                        transform,
                        FlowStep.builder(QuestionKind.ADMIT).candidates(1).build()), null, null))
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

    /// Builds the stream as a task has it: the header and logons as the prefix, and a supplier that opens the
    /// whole stream for a fragment to run over.
    private static Input streamed(final long id) {
        final int prefix = STREAM.indexOf("logoff") - "2020-06-17T08:08:00.000Z,user8,office,".length();
        return Input.streamed(id, FEED, "Raw Events", Map.of(), STREAM.substring(0, prefix), "pipeline-1",
                () -> new ByteArrayInputStream(STREAM.getBytes(StandardCharsets.UTF_8)));
    }
}
