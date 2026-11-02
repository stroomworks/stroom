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
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Deferred;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.RegressionSet.Accepted;
import stroom.shapeshifter.ai.stage.RegressionSet.Kind;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearnedUnknown;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.StepOutcome;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 02 §5, scenario 59: **an exhausted session admits a marked Unknown** (A51–A53, A62, A74–A76;
/// design 04 §4 and its §8 scenarios 4 and 14).
///
/// A session whose transform candidates are spent with records of the held stream still short reaches
/// the flow's exhaustion step, which asks for the transform once more with the admitted form in it: an
/// `EventDetail/Unknown` carrying the marker. The candidate is held to the records that fell short as
/// every transform candidate is, the marked Unknown clears the bar where a bare one does not, the corpus
/// holds the admitted records as admissions, and the stream is served with marked Unknown events among
/// its own. A later stream of the same kinds is served at once and opens no session. A model that offers
/// the admitted form before the exhaustion step is refused it.
class TestScenario59AnExhaustedSessionAdmitsAMarkedUnknown {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String LOGON_ONLY = Scenarios.resource("csv-logon-only.xsl");
    private static final String ADMITS_LOGOFF = Scenarios.resource("csv-logon-admits-logoff.xsl");
    private static final int LOGONS = 8;
    private static final int LOGOFFS = 4;
    private static final String STREAM = stream();

    @Test
    void theExhaustionStepAdmitsWhatTheCandidatesCouldNotTranslateAndTheStreamIsServed() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        scenarios.documents.put(doc);
        scenarios.inputs.put(streamed(1L));
        final Script silent = Script.of();
        assertThat(scenarios.stage(silent).decide(doc, Scenarios.LEARNER,
                streamed(1L)).decision()).isInstanceOf(Deferred.class);

        // Round one learns from the sample of logons and is promoted; the full pass finds the logoffs.
        // Round two is held to them: the model offers the admitted form at the transform's own question —
        // which the sample, holding no logoff, would never have shown — and is refused for it, which
        // spends the transform's one candidate; the flow goes to the exhaustion step, whose question
        // carries the refusal and the admitted form, and the admission clears there.
        final Script script = scenarios.script(CSV.configuration(), LOGON_ONLY)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(LOGON_ONLY))
                .expect(QuestionMatcher.chain().withFeedbackMentioning("of the held stream fell short"))
                .reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(ADMITS_LOGOFF))
                .expect(QuestionMatcher.admission("XSLTFilter")
                        .withFeedbackMentioning("is the exhaustion step's to ask for"))
                .reply(Scenarios.fenced(ADMITS_LOGOFF));
        assertThat(scenarios.worker(script).advance(10)).isEqualTo(1);
        script.verifyExhausted();

        final List<Recorded> attempts = scenarios.attempts.forDocument(DOC, 10);
        assertThat(attempts).hasSize(1);
        final Recorded session = attempts.get(0);
        assertThat(session.status()).describedAs(String.valueOf(session.decision())).isEqualTo(AttemptStatus.PROMOTED);
        assertThat(session.turns()).extracting(Turn::kind).contains(QuestionKind.ADMIT);
        final Binding binding = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        final List<Accepted> corpus = scenarios.regressionSet.accepted(binding.getUuid());
        assertThat(corpus).filteredOn(entry -> entry.kind() == Kind.UNKNOWN)
                .describedAs("the admitted records are in the corpus as admissions (A75)")
                .hasSize(LOGOFFS)
                .allSatisfy(entry -> assertThat(entry.output()).contains(LearnedUnknown.MARKER).contains("logoff"));
        assertThat(corpus).filteredOn(entry -> entry.kind() == Kind.TRANSLATION).isNotEmpty();
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId).contains(1L);

        // Served in full, the admitted records as marked Unknown events.
        final StageRun decided = scenarios.stage(silent).decide(doc, Scenarios.LEARNER, streamed(1L));
        assertThat(decided.decision()).isInstanceOf(Bound.class);
        final TextSink sink = new TextSink();
        assertThat(scenarios.stage(silent).stream(doc, Scenarios.LEARNER, ((Bound) decided.decision()).binding(),
                streamed(1L), sink)
                .clear()).isTrue();
        assertThat(sink.records()).isEqualTo(LOGONS + LOGOFFS);
        assertThat(sink.text()).contains("<Action>Logon</Action>")
                .contains("Name=\"" + LearnedUnknown.MARKER + "\" Value=\"logoff\"");

        // A marked Unknown is not retried (§8 scenario 14): the next stream of the same kinds is served at
        // once and no session opens for it.
        scenarios.inputs.put(streamed(2L));
        final StageRun next = scenarios.stage(silent).decide(doc, Scenarios.LEARNER, streamed(2L));
        assertThat(next.decision()).isInstanceOf(Bound.class);
        assertThat(scenarios.stage(silent).stream(doc, Scenarios.LEARNER, ((Bound) next.decision()).binding(),
                streamed(2L), new TextSink())
                .clear()).isTrue();
        assertThat(scenarios.attempts.forDocument(DOC, 10)).hasSize(1);
        assertThat(silent.asked()).isEmpty();
    }

    @Test
    void theAdmittedFormOfferedBeforeTheExhaustionStepIsRefusedOnTheSampleToo() {
        // The logoffs are in the sample this time, so the admitted form would pass the scorecard: it is
        // refused at the transform's own question all the same, and accepted at the exhaustion step.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc().copy().sampleSizeLimit(100_000).build();
        final Input whole = new Input(1L, FEED, "Raw Events", Map.of(), STREAM, "pipeline-1");
        scenarios.documents.put(doc);
        scenarios.inputs.put(whole);
        final Script silent = Script.of();
        scenarios.stage(silent).decide(doc, Scenarios.LEARNER, whole);
        final Script script = scenarios.script(CSV.configuration(), LOGON_ONLY)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(ADMITS_LOGOFF))
                .expect(QuestionMatcher.admission("XSLTFilter")
                        .withFeedbackMentioning("is the exhaustion step's to ask for"))
                .reply(Scenarios.fenced(ADMITS_LOGOFF));
        assertThat(scenarios.worker(script).advance(10)).isEqualTo(1);
        script.verifyExhausted();

        final Recorded session = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(session.status()).describedAs(String.valueOf(session.decision())).isEqualTo(AttemptStatus.PROMOTED);
        assertThat(session.turns()).filteredOn(turn -> turn.kind() == QuestionKind.CONFIGURE
                                                       && turn.question().contains("XSLTFilter"))
                .extracting(Turn::outcome).containsExactly(StepOutcome.REFUSED);
        assertThat(session.turns()).filteredOn(turn -> turn.kind() == QuestionKind.ADMIT)
                .extracting(Turn::outcome).containsExactly(StepOutcome.PASSED);
    }

    /// The demo document under a flow whose transform has one candidate before the exhaustion step, and
    /// whose sample holds the header and a few logons.
    private static ShapeshifterAiDoc doc() {
        return DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door")
                .heldOutFraction(0.0)
                .sampleSizeLimit(200)
                .flow(new LearningFlow(List.of(
                        FlowStep.parse("CHAIN"),
                        FlowStep.parse("CONFIGURE parser"),
                        FlowStep.parse("CONFIGURE transform candidates 1 on spent goto admit"),
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
