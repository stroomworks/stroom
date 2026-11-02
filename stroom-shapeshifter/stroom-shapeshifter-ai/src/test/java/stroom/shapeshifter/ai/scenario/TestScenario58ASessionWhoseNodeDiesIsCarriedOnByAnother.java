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
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Design 02 §5, scenario 58: **a session whose node dies is carried on by another** (design 04 §4 and
/// its §8 scenarios 13 and 15).
///
/// A worker on one node takes a session and dies in the middle of it — nothing closes the attempt, the
/// turns it had been answered are in the table, its lease runs on. Streams of the learner keep waiting on
/// the session and open no other. Once the lease lapses, a worker on another node finds the attempt,
/// takes it, and re-walks it: the recorded turns are replayed without asking the model, the question the
/// dead node stopped at is asked, and the session is finished — one attempt, promoted, under the second
/// node's name — and every waiting stream is woken.
class TestScenario58ASessionWhoseNodeDiesIsCarriedOnByAnother {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");

    @Test
    void theNextWorkerToFindALapsedSessionCarriesItOnFromItsRecordedTurns() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        scenarios.documents.put(doc);
        scenarios.inputs.put(input(1L));
        final Script silent = Script.of();
        assertThat(scenarios.stage(silent).decide(doc, Scenarios.LEARNER,
                input(1L)).decision()).isInstanceOf(Deferred.class);
        final long session = scenarios.attempts.forDocument(DOC, 10).get(0).id();

        // Node one takes the session and dies at the transform question: the JVM is gone, and nothing
        // records anything against the attempt.
        final Script dying = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(() -> {
                    throw new NodeDied();
                });
        assertThatThrownBy(() -> scenarios.worker(dying).advance(10)).isInstanceOf(NodeDied.class);
        final Recorded left = scenarios.attempts.byId(session).orElseThrow();
        assertThat(left.status()).isEqualTo(AttemptStatus.IN_PROGRESS);
        assertThat(left.attempt().node()).isEqualTo("node-1");
        final int recorded = left.turns().size();
        assertThat(recorded).describedAs("the turns it had been answered, the one it stopped at not among them")
                .isGreaterThanOrEqualTo(2);

        // The learner is still the session's: a second stream waits on it and no other opens; and while
        // the lease runs the worker leaves it to the node that holds it.
        scenarios.inputs.put(input(2L));
        assertThat(scenarios.stage(silent).decide(doc, Scenarios.LEARNER,
                input(2L)).decision()).isInstanceOf(Deferred.class);
        assertThat(scenarios.attempts.forDocument(DOC, 10)).hasSize(1);
        scenarios.node = "node-2";
        assertThat(scenarios.worker(silent).advance(10)).describedAs("not lapsed yet: nobody else's").isZero();

        // The lease lapses and node two's worker finds it: the recorded turns are replayed and only the
        // question the dead node stopped at is put to the model.
        scenarios.attempts.heartbeat(session, Scenarios.NOW.toEpochMilli() - 1);
        final Script carryingOn = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        assertThat(scenarios.worker(carryingOn).advance(10)).isEqualTo(1);
        carryingOn.verifyExhausted();

        final List<Recorded> attempts = scenarios.attempts.forDocument(DOC, 10);
        assertThat(attempts).describedAs("one session, not a second").hasSize(1);
        final Recorded finished = attempts.get(0);
        assertThat(finished.status()).describedAs(String.valueOf(finished.decision()))
                .isEqualTo(AttemptStatus.PROMOTED);
        assertThat(finished.attempt().node()).describedAs("under the node that finished it").isEqualTo("node-2");
        assertThat(finished.turns()).extracting(Turn::number).isSorted();
        assertThat(finished.turns().size()).describedAs("the replayed turns and the one asked").isEqualTo(recorded + 1);
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).hasSize(1);
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId)
                .describedAs("every stream that waited on it is woken").contains(1L, 2L);
        assertThat(scenarios.attempts.lapsed(Scenarios.NOW.toEpochMilli(), 10)).isEmpty();
    }

    private static ShapeshifterAiDoc doc() {
        return DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door").heldOutFraction(0.0).build();
    }

    private static Input input(final long id) {
        return new Input(id, FEED, "Raw Events", Map.of(), CSV.input(), "pipeline-1");
    }

    /// The node's JVM went: not a failure anything catches and records.
    private static final class NodeDied extends Error {

    }
}
