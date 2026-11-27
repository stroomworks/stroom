/*
 * Copyright 2016-2026 Crown Copyright
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
import stroom.learningflow.learning.Advisor;
import stroom.learningflow.learning.Advisors;
import stroom.learningflow.learning.Exchange;
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.learning.Question;
import stroom.learningflow.learning.RecordedAdvisor;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Attempts.Attempt;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Attempts.Turn;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.Deferred;
import stroom.learningflow.stage.Decision.Drafted;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Decision.Sentinel;
import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerBindings;
import stroom.learningflow.stage.RecordSkeleton;
import stroom.learningflow.stage.Stage;
import stroom.learningflow.stage.StageRun;
import stroom.query.api.ExpressionTerm.Condition;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests what the stage does with a stream before, or instead of, asking the model.
///
/// This covers learning being disabled, too few records to promote automatically, a draft binding awaiting
/// review, another node already learning for the learner, and how attempts are recorded and resumed. Most
/// tests check the model is not asked at all, using an empty script, so any question asked is a failure.
class TestScenariosBindingBeforeLearning {

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");

    /// Builds the document every test here uses, learning automatically.
    private static LearningFlowDoc doorAccess() {
        return Scenarios.document()
                .uuid("doc-1")
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

    /// Opens an attempt for the learner on another node, so that a stage meeting the learner finds it already
    /// claimed instead of claiming it.
    ///
    /// @return The ID of the other node's attempt.
    private static long heldByAnotherNode(final Scenarios scenarios, final long untilMs) {
        return scenarios.attempts.opened(new Attempt(Scenarios.LEARNER, "doc-1", "DOOR-ACCESS",
                "Raw Events", 99L,
                        "node-2", PromotionMode.AUTOMATIC, untilMs),
                Scenarios.NOW.toEpochMilli()).orElseThrow();
    }

    private static Input stream(final String format) {
        return new Input(1L, "DOOR-ACCESS", "Raw Events", Map.of("Format", format), CSV.input());
    }

    /// Learns a CSV stream (Format=CSV) with a scripted model, so that the learner is bound.
    private static StageRun learnDoorAccess(final Scenarios scenarios, final LearningFlowDoc doc) {
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final StageRun run = scenarios.process(script, doc, stream("CSV")).learned();
        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        return run;
    }

    // There are no tests of one learner's binding serving or reserving another learner's streams: a learner
    // is one element in one pipeline, so no other learner can share its binding.

    // There is no test of reading a record back from the span the parser recorded, because record spans are
    // not stored. Showing the model the record that caused a fault is done through the regression corpus,
    // which keeps each record's own text.

    @Test
    void scenario29DisabledLearnsNothingAndTheStreamWaits() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc disabled = doorAccess().copy().learningMode(LearningMode.DISABLED).build();

        // With learning disabled, a stream nothing serves waits for a person: no session opens and the model
        // is never asked.
        final Script silent = Script.of();
        final StageRun waiting = scenarios.process(silent, disabled, stream("CSV")).learned();
        assertThat(waiting.decision()).isInstanceOf(Deferred.class);
        assertThat(((Deferred) waiting.decision()).reason()).contains("disabled");
        assertThat(silent.asked()).isEmpty();
        assertThat(scenarios.attempts.forDocument("doc-1", 10)).describedAs("no session opened").isEmpty();
    }

    @Test
    void scenario14TooFewRecordsDraftsForAPersonEvenOnAnAutomaticFlow() {
        // A first result learned from too few records to be judged on records the model never saw is never
        // approved automatically. Even with automatic promotion the result is a draft, and either a person
        // approves it or a later session learns from more data.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doorAccess().copy().minRecordsToLearn(10).build();
        assertThat(doc.getPromotionMode()).isEqualTo(PromotionMode.AUTOMATIC);
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));

        // Seven lines (six records and a header) against a minimum of ten: the candidate passes its checks,
        // but there are not enough records to judge it on, so it waits for a person.
        final StageRun first = scenarios.process(script, doc, stream("CSV")).learned();
        script.verifyExhausted();
        assertThat(first.decision()).isInstanceOf(Drafted.class);
        final Binding draft = ((Drafted) first.decision()).binding();
        assertThat(draft.isDraft()).isTrue();
        assertThat(draft.getPromotedTimeMs()).isNull();
        assertThat(first.output()).describedAs("a draft serves nothing, so this stream waits too").isNull();
        assertThat(scenarios.regressionSet.accepted(draft.getUuid()))
                .describedAs("what was learned for goes in under a draft too (A56): the five records of "
                             + "the sample")
                .hasSize(5);

        // A second stream of the same learner waits behind the draft rather than opening a second session.
        final Script silent = Script.of();
        final StageRun waiting = scenarios.process(silent, first.doc(), stream("CSV")).learned();
        assertThat(waiting.decision()).isInstanceOf(Deferred.class);
        assertThat(((Deferred) waiting.decision()).reason())
                .startsWith("Awaiting review: draft binding " + draft.getUuid());
        assertThat(silent.asked()).isEmpty();

        // A person approves it, and it goes live with the records it was learned from in its corpus, and
        // serves.
        scenarios.promotion().approve(first.doc(), draft.getUuid(), null);
        final Binding live = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(live.getUuid()).isEqualTo(draft.getUuid());
        assertThat(live.isDraft()).isFalse();
        assertThat(live.getPromotedTimeMs()).isEqualTo(Scenarios.NOW.toEpochMilli());

        final StageRun served = scenarios.process(silent, first.doc(), stream("CSV")).learned();
        assertThat(served.decision()).isInstanceOf(Bound.class);
        assertThat(served.output()).describedAs("served by the binding a person approved").isNotNull();
    }

    @Test
    void aDraftBindingIsNotBoundAndItsLearnerWaits() {
        // As in TestScenario22ReviewMode, a draft serves nothing: a stream of its learner waits behind it,
        // naming it, and the model is not asked again.
        final Scenarios scenarios = new Scenarios();
        final StageRun learned = learnDoorAccess(scenarios, doorAccess());
        final Binding live = ((Promoted) learned.decision()).binding();
        final Binding draft = live.copy().draft(true).build();
        scenarios.learnerBindings.replace(Scenarios.LEARNER, "doc-1", draft);

        final Script silent = Script.of();
        final StageRun run = scenarios.process(silent, learned.doc(), stream("CSV")).learned();

        assertThat(run.decision()).isInstanceOf(Deferred.class);
        assertThat(((Deferred) run.decision()).reason())
                .startsWith("Awaiting review: draft binding " + draft.getUuid());
        assertThat(silent.asked()).isEmpty();
        assertThat(run.output()).isNull();
    }

    @Test
    void aSecondNodeMeetingTheSameLearnerWaitsWithoutHoldingAThread() {
        // There is one attempt per learner across the cluster, and the open attempt is what records it. The
        // task on the node that did not get the claim does not block, which would hold a processing thread
        // for the length of an attempt. It defers instead, and the other node's promotion wakes it.
        final Scenarios scenarios = new Scenarios();
        final long held = heldByAnotherNode(scenarios, Long.MAX_VALUE);

        final Script silent = Script.of();
        scenarios.node = "node-1";
        final StageRun run = scenarios.process(silent, doorAccess(), stream("CSV")).learned();

        assertThat(run.decision()).isInstanceOf(Deferred.class);
        assertThat(((Deferred) run.decision()).reason())
                .isEqualTo("Waiting for learning run 1, which is learning on node node-2; this stream takes what it "
                           + "learns.");
        assertThat(silent.asked()).describedAs("the model is asked once for a learner, not once per node")
                .isEmpty();
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 1L)).isPresent();
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).isEmpty();

        // Once that attempt is over, the learner's next stream learns, and the stream that waited is woken.
        scenarios.attempts.closed(held, AttemptStatus.ABANDONED, "The other node gave up", null, null, 0L);
        final StageRun learned = learnDoorAccess(scenarios, doorAccess());

        assertThat(learned.decision()).describedAs(learned.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId).contains(1L);
    }

    @Test
    void twoStagesSharingTheStateAreOneLearner() {
        // Rather than a lease set up by hand, this uses two stages sharing one state, as two nodes do, each
        // taking the lease for itself. The first must hold it until its binding is written; a second node
        // that took it in between would find no binding for the learner, learn again, and append a second
        // binding for the same learner.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doorAccess();
        final Script watching = Script.of();
        scenarios.node = "node-2";
        final Stage other = scenarios.stage(watching);
        scenarios.node = "node-1";

        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        // The other node arrives at the moment the binding is written. The model has been asked and has
        // answered, so if the lease were released when the conversation ended, the other node would learn
        // for the learner again.
        final LearnerBindings watched = new LearnerBindings() {
            @Override
            public List<Binding> forLearner(final Learner learner) {
                return scenarios.learnerBindings.forLearner(learner);
            }

            @Override
            public Optional<Held> byUuid(final String bindingUuid) {
                return scenarios.learnerBindings.byUuid(bindingUuid);
            }

            @Override
            public Binding append(final Learner learner, final String docUuid, final Binding binding) {
                final StageRun meanwhile = scenarios.process(watching, doc, stream("CSV")).learned();
                assertThat(meanwhile.decision()).describedAs(meanwhile.decision().toString())
                        .isInstanceOf(Deferred.class);
                assertThat(watching.asked()).describedAs("it did not ask the model").isEmpty();
                return scenarios.learnerBindings.append(learner, docUuid, binding);
            }

            @Override
            public boolean replace(final Learner learner, final String docUuid, final Binding binding) {
                return scenarios.learnerBindings.replace(learner, docUuid, binding);
            }

            @Override
            public void remove(final Learner learner, final String bindingUuid) {
                scenarios.learnerBindings.remove(learner, bindingUuid);
            }
        };

        final StageRun run = scenarios.process(script, watched, doc, stream("CSV")).learned();

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("one learner, one binding: the loser did not write a second")
                .hasSize(1);
        assertThat(scenarios.stores.pipelines.list()).describedAs("and no orphaned fragment").hasSize(1);
    }

    @Test
    void aClaimThatHasLapsedStillHoldsTheLearner() {
        // The holder died mid-attempt and its claim has expired, but the learner still belongs to that
        // attempt. The session is resumed from its recorded turns by the next worker to find it (as in
        // TestScenario58ASessionWhoseNodeDiesIsCarriedOnByAnother), not learned again from scratch by the
        // next stream.
        final Scenarios scenarios = new Scenarios();
        final long held = heldByAnotherNode(scenarios, Scenarios.NOW.toEpochMilli() - 1);
        final Script silent = Script.of();

        final StageRun run = scenarios.process(silent, doorAccess(), stream("CSV")).learned();

        assertThat(run.decision()).describedAs(run.decision().toString()).isNotInstanceOf(Promoted.class);
        assertThat(silent.asked()).describedAs("nothing is learned over it").isEmpty();
        assertThat(scenarios.attempts.forDocument("doc-1", 10)).extracting(Recorded::id)
                .describedAs("and no attempt opens beside it").containsExactly(held);
    }

    @Test
    void everyAttemptIsRecordedWithItsTurnsAndWhatItCameTo() {
        // The attempt record outlives the node that made it. It does not store the rendered prompt, because
        // that holds the stream's own text, which may need redacting.
        final Scenarios scenarios = new Scenarios();

        final StageRun learned = learnDoorAccess(scenarios, doorAccess());

        final List<Recorded> recorded = scenarios.attempts.forDocument("doc-1", 10);
        assertThat(recorded).hasSize(1);
        final Recorded attempt = recorded.get(0);
        assertThat(attempt.status()).isEqualTo(AttemptStatus.PROMOTED);
        assertThat(attempt.bindingUuid()).isEqualTo(((Promoted) learned.decision()).binding().getUuid());
        assertThat(attempt.score()).isEqualTo(((Promoted) learned.decision()).score());
        assertThat(attempt.attempt().feed()).isEqualTo("DOOR-ACCESS");
        assertThat(attempt.attempt().node()).isEqualTo("node-1");
        assertThat(attempt.turns()).hasSameSizeAs(learned.transcript());
        assertThat(attempt.turns()).extracting(Turn::number)
                .describedAs("every turn of the conversation, in order, including those the structure answered")
                .isEqualTo(IntStream.rangeClosed(1, learned.transcript().size()).boxed().toList());
        assertThat(attempt.turns().get(0).kind()).isEqualTo(QuestionKind.CHAIN);
        assertThat(attempt.turns().get(0).question())
                .describedAs("what was asked, without the stream's own text in it")
                .contains("Chain").contains("DSParser")
                .doesNotContain("jim,warehouse");
        assertThat(attempt.turns().get(0).answer()).isEqualTo("DSParser -> XSLTFilter");
        assertThat(attempt.turns()).allSatisfy(turn ->
                assertThat(turn.answeredBy()).describedAs("nothing but the model answers a turn yet")
                        .isNotNull());
        assertThat(attempt.turns()).extracting(Turn::outcome).containsOnly(StepOutcome.PASSED);
    }

    @Test
    void anAttemptThatBoundNothingSaysSoAndOneWhoseLearnerIsTakenOpensNone() {
        final Scenarios scenarios = new Scenarios();
        // The document allows nothing the stage can learn a translation with, so the attempt is opened and
        // then recorded as abandoned.
        final LearningFlowDoc nothingRunnable = doorAccess().copy()
                .allowedElements(List.of("JSONParser"))
                .build();

        scenarios.process(Script.of(), nothingRunnable, stream("CSV")).learned();

        final List<Recorded> recorded = scenarios.attempts.forDocument("doc-1", 10);
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).status()).isEqualTo(AttemptStatus.ABANDONED);
        assertThat(recorded.get(0).bindingUuid()).isNull();

        // A node that cannot claim the learner opens no attempt of its own, since it learns nothing.
        heldByAnotherNode(scenarios, Long.MAX_VALUE);
        scenarios.process(Script.of(), doorAccess(), stream("CSV")).learned();

        assertThat(scenarios.attempts.forDocument("doc-1", 10))
                .describedAs("two: the abandoned one and the other node's, and none for the loser")
                .hasSize(2);
    }

    @Test
    void whatAnAttemptSpentIsCountedFromOneAdvisor() {
        // The node's `Advisors` makes a new advisor per call, each counting its own tokens. An attempt that
        // asked one advisor and read the count from another would record nothing spent.
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        // A new advisor per call, as the node's `Advisors` makes, each charging as it is asked.
        final Advisors fresh = document -> new CountingAdvisor(script);

        final StageRun run = scenarios.process(fresh, scenarios.learnerBindings, null, doorAccess(), stream("CSV"))
                .learned();

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(scenarios.attempts.forDocument("doc-1", 10).get(0).tokensSpent())
                .describedAs("the attempt asked one advisor, so its charges are the attempt's")
                .isGreaterThan(0L);
    }

    @Test
    void aTurnIsRecordedAsItIsAskedNotOnlyWhenTheAttemptEnds() {
        // The transcript a person most needs is the one from an attempt that did not finish.
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(() -> {
                    // Mid-attempt, the chain question is already recorded, answered and judged.
                    final Recorded far = scenarios.attempts.forDocument("doc-1", 10).get(0);
                    assertThat(far.status()).isEqualTo(AttemptStatus.IN_PROGRESS);
                    assertThat(far.turns()).isNotEmpty();
                    assertThat(far.turns().get(0).kind()).isEqualTo(QuestionKind.CHAIN);
                    assertThat(far.turns().get(0).answer()).isEqualTo("DSParser -> XSLTFilter");
                    assertThat(far.turns().get(0).outcome()).isEqualTo(StepOutcome.PASSED);
                    throw new IllegalStateException("the model fell over");
                });

        assertThatThrownBy(() -> scenarios.process(script, doorAccess(), stream("CSV")).learned())
                .isInstanceOf(IllegalStateException.class);

        final Recorded attempt = scenarios.attempts.forDocument("doc-1", 10).get(0);
        assertThat(attempt.status()).describedAs("an attempt that threw says so rather than vanishing")
                .isEqualTo(AttemptStatus.ERROR);
        assertThat(attempt.decision()).contains("the model fell over");
        assertThat(attempt.turns()).describedAs("with the turns it had got to").isNotEmpty();
    }


    @Test
    void anAttemptThatStopsAtAQuestionIsCarriedOnFromWhatItWasAnswered() {
        // An attempt waiting at a question keeps its claim on the learner, and the questions it already has
        // answers for are not asked again. The conversation keeps no state of its own: it is replayed with
        // those answers, which recreates what they produced.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doorAccess();
        scenarios.documents.put(doc);
        scenarios.inputs.put(stream("CSV"));

        // The task opens the session and leaves it waiting at its first question.
        final StageRun parked = scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER, stream("CSV"));
        assertThat(parked.decision()).describedAs(parked.decision().toString()).isInstanceOf(Deferred.class);
        final Recorded waiting = scenarios.attempts.forDocument("doc-1", 10).get(0);
        assertThat(waiting.status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(waiting.turns()).describedAs("the question it stopped at").hasSize(1);
        assertThat(waiting.turns().get(0).answer())
                .describedAs("which nobody has answered, so a person can (A28)").isNull();
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).isEmpty();

        // While it waits it holds the learner, so another stream of the learner learns nothing alongside it.
        final Script other = Script.of();
        assertThat(scenarios.process(other, doc, new Input(2L, "DOOR-ACCESS", "Raw Events",
                Map.of("Format", "CSV"), CSV.input())).learned().decision()).isInstanceOf(Deferred.class);
        assertThat(other.asked()).isEmpty();
        assertThat(scenarios.attempts.forDocument("doc-1", 10)).hasSize(1);

        // A person answers the chain question, and the attempt is resumed: the chain is answered from the
        // record, so the model is asked only the questions the attempt had not reached.
        scenarios.supervisorActions().amend(doc, waiting.id(), waiting.turns().get(0).number(),
                "DSParser -> XSLTFilter", "a person");
        final Script carrying = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final StageRun resumed = scenarios.stage(carrying).resume(doc, waiting.id(), stream("CSV"), carrying);

        carrying.verifyExhausted();
        assertThat(resumed.decision()).describedAs(resumed.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(carrying.asked()).describedAs("the chain was not asked again")
                .noneMatch(Question.Chain.class::isInstance);
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).hasSize(1);
        final Recorded finished = scenarios.attempts.byId(waiting.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(AttemptStatus.PROMOTED);
        assertThat(finished.turns()).describedAs("every turn, the replayed one and the new")
                .hasSize(resumed.transcript().size());
        assertThat(finished.turns().get(0).answeredBy())
                .describedAs("a replayed turn keeps whoever answered it").isEqualTo("a person");
    }

    @Test
    void anAttemptIsOnlyCarriedOnByWhoeverHoldsIt() {
        // Resuming an attempt means claiming its learner. A waiting attempt has no thread working on it, so
        // any node's worker may claim it. An attempt running on another node must not be resumed, or two
        // nodes would learn at once, and nor must one that has finished.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doorAccess();
        final Script started = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter");
        scenarios.process(new StoppingAdvisor(started, 1), doc, stream("CSV")).learned();
        final Recorded waiting = scenarios.attempts.forDocument("doc-1", 10).get(0);

        // The attempt is running on another node, whose claim is still in force.
        final long until = Scenarios.NOW.toEpochMilli() + 60_000L;
        assertThat(scenarios.attempts.claimed(waiting.id(), "node-1", Scenarios.NOW.toEpochMilli(), until))
                .describedAs("a parked attempt is anyone's to take, which is how the worker takes it")
                .isTrue();
        final Script elsewhere = Script.of();
        scenarios.node = "node-3";
        final StageRun refused = scenarios.stage(Script.of()).resume(doc, waiting.id(), stream("CSV"), elsewhere);

        assertThat(refused.decision()).describedAs(refused.decision().toString()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) refused.decision()).reason()).contains("not this node's to carry on");
        assertThat(elsewhere.asked()).isEmpty();
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).isEmpty();

        // A finished attempt is not resumed either.
        scenarios.node = "node-1";
        final Stage stage = scenarios.stage(Script.of());
        scenarios.attempts.closed(waiting.id(), AttemptStatus.ABANDONED, "Given up", null, null, 0L);
        assertThat(stage.resume(doc, waiting.id(), stream("CSV"), Script.of()).decision())
                .isInstanceOf(Sentinel.class);
    }

    @Test
    void aReplayThatNoLongerFitsTheRecordIsRefusedRatherThanMisfed() {
        // The replay checks that each question matches the record rather than assuming it does. A document
        // edited while its attempt waited asks different questions, and answering those from the record
        // would judge, configure and possibly bind an answer that was given to a different question.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doorAccess();
        final Script started = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter");
        scenarios.process(new StoppingAdvisor(started, 1), doc, stream("CSV")).learned();
        final Recorded waiting = scenarios.attempts.forDocument("doc-1", 10).get(0);

        // The document now allows an element it did not when the chain was chosen, so the chain question
        // is a different question.
        final LearningFlowDoc edited = doc.copy()
                .allowedElements(List.of("DSParser", "XSLTFilter", "JSONParser"))
                .build();
        final Script carrying = Script.of();
        final Stage stage = scenarios.stage(Script.of());

        assertThatThrownBy(() -> stage.resume(edited, waiting.id(), stream("CSV"), carrying))
                .isInstanceOf(RecordedAdvisor.ReplayDiverged.class)
                .hasMessageContaining("cannot be resumed");

        assertThat(carrying.asked()).describedAs("nothing was asked beyond the record either").isEmpty();
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).isEmpty();
        final Recorded refused = scenarios.attempts.byId(waiting.id()).orElseThrow();
        assertThat(refused.status()).describedAs("and the attempt says why it could not go on")
                .isEqualTo(AttemptStatus.ERROR);
        assertThat(refused.decision()).startsWith("The learning run failed: The attempt cannot be resumed");
        assertThat(refused.report().getDetails()).containsExactly("It failed with ReplayDiverged.");
    }


    // --------------------------------------------------------------------------------


    /// An advisor that answers a given number of questions and then stops, leaving the attempt waiting for
    /// the worker, as an attempt opened by a processing task does.
    private static final class StoppingAdvisor implements Advisor {

        private final Advisor delegate;
        private final int answers;
        private int asked;

        private StoppingAdvisor(final Advisor delegate, final int answers) {
            this.delegate = delegate;
            this.answers = answers;
        }

        @Override
        public String ask(final List<Exchange> transcript, final Question question) {
            if (asked++ >= answers) {
                return RecordedAdvisor.awaiting().ask(transcript, question);
            }
            return delegate.ask(transcript, question);
        }
    }


    // --------------------------------------------------------------------------------


    /// An advisor that charges 100 tokens for each question it is asked, as the node's advisor charges.
    private static final class CountingAdvisor implements Advisor {

        private final Advisor delegate;
        private long tokens;

        private CountingAdvisor(final Advisor delegate) {
            this.delegate = delegate;
        }

        @Override
        public String ask(final List<Exchange> transcript, final Question question) {
            tokens += 100;
            return delegate.ask(transcript, question);
        }

        @Override
        public long tokensUsed() {
            return tokens;
        }
    }
}
