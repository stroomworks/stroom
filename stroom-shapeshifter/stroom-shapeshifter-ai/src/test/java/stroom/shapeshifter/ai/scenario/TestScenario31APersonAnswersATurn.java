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

import stroom.shapeshifter.ai.stage.Attempts.Attempt;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Attempts.Turn;
import stroom.shapeshifter.ai.stage.Decision.GivenUp;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.AttemptCriteria;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.XPathAssertion;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Design 02 §5, scenario 31 (A28): a person answers a turn. The model's splitter keeps half of each
 * record and the attempt is given up at its candidate limit. A person puts their own configuration in
 * place of the model's — <em>edit and re-run from here</em> — and the attempt runs again from that turn:
 * everything before it is replayed from the record, the person's answer is given where the model's was,
 * and the model is asked only what comes after. The transcript says who answered each turn.
 */
class TestScenario31APersonAnswersATurn {

    private static final String FOUR_FIELDS = Scenarios.resource("csv-fields.ds3.xml");
    private static final String TWO_FIELDS = Scenarios.resource("csv-two-fields.ds3.xml");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DOC = "doc-1";
    private static final String OPERATOR = "an.operator";

    @Test
    void scenario31APersonsAnswerReplacesTheModelsAndTheAttemptRunsAgain() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = scenarios.documents.put(doc());
        final Input input = scenarios.inputs.put(stream(1L));
        // One candidate, and the model spends it on a splitter that drops the fields the event needs.
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

        // A person puts their own configuration in its place. Nothing runs here: the answer is written
        // and the attempt waits, since a person's request must not wait on a model.
        final Script carrying = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        scenarios.stage(carrying).amend(doc, abandoned.id(), theirs.number(), Scenarios.fenced(FOUR_FIELDS),
                OPERATOR);

        assertThat(carrying.asked()).describedAs("amending asks nothing").isEmpty();
        final Recorded waiting = scenarios.attempts.byId(abandoned.id()).orElseThrow();
        assertThat(waiting.status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(waiting.turns()).describedAs("the turns up to and including the one answered")
                .hasSize(theirs.number());
        assertThat(waiting.turns().get(theirs.number() - 1).answeredBy()).isEqualTo(OPERATOR);
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER))
                .describedAs("re-running says the learner is not settled after all").isEmpty();

        // The worker carries it on: everything before the person's turn is replayed, their answer is
        // given where the model's was, and only what comes after is asked.
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
        // A deferred attempt asks nobody and stops at its first question (A5). What it stopped at is a
        // turn of its own, with no answer and no answerer: without it a person has nothing to answer.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = scenarios.documents.put(doc().copy()
                .build());
        scenarios.inputs.put(stream(1L));

        // The task opens the session and asks nobody (A60): it stops at the first question, recorded for
        // whoever will answer it.
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

        // A person answers it instead, and the attempt goes on from what they said.
        final Script carrying = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        scenarios.stage(carrying).amend(doc, parked.id(), 1, "DSParser -> XSLTFilter", OPERATOR);

        assertThat(scenarios.worker(carrying).advance(10)).isEqualTo(1);

        carrying.verifyExhausted();
        assertThat(carrying.asked()).describedAs("the chain was not asked: a person had answered it")
                .noneMatch(stroom.shapeshifter.ai.learning.Question.Chain.class::isInstance);
        assertThat(scenarios.attempts.byId(parked.id()).orElseThrow().status())
                .isEqualTo(AttemptStatus.PROMOTED);
        assertThat(scenarios.attempts.byId(parked.id()).orElseThrow().turns().get(0).answeredBy())
                .isEqualTo(OPERATOR);
    }

    @Test
    void aTurnNumberThatNamesNothingChangesNothing() {
        // A person's mistyped turn number, or a stale one from a view: the attempt must be left exactly as
        // it was. Opening it again first and failing afterwards would wipe what it came to and tell them
        // their request had failed.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = scenarios.documents.put(doc());
        scenarios.inputs.put(stream(1L));
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(TWO_FIELDS));
        scenarios.process(script, doc, stream(1L)).learned();
        final Recorded abandoned = scenarios.attempts.forDocument(DOC, 10).get(0);

        assertThatThrownBy(() -> scenarios.stage(Script.of())
                .amend(doc, abandoned.id(), 99, Scenarios.fenced(FOUR_FIELDS), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no turn 99");

        final Recorded after = scenarios.attempts.byId(abandoned.id()).orElseThrow();
        assertThat(after.status()).describedAs("still what it was").isEqualTo(abandoned.status());
        assertThat(after.decision()).isEqualTo(abandoned.decision());
        assertThat(after.turns()).describedAs("with every turn it had").hasSameSizeAs(abandoned.turns());
        assertThat(scenarios.attempts.awaiting(10))
                .describedAs("and the worker has nothing to pick up").isEmpty();

        // The same of the wrong document: an attempt belongs to the document that raised it, and
        // resetting another document's learner would clear state that has nothing to do with this.
        final ShapeshifterAiDoc other = doc().copy().uuid("doc-2").build();
        assertThatThrownBy(() -> scenarios.stage(Script.of())
                .amend(other, abandoned.id(), 1, "DSParser -> XSLTFilter", OPERATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to document");
    }

    @Test
    void anAttemptBeingWalkedNowIsNotAmendedUnderneathTheWalk() {
        // A45, A28: the answers of an attempt a node is carrying on are that walk's to give. A person's
        // answer written into it would be overwritten by the next turn the walk records, and they would
        // be told it had been taken.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = scenarios.documents.put(doc().copy()
                .build());
        scenarios.inputs.put(stream(1L));
        scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER, stream(1L));
        final Recorded parked = scenarios.attempts.forDocument(DOC, 10).get(0);
        // A worker takes it up, and is walking it now.
        assertThat(scenarios.attempts.claimed(parked.id(), "node-1", Scenarios.NOW.toEpochMilli(),
                Scenarios.NOW.toEpochMilli() + 60_000L)).isTrue();

        assertThatThrownBy(() -> scenarios.stage(Script.of())
                .amend(doc, parked.id(), 1, "DSParser -> XSLTFilter", OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("being carried on now");

        // The node carrying it stops without finishing: its claim lapses, and the answer may be given.
        scenarios.attempts.heartbeat(parked.id(), Scenarios.NOW.toEpochMilli() - 1);
        scenarios.stage(Script.of()).amend(doc, parked.id(), 1, "DSParser -> XSLTFilter", OPERATOR);

        final Recorded amended = scenarios.attempts.byId(parked.id()).orElseThrow();
        assertThat(amended.status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(amended.turns().get(0).answeredBy()).isEqualTo(OPERATOR);
        assertThat(amended.attempt().expiryMs())
                .describedAs("and its claim is pushed out, or the next stream of the learner would sweep it "
                             + "away before the worker reached it")
                .isGreaterThan(Scenarios.NOW.toEpochMilli());
    }

    /// What an attempt drafted goes with the answer that produced it.
    ///
    /// The turns after the one answered are discarded because they are a consequence of an answer that
    /// has changed, and a draft binding is the last of those consequences. Left in place it is a draft
    /// nothing awaits: the reset clears the pointer that says which learner is waiting for it, so
    /// approving or rejecting it throws, every stream of the learner is sentinelled by it, and the only
    /// way out is deleting the binding by hand.
    @Test
    void amendingAnAttemptTakesBackWhatItDrafted() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = scenarios.documents.put(doc().copy()
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

        scenarios.stage(Script.of()).amend(doc, drafted.id(), 1, "DSParser -> XSLTFilter", OPERATOR);

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
        // A45: re-running takes the learner back, and a learner another attempt is learning is not free. A
        // person is told rather than two attempts learning one learner at once.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = scenarios.documents.put(doc());
        scenarios.inputs.put(stream(1L));
        final Script model = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(TWO_FIELDS));
        scenarios.process(model, doc, stream(1L)).learned();
        final Recorded abandoned = scenarios.attempts.forDocument(DOC, 10).get(0);
        // Another node takes the learner in the meantime, by opening an attempt for it.
        final long theirs = scenarios.attempts.opened(new Attempt(Scenarios.LEARNER, DOC, "DOOR-ACCESS",
                "Raw Events",
                        2L, "node-2", PromotionMode.AUTOMATIC, Long.MAX_VALUE),
                Scenarios.NOW.toEpochMilli()).orElseThrow();

        assertThatThrownBy(() -> scenarios.stage(Script.of())
                .amend(doc, abandoned.id(), 1, "DSParser -> XSLTFilter", OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be run again");

        // That node's lease lapses and it still holds the learner: a lapsed session is resumed by the next
        // worker, not swept aside for an answer to an older attempt (design 04 §4, scenario 58).
        scenarios.attempts.heartbeat(theirs, Scenarios.NOW.toEpochMilli() - 1);
        assertThatThrownBy(() -> scenarios.stage(Script.of())
                .amend(doc, abandoned.id(), 1, "DSParser -> XSLTFilter", OPERATOR))
                .isInstanceOf(IllegalStateException.class);
        assertThat(scenarios.attempts.byId(theirs).orElseThrow().status()).isEqualTo(AttemptStatus.IN_PROGRESS);

        // Once it has finished, the learner is free and the answer goes through.
        scenarios.attempts.closed(theirs, AttemptStatus.ABANDONED, "Given up", null, null, 0L);
        scenarios.stage(Script.of()).amend(doc, abandoned.id(), 1, "DSParser -> XSLTFilter", OPERATOR);

        assertThat(scenarios.attempts.byId(abandoned.id()).orElseThrow().status())
                .isEqualTo(AttemptStatus.AWAITING_MODEL);
    }


    // --------------------------------------------------------------------------------


    @Test
    void theRecordOfWhatIsOverIsPrunedAndTheListDoesNotCarryTranscripts() {
        // Design 01 §12 item 8, and A28's list/detail split: what a node keeps is bounded, and a page of
        // attempts is not a page of transcripts. The twins must agree, or a scenario passing over the
        // in-memory one proves nothing about the rows.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = scenarios.documents.put(doc());
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

    private static ShapeshifterAiDoc doc() {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExample.TARGET_FIRST)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .maxAttempts(1)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.9, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true,
                                new SchemaConformanceParameters("EVENTS")),
                        new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, true,
                                new ExtractionQualityParameters(false, List.of("EventSource/User/Id"))),
                        new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 1.0, false,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                        "interactive events name the user",
                                        "not(EventDetail/Authenticate) "
                                        + "or EventDetail/Authenticate/User/Id[normalize-space(.) != '']")),
                                        true))))
                .build();
    }

    private static Input stream(final long id) {
        return new Input(id, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV"), CsvLines.lines(6));
    }
}
