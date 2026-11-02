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
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Deferred;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.FlowExample;
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

/**
 * Design 02 §5, scenario 30 (A5, A28): deferred learning. A stream for a learner that knows nothing in a deferred
 * document costs its task a sentinel and no model call — the attempt is opened and parked at its first
 * question — and the worker carries it on outside the task. What it promotes wakes its pipeline as any
 * other promotion does, so the streams that waited are tried again.
 */
class TestScenario30DeferredWorker {

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DOC = "doc-1";

    private static ShapeshifterAiDoc deferred() {
        return ShapeshifterAiDoc.builder()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExample.TARGET_FIRST)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .promotionFloor(0.85)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.8, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build();
    }

    private static Input stream(final long id) {
        return new Input(id, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV"), CSV.input());
    }

    private static Script learning(final Scenarios scenarios) {
        return scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    @Test
    void theTaskAsksNothingAndTheWorkerLearnsLater() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = scenarios.documents.put(deferred());
        final Input first = scenarios.inputs.put(stream(1L));
        final Script inTheTask = Script.of();

        final StageRun run = scenarios.stage(inTheTask).decide(doc, Scenarios.LEARNER, first);

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Deferred.class);
        assertThat(inTheTask.asked()).describedAs("no model call in the task: a task decides and never learns (A60)")
                .isEmpty();
        assertThat(run.output()).isNull();
        final Recorded parked = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(parked.status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(parked.turns()).describedAs("nothing answered, and the question it stopped at recorded")
                .hasSize(1);
        assertThat(parked.turns().get(0).answer()).isNull();
        assertThat(parked.turns().get(0).answeredBy()).isNull();
        assertThat(scenarios.deferrals.forDocument(DOC)).describedAs("the stream waits, on a row").hasSize(1);

        // A second stream of the learner while it waits: held behind the same attempt, not a second.
        final Input second = scenarios.inputs.put(stream(2L));
        assertThat(scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER,
                second).decision()).isInstanceOf(Deferred.class);
        assertThat(scenarios.attempts.forDocument(DOC, 10)).describedAs("one attempt for one learner").hasSize(1);
        assertThat(scenarios.deferrals.forDocument(DOC)).hasSize(2);

        // The worker carries it on, against the script the task never asked.
        final Script script = learning(scenarios);
        final int advanced = scenarios.worker(script).advance(10);

        script.verifyExhausted();
        assertThat(advanced).isEqualTo(1);
        final Recorded finished = scenarios.attempts.byId(parked.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(AttemptStatus.PROMOTED);
        assertThat(finished.turns()).describedAs("every turn of it, asked by the worker").isNotEmpty();
        assertThat(finished.turns()).allSatisfy(turn ->
                assertThat(turn.answer()).describedAs("and every one answered now").isNotNull());
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).hasSize(1);

        // And the promotion wakes what waited on the learner.
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId).containsExactly(1L, 2L);

        // Nothing is left awaiting, so a second pass has nothing to do.
        assertThat(scenarios.worker(Script.of()).advance(10)).isZero();
    }

    @Test
    void aStreamOfABoundLearnerIsServedInTheTask() {
        // Once the learner is bound, the task serves it with no model call and no worker.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = scenarios.documents.put(deferred());
        scenarios.inputs.put(stream(1L));
        scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER, stream(1L));
        scenarios.worker(learning(scenarios)).advance(10);

        final Script silent = Script.of();
        final StageRun served = scenarios.process(silent, doc, stream(3L)).learned();

        assertThat(served.decision()).describedAs(served.decision().toString()).isInstanceOf(Bound.class);
        assertThat(silent.asked()).isEmpty();
        assertThat(served.output()).isNotNull();
    }

    @Test
    void aBoundLearnerMarkedByHandIsStillServedByTheTaskWhileItsSessionOpens() {
        // A29, A60 and A100 together: a learner a person marked for relearning is served by its incumbent as
        // every stream of it is, and the task asks nothing; its next stream that brings enough records
        // opens the relearning session, parked for the worker.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = scenarios.documents.put(deferred());
        scenarios.inputs.put(stream(1L));
        scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER, stream(1L));
        scenarios.worker(learning(scenarios)).advance(10);
        final Recorded promoted = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(promoted.status()).isEqualTo(AttemptStatus.PROMOTED);
        scenarios.states.markForRelearning(Scenarios.LEARNER, DOC, "A person's reading of the serving list");

        final Input next = scenarios.inputs.put(stream(5L));
        final Script inTheTask = Script.of();
        final StageRun served = scenarios.stage(inTheTask).decide(doc, Scenarios.LEARNER, next);

        assertThat(served.decision()).describedAs(served.decision().toString()).isInstanceOf(Bound.class);
        assertThat(inTheTask.asked()).isEmpty();
        assertThat(scenarios.attempts.forDocument(DOC, 10)).extracting(Recorded::status)
                .describedAs("the session the mark opened, parked for the worker, beside the one that learned")
                .containsExactlyInAnyOrder(AttemptStatus.PROMOTED, AttemptStatus.AWAITING_MODEL);
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER,
                5L)).describedAs("the stream does not wait on it").isEmpty();
    }

    @Test
    void anAttemptWhoseStreamOrDocumentIsGoneIsAbandonedRatherThanWaitingForEver() {
        // The worker re-walks the conversation over the sample, and the sample is the stream (A45): a stream
        // aged off leaves nothing to carry on from, and a learner whose attempt waits for ever is a learner
        // nothing else may learn.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = scenarios.documents.put(deferred());
        scenarios.inputs.put(stream(1L));
        scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER, stream(1L));
        final Recorded parked = scenarios.attempts.forDocument(DOC, 10).get(0);
        scenarios.inputs.remove(1L);

        final Script script = learning(scenarios);
        assertThat(scenarios.worker(script).advance(10)).isZero();

        assertThat(script.asked()).isEmpty();
        final Recorded abandoned = scenarios.attempts.byId(parked.id()).orElseThrow();
        assertThat(abandoned.status()).isEqualTo(AttemptStatus.ABANDONED);
        assertThat(abandoned.decision()).contains("no longer held");

        // The learner is free again: the next stream of it opens an attempt of its own.
        scenarios.inputs.put(stream(4L));
        scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER, stream(4L));
        assertThat(scenarios.attempts.forDocument(DOC, 10)).hasSize(2);

        // And a document deleted while its attempt waited is the same story.
        scenarios.documents.remove(DOC);
        assertThat(scenarios.worker(Script.of()).advance(10)).isZero();
        assertThat(scenarios.attempts.forDocument(DOC, 10).get(0).decision()).contains("has been deleted");
    }

    @Test
    void aLearnerNobodyWantsLearningAnyMoreIsNotCarriedOnByTheWorker() {
        // An attempt may wait a long while, and the learner is not as it left it: an operator may have
        // turned learning off, or it may have been given up. Whatever the attempt
        // was learning towards is not wanted now, so it is closed rather than carried on into a binding
        // nobody asked for.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = scenarios.documents.put(deferred());
        scenarios.inputs.put(stream(1L));
        scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER, stream(1L));
        final Recorded parked = scenarios.attempts.forDocument(DOC, 10).get(0);
        // A person gives the learner up while it waits.
        scenarios.states.giveUp(Scenarios.LEARNER, DOC, "Not wanted");

        final Script script = learning(scenarios);
        assertThat(scenarios.worker(script).advance(10)).isZero();

        assertThat(script.asked()).describedAs("the model is not asked for a learner nobody wants").isEmpty();
        final Recorded closed = scenarios.attempts.byId(parked.id()).orElseThrow();
        assertThat(closed.status()).isEqualTo(AttemptStatus.ABANDONED);
        assertThat(closed.decision()).contains("Given up: Not wanted");
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("and no binding was written").isEmpty();
    }

    @Test
    void anAttemptWhoseClaimLapsedWhileNobodyCarriedItIsStillCarriedOn() {
        // The claim's expiry says who may carry an attempt, not whether it is still wanted: nobody is
        // behind a parked attempt, so there is nothing to take it from. A worker that was down while the
        // claim lapsed must still pick it up, or the learner waits for a stream that may never come.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = scenarios.documents.put(deferred());
        scenarios.inputs.put(stream(1L));
        scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER, stream(1L));
        final Recorded parked = scenarios.attempts.forDocument(DOC, 10).get(0);
        scenarios.attempts.heartbeat(parked.id(), Scenarios.NOW.toEpochMilli() - 1);

        assertThat(scenarios.worker(learning(scenarios)).advance(10))
                .describedAs("still waiting, and still the only attempt for its learner").isEqualTo(1);

        assertThat(scenarios.attempts.byId(parked.id()).orElseThrow().status())
                .isEqualTo(AttemptStatus.PROMOTED);
    }

    @Test
    void oneBadAttemptDoesNotStopThePass() {
        // A job that stopped at the first attempt it could not carry on would never reach the ones behind
        // it, and a learner that cannot learn would hold up every other.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = scenarios.documents.put(deferred());
        scenarios.inputs.put(stream(1L));
        scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER, stream(1L));
        final Recorded first = scenarios.attempts.forDocument(DOC, 10).get(0);
        scenarios.inputs.remove(1L);

        // A second document, a second pipeline and so a second learner (A127), and an attempt that can be
        // carried on.
        final Learner second = new Learner("pipeline-2", Scenarios.LEARNER.elementId());
        final ShapeshifterAiDoc other = scenarios.documents.put(deferred().copy().uuid("doc-2").build());
        final Input fine = scenarios.inputs.put(new Input(2L, "OTHER-FEED", "Raw Events",
                Map.of("Format", "CSV"), CSV.input(), second.pipelineUuid()));
        scenarios.stage(Script.of()).decide(other, second, fine);

        final int advanced = scenarios.worker(learning(scenarios)).advance(10);

        assertThat(advanced).describedAs("the good one behind the bad").isEqualTo(1);
        assertThat(scenarios.attempts.byId(first.id()).orElseThrow().status())
                .isEqualTo(AttemptStatus.ABANDONED);
        assertThat(scenarios.learnerBindings.forLearner(second)).hasSize(1);
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId).containsExactly(2L);
    }
}
