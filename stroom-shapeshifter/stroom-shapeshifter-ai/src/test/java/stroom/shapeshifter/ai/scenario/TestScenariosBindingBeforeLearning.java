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

package stroom.shapeshifter.ai.scenario;

import stroom.query.api.ExpressionTerm.Condition;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.learning.Advisor;
import stroom.shapeshifter.ai.learning.Advisors;
import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.learning.Question;
import stroom.shapeshifter.ai.learning.RecordedAdvisor;
import stroom.shapeshifter.ai.stage.Attempts.Attempt;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Attempts.Turn;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Deferred;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Provisional;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearnerBindings;
import stroom.shapeshifter.ai.stage.RecordSkeleton;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Design 02 §5, scenarios 14 and 29, and the draft-binding sentinel of 22: what the stage does
 * with a stream before — or instead of — asking the model. Every scenario here runs with an empty
 * script: a question asked is the failure.
 */
class TestScenariosBindingBeforeLearning {

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String EXPECTED_EVENTS = Scenarios.resource("csv-logon.events.xml");

    /// The document every scenario here runs: learning automatically, keyed as every document now is on
    /// feed and type (design 06 phase J slice 2a).
    private static ShapeshifterAiDoc doorAccess() {
        return Scenarios.document()
                .uuid("doc-1")
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

    /// Another node already learning for this learner, as the claim of A45 has it: the row that a stage meeting
    /// the learner finds instead of taking it.
    private static long heldByAnotherNode(final Scenarios scenarios, final long untilMs) {
        return scenarios.attempts.opened(new Attempt(Scenarios.LEARNER, "doc-1", "DOOR-ACCESS",
                "Raw Events", 99L,
                        "node-2", PromotionMode.AUTOMATIC, untilMs),
                Scenarios.NOW.toEpochMilli()).orElseThrow();
    }

    private static Input stream(final String format) {
        return new Input(1L, "DOOR-ACCESS", "Raw Events", Map.of("Format", format), CSV.input());
    }

    /**
     * Learns a CSV stream (Format=CSV) with a scripted model, so that the learner is bound.
     */
    private static StageRun learnDoorAccess(final Scenarios scenarios, final ShapeshifterAiDoc doc) {
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final StageRun run = scenarios.process(script, doc, stream("CSV")).learned();
        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        return run;
    }

    // Scenarios 25 and 26 are retired with routing (design 06 phase J slice 2a): a learner is one element in
    // one pipeline, so there is no neighbouring learner for an existing binding to fit, and no binding to
    // reserve one.

    @Test
    void scenario29DisabledLearnsNothingAndTheStreamWaits() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc disabled = doorAccess().copy().learningMode(LearningMode.DISABLED).build();

        // A stream nothing serves waits for a person (design 04 §3, the kill switch): no session opens and
        // the model is never asked.
        final Script silent = Script.of();
        final StageRun waiting = scenarios.process(silent, disabled, stream("CSV")).learned();
        assertThat(waiting.decision()).isInstanceOf(Deferred.class);
        assertThat(((Deferred) waiting.decision()).reason()).contains("disabled");
        assertThat(silent.asked()).isEmpty();
        assertThat(scenarios.attempts.forDocument("doc-1", 10)).describedAs("no session opened").isEmpty();
    }

    @Test
    void scenario14TooFewRecordsBindsProvisionallyThenPromotesWhenEnoughArrive() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doorAccess().copy().minRecordsToLearn(10).build();
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));

        // Seven lines — six records and a header — against a minimum of ten: the candidate clears the
        // floor, so it is bound and serves this stream, but provisionally — A14 cannot yet be met.
        final StageRun first = scenarios.process(script, doc, stream("CSV")).learned();
        script.verifyExhausted();
        assertThat(first.decision()).isInstanceOf(Provisional.class);
        final Provisional provisional = (Provisional) first.decision();
        assertThat(provisional.records()).describedAs("the six records the parser cut, not the seven lines")
                .isEqualTo(6);
        assertThat(provisional.required()).isEqualTo(10);
        assertThat(provisional.binding().isProvisional()).isTrue();
        assertThat(provisional.binding().getPromotedTimeMs()).isNull();
        assertThat(first.output()).isEqualTo(EXPECTED_EVENTS);
        assertThat(scenarios.regressionSet.accepted(provisional.binding().getUuid()))
                .describedAs("what was learned for goes in under a provisional binding too (A56): the five "
                             + "records of the sample")
                .hasSize(5);

        // The same learner again with twelve records: the provisional binding serves it and, now that the
        // learner has enough records and clears the floor, is promoted in place.
        final String[] lines = CSV.input().split("\n");
        final StringBuilder twelve = new StringBuilder(lines[0]).append('\n');
        for (int i = 0; i < 2; i++) {
            for (int line = 1; line < lines.length; line++) {
                twelve.append(lines[line]).append('\n');
            }
        }
        final Script silent = Script.of();
        final StageRun second = scenarios.process(silent, first.doc(),
                new Input(2L, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV"), twelve.toString())).learned();

        assertThat(silent.asked()).isEmpty();
        // Served by the provisional binding, which the stream promotes in place as it is served (design 04 §3).
        assertThat(second.decision()).isInstanceOf(Bound.class);
        assertThat(second.output()).describedAs("served").isNotNull();
        final Binding promoted = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(promoted.getUuid()).isEqualTo(provisional.binding().getUuid());
        assertThat(promoted.isProvisional()).isFalse();
        assertThat(promoted.getPromotedTimeMs()).isEqualTo(Scenarios.NOW.toEpochMilli());
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).containsExactly(promoted);
        assertThat(scenarios.regressionSet.accepted(promoted.getUuid()))
                .describedAs("a stream that merely brought enough records learned nothing, and adds nothing")
                .hasSize(5);

        // And a third stream is simply bound.
        final StageRun third = scenarios.process(silent, second.doc(), stream("CSV")).learned();
        assertThat(third.decision()).isInstanceOf(Bound.class);
    }

    @Test
    void aDraftBindingIsNotBoundAndItsLearnerWaits() {
        // The serving half of scenario 22 (A25; design 04 §3): a draft serves nothing; a stream of
        // its learner waits behind it, naming it, and the model is not asked again.
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
        // A42, A45; design 04 §3: one attempt per learner across the cluster, and the open attempt is what
        // says so. The loser's task does not block — that would hold a processing thread for the length of
        // an attempt — it defers, on a row, and the winner's promotion wakes it.
        final Scenarios scenarios = new Scenarios();
        final long held = heldByAnotherNode(scenarios, Long.MAX_VALUE);

        final Script silent = Script.of();
        scenarios.node = "node-1";
        final StageRun run = scenarios.process(silent, doorAccess(), stream("CSV")).learned();

        assertThat(run.decision()).isInstanceOf(Deferred.class);
        assertThat(((Deferred) run.decision()).reason()).contains("Another node is learning for this pipeline");
        assertThat(silent.asked()).describedAs("the model is asked once for a learner, not once per node")
                .isEmpty();
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 1L)).isPresent();
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).isEmpty();

        // That attempt over, the next stream of the learner learns, and the stream that waited is woken.
        scenarios.attempts.closed(held, AttemptStatus.ABANDONED, "The other node gave up", null, null, 0L);
        final StageRun learned = learnDoorAccess(scenarios, doorAccess());

        assertThat(learned.decision()).describedAs(learned.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId).contains(1L);
    }

    @Test
    void twoStagesSharingTheStateAreOneLearner() {
        // Not a lease planted by hand: two stages over one state, as two nodes are, each taking the lease
        // for itself. The first holds it until its binding is written — a second node that took it in between
        // would find no binding for the learner, learn again, and append a second binding for one learner.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doorAccess();
        final Script watching = Script.of();
        scenarios.node = "node-2";
        final Stage other = scenarios.stage(watching);
        scenarios.node = "node-1";

        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        // The other node arrives at the moment the binding is written: the model has been asked and answered,
        // so a lease released when the conversation ended would be free, and it would learn for the learner again.
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
            public void replace(final Learner learner, final String docUuid, final Binding binding) {
                scenarios.learnerBindings.replace(learner, docUuid, binding);
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
        // The holder died mid-attempt: its claim has run out, and the learner is still its — the session is
        // carried on from its recorded turns by the next worker to find it (design 04 §4, scenario 58),
        // not learned again from nothing by the next stream.
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
        // A28: the record outlives the node that made it, and is the same record whichever mode produced
        // it. What it does not carry is the rendered prompt, which holds the stream's own text and waits
        // for redaction (A17, A38).
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
        // Nothing to learn with: the attempt is opened, abandoned and recorded as abandoned.
        final ShapeshifterAiDoc nothingRunnable = doorAccess().copy()
                .allowedElements(List.of("JSONParser"))
                .build();

        scenarios.process(Script.of(), nothingRunnable, stream("CSV")).learned();

        final List<Recorded> recorded = scenarios.attempts.forDocument("doc-1", 10);
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).status()).isEqualTo(AttemptStatus.ABANDONED);
        assertThat(recorded.get(0).bindingUuid()).isNull();

        // A node that does not take the learner opens no attempt of its own: it did not learn anything.
        heldByAnotherNode(scenarios, Long.MAX_VALUE);
        scenarios.process(Script.of(), doorAccess(), stream("CSV")).learned();

        assertThat(scenarios.attempts.forDocument("doc-1", 10))
                .describedAs("two: the abandoned one and the other node's, and none for the loser")
                .hasSize(2);
    }

    @Test
    void whatAnAttemptSpentIsCountedFromOneAdvisor() {
        // The node's Advisors makes a new advisor per call, each counting its own tokens, so an attempt
        // that asked one and read another would record nothing spent — which is what it did.
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        // A fresh advisor per call, as the node's does, each charging as it is asked.
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
                    // Mid-attempt: the chain question is already a row, answered and judged.
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
        // A28 and A45: an attempt parked at a question keeps its claim on the learner, and the answers it
        // already has are not asked again. The conversation keeps no state of its own — it is re-walked with
        // those answers, which re-derives what they produced.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doorAccess();
        scenarios.documents.put(doc);
        scenarios.inputs.put(stream("CSV"));

        // The task opens the session and parks it at its first question (design 04 §3).
        final StageRun parked = scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER, stream("CSV"));
        assertThat(parked.decision()).describedAs(parked.decision().toString()).isInstanceOf(Deferred.class);
        final Recorded waiting = scenarios.attempts.forDocument("doc-1", 10).get(0);
        assertThat(waiting.status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(waiting.turns()).describedAs("the question it stopped at").hasSize(1);
        assertThat(waiting.turns().get(0).answer())
                .describedAs("which nobody has answered, so a person can (A28)").isNull();
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).isEmpty();

        // While it waits it holds the learner: another stream of it learns nothing beside it.
        final Script other = Script.of();
        assertThat(scenarios.process(other, doc, new Input(2L, "DOOR-ACCESS", "Raw Events",
                Map.of("Format", "CSV"), CSV.input())).learned().decision()).isInstanceOf(Deferred.class);
        assertThat(other.asked()).isEmpty();
        assertThat(scenarios.attempts.forDocument("doc-1", 10)).hasSize(1);

        // A person answers the chain, and the worker carries it on: the chain is answered from the
        // record, so the model is asked only what the attempt had not reached.
        scenarios.stage(Script.of()).amend(doc, waiting.id(), waiting.turns().get(0).number(),
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
        // A45: carrying an attempt on is taking its learner's claim. A parked attempt is nobody's — no
        // thread is behind it — so any node's worker may take it; one that is *running* on another node,
        // or one that has finished, would learn beside the node that is learning.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doorAccess();
        final Script started = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter");
        scenarios.process(new StoppingAdvisor(started, 1), doc, stream("CSV")).learned();
        final Recorded waiting = scenarios.attempts.forDocument("doc-1", 10).get(0);

        // Running on another node, whose claim holds.
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

        // Finished, and not carried on either.
        scenarios.node = "node-1";
        final Stage stage = scenarios.stage(Script.of());
        scenarios.attempts.closed(waiting.id(), AttemptStatus.ABANDONED, "Given up", null, null, 0L);
        assertThat(stage.resume(doc, waiting.id(), stream("CSV"), Script.of()).decision())
                .isInstanceOf(Sentinel.class);
    }

    @Test
    void aReplayThatNoLongerFitsTheRecordIsRefusedRatherThanMisfed() {
        // A45: the replay is a check, not an assumption. A document edited while its attempt waited puts
        // different questions, and answering those from the record would judge, configure and possibly
        // bind an answer given to something else.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doorAccess();
        final Script started = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter");
        scenarios.process(new StoppingAdvisor(started, 1), doc, stream("CSV")).learned();
        final Recorded waiting = scenarios.attempts.forDocument("doc-1", 10).get(0);

        // The document now allows an element it did not when the chain was chosen, so the chain question
        // is a different question.
        final ShapeshifterAiDoc edited = doc.copy()
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
        assertThat(refused.decision()).contains("ReplayDiverged");
    }


    // --------------------------------------------------------------------------------


    /// An advisor that answers so many questions and then stops, as a deferred attempt does when its
    /// budget says to wait for the worker (A5, A28).
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


    /// An advisor that charges for what it is asked, as the node's does.
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
