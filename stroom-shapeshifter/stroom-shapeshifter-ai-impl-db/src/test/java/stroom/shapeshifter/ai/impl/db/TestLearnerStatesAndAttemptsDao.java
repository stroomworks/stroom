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

package stroom.shapeshifter.ai.impl.db;

import stroom.docref.DocRef;
import stroom.pipeline.shared.PipelineDoc;
import stroom.shapeshifter.ai.stage.Attempts.Attempt;
import stroom.shapeshifter.ai.stage.Attempts.Grant;
import stroom.shapeshifter.ai.stage.Attempts.Page;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Attempts.Turn;
import stroom.shapeshifter.ai.stage.Guidance.Given;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.Produced;
import stroom.shapeshifter.ai.stage.Replayable;
import stroom.shapeshifter.ai.stage.Spend.Spent;
import stroom.shapeshifter.shared.AttemptCriteria;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.TurnScore;
import stroom.util.shared.DefaultLocation;
import stroom.util.shared.PageRequest;
import stroom.util.shared.TextRange;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/// The learner state of A117 and A124, the attempts and guidance as rows.
class TestLearnerStatesAndAttemptsDao {

    private static final String DOC = "doc-" + System.nanoTime();
    private static final Learner LEARNER = learner("door");
    /// A neighbouring learner under the same document: another pipeline's element.
    private static final Learner OTHER = learner("turnstile");

    @Inject
    private LearnerStatesDao states;
    @Inject
    private GuidanceDao guidance;
    @Inject
    private SpendDao spend;
    @Inject
    private AttemptsDao attempts;
    @Inject
    private OutputsDao outputs;

    @BeforeEach
    void setUp() {
        Guice.createInjector(new TestModule()).injectMembers(this);
        states.reset(LEARNER);
    }

    /// A learner of this run's own, so that rows a failed run left behind are not this one's.
    private static Learner learner(final String name) {
        return new Learner("pipeline-" + DOC + "-" + name, "shapeshifterAi");
    }

    @Test
    void aLearnerRemembersWhyItGaveUpWasMarkedOrIsHeldForReview() {
        assertThat(states.reasonGivenUp(LEARNER)).describedAs("a learner nothing has said anything about")
                .isEmpty();

        states.giveUp(LEARNER, DOC, "Rejected: the wrong parser");
        states.markForRelearning(LEARNER, DOC, "Rolling score 0.6 fell below 0.8");
        states.awaitReview(LEARNER, DOC, "rule-1");

        assertThat(states.reasonGivenUp(LEARNER)).contains("Rejected: the wrong parser");
        assertThat(states.relearnReason(LEARNER)).contains("Rolling score 0.6 fell below 0.8");
        assertThat(states.draftAwaiting(LEARNER)).contains("rule-1");
        assertThat(states.reasonGivenUp(OTHER)).describedAs("a neighbour's state is its own").isEmpty();

        states.reset(LEARNER);
        assertThat(states.reasonGivenUp(LEARNER)).isEmpty();
        assertThat(states.relearnReason(LEARNER)).isEmpty();
        assertThat(states.draftAwaiting(LEARNER)).isEmpty();
    }

    @Test
    void theRollingScoreIsOfWhatTheLearnerHasDoneLately() {
        // Nothing is said until the learner has brought enough records to rest a judgement on.
        assertThat(states.scored(LEARNER, DOC, 1.0, 4, 10)).isEmpty();
        assertThat(states.scored(LEARNER, DOC, 1.0, 4, 10)).isEmpty();

        final OptionalDouble rolling = states.scored(LEARNER, DOC, 0.0, 4, 10);

        assertThat(rolling).isPresent();
        assertThat(rolling.getAsDouble()).describedAs("eight good records, then four bad, remembering ten")
                .isEqualTo(8.0 / 12);
        // Reset starts it afresh, as a promotion or a retraction does.
        states.reset(LEARNER);
        assertThat(states.scored(LEARNER, DOC, 1.0, 1, 1)).hasValue(1.0);
    }

    /// The first streams of a new learner are often served at once, and each finds no row and makes it.
    /// Made under a shared lock, the next step's `FOR UPDATE` deadlocked them and the database killed one,
    /// erroring its stream; made under the exclusive lock, they queue. Several new learners, each scored
    /// by many threads together, every score counted.
    @Test
    void theFirstStreamsOfANewLearnerScoredAtOnceAllCount() throws Exception {
        final int threads = 8;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 5; round++) {
                final Learner fresh = new Learner("fresh-" + System.nanoTime(), "shapeshifterAi");
                final CountDownLatch start = new CountDownLatch(1);
                final List<Future<OptionalDouble>> scored = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    scored.add(pool.submit(() -> {
                        start.await();
                        return states.scored(fresh, DOC, 1.0, 1, 1000);
                    }));
                }
                start.countDown();
                for (final Future<OptionalDouble> each : scored) {
                    each.get(30, TimeUnit.SECONDS);
                }
                assertThat(states.rolling(fresh))
                        .describedAs("round " + round + ": every thread's record counted")
                        .hasValueSatisfying(rolling -> assertThat(rolling.records()).isEqualTo(threads));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void resettingALearnerLeavesTheAttemptLearningAlone() {
        // Reset is about what was learned, not about who is learning: a promotion resets the learner while
        // the attempt that promoted it is still open, and must not hand its claim to another node (A45).
        final Learner learner = learner("reset");
        attempts.opened(new Attempt(learner, DOC, "F", "T", 1L, "node-1",
                PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();

        states.reset(learner);

        assertThat(attempts.opened(new Attempt(learner, DOC, "F", "T", 2L, "node-2",
                PromotionMode.AUTOMATIC, now() + 60_000L), now())).isEmpty();
    }

    @Test
    void anAttemptIsCarriedOnByItsOwnNodeOrByWhoeverFindsItLapsed() {
        // A45: taking an attempt up is taking its learner's claim. The heartbeat is what keeps a slow model
        // call from costing a node the claim it is learning under.
        final Learner learner = learner("resume");
        final long id = attempts.opened(new Attempt(learner, DOC, "F", "T", 1L, "node-1",
                PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        attempts.parked(id, AttemptStatus.AWAITING_MODEL, now() + 60_000L, 500L);

        assertThat(attempts.awaiting(10)).extracting(Recorded::id)
                .describedAs("what the worker advances").contains(id);
        assertThat(attempts.claimed(id, "node-2", now(), now() + 120_000L))
                .describedAs("a parked attempt is nobody's: any node's worker may take it").isTrue();
        assertThat(attempts.byId(id).orElseThrow().status()).isEqualTo(AttemptStatus.IN_PROGRESS);
        assertThat(attempts.awaiting(10)).extracting(Recorded::id)
                .describedAs("and is no longer waiting once taken").doesNotContain(id);
        assertThat(attempts.claimed(id, "node-1", now(), now() + 120_000L))
                .describedAs("but a running attempt is its own node's").isFalse();
        assertThat(attempts.claimed(id, "node-2", now(), now() + 120_000L))
                .describedAs("which carries it on").isTrue();

        attempts.heartbeat(id, now() - 1);
        assertThat(attempts.open(learner, now())).describedAs("lapsed, and it still holds its claim: the "
                                                                 + "session is resumed, not replaced (design 04 §4)")
                .map(Recorded::id).contains(id);
        assertThat(attempts.lapsed(now(), 10)).extracting(Recorded::id)
                .describedAs("which is what the next worker finds it by").contains(id);
        assertThat(attempts.opened(new Attempt(learner, DOC, "F", "T", 2L, "node-2",
                PromotionMode.AUTOMATIC, now() + 60_000L), now()))
                .describedAs("so no attempt opens beside it").isEmpty();
        assertThat(attempts.claimed(id, "node-1", now(), now() + 60_000L))
                .describedAs("a node that died lets the next one in").isTrue();
        assertThat(attempts.lapsed(now(), 10)).extracting(Recorded::id)
                .describedAs("and taken, it is nobody's to find").doesNotContain(id);
        assertThat(attempts.byId(id).orElseThrow().attempt().node()).isEqualTo("node-1");

        // What it spent on each leg is added up, not overwritten, or a resumed attempt's cost is only its
        // last leg's (A5).
        attempts.closed(id, AttemptStatus.PROMOTED, "Promoted 1.0", "rule-3", 1.0, 700L);
        assertThat(attempts.byId(id).orElseThrow().tokensSpent()).isEqualTo(1_200L);
        assertThat(attempts.claimed(id, "node-1", now(), now() + 60_000L))
                .describedAs("a finished attempt is not carried on").isFalse();
        attempts.parked(id, AttemptStatus.AWAITING_MODEL, now() + 60_000L, 1L);
        assertThat(attempts.byId(id).orElseThrow().status())
                .describedAs("nor parked back into life").isEqualTo(AttemptStatus.PROMOTED);
        assertThat(attempts.awaiting(10)).extracting(Recorded::id).doesNotContain(id);
    }

    @Test
    void anAttemptParkedForAPersonKeepsItsLearnerAndIsNobodysToCarry() {
        // A65: the worker leaves it alone, no task of the learner opens another, and its lease does not
        // lapse — until a person acts, which is not the DAO's to do.
        final Learner learner = learner("parked");
        final long id = attempts.opened(new Attempt(learner, DOC, "F", "T", 1L, "node-1",
                PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        attempts.claimed(id, "node-1", now(), now() + 60_000L);

        attempts.parked(id, "3 of 12 record(s) fell short of the bar on the held stream; out of candidates", 400L);

        final Recorded parked = attempts.byId(id).orElseThrow();
        assertThat(parked.status()).isEqualTo(AttemptStatus.PARKED);
        assertThat(parked.decision()).startsWith("3 of 12 record(s) fell short");
        assertThat(parked.tokensSpent()).isEqualTo(400L);
        assertThat(attempts.awaiting(10)).extracting(Recorded::id).describedAs("not the worker's").doesNotContain(id);
        assertThat(attempts.claimed(id, "node-2", now() + 1_000_000_000L, now() + 1_000_060_000L))
                .describedAs("nobody's to carry, however long it has stood").isFalse();
        assertThat(attempts.open(learner, now() + 1_000_000_000L)).describedAs("and it keeps its claim")
                .map(Recorded::id).contains(id);
        assertThat(attempts.opened(new Attempt(learner, DOC, "F", "T", 2L, "node-2",
                PromotionMode.AUTOMATIC, now() + 60_000L), now() + 1_000_000_000L))
                .describedAs("so no second attempt opens for it").isEmpty();

        // A finished attempt is not parked after the fact.
        attempts.closed(id, AttemptStatus.ABANDONED, "Given up", null, null, 0L);
        attempts.parked(id, "late", 1L);
        assertThat(attempts.byId(id).orElseThrow().status()).isEqualTo(AttemptStatus.ABANDONED);
    }

    @Test
    void aGrantIsAddedToTheAttemptAndKeptOnItsRow() {
        // A77: what a person grants is the attempt's alone, added up as it is granted again, and read back
        // with the row.
        final Learner learner = learner("grant");
        final long id = attempts.opened(new Attempt(learner, DOC, "F", "T", 1L, "node-1",
                PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        assertThat(attempts.byId(id).orElseThrow().grant()).isEqualTo(Grant.NONE);

        attempts.granted(id, 500L, 60_000L);
        attempts.granted(id, 250L, 0L);

        final Recorded granted = attempts.byId(id).orElseThrow();
        assertThat(granted.grant().tokens()).isEqualTo(750L);
        assertThat(granted.grant().ms()).isEqualTo(60_000L);
        attempts.closed(id, AttemptStatus.PROMOTED, "Promoted", "rule-g", 1.0, 100L);
        assertThat(attempts.byId(id).orElseThrow().grant().tokens()).describedAs("kept with the row").isEqualTo(750L);
    }

    @Test
    void aSendingToTheExhaustionStepStandsOnTheRowUntilTheAttemptCloses() {
        // A65: a person's sending is read back with the row for the worker to take, stands through a park
        // and the reopening that follows, and is cleared when the attempt closes.
        final Learner learner = learner("exhaust");
        final long id = attempts.opened(new Attempt(learner, DOC, "F", "T", 1L, "node-1",
                PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        assertThat(attempts.byId(id).orElseThrow().exhaustNext()).isFalse();

        attempts.exhaustNext(id);
        assertThat(attempts.byId(id).orElseThrow().exhaustNext()).isTrue();

        attempts.parked(id, "over budget", 1L);
        assertThat(attempts.byId(id).orElseThrow().exhaustNext()).describedAs("stands through a park").isTrue();
        assertThat(attempts.reopened(id, now(), now() + 60_000L)).isTrue();
        assertThat(attempts.byId(id).orElseThrow().exhaustNext()).describedAs("and a reopening").isTrue();
        attempts.closed(id, AttemptStatus.PROMOTED, "Promoted", "rule-x", 1.0, 1L);
        assertThat(attempts.byId(id).orElseThrow().exhaustNext()).describedAs("cleared by the close").isFalse();
    }

    @Test
    void whatEveryNodeSpendsIsCountedInOnePlace() {
        // A44: a budget divided by node count is not a budget, and a feed burning spend on one node is
        // invisible to the others.
        final long window = 60_000L;
        assertThat(spend.spent(DOC, window).tokens()).isZero();

        final Spent afterOne = spend.record(DOC, 1_500L, 3, window);
        final Spent afterTwo = spend.record(DOC, 2_500L, 4, window);

        assertThat(afterOne.tokens()).isEqualTo(1_500L);
        assertThat(afterTwo.tokens()).describedAs("the second node adds to the first's count")
                .isEqualTo(4_000L);
        assertThat(afterTwo.calls()).isEqualTo(7);
        assertThat(afterTwo.windowStartMs()).isEqualTo(afterOne.windowStartMs());
        assertThat(spend.spent(DOC, window).tokens()).isEqualTo(4_000L);
        assertThat(spend.spent("another-document", window).tokens())
                .describedAs("one document's spend is its own").isZero();

        // A window that has run out starts the count again: what matters is what was spent lately.
        final Spent afresh = spend.spent(DOC, 0L);
        assertThat(afresh.tokens()).isZero();
        assertThat(spend.record(DOC, 100L, 1, 0L).tokens()).isEqualTo(100L);
    }

    @Test
    void anAttemptAndItsTurnsAreRowsThatOutliveTheNode() {
        // A28: what was asked and answered survives the node that asked it, and the turns are readable in
        // the order they were put.
        final long id = attempts.opened(new Attempt(LEARNER, DOC, "DOOR-ACCESS", "Raw Events", 42L, "node-1",
                PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        attempts.turn(id, new Turn(1, "chain", 1, QuestionKind.CHAIN, "CHAIN: choose from [DSParser]",
                "DSParser -> XSLTFilter", "local-model", StepOutcome.PASSED));
        attempts.turn(id, new Turn(2, "configure", 2, QuestionKind.CONFIGURE, "CONFIGURE: DSParser",
                "<dataSplitter/>", "local-model", StepOutcome.COVERAGE_SHORT,
                new TurnScore(ScorerType.INPUT_COVERAGE, 0.41, 0.8), List.of()));

        attempts.closed(id, AttemptStatus.PROMOTED, "Promoted 0.97", "rule-1", 0.97, 1_234L);

        final Recorded read = attempts.byId(id).orElseThrow();
        assertThat(read.status()).isEqualTo(AttemptStatus.PROMOTED);
        assertThat(read.decision()).isEqualTo("Promoted 0.97");
        assertThat(read.bindingUuid()).isEqualTo("rule-1");
        assertThat(read.score()).isEqualTo(0.97);
        assertThat(read.tokensSpent()).isEqualTo(1_234L);
        assertThat(read.attempt().feed()).isEqualTo("DOOR-ACCESS");
        assertThat(read.attempt().inputId()).isEqualTo(42L);
        assertThat(read.attempt().expiryMs())
                .describedAs("a claim that has ended holds nothing (A45)").isZero();
        assertThat(read.turns()).extracting(Turn::number).containsExactly(1, 2);
        assertThat(read.turns().get(1).candidate()).isEqualTo(2);
        assertThat(read.turns().get(1).outcome()).isEqualTo(StepOutcome.COVERAGE_SHORT);
        assertThat(read.turns().get(0).answeredBy()).isEqualTo("local-model");
        assertThat(read.turns().get(1).score())
                .describedAs("the check that decided it, against its threshold (A144)")
                .isEqualTo(new TurnScore(ScorerType.INPUT_COVERAGE, 0.41, 0.8));
        assertThat(read.turns().get(0).score()).describedAs("a turn no scorer decided").isNull();

        // The learner's next attempt, once this one has closed, with no stream behind it.
        final long nextId = attempts.opened(new Attempt(LEARNER, DOC, null, null, null, "node-1",
                PromotionMode.REVIEW, now()), now()).orElseThrow();
        assertThat(attempts.byId(nextId).orElseThrow().attempt().feed()).isNull();
        assertThat(attempts.forDocument(DOC, 10)).extracting(Recorded::id)
                .describedAs("newest first, for the Supervisor view")
                .startsWith(nextId, id);
        attempts.closed(nextId, AttemptStatus.ABANDONED, "done with", null, null, 0L);
    }

    @Test
    void oneOpenAttemptPerLearner() {
        // A45: the attempt row is the claim. A parked attempt is still learning, and one whose expiry has
        // passed has lapsed and is still learning too: it is resumed, not replaced (design 04 §4).
        final Learner learner = learner("claim");
        final long mine = attempts.opened(new Attempt(learner, DOC, "F", "T", 1L, "node-1",
                PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();

        assertThat(attempts.opened(new Attempt(learner, DOC, "F", "T", 2L, "node-2",
                PromotionMode.AUTOMATIC, now() + 60_000L), now()))
                .describedAs("one attempt at a time per learner").isEmpty();
        assertThat(attempts.open(learner, now()).orElseThrow().id()).isEqualTo(mine);

        // Parked awaiting the model, it keeps the claim.
        attempts.parked(mine, AttemptStatus.AWAITING_MODEL, now() + 60_000L, 0L);
        assertThat(attempts.open(learner, now())).isPresent();
        assertThat(attempts.opened(new Attempt(learner, DOC, "F", "T", 3L, "node-2",
                PromotionMode.AUTOMATIC, now() + 60_000L), now())).isEmpty();

        // Lapsed, it still holds the claim: nobody opens beside it.
        attempts.parked(mine, AttemptStatus.AWAITING_MODEL, now() - 1, 0L);
        assertThat(attempts.open(learner, now())).map(Recorded::id).contains(mine);
        assertThat(attempts.opened(new Attempt(learner, DOC, "F", "T", 4L, "node-2",
                PromotionMode.AUTOMATIC, now() + 60_000L), now())).isEmpty();

        // Closed, it holds nothing, and the learner is free for the attempt after it.
        attempts.closed(mine, AttemptStatus.PROMOTED, "Promoted 1.0", "rule-2", 1.0, 0L);
        assertThat(attempts.open(learner, now())).isEmpty();
        assertThat(attempts.opened(new Attempt(learner, DOC, "F", "T", 5L, "node-3",
                PromotionMode.AUTOMATIC, now() + 60_000L), now())).isPresent();
    }

    @Test
    void aPersonsAnswerReplacesATurnsAndWhatFollowedItGoes() {
        // A28: *answer instead* and *edit and re-run from here*. What came after the turn answered is a
        // consequence of an answer that has changed, and the walk derives it again.
        final Learner learner = learner("amend");
        final long id = attempts.opened(new Attempt(learner, DOC, "F", "T", 1L, "node-1",
                PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        attempts.turn(id, new Turn(1, "chain", 1, QuestionKind.CHAIN, "Chain: choose from [DSParser]",
                "DSParser", "local-model", StepOutcome.PASSED));
        attempts.turn(id, new Turn(2, "configure", 1, QuestionKind.CONFIGURE, "Configure: DSParser",
                "<dataSplitter/>", "local-model", StepOutcome.COVERAGE_SHORT,
                new TurnScore(ScorerType.INPUT_COVERAGE, 0.41, 0.8), List.of()));
        attempts.turn(id, new Turn(3, "configure", 2, QuestionKind.CONFIGURE, "Configure: DSParser",
                "<dataSplitter/>", "local-model", StepOutcome.COVERAGE_SHORT));
        attempts.closed(id, AttemptStatus.ABANDONED, "No passing configuration", null, null, 10L);

        // Opened again, it takes its claim back.
        assertThat(attempts.reopened(id, now(), now() + 60_000L)).isTrue();
        assertThat(attempts.byId(id).orElseThrow().status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(attempts.byId(id).orElseThrow().decision())
                .describedAs("what it came to before is not what it has come to now").isNull();
        assertThat(attempts.opened(new Attempt(learner, DOC, "F", "T", 2L, "node-2",
                PromotionMode.AUTOMATIC, now() + 60_000L), now()))
                .describedAs("and holds it against another").isEmpty();
        assertThat(attempts.reopened(id, now(), now() + 60_000L))
                .describedAs("opening an open attempt again is nothing to do").isTrue();

        attempts.amended(id, 2, "<dataSplitter>better</dataSplitter>", "an.operator");

        final Recorded read = attempts.byId(id).orElseThrow();
        assertThat(read.turns()).extracting(Turn::number).containsExactly(1, 2);
        assertThat(read.turns().get(1).answer()).isEqualTo("<dataSplitter>better</dataSplitter>");
        assertThat(read.turns().get(1).answeredBy()).isEqualTo("an.operator");
        assertThat(read.turns().get(1).outcome())
                .describedAs("unjudged: it has not been run yet").isNull();
        assertThat(read.turns().get(1).score()).describedAs("nor scored").isNull();
        assertThat(read.turns().get(0).answeredBy())
                .describedAs("the turns before it are as they were").isEqualTo("local-model");
        assertThat(attempts.awaiting(10)).extracting(Recorded::id).contains(id);

        assertThatThrownBy(() -> attempts.amended(id, 9, "anything", "an.operator"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(attempts.byId(id).orElseThrow().turns())
                .describedAs("a turn number that names nothing leaves the transcript as it was")
                .hasSize(2);
        attempts.closed(id, AttemptStatus.ABANDONED, "done with", null, null, 0L);
    }

    @Test
    void anAttemptIsOpenedAgainDespiteAClaimNobodyIsBehind() {
        // A45: an attempt whose node died holds its claim, and a person may still answer it and run it
        // again. The claim of the attempt reopened is pushed out, or a worker would find it lapsed and
        // take it before the answer was recorded against it.
        final Learner learner = learner("lapsed");
        final long dead = attempts.opened(new Attempt(learner, DOC, "F", "T", 1L, "node-dead",
                PromotionMode.AUTOMATIC, now() - 1), now() - 2).orElseThrow();
        attempts.closed(dead, AttemptStatus.PROMOTED, "Promoted", "rule-x", 1.0, 0L);
        final long mine = attempts.opened(new Attempt(learner, DOC, "F", "T", 2L, "node-1",
                PromotionMode.AUTOMATIC, now() - 1), now() - 2).orElseThrow();
        attempts.turn(mine, new Turn(1, "chain", 1, QuestionKind.CHAIN, "Chain: choose from [DSParser]",
                "DSParser", "local-model", StepOutcome.PASSED));
        attempts.parked(mine, AttemptStatus.AWAITING_MODEL, now() - 1, 0L);

        assertThat(attempts.reopened(mine, now(), now() + 60_000L))
                .describedAs("lapsed or not, a person's answer reopens it")
                .isTrue();

        final Recorded reopened = attempts.byId(mine).orElseThrow();
        assertThat(reopened.status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(reopened.attempt().expiryMs()).isGreaterThan(now());
        assertThat(attempts.open(learner, now()).orElseThrow().id()).isEqualTo(mine);
        attempts.closed(mine, AttemptStatus.ABANDONED, "done with", null, null, 0L);
    }

    @Test
    void anAttemptBeingWalkedNowIsNotOpenedAgain() {
        final Learner learner = learner("walking");
        final long id = attempts.opened(new Attempt(learner, DOC, "F", "T", 1L, "node-1",
                PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();

        assertThat(attempts.reopened(id, now(), now() + 60_000L))
                .describedAs("its answers are that walk's to give").isFalse();

        attempts.heartbeat(id, now() - 1);
        assertThat(attempts.reopened(id, now(), now() + 60_000L))
                .describedAs("the walk stopped without finishing, and a person may answer it").isTrue();
        assertThat(attempts.byId(id).orElseThrow().status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        attempts.closed(id, AttemptStatus.ABANDONED, "done with", null, null, 0L);
    }

    @Test
    void anAttemptCannotBeOpenedAgainWhileAnotherHoldsItsLearner() {
        final Learner learner = learner("contended");
        final long mine = attempts.opened(new Attempt(learner, DOC, "F", "T", 1L, "node-1",
                PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        attempts.closed(mine, AttemptStatus.PROMOTED, "Promoted 1.0", "rule-9", 1.0, 0L);
        final long theirs = attempts.opened(new Attempt(learner, DOC, "F", "T", 2L, "node-2",
                PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();

        assertThat(attempts.reopened(mine, now(), now() + 60_000L))
                .describedAs("a person is told rather than two attempts learning for one learner").isFalse();
        assertThat(attempts.byId(mine).orElseThrow().status()).isEqualTo(AttemptStatus.PROMOTED);
        attempts.closed(theirs, AttemptStatus.ABANDONED, "done with", null, null, 0L);
        assertThat(attempts.reopened(mine, now(), now() + 60_000L))
                .describedAs("and may run again once the learner is free").isTrue();
        attempts.closed(mine, AttemptStatus.ABANDONED, "done with", null, null, 0L);
    }

    @Test
    void whatABindingProducedIsFoundWhereverItWasProduced() {
        // Design 01 §7.3 rule 3: retracting a binding means finding the inputs whose outputs it produced,
        // and the node that retracts is rarely the node that produced them. The bindings are on the
        // output stream's attributes too, where a person reads them, but a custom stream attribute is not
        // a field stroom can query.
        final DocRef fragment = PipelineDoc.buildDocRef().uuid("fragment-1").name("door-v1").build();
        outputs.emitted(1L, "pipeline-1", new Produced(DOC, "rule-1", fragment,
                RecordBoundary.ofElement("Event").atDepth(1), false, 0.97));
        outputs.emitted(2L, "pipeline-2", new Produced(DOC, "rule-1", fragment, null, false, 0.98));
        outputs.emitted(3L, null, new Produced(DOC, "rule-2", fragment, null, true, 0.5));

        assertThat(outputs.boundBy("rule-1", "fragment-1"))
                .extracting(Replayable::inputId, Replayable::pipeline)
                .describedAs("each through the pipeline that produced it, which may not be this one")
                .containsExactly(tuple(1L, "pipeline-1"), tuple(2L, "pipeline-2"));
        assertThat(outputs.boundBy("rule-2", "fragment-1")).extracting(Replayable::pipeline)
                .describedAs("a stream no pipeline produced names none").containsExactly((String) null);
        assertThat(outputs.boundBy("rule-3", "fragment-1")).isEmpty();

        // A binding keeps its uuid when it is rebound (§7.3 rule 3), so what an earlier generation produced
        // is not what a retraction of this one asks for.
        final DocRef rebound = PipelineDoc.buildDocRef().uuid("fragment-2").name("door-v2").build();
        outputs.emitted(4L, "pipeline-1", new Produced(DOC, "rule-1", rebound, null, false, 0.99));
        assertThat(outputs.boundBy("rule-1", "fragment-2")).extracting(Replayable::inputId)
                .describedAs("only what this binding produced").containsExactly(4L);
        assertThat(outputs.boundBy("rule-1", "fragment-1")).extracting(Replayable::inputId)
                .describedAs("and the generation before it is left alone").containsExactly(1L, 2L);

        // A stream processed twice by one pipeline under one binding is one thing to replay; by two
        // pipelines it is two outputs, and a retraction asks for both, because both are still there.
        outputs.emitted(1L, "pipeline-3", new Produced(DOC, "rule-1", fragment, null, false, 0.99));
        assertThat(outputs.boundBy("rule-1", "fragment-1"))
                .extracting(Replayable::inputId, Replayable::pipeline)
                .containsExactly(tuple(1L, "pipeline-1"), tuple(2L, "pipeline-2"), tuple(1L, "pipeline-3"));
        outputs.emitted(1L, "pipeline-1", new Produced(DOC, "rule-1", fragment,
                RecordBoundary.ofElement("Event").atDepth(1), false, 0.95));
        assertThat(outputs.boundBy("rule-1", "fragment-1"))
                .describedAs("and the same pipeline again is the same output, said again")
                .hasSize(3);

        // What an as-processed reprocess asks (design 01 §7.3): what was bound when this input was last
        // processed by this pipeline, so that the fragment which produced the output produces it again.
        assertThat(outputs.asProcessed(DOC, 2L, "pipeline-2")).get()
                .extracting(Produced::bindingUuid, bindings -> bindings.fragment().getUuid(), Produced::provisional)
                .containsExactly("rule-1", "fragment-1", false);
        assertThat(outputs.asProcessed(DOC, 1L, "pipeline-1")).get()
                .describedAs("under the boundary that produced it, not the binding's boundary today")
                .extracting(bindings -> bindings.boundary().getElement(),
                        bindings -> bindings.boundary().splitDepth().orElseThrow())
                .containsExactly("Event", 1);
        assertThat(outputs.asProcessed(DOC, 1L, "pipeline-3")).describedAs("another pipeline's run of the same "
                                                                     + "input is another output, not a "
                                                                     + "replacement for it")
                .get().extracting(Produced::score).isEqualTo(0.99);
        assertThat(outputs.asProcessed(DOC, 1L, "pipeline-9")).isEmpty();
        assertThat(outputs.asProcessed(DOC, 3L, null))
                .describedAs("an output of no pipeline is of no pipeline rather than of any")
                .isPresent();
        assertThat(outputs.asProcessed(DOC, 3L, "pipeline-1")).isEmpty();
        assertThat(outputs.asProcessed(DOC, 99L, "pipeline-1"))
                .describedAs("an input nothing remembers cannot be processed again as it was").isEmpty();

        // A pipeline may hold two supervised stages (design 01 §3), and they record what each made of
        // the same input on the same pipeline. Whose stage is asking is what tells the two apart:
        // without it the extraction stage would be answered with the transformation stage's fragment,
        // which would then be run over raw bytes.
        final DocRef transform = PipelineDoc.buildDocRef().uuid("fragment-t").name("transform").build();
        outputs.emitted(2L, "pipeline-2", new Produced("doc-2", "rule-t", transform, null, false, 0.97));
        assertThat(outputs.asProcessed(DOC, 2L, "pipeline-2")).get()
                .describedAs("the stage that asked, not the one that recorded last")
                .extracting(bindings -> bindings.fragment().getUuid()).isEqualTo("fragment-1");
        assertThat(outputs.asProcessed("doc-2", 2L, "pipeline-2")).get()
                .extracting(bindings -> bindings.fragment().getUuid()).isEqualTo("fragment-t");

        // Where each record began and ended in the stream it was cut from (§12 item 21), so that a
        // fault found at an event is put to the model with the record that made it.
        outputs.emitted(5L, "pipeline-1", new Produced(DOC, "rule-1", fragment, null, false, 0.9),
                List.of(new TextRange(DefaultLocation.of(2, 1), DefaultLocation.of(2, 40)),
                        new TextRange(DefaultLocation.of(3, 1), DefaultLocation.of(4, 12))));
        assertThat(outputs.span(DOC, 5L, "pipeline-1", 0)).get()
                .extracting(span -> span.getFrom().getLineNo(), span -> span.getTo().getColNo())
                .containsExactly(2, 40);
        assertThat(outputs.span(DOC, 5L, "pipeline-1", 1)).get()
                .describedAs("a record that spans two lines says both")
                .extracting(span -> span.getFrom().getLineNo(), span -> span.getTo().getLineNo())
                .containsExactly(3, 4);
        assertThat(outputs.span(DOC, 5L, "pipeline-1", 2))
                .describedAs("a record past what was cut has no span").isEmpty();
        assertThat(outputs.span(DOC, 5L, "pipeline-9", 0))
                .describedAs("and another pipeline's run is another output").isEmpty();
        assertThat(outputs.span(DOC, 1L, "pipeline-1", 0))
                .describedAs("an output recorded with no spans has none to give").isEmpty();
        // And a later run with nothing to say about the records — an as-processed reprocess, which has
        // no parser to ask — leaves what was recorded where it is.
        outputs.emitted(5L, "pipeline-1", new Produced(DOC, "rule-1", fragment, null, false, 0.9));
        assertThat(outputs.span(DOC, 5L, "pipeline-1", 0))
                .describedAs("knowing nothing does not erase what was known").isPresent();

        // Kept for as long as a node is told to keep them, and no longer: a row per output stream is a
        // row per stream (design 01 §12 item 8).
        assertThat(outputs.prune(now() - 60_000L)).describedAs("nothing old enough yet").isZero();
        assertThat(outputs.prune(now() + 60_000L)).isGreaterThanOrEqualTo(3);
        assertThat(outputs.boundBy("rule-1", "fragment-1")).isEmpty();
    }

    @Test
    void theSupervisorSeesEveryDocumentsAttemptsNarrowedByWhatAPersonKnows() {
        // A28: the Supervisor is a view of every document's attempts, not a tab on one, filtered by the
        // things a person looking for an attempt would know.
        final String doc = "doc-" + System.nanoTime();
        final String other = "doc-" + System.nanoTime() + "-other";
        final long first = attempts.opened(new Attempt(new Learner("pipeline-" + doc, "shapeshifterAi"), doc,
                "DOOR-ACCESS", "Raw Events", 1L, "node-1", PromotionMode.AUTOMATIC, now() + 60_000L), now())
                .orElseThrow();
        attempts.closed(first, AttemptStatus.PROMOTED, "Promoted 1.0", "rule-1", 1.0, 10L);
        final long second = attempts.opened(new Attempt(new Learner("pipeline-" + doc + "-other-feed",
                "shapeshifterAi"), doc, "OTHER-FEED", "Raw Events", 2L, "node-2", PromotionMode.REVIEW,
                now() + 60_000L), now()).orElseThrow();
        final long elsewhere = attempts.opened(new Attempt(new Learner("pipeline-" + other, "shapeshifterAi"),
                other, "DOOR-ACCESS", "Raw Events", 3L, "node-1", PromotionMode.AUTOMATIC, now() + 60_000L),
                now()).orElseThrow();

        assertThat(attempts.found(new AttemptCriteria(), List.of(doc, other)).attempts())
                .extracting(Recorded::id)
                .describedAs("every document the person may see, newest first")
                .contains(elsewhere, second, first);
        assertThat(attempts.found(new AttemptCriteria(), List.of(other)).attempts())
                .extracting(Recorded::id)
                .describedAs("and only those: an attempt of a document they may not see is not theirs to "
                             + "know about")
                .doesNotContain(first, second);
        assertThat(attempts.found(new AttemptCriteria(), List.of()).total())
                .describedAs("nor counted, or the count says how many exist elsewhere").isZero();
        assertThat(found(new AttemptCriteria(null, null, doc, null, null, null)))
                .containsExactly(second, first);
        assertThat(found(new AttemptCriteria(null, null, doc, "OTHER-FEED", null, null)))
                .containsExactly(second);
        assertThat(found(new AttemptCriteria(null, null, doc, null, PromotionMode.REVIEW, null)))
                .containsExactly(second);
        assertThat(found(new AttemptCriteria(null, null, doc, null, null,
                List.of(AttemptStatus.PROMOTED))))
                .describedAs("and by what it came to").containsExactly(first);

        // A page, and how many there are to page through.
        final Page page = attempts.found(new AttemptCriteria(new PageRequest(0, 1), null, doc, null, null,
                null), List.of(doc, other));
        assertThat(page.attempts()).hasSize(1);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.attempts().get(0).turns())
                .describedAs("without their turns: a page of transcripts is a page nobody reads").isEmpty();
        attempts.closed(second, AttemptStatus.ABANDONED, "done with", null, null, 0L);
        attempts.closed(elsewhere, AttemptStatus.ABANDONED, "done with", null, null, 0L);
    }

    private List<Long> found(final AttemptCriteria criteria) {
        return attempts.found(criteria, List.of(criteria.getDocUuid())).attempts().stream()
                .map(Recorded::id)
                .toList();
    }

    @Test
    void whatIsOverIsPrunedAndWhatIsNotIsKept() {
        // Design 01 §12 item 8: one row per attempt and one per turn is the fastest-growing thing this
        // feature writes. An attempt still learning, still waiting for the model, or waiting for a person
        // to decide is never pruned however old it is: age is not what says an attempt is over.
        final String doc = "doc-" + System.nanoTime() + "-prune";
        final long finished = attempts.opened(new Attempt(
                new Learner("pipeline-" + doc + "-finished", "shapeshifterAi"),
                doc, "F", "T", 1L, "node-1", PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        attempts.turn(finished, new Turn(1, "chain", 1, QuestionKind.CHAIN, "Chain: choose", "DSParser",
                "local-model", StepOutcome.PASSED));
        attempts.closed(finished, AttemptStatus.PROMOTED, "Promoted", "rule-1", 1.0, 0L);
        final long waiting = attempts.opened(new Attempt(
                new Learner("pipeline-" + doc + "-waiting", "shapeshifterAi"),
                doc, "F", "T", 2L, "node-1", PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        attempts.parked(waiting, AttemptStatus.AWAITING_MODEL, now() + 60_000L, 0L);
        final long reviewing = attempts.opened(new Attempt(
                new Learner("pipeline-" + doc + "-reviewing", "shapeshifterAi"),
                doc, "F", "T", 3L, "node-1", PromotionMode.REVIEW, now() + 60_000L), now()).orElseThrow();
        attempts.closed(reviewing, AttemptStatus.AWAITING_REVIEW, "Drafted", "rule-2", 0.9, 0L);
        final long learning = attempts.opened(new Attempt(
                new Learner("pipeline-" + doc + "-learning", "shapeshifterAi"),
                doc, "F", "T", 4L, "node-1", PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();

        assertThat(attempts.prune(now() - 60_000L)).describedAs("nothing is old enough yet").isZero();
        final int pruned = attempts.prune(now() + 60_000L);

        assertThat(pruned).isGreaterThanOrEqualTo(1);
        assertThat(attempts.byId(finished)).describedAs("the finished one is gone").isEmpty();
        assertThat(attempts.byId(waiting)).describedAs("the one waiting for the model is not").isPresent();
        assertThat(attempts.byId(reviewing)).describedAs("nor the one waiting for a person").isPresent();
        assertThat(attempts.byId(learning)).describedAs("nor the one still learning").isPresent();

        attempts.closed(waiting, AttemptStatus.ABANDONED, "done with", null, null, 0L);
        attempts.closed(reviewing, AttemptStatus.ABANDONED, "done with", null, null, 0L);
        attempts.closed(learning, AttemptStatus.ABANDONED, "done with", null, null, 0L);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /// A supervisor's messages as rows (A46): kept per learner, read oldest first, and taken back by id.
    ///
    /// Per learner and not per attempt, which is the ruling's first decision: what a person knows is
    /// about the feed, so a hint outlives the attempt that first used it and the relearning of A29 months
    /// later carries it too.
    @Test
    void whatASupervisorSaysIsKeptAgainstTheLearner() {
        final long first = guidance.given(LEARNER, DOC, "Timestamps are local, not UTC.", "jo");
        final long second = guidance.given(LEARNER, DOC, "Column four is a terminal id.", "sam");
        guidance.given(OTHER, DOC, "Nothing to do with the door.", "jo");

        assertThat(guidance.standing(LEARNER))
                .describedAs("oldest first, so that a later hint correcting an earlier one reads as one")
                .extracting(Given::id, Given::message, Given::author)
                .containsExactly(
                        tuple(first, "Timestamps are local, not UTC.", "jo"),
                        tuple(second, "Column four is a terminal id.", "sam"));
        assertThat(guidance.standing(LEARNER).get(0).timeMs()).isPositive();
        assertThat(guidance.standing(OTHER))
                .describedAs("a hint is said to one learner, and a neighbour's is not this one's")
                .hasSize(1);
        assertThat(guidance.standing(learner("nobody"))).isEmpty();

        guidance.withdraw(DOC, first);
        assertThat(guidance.standing(LEARNER))
                .describedAs("a hint that turned out to be wrong is worse than no hint")
                .extracting(Given::id).containsExactly(second);
        guidance.withdraw("another-document", second);
        assertThat(guidance.standing(LEARNER))
                .describedAs("and taking one back is by document as well as id, so a mistyped id cannot "
                             + "take back somebody else's")
                .hasSize(1);

        guidance.withdraw(DOC, second);
        guidance.standing(OTHER).forEach(given -> guidance.withdraw(DOC, given.id()));
    }

}
