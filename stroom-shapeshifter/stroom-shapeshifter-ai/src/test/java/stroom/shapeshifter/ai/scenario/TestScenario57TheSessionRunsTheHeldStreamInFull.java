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
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 02 §5, scenario 57: **the session runs the held stream in full** (A64, A65, A73; design 04 §4
/// and its §8 scenarios 1 and 12).
///
/// The held stream is the acceptance test (A50). A stream of twelve records whose logoffs all lie beyond
/// the sample the model is shown: the first candidate translates logons alone and clears the sample, is
/// promoted, and the worker's full pass over the stream finds the records that fell short — as the
/// parser cut them, with what the transform made of them — and puts them to the model as the next
/// question's evidence. The second candidate translates both; the full pass clears; the session ends
/// with one attempt, one binding, one fragment edited in place, and the stream woken. A session that cannot
/// finish — out of candidates — parks for a person: its learner held, its stream waiting, the worker
/// leaving it alone.
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
        final ShapeshifterAiDoc doc = doc();
        scenarios.documents.put(doc);
        scenarios.inputs.put(input(1L, STREAM));
        final Script silent = Script.of();
        final StageRun first = task(scenarios, silent).decide(doc, Scenarios.LEARNER, input(1L, STREAM));
        assertThat(first.decision()).isInstanceOf(Deferred.class);

        // Round one learns from the sample, where every record is a logon; the full pass then finds the
        // logoffs and round two is asked with them in front of it.
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
        // Both rounds' turns are the attempt's, numbered on from one round to the next, so that a re-walk
        // replays the whole session in order and no round writes over an earlier one.
        final List<Turn> turns = attempts.get(0).turns();
        assertThat(turns).extracting(Turn::number)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, turns.size()).boxed().toList());
        assertThat(turns).filteredOn(turn -> turn.question().startsWith("Configure: XSLTFilter"))
                .describedAs("the transform was asked for in each round").hasSize(2);
        final Binding binding = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(scenarios.stores.pipelines.list()).describedAs("one fragment, edited in place").hasSize(1);
        assertThat(scenarios.snapshots.forBinding(binding.getUuid())).describedAs("two promotions").hasSize(2);
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId).contains(1L);

        // The stream is served in full by the task that wakes.
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

    /// §8 scenario 12: a session out of candidates parks and holds. Its stream stays deferred, no second
    /// session opens for the learner, and the worker leaves it alone.
    @Test
    void aSessionOutOfCandidatesParksForAPersonAndHoldsItsLearner() {
        final Scenarios scenarios = new Scenarios();
        // The direct flow with one candidate a step: the session has one try at the transform.
        final ShapeshifterAiDoc doc = doc().copy().flow(FlowExample.DIRECT).maxAttempts(1).build();
        scenarios.documents.put(doc);
        scenarios.inputs.put(input(1L, STREAM));
        final Script silent = Script.of();
        task(scenarios, silent).decide(doc, Scenarios.LEARNER, input(1L, STREAM));

        // The one candidate does not compile, nor does the one the exhaustion step asks for: the session
        // has nothing left to try.
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

        // The worker leaves it alone; a task of the learner waits on it and opens no second session.
        assertThat(scenarios.worker(script).advance(10)).isZero();
        final StageRun again = task(scenarios, silent).decide(doc, Scenarios.LEARNER, input(2L, STREAM));
        assertThat(again.decision()).isInstanceOf(Deferred.class);
        assertThat(scenarios.attempts.forDocument(DOC, 10)).hasSize(1);
        assertThat(scenarios.attempts.awaiting(10)).isEmpty();

        // A person's answer carries it on (A28, A65): the stylesheet answered instead, the worker re-walks
        // the session from what it has now been told, the full pass clears and both streams are woken.
        final int stylesheetTurn = parked.turns().get(parked.turns().size() - 1).number();
        task(scenarios, silent).amend(doc, parked.id(), stylesheetTurn, Scenarios.fenced(TRANSLATES_BOTH), "a person");
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

    /// The demo document, with the sample the model is shown cut to the header and the first few logons.
    private static ShapeshifterAiDoc doc() {
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

    /// The stream as a task has it (design 04 §3): the prefix as text — here the header and the logons —
    /// and the whole of it to be read as a fragment runs over it.
    private static Input input(final long id, final String data) {
        final int prefix = data.indexOf("logoff") - "2020-06-17T08:08:00.000Z,user8,office,".length();
        return Input.streamed(id, FEED, "Raw Events", Map.of(), data.substring(0, prefix), "pipeline-1",
                () -> new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
    }
}
