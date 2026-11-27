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
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.pack.DemoDocument;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Attempts.Turn;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.Deferred;
import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Stage;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that a session is only done when the whole held stream (the stream that opened it) clears the bar,
/// not just the sample the model was shown.
///
/// The stream has twelve records whose logoffs all lie beyond the sample. The first candidate translates
/// only logons, clears the sample and is promoted. The worker's full pass over the stream then finds the
/// records that fell short, shows them to the model as the parser split them along with what the transform
/// made of them, and asks again. The second candidate translates both, and the full pass clears. The session
/// ends with one attempt, one binding, one fragment edited in place, and the stream woken. A session that
/// runs out of candidates is parked for a person: it keeps its claim on the learner, its stream waits, and
/// the worker leaves it alone.
class TestScenario57TheSessionRunsTheHeldStreamInFull {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String TRANSLATES_BOTH = Scenarios.resource("csv-logon.xsl");
    private static final String LOGON_ONLY = Scenarios.resource("csv-logon-only.xsl");
    private static final String BROKEN = "<xsl:stylesheet version=\"2.0\">not a stylesheet";

    /// Twelve records: eight logons, then four logoffs that the sample never reaches.
    private static final String STREAM = stream();

    @Test
    void theFullPassFindsWhatTheSampleDidNotAndTheSessionLearnsForItInOneAttempt() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        scenarios.documents.put(doc);
        scenarios.inputs.put(input(1L, STREAM));
        final Script silent = Script.of();
        final StageRun first = task(scenarios, silent).decide(doc, Scenarios.LEARNER, input(1L, STREAM));
        assertThat(first.decision()).isInstanceOf(Deferred.class);

        // Round one learns from the sample, where every record is a logon. The full pass then finds the
        // logoffs, and round two is asked with them included.
        final Script script = scenarios.script(CSV.configuration(), LOGON_ONLY)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(LOGON_ONLY))
                .expect(QuestionMatcher.chain()
                        .withFeedbackMentioning("of the held stream fell short of the bar")
                        .withFeedbackMentioning("The record as the parser cut it")
                        .withFeedbackMentioning("logoff"))
                .reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(TRANSLATES_BOTH));
        assertThat(scenarios.worker(script).advance(10)).isEqualTo(1);
        script.verifyExhausted();

        final List<Recorded> attempts = scenarios.attempts.forDocument(DOC, 10);
        assertThat(attempts).describedAs("one session, carried to its end (A64)").hasSize(1);
        assertThat(attempts.get(0).status()).isEqualTo(AttemptStatus.PROMOTED);
        // Both rounds' turns belong to the one attempt, numbered on from one round to the next, so that
        // replaying the session replays it all in order and no round overwrites an earlier one.
        final List<Turn> turns = attempts.get(0).turns();
        assertThat(turns).extracting(Turn::number)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, turns.size()).boxed().toList());
        assertThat(turns).filteredOn(turn -> turn.question().startsWith("Configure: XSLTFilter"))
                .describedAs("the transform was asked for in each round").hasSize(2);
        final Binding binding = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(scenarios.stores.pipelines.list()).describedAs("one fragment, edited in place").hasSize(1);
        assertThat(scenarios.snapshots.forBinding(binding.getUuid())).describedAs("two promotions").hasSize(2);
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId).contains(1L);

        // The woken task serves the stream in full.
        final StageRun decided = task(scenarios, silent).decide(doc, Scenarios.LEARNER, input(1L, STREAM));
        assertThat(decided.decision()).describedAs(decided.decision().toString()).isInstanceOf(Bound.class);
        final TextSink sink = new TextSink();
        assertThat(task(scenarios, silent).stream(doc, Scenarios.LEARNER, ((Bound) decided.decision()).binding(),
                input(1L, STREAM), sink)
                .clear()).isTrue();
        assertThat(sink.records()).isEqualTo(12);
        assertThat(sink.text()).contains("<Action>Logoff</Action>");
        assertThat(scenarios.deferrals.forDocument(DOC)).isEmpty();
    }

    /// A session out of candidates is parked and keeps its claim on the learner. Its stream stays deferred,
    /// no second session opens for the learner, and the worker leaves it alone until a person acts.
    @Test
    void aSessionOutOfCandidatesParksForAPersonAndHoldsItsLearner() {
        final Scenarios scenarios = new Scenarios();
        // The direct flow with one candidate per step, so the session has one try at the transform.
        final LearningFlowDoc doc = doc().copy().flow(FlowExamples.flow(FlowExample.DIRECT)).maxAttempts(1).build();
        scenarios.documents.put(doc);
        scenarios.inputs.put(input(1L, STREAM));
        final Script silent = Script.of();
        task(scenarios, silent).decide(doc, Scenarios.LEARNER, input(1L, STREAM));

        // The one candidate does not compile, and nor does the one the exhaustion step asks for, so the
        // session has nothing left to try.
        final Script script = scenarios.script(CSV.configuration(), LOGON_ONLY)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(BROKEN))
                .expect(QuestionMatcher.admission("XSLTFilter")).reply(Scenarios.fenced(BROKEN));
        assertThat(scenarios.worker(script).advance(10)).isEqualTo(1);
        script.verifyExhausted();

        final Recorded parked = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(parked.status()).describedAs(String.valueOf(parked.decision())).isEqualTo(AttemptStatus.PARKED);
        assertThat(parked.decision()).isNotBlank();
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).describedAs("nothing was bound").isEmpty();
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER))
                .describedAs("and the learner is not given up")
                .isEmpty();
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 1L)).describedAs("the stream waits").isPresent();

        // The worker leaves it alone, and a task for the learner waits on it without opening a second session.
        assertThat(scenarios.worker(script).advance(10)).isZero();
        final StageRun again = task(scenarios, silent).decide(doc, Scenarios.LEARNER, input(2L, STREAM));
        assertThat(again.decision()).isInstanceOf(Deferred.class);
        assertThat(scenarios.attempts.forDocument(DOC, 10)).hasSize(1);
        assertThat(scenarios.attempts.awaiting(10)).isEmpty();

        // A person's answer lets it carry on. They answer the stylesheet turn themselves, the worker replays
        // the session with that answer, the full pass clears and both streams are woken.
        final int stylesheetTurn = parked.turns().get(parked.turns().size() - 1).number();
        scenarios.supervisorActions().amend(doc, parked.id(), stylesheetTurn, Scenarios.fenced(TRANSLATES_BOTH),
                "a person");
        assertThat(scenarios.attempts.awaiting(10)).extracting(Recorded::id).contains(parked.id());
        assertThat(scenarios.worker(silent).advance(10)).isEqualTo(1);
        assertThat(silent.asked()).describedAs("nothing is asked again: the turns are replayed").isEmpty();
        final Recorded carried = scenarios.attempts.byId(parked.id()).orElseThrow();
        assertThat(carried.status()).describedAs(String.valueOf(carried.decision())).isEqualTo(AttemptStatus.PROMOTED);
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).hasSize(1);
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId).contains(1L, 2L);
    }

    private static Stage task(final Scenarios scenarios, final Script silent) {
        return scenarios.stage(document -> silent, scenarios.learnerBindings);
    }

    /// Builds the demo document, with the sample the model is shown limited to the header and the first few
    /// logons.
    private static LearningFlowDoc doc() {
        return DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door")
                .heldOutFraction(0.0)
                .sampleSizeLimit(200)
                .build();
    }

    private static String stream() {
        final StringBuilder text = new StringBuilder("dt,who,where,what\n");
        for (int i = 0; i < 12; i++) {
            text.append("2020-06-17T08:").append(String.format("%02d", i)).append(":00.000Z,user").append(i)
                    .append(",office,").append(i < 8
                            ? "logon"
                            : "logoff").append('\n');
        }
        return text.toString();
    }

    /// Builds the stream as a task has it: a text prefix (here the header and the logons), and a supplier
    /// that opens the whole stream for a fragment to run over.
    private static Input input(final long id, final String data) {
        final int prefix = data.indexOf("logoff") - "2020-06-17T08:08:00.000Z,user8,office,".length();
        return Input.streamed(id, FEED, "Raw Events", Map.of(), data.substring(0, prefix), "pipeline-1",
                () -> new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
    }
}
