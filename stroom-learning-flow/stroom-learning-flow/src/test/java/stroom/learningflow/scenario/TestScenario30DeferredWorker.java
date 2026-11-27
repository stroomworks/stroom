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
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.Deferred;
import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that learning happens in the background worker, never in the processing task.
///
/// When a stream arrives for a learner that has learned nothing, its task defers the stream without calling
/// the model: an attempt is opened and left awaiting the model at its first question, and the background
/// worker resumes it later, outside the task. A promotion by the worker wakes the pipeline like any other
/// promotion, so the streams that waited are processed again.
class TestScenario30DeferredWorker {

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DOC = "doc-1";

    private static LearningFlowDoc deferred() {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST))
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.COVERAGE, CheckState.ADVICE, 0.8, null),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.5,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        CheckSetting.of(Check.CONFORMANCE, CheckState.OFF),
                        CheckSetting.of(Check.QUALITY, CheckState.OFF),
                        CheckSetting.of(Check.RULES, CheckState.OFF),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
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
        final LearningFlowDoc doc = scenarios.documents.put(deferred());
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

        // A second stream for the learner while it waits is held behind the same attempt, not given a new one.
        final Input second = scenarios.inputs.put(stream(2L));
        assertThat(scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER,
                second).decision()).isInstanceOf(Deferred.class);
        assertThat(scenarios.attempts.forDocument(DOC, 10)).describedAs("one attempt for one learner").hasSize(1);
        assertThat(scenarios.deferrals.forDocument(DOC)).hasSize(2);

        // The worker resumes the attempt and asks the model the questions the task never asked.
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

        // The promotion wakes the streams that waited on the learner. Each is woken twice, because the same
        // pass then sweeps for streams that a wake could not reach, and these deferral rows are still present:
        // a deferral is removed when its stream is served, not when it is woken. Waking a task that is already
        // `CREATED` updates no rows, so this costs nothing.
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId)
                .containsExactly(1L, 2L, 1L, 2L);

        // Nothing is left awaiting, so a second pass has nothing to do.
        assertThat(scenarios.worker(Script.of()).advance(10)).isZero();
    }

    @Test
    void aStreamOfABoundLearnerIsServedInTheTask() {
        // Once the learner is bound, the task serves it with no model call and no worker.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = scenarios.documents.put(deferred());
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
        // A learner that a person has marked for relearning is still served by its current binding, and the
        // task asks the model nothing. The learner's next stream with enough records opens the relearning
        // session, which is left awaiting the model for the worker to resume.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = scenarios.documents.put(deferred());
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
        // The worker resumes a session by replaying its conversation over the sample, and the sample comes
        // from the held stream. A stream that has been deleted leaves nothing to resume from, and an attempt
        // left waiting for ever would block the learner from ever learning, since a learner has only one open
        // attempt at a time.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = scenarios.documents.put(deferred());
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

        // The learner is free again, so its next stream opens a new attempt.
        scenarios.inputs.put(stream(4L));
        scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER, stream(4L));
        assertThat(scenarios.attempts.forDocument(DOC, 10)).hasSize(2);

        // An attempt whose document was deleted while it waited is abandoned in the same way.
        scenarios.documents.remove(DOC);
        assertThat(scenarios.worker(Script.of()).advance(10)).isZero();
        assertThat(scenarios.attempts.forDocument(DOC, 10).get(0).decision()).contains("has been deleted");
    }

    @Test
    void aLearnerNobodyWantsLearningAnyMoreIsNotCarriedOnByTheWorker() {
        // An attempt may wait a long time, and meanwhile the learner may have changed: an operator may have
        // turned learning off, or the learner may have been given up. What the attempt was learning is then
        // not wanted, so it is closed rather than resumed into a binding nobody asked for.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = scenarios.documents.put(deferred());
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
        // The claim's expiry decides who may resume an attempt, not whether it is still wanted. No node is
        // working on an attempt that is waiting, so there is nobody to take it from. A worker that was down
        // while the claim expired must still pick it up, or the learner would wait for a stream that may
        // never come.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = scenarios.documents.put(deferred());
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
        // A job that stopped at the first attempt it could not resume would never reach the ones after it,
        // so one learner that cannot learn would hold up every other.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = scenarios.documents.put(deferred());
        scenarios.inputs.put(stream(1L));
        scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER, stream(1L));
        final Recorded first = scenarios.attempts.forDocument(DOC, 10).get(0);
        scenarios.inputs.remove(1L);

        // A second document and a second pipeline, and so a second learner, with an attempt that can be
        // resumed.
        final Learner second = new Learner("pipeline-2", Scenarios.LEARNER.elementId());
        final LearningFlowDoc other = scenarios.documents.put(deferred().copy().uuid("doc-2").build());
        final Input fine = scenarios.inputs.put(new Input(2L, "OTHER-FEED", "Raw Events",
                Map.of("Format", "CSV"), CSV.input(), second.pipelineUuid()));
        scenarios.stage(Script.of()).decide(other, second, fine);

        final int advanced = scenarios.worker(learning(scenarios)).advance(10);

        assertThat(advanced).describedAs("the good one behind the bad").isEqualTo(1);
        assertThat(scenarios.attempts.byId(first.id()).orElseThrow().status())
                .isEqualTo(AttemptStatus.ABANDONED);
        assertThat(scenarios.learnerBindings.forLearner(second)).hasSize(1);
        // Woken twice: once by the promotion, and once by the same pass's sweep for streams a wake could not
        // reach.
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId).containsExactly(2L, 2L);
    }
}
