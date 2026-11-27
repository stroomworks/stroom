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

import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.shared.AttemptCriteria;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.BusinessRulesParameters;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.ExtractionQualityParameters;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.XPathAssertion;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Attempts.Attempt;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Attempts.Turn;
import stroom.learningflow.stage.Decision.GivenUp;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests a person answering a turn of a session in place of the model, so that they can rescue a session.
///
/// In the main case, the model's splitter keeps only half of each record and the attempt gives up at its
/// candidate limit. A person replaces the model's configuration with their own ("edit and re-run from here")
/// and the attempt runs again from that turn: the turns before it are replayed from the record, the person's
/// answer is used where the model's was, and the model is asked only the questions that come after. The
/// transcript records who answered each turn.
class TestScenario31APersonAnswersATurn {

    private static final String FOUR_FIELDS = Scenarios.resource("csv-fields.ds3.xml");
    private static final String TWO_FIELDS = Scenarios.resource("csv-two-fields.ds3.xml");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DOC = "doc-1";
    private static final String OPERATOR = "an.operator";

    @Test
    void scenario31APersonsAnswerReplacesTheModelsAndTheAttemptRunsAgain() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = scenarios.documents.put(doc());
        final Input input = scenarios.inputs.put(stream(1L));
        // Only one candidate is allowed, and the model spends it on a splitter that drops fields the event
        // needs.
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(TWO_FIELDS));

        final StageRun given = scenarios.process(script, doc, input).learned();

        script.verifyExhausted();
        assertThat(given.decision()).describedAs(given.decision().toString()).isInstanceOf(GivenUp.class);
        final Recorded abandoned = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(abandoned.status()).isEqualTo(AttemptStatus.ABANDONED);
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER)).isPresent();
        final Turn theirs = abandoned.turns().get(abandoned.turns().size() - 1);
        assertThat(theirs.kind()).describedAs("the turn the attempt came apart on")
                .isEqualTo(QuestionKind.CONFIGURE);
        final String model = abandoned.turns().get(0).answeredBy();
        assertThat(theirs.answeredBy()).describedAs("answered by the model, as every turn was so far")
                .isEqualTo(model);

        // A person puts their own configuration in its place. Nothing runs yet: the answer is stored and the
        // attempt waits for the worker, because a person's web request must not wait on a model.
        final Script carrying = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        scenarios.supervisorActions().amend(doc, abandoned.id(), theirs.number(), Scenarios.fenced(FOUR_FIELDS),
                OPERATOR);

        assertThat(carrying.asked()).describedAs("amending asks nothing").isEmpty();
        final Recorded waiting = scenarios.attempts.byId(abandoned.id()).orElseThrow();
        assertThat(waiting.status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(waiting.turns()).describedAs("the turns up to and including the one answered")
                .hasSize(theirs.number());
        assertThat(waiting.turns().get(theirs.number() - 1).answeredBy()).isEqualTo(OPERATOR);
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER))
                .describedAs("re-running says the learner is not settled after all").isEmpty();

        // The worker resumes the attempt: everything before the person's turn is replayed, their answer is
        // used where the model's was, and only the questions after it are asked.
        assertThat(scenarios.worker(carrying).advance(10)).isEqualTo(1);

        carrying.verifyExhausted();
        assertThat(carrying.asked()).describedAs("only the transform, which the attempt had not reached")
                .hasSize(1);
        final Recorded promoted = scenarios.attempts.byId(abandoned.id()).orElseThrow();
        assertThat(promoted.status()).describedAs(String.valueOf(promoted.decision()))
                .isEqualTo(AttemptStatus.PROMOTED);
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).hasSize(1);
        assertThat(promoted.turns()).extracting(Turn::answeredBy)
                .describedAs("the transcript says who answered each turn (A28)")
                .containsSubsequence(model, OPERATOR, model);
    }

    @Test
    void aTurnAnAttemptStoppedAtIsRecordedUnansweredSoThatAPersonCanAnswerIt() {
        // An attempt opened by a task asks nobody and stops at its first question. That question is recorded
        // as a turn with no answer and no answerer, so that a person has something to answer.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = scenarios.documents.put(doc().copy()
                .build());
        scenarios.inputs.put(stream(1L));

        // The task opens the session without asking the model, because learning never runs on a processing
        // thread. It stops at the first question, recorded for whoever will answer it.
        scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER, stream(1L));

        final Recorded parked = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(parked.status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(parked.turns()).hasSize(1);
        final Turn stopped = parked.turns().get(0);
        assertThat(stopped.number()).isEqualTo(1);
        assertThat(stopped.kind()).isEqualTo(QuestionKind.CHAIN);
        assertThat(stopped.question()).describedAs("what is being waited for").contains("choose from");
        assertThat(stopped.answer()).isNull();
        assertThat(stopped.answeredBy()).describedAs("nobody has answered it").isNull();

        // A person answers it instead, and the attempt carries on from their answer.
        final Script carrying = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        scenarios.supervisorActions().amend(doc, parked.id(), 1, "DSParser -> XSLTFilter", OPERATOR);

        assertThat(scenarios.worker(carrying).advance(10)).isEqualTo(1);

        carrying.verifyExhausted();
        assertThat(carrying.asked()).describedAs("the chain was not asked: a person had answered it")
                .noneMatch(stroom.learningflow.learning.Question.Chain.class::isInstance);
        assertThat(scenarios.attempts.byId(parked.id()).orElseThrow().status())
                .isEqualTo(AttemptStatus.PROMOTED);
        assertThat(scenarios.attempts.byId(parked.id()).orElseThrow().turns().get(0).answeredBy())
                .isEqualTo(OPERATOR);
    }

    @Test
    void aTurnNumberThatNamesNothingChangesNothing() {
        // For a mistyped turn number, or a stale one from an out-of-date view, the attempt must be left
        // exactly as it was. Reopening it first and failing afterwards would wipe its outcome while telling
        // the person their request had failed.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = scenarios.documents.put(doc());
        scenarios.inputs.put(stream(1L));
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(TWO_FIELDS));
        scenarios.process(script, doc, stream(1L)).learned();
        final Recorded abandoned = scenarios.attempts.forDocument(DOC, 10).get(0);

        assertThatThrownBy(() -> scenarios.supervisorActions()
                .amend(doc, abandoned.id(), 99, Scenarios.fenced(FOUR_FIELDS), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no turn 99");

        final Recorded after = scenarios.attempts.byId(abandoned.id()).orElseThrow();
        assertThat(after.status()).describedAs("still what it was").isEqualTo(abandoned.status());
        assertThat(after.decision()).isEqualTo(abandoned.decision());
        assertThat(after.turns()).describedAs("with every turn it had").hasSameSizeAs(abandoned.turns());
        assertThat(scenarios.attempts.awaiting(10))
                .describedAs("and the worker has nothing to pick up").isEmpty();

        // The same applies to the wrong document. An attempt belongs to the document that opened it, and
        // resetting a learner through another document would clear state that has nothing to do with it.
        final LearningFlowDoc other = doc().copy().uuid("doc-2").build();
        assertThatThrownBy(() -> scenarios.supervisorActions()
                .amend(other, abandoned.id(), 1, "DSParser -> XSLTFilter", OPERATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to document");
    }

    @Test
    void anAttemptBeingWalkedNowIsNotAmendedUnderneathTheWalk() {
        // While a node is working through an attempt, only that node records its answers. A person's answer
        // written into it would be overwritten by the next turn the node records, even though the person had
        // been told it was accepted.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = scenarios.documents.put(doc().copy()
                .build());
        scenarios.inputs.put(stream(1L));
        scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER, stream(1L));
        final Recorded parked = scenarios.attempts.forDocument(DOC, 10).get(0);
        // A worker claims the attempt and is working through it now.
        assertThat(scenarios.attempts.claimed(parked.id(), "node-1", Scenarios.NOW.toEpochMilli(),
                Scenarios.NOW.toEpochMilli() + 60_000L)).isTrue();

        assertThatThrownBy(() -> scenarios.supervisorActions()
                .amend(doc, parked.id(), 1, "DSParser -> XSLTFilter", OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("being carried on now");

        // The node working on it stops without finishing. Once its claim expires, the answer is accepted.
        scenarios.attempts.heartbeat(parked.id(), Scenarios.NOW.toEpochMilli() - 1);
        scenarios.supervisorActions().amend(doc, parked.id(), 1, "DSParser -> XSLTFilter", OPERATOR);

        final Recorded amended = scenarios.attempts.byId(parked.id()).orElseThrow();
        assertThat(amended.status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(amended.turns().get(0).answeredBy()).isEqualTo(OPERATOR);
        assertThat(amended.attempt().expiryMs())
                .describedAs("and its claim is pushed out, or the next stream of the learner would sweep it "
                             + "away before the worker reached it")
                .isGreaterThan(Scenarios.NOW.toEpochMilli());
    }

    /// Amending an attempt removes the draft it wrote, along with the turns after the one answered.
    ///
    /// Those turns are discarded because they followed from an answer that has changed, and the draft binding
    /// is the last thing that followed from them. If it were left in place, nothing would refer to it: the
    /// reset clears the learner's record of which draft awaits review, so approving or rejecting it would
    /// fail, every stream of the learner would be errored because of it, and the only way out would be to
    /// delete the binding by hand.
    @Test
    void amendingAnAttemptTakesBackWhatItDrafted() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = scenarios.documents.put(doc().copy()
                .promotionMode(PromotionMode.REVIEW)
                .build());
        scenarios.inputs.put(stream(1L));
        final Script model = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        scenarios.process(model, doc, stream(1L)).learned();
        final Recorded drafted = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(drafted.status()).isEqualTo(AttemptStatus.AWAITING_REVIEW);
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("review mode wrote a draft for somebody to decide (A25)")
                .anySatisfy(binding -> assertThat(binding.isDraft()).isTrue());

        scenarios.supervisorActions().amend(doc, drafted.id(), 1, "DSParser -> XSLTFilter", OPERATOR);

        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("the draft goes with the answer that produced it, or it is a draft nothing "
                             + "awaits: unapprovable, unrejectable, and sentinelling every stream of its "
                             + "learner until somebody deletes the binding by hand")
                .noneSatisfy(binding -> assertThat(binding.isDraft()).isTrue());
        assertThat(scenarios.states.draftAwaiting(Scenarios.LEARNER))
                .describedAs("and nothing is waiting for one")
                .isEmpty();
    }

    @Test
    void anAttemptCannotBeRunAgainWhileAnotherHoldsItsLearner() {
        // Running an attempt again needs it to claim the learner, and a learner that another attempt is
        // learning is not free. The person is told so, rather than two attempts learning one learner at once.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = scenarios.documents.put(doc());
        scenarios.inputs.put(stream(1L));
        final Script model = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(TWO_FIELDS));
        scenarios.process(model, doc, stream(1L)).learned();
        final Recorded abandoned = scenarios.attempts.forDocument(DOC, 10).get(0);
        // Meanwhile another node claims the learner by opening an attempt for it.
        final long theirs = scenarios.attempts.opened(new Attempt(Scenarios.LEARNER, DOC, "DOOR-ACCESS",
                "Raw Events",
                        2L, "node-2", PromotionMode.AUTOMATIC, Long.MAX_VALUE),
                Scenarios.NOW.toEpochMilli()).orElseThrow();

        assertThatThrownBy(() -> scenarios.supervisorActions()
                .amend(doc, abandoned.id(), 1, "DSParser -> XSLTFilter", OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be run again");

        // That node's lease expires but it still holds the learner: a session whose lease has expired is
        // resumed by the next worker (as in TestScenario58ASessionWhoseNodeDiesIsCarriedOnByAnother), not
        // pushed aside for an answer to an older attempt.
        scenarios.attempts.heartbeat(theirs, Scenarios.NOW.toEpochMilli() - 1);
        assertThatThrownBy(() -> scenarios.supervisorActions()
                .amend(doc, abandoned.id(), 1, "DSParser -> XSLTFilter", OPERATOR))
                .isInstanceOf(IllegalStateException.class);
        assertThat(scenarios.attempts.byId(theirs).orElseThrow().status()).isEqualTo(AttemptStatus.IN_PROGRESS);

        // Once it has finished, the learner is free and the answer goes through.
        scenarios.attempts.closed(theirs, AttemptStatus.ABANDONED, "Given up", null, null, 0L);
        scenarios.supervisorActions().amend(doc, abandoned.id(), 1, "DSParser -> XSLTFilter", OPERATOR);

        assertThat(scenarios.attempts.byId(abandoned.id()).orElseThrow().status())
                .isEqualTo(AttemptStatus.AWAITING_MODEL);
    }


    // --------------------------------------------------------------------------------


    @Test
    void theRecordOfWhatIsOverIsPrunedAndTheListDoesNotCarryTranscripts() {
        // Finished attempts are pruned so that what is kept stays bounded, and the attempt list does not load
        // transcripts, which only the detail view reads. The in-memory and database implementations must
        // behave the same way, or a scenario passing against the in-memory one proves nothing about the
        // database.
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = scenarios.documents.put(doc());
        scenarios.inputs.put(stream(1L));
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(TWO_FIELDS));
        scenarios.process(script, doc, stream(1L)).learned();
        final Recorded abandoned = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(abandoned.turns()).isNotEmpty();

        assertThat(scenarios.attempts.found(new AttemptCriteria(), List.of(DOC)).attempts())
                .describedAs("the list has the attempt")
                .extracting(Recorded::id).containsExactly(abandoned.id());
        assertThat(scenarios.attempts.found(new AttemptCriteria(), List.of(DOC)).attempts().get(0).turns())
                .describedAs("and not its transcript, which the detail reads").isEmpty();
        assertThat(scenarios.attempts.found(new AttemptCriteria(), List.of()).total())
                .describedAs("a document nobody may see is not counted").isZero();

        assertThat(scenarios.attempts.prune(Scenarios.NOW.toEpochMilli() - 1))
                .describedAs("nothing is old enough yet").isZero();
        assertThat(scenarios.attempts.prune(System.currentTimeMillis() + 60_000L)).isEqualTo(1);
        assertThat(scenarios.attempts.byId(abandoned.id())).isEmpty();
        assertThat(scenarios.attempts.forDocument(DOC, 10)).isEmpty();
    }

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST))
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .maxAttempts(1)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.COVERAGE, CheckState.ADVICE, 0.9, null),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.5,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        new CheckSetting(Check.CONFORMANCE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.QUALITY, CheckState.GATE, 0.7,
                                new ExtractionQualityParameters(false, List.of("EventSource/User/Id"))),
                        new CheckSetting(Check.RULES, CheckState.ADVICE, 1.0,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                                "interactive events name the user",
                                                "not(EventDetail/Authenticate) "
                                                + "or EventDetail/Authenticate/User/Id[normalize-space(.) != '']")),
                                                true)),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
                .build();
    }

    private static Input stream(final long id) {
        return new Input(id, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV"), CsvLines.lines(6));
    }
}
