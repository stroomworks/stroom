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
import stroom.learningflow.stage.Attempts.Turn;
import stroom.learningflow.stage.Decision.Deferred;
import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests that a session whose node dies part-way through is resumed by a worker on another node.
///
/// A worker on one node claims a session and dies in the middle of it. Nothing closes the attempt, the
/// turns already answered are recorded, and its lease stays in force. The learner's streams keep waiting on
/// the session and open no other. Once the lease expires, a worker on another node finds the attempt,
/// claims it and resumes it: the recorded turns are replayed without asking the model, the question the
/// dead node stopped at is asked, and the session finishes as one promoted attempt under the second node's
/// name. Every waiting stream is then woken.
class TestScenario58ASessionWhoseNodeDiesIsCarriedOnByAnother {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");

    @Test
    void theNextWorkerToFindALapsedSessionCarriesItOnFromItsRecordedTurns() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        scenarios.documents.put(doc);
        scenarios.inputs.put(input(1L));
        final Script silent = Script.of();
        assertThat(scenarios.stage(silent).decide(doc, Scenarios.LEARNER,
                input(1L)).decision()).isInstanceOf(Deferred.class);
        final long session = scenarios.attempts.forDocument(DOC, 10).get(0).id();

        // Node one claims the session and dies at the transform question: the JVM is gone, so nothing records
        // anything against the attempt.
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

        // The session still holds the learner: a second stream waits on it and no other session opens. While
        // the lease is in force, other workers leave it to the node that holds it.
        scenarios.inputs.put(input(2L));
        assertThat(scenarios.stage(silent).decide(doc, Scenarios.LEARNER,
                input(2L)).decision()).isInstanceOf(Deferred.class);
        assertThat(scenarios.attempts.forDocument(DOC, 10)).hasSize(1);
        scenarios.node = "node-2";
        assertThat(scenarios.worker(silent).advance(10)).describedAs("not lapsed yet: nobody else's").isZero();

        // The lease expires and node two's worker finds the attempt: the recorded turns are replayed and only
        // the question the dead node stopped at is put to the model.
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

    private static LearningFlowDoc doc() {
        return DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door").heldOutFraction(0.0).build();
    }

    private static Input input(final long id) {
        return new Input(id, FEED, "Raw Events", Map.of(), CSV.input(), "pipeline-1");
    }

    /// Simulates the node's JVM dying, as an `Error` that nothing catches and records.
    private static final class NodeDied extends Error {

    }
}
