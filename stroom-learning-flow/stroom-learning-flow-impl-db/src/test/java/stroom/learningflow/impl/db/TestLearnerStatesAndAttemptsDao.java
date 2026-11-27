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

package stroom.learningflow.impl.db;

import stroom.docref.DocRef;
import stroom.learningflow.learning.AskedWith;
import stroom.learningflow.shared.AttemptCriteria;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckResult;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.StopCause;
import stroom.learningflow.shared.StopReport;
import stroom.learningflow.shared.Suggestion;
import stroom.learningflow.shared.SuggestionAction;
import stroom.learningflow.shared.Template;
import stroom.learningflow.shared.TurnScore;
import stroom.learningflow.stage.Attempts.Attempt;
import stroom.learningflow.stage.Attempts.Grant;
import stroom.learningflow.stage.Attempts.Page;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Attempts.Review;
import stroom.learningflow.stage.Attempts.Turn;
import stroom.learningflow.stage.Guidance.Given;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerStates.Rolling;
import stroom.learningflow.stage.Produced;
import stroom.learningflow.stage.Replayable;
import stroom.learningflow.stage.Spend.Spent;
import stroom.pipeline.shared.PipelineDoc;
import stroom.util.shared.DefaultLocation;
import stroom.util.shared.PageRequest;
import stroom.util.shared.TextRange;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/// Tests [LearnerStatesDao], [AttemptsDao], [GuidanceDao], [SpendDao] and [OutputsDao] against a real
/// database.
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

    /// Makes a learner unique to this run, so that rows left behind by a failed earlier run do not
    /// interfere.
    private static Learner learner(final String name) {
        return new Learner("pipeline-" + DOC + "-" + name, "learningFlow");
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
        // No score is returned until the learner has served enough records to fill its memory.
        assertThat(states.scored(LEARNER, DOC, 1.0, 4, 10)).isEmpty();
        assertThat(states.scored(LEARNER, DOC, 1.0, 4, 10)).isEmpty();

        final OptionalDouble rolling = states.scored(LEARNER, DOC, 0.0, 4, 10);

        assertThat(rolling).isPresent();
        assertThat(rolling.getAsDouble()).describedAs("eight good records, then four bad, remembering ten")
                .isEqualTo(8.0 / 12);
        // A reset starts the score again, as a promotion or a retraction does.
        states.reset(LEARNER);
        assertThat(states.scored(LEARNER, DOC, 1.0, 1, 1)).hasValue(1.0);
    }

    @Test
    void theSkipsAreKeptBesideTheRecordsTheyBelongTo() {
        // Nothing limits how many records a translation may declare it skipped, so the count has to be
        // kept and shown to people. It is kept with the rolling score, over the same memory.
        states.scored(LEARNER, DOC, 1.0, 10, 2, 100);
        states.scored(LEARNER, DOC, 1.0, 10, 3, 100);

        assertThat(states.rolling(LEARNER)).get()
                .extracting(Rolling::records, Rolling::skips)
                .containsExactly(20, 5);
    }

    @Test
    void skipsGoWithTheRecordsTheMemoryForgets() {
        // Otherwise a translation that once skipped many records would go on looking as though it still
        // did, for as long as the learner existed.
        states.scored(LEARNER, DOC, 1.0, 10, 10, 10);
        states.scored(LEARNER, DOC, 1.0, 10, 0, 10);

        assertThat(states.rolling(LEARNER)).get()
                .describedAs("ten skipped records remembered, then ten clean ones over a memory of ten")
                .extracting(Rolling::records, Rolling::skips)
                .containsExactly(20, 10);

        states.scored(LEARNER, DOC, 1.0, 10, 0, 10);
        assertThat(states.rolling(LEARNER).orElseThrow().skips())
                .describedAs("and the skipped ones keep falling out of memory")
                .isEqualTo(5);
    }

    @Test
    void aResetForgetsTheSkipsAsWellAsTheScore() {
        states.scored(LEARNER, DOC, 1.0, 10, 4, 10);
        states.reset(LEARNER);
        states.scored(LEARNER, DOC, 1.0, 10, 0, 10);

        assertThat(states.rolling(LEARNER).orElseThrow().skips()).isZero();
    }

    /// The first streams of a new learner are often served at the same time, and each finds no state row
    /// and creates it. If the row were created under a shared lock, the following `FOR UPDATE` would
    /// deadlock and the database would abort one transaction, failing its stream; created under the
    /// exclusive lock, they queue instead. This scores several new learners from many threads at once and
    /// checks that every score is counted.
    @Test
    void theFirstStreamsOfANewLearnerScoredAtOnceAllCount() throws Exception {
        final int threads = 8;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 5; round++) {
                final Learner fresh = new Learner("fresh-" + System.nanoTime(), "learningFlow");
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
        // A reset clears what was learned, not who is learning. A promotion resets the learner while the
        // attempt that promoted it is still open, and that attempt must not lose its claim to another node.
        final Learner learner = learner("reset");
        attempts.opened(new Attempt(learner, DOC, "F", "T", 1L, "node-1",
                PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();

        states.reset(learner);

        assertThat(attempts.opened(new Attempt(learner, DOC, "F", "T", 2L, "node-2",
                PromotionMode.AUTOMATIC, now() + 60_000L), now())).isEmpty();
    }

    @Test
    void anAttemptIsCarriedOnByItsOwnNodeOrByWhoeverFindsItLapsed() {
        // Resuming an attempt means taking its learner's claim. The heartbeat stops a slow model call from
        // making a node lose the claim it is learning under.
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

        // What it spent on each run is added up, not overwritten; otherwise a resumed attempt's cost would
        // only be that of its last run.
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
        // An attempt parked for a person (for example, out of budget) is left alone by the worker, no other
        // attempt can open for its learner, and its lease never expires. Only a person can move it on,
        // which happens outside the DAO.
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
    void whyAnAttemptStoppedIsKeptInPartsAndClearedWhenItIsTakenUpAgain() {
        // A stopped attempt's report is read back as it was written. A reopened attempt is no longer
        // stopped, so it has no report, and an attempt that fails also records why in parts.
        final Learner learner = learner("report");
        final long id = attempts.opened(new Attempt(learner, DOC, "F", "T", 1L, "node-1",
                PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        final StopReport report = new StopReport(StopCause.TIME_BUDGET, "parser",
                "Ran out of time at \"Write the parser\" (question 6).",
                List.of("Each run may take 5 minutes; this one had taken 5 minutes 2 seconds."),
                List.of(new Suggestion("Give it more time.", SuggestionAction.GIVE_MORE),
                        new Suggestion("Raise \"Time for each run\" on the Setup tab.",
                                SuggestionAction.CHANGE_LIMITS)));

        attempts.parked(id, report.getError(), report, 10L);
        final Recorded parked = attempts.byId(id).orElseThrow();
        assertThat(parked.decision()).isEqualTo(report.getError());
        assertThat(parked.report()).isEqualTo(report);

        assertThat(attempts.reopened(id, now(), now() + 60_000L)).isTrue();
        assertThat(attempts.byId(id).orElseThrow().report()).describedAs("taken up again").isNull();

        final StopReport failed = new StopReport(StopCause.MODEL_TIMEOUT, null, "The model didn't reply in time.",
                List.of(), List.of(new Suggestion("Open the model's document.", SuggestionAction.OPEN_MODEL)));
        attempts.closed(id, AttemptStatus.ERROR, failed.getError(), null, null, failed, 1L);
        assertThat(attempts.byId(id).orElseThrow().report()).isEqualTo(failed);
    }

    @Test
    void aGrantIsAddedToTheAttemptAndKeptOnItsRow() {
        // A grant of extra budget applies to this attempt alone. Repeated grants are added together, and
        // the total is read back with the row.
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
    void theEvidenceADraftWasJudgedOnAndWhoDecidedAreKeptOnItsRow() {
        // The evidence that a person approving a draft is shown, and who decided and when.
        final Learner learner = learner("review");
        final long id = attempts.opened(new Attempt(learner, DOC, "F", "T", 1L, "node-1",
                PromotionMode.REVIEW, now() + 60_000L), now()).orElseThrow();
        assertThat(attempts.byId(id).orElseThrow().review()).isEqualTo(Review.NONE);
        final List<CheckResult> evidence = List.of(
                new CheckResult(Check.COMPILE, CheckState.GATE, 1.0, 1.0),
                new CheckResult(Check.TIMES, CheckState.ADVICE, 0.5, 1.0));

        attempts.closed(id, AttemptStatus.AWAITING_REVIEW, "Awaiting review", "rule-r", 0.75, 100L);
        attempts.evidence(id, evidence);
        attempts.decided(DOC, "rule-r", AttemptStatus.PROMOTED, "Approved", "jo");

        final Review review = attempts.byId(id).orElseThrow().review();
        assertThat(review.evidence()).isEqualTo(evidence);
        assertThat(review.reviewedBy()).isEqualTo("jo");
        assertThat(review.reviewedTimeMs()).isNotNull();
    }

    @Test
    void aSendingToTheExhaustionStepStandsOnTheRowUntilTheAttemptCloses() {
        // A person's request to go to the exhaustion step is read back with the row for the worker to act
        // on. It survives a park and the reopening that follows, and is cleared when the attempt closes.
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
        // Spending is counted across the cluster: a budget split between nodes would not limit the total,
        // and heavy spending on one node would be invisible to the others.
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

        // An expired window starts the count again, since the limit applies to recent spending.
        final Spent afresh = spend.spent(DOC, 0L);
        assertThat(afresh.tokens()).isZero();
        assertThat(spend.record(DOC, 100L, 1, 0L).tokens()).isEqualTo(100L);
    }

    @Test
    void theWordsARunWasAskedWithAreRowsToo() {
        // A parked attempt has to resume with the same templates and instructions it began with, and the
        // node that resumes it is rarely the node that opened it, so they are stored in the database
        // rather than in memory.
        final AskedWith asked = new AskedWith(9,
                Map.of(Template.CHAIN, "Pick one:${elements}", Template.SPLIT, "Cut it:${sample}"),
                "Badge readers.", "7.13-beta.3");
        final Learner learner = learner("asked-with");
        final long id = attempts.opened(new Attempt(learner, DOC, "DOOR-ACCESS", "Raw Events", 42L, "node-1",
                PromotionMode.AUTOMATIC, now() + 60_000L, asked), now()).orElseThrow();

        final AskedWith read = attempts.byId(id).orElseThrow().attempt().askedWith();

        assertThat(read.known()).isTrue();
        assertThat(read.builtInVersion()).isEqualTo(9);
        assertThat(read.instructions()).isEqualTo("Badge readers.");
        assertThat(read.build()).isEqualTo("7.13-beta.3");
        assertThat(read.templates()).isEqualTo(asked.templates());

        // Parked for the worker, which is the case this protects: the worker that resumes it, usually on
        // another node, must find the templates there.
        attempts.parked(id, AttemptStatus.AWAITING_MODEL, now() + 60_000L, 10L);
        assertThat(attempts.awaiting(100))
                .filteredOn(recorded -> recorded.id() == id)
                .singleElement()
                .extracting(recorded -> recorded.attempt().askedWith())
                .isEqualTo(asked);
        attempts.closed(id, AttemptStatus.ABANDONED, "done", null, null, 0L);
    }

    @Test
    void anAttemptOpenedBeforeItsWordsWereKeptSaysSoRatherThanGuessing() {
        // This is the case for every attempt made before the templates were recorded. Offering today's
        // templates as though they were the ones used would be worse than saying they are unknown.
        final Learner learner = learner("asked-with-unknown");
        final long id = attempts.opened(new Attempt(learner, DOC, "DOOR-ACCESS", "Raw Events", 42L, "node-1",
                PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();

        final AskedWith read = attempts.byId(id).orElseThrow().attempt().askedWith();

        assertThat(read).isEqualTo(AskedWith.UNKNOWN);
        assertThat(read.known()).isFalse();
        attempts.closed(id, AttemptStatus.ABANDONED, "done", null, null, 0L);
    }

    @Test
    void anAttemptAndItsTurnsAreRowsThatOutliveTheNode() {
        // What was asked and answered survives the node that asked it, and the turns are read back in the
        // order they were asked.
        final long id = attempts.opened(new Attempt(LEARNER, DOC, "DOOR-ACCESS", "Raw Events", 42L, "node-1",
                PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        attempts.turn(id, new Turn(1, "chain", 1, QuestionKind.CHAIN, "CHAIN: choose from [DSParser]",
                "DSParser -> XSLTFilter", "local-model", StepOutcome.PASSED));
        attempts.turn(id, new Turn(2, "configure", 2, QuestionKind.CONFIGURE, "CONFIGURE: DSParser",
                "<dataSplitter/>", "local-model", StepOutcome.COVERAGE_SHORT,
                new TurnScore(Check.COVERAGE, 0.41, 0.8), List.of()));

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
                .isEqualTo(new TurnScore(Check.COVERAGE, 0.41, 0.8));
        assertThat(read.turns().get(0).score()).describedAs("a turn no scorer decided").isNull();

        // The learner's next attempt, once this one has closed, with no stream behind it (as for an
        // Improve run).
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
        // The open attempt row is the learner's claim. A parked attempt is still learning, and so is one
        // whose lease has expired: it is resumed, not replaced.
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
        // A person can answer a turn instead of the model, or edit an answer and re-run from there. The
        // turns after the amended one followed from the old answer, so they are deleted and the walk
        // produces them again.
        final Learner learner = learner("amend");
        final long id = attempts.opened(new Attempt(learner, DOC, "F", "T", 1L, "node-1",
                PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        attempts.turn(id, new Turn(1, "chain", 1, QuestionKind.CHAIN, "Chain: choose from [DSParser]",
                "DSParser", "local-model", StepOutcome.PASSED));
        attempts.turn(id, new Turn(2, "configure", 1, QuestionKind.CONFIGURE, "Configure: DSParser",
                "<dataSplitter/>", "local-model", StepOutcome.COVERAGE_SHORT,
                new TurnScore(Check.COVERAGE, 0.41, 0.8), List.of()));
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
        // An attempt whose node died still holds its claim, and a person may still answer it and run it
        // again. Reopening it gives the claim a new expiry; otherwise a worker would find it expired and
        // take it before the answer was recorded.
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
        // Retracting a binding means finding the inputs it produced output for, and the node that retracts
        // is rarely the node that produced them. The bindings are also on the output stream's attributes,
        // where a person reads them, but Stroom cannot query a custom stream attribute.
        final DocRef fragment = PipelineDoc.buildDocRef().uuid("fragment-1").name("door-v1").build();
        outputs.emitted(1L, "pipeline-1", new Produced(DOC, "rule-1", fragment,
                RecordBoundary.ofElement("Event").atDepth(1), 0.97));
        outputs.emitted(2L, "pipeline-2", new Produced(DOC, "rule-1", fragment, null, 0.98));
        outputs.emitted(3L, null, new Produced(DOC, "rule-2", fragment, null, 0.5));

        assertThat(outputs.boundBy("rule-1", "fragment-1"))
                .extracting(Replayable::inputId, Replayable::pipeline)
                .describedAs("each through the pipeline that produced it, which may not be this one")
                .containsExactly(tuple(1L, "pipeline-1"), tuple(2L, "pipeline-2"));
        assertThat(outputs.boundBy("rule-2", "fragment-1")).extracting(Replayable::pipeline)
                .describedAs("a stream no pipeline produced names none").containsExactly((String) null);
        assertThat(outputs.boundBy("rule-3", "fragment-1")).isEmpty();

        // A binding keeps its UUID when it is rebound to a new fragment, so the outputs of the earlier
        // fragment are not included when the later one is retracted.
        final DocRef rebound = PipelineDoc.buildDocRef().uuid("fragment-2").name("door-v2").build();
        outputs.emitted(4L, "pipeline-1", new Produced(DOC, "rule-1", rebound, null, 0.99));
        assertThat(outputs.boundBy("rule-1", "fragment-2")).extracting(Replayable::inputId)
                .describedAs("only what this binding produced").containsExactly(4L);
        assertThat(outputs.boundBy("rule-1", "fragment-1")).extracting(Replayable::inputId)
                .describedAs("and the generation before it is left alone").containsExactly(1L, 2L);

        // A stream processed twice by one pipeline under one binding is one row to replay. Processed by two
        // pipelines it has two outputs, and a retraction finds both, because both still exist.
        outputs.emitted(1L, "pipeline-3", new Produced(DOC, "rule-1", fragment, null, 0.99));
        assertThat(outputs.boundBy("rule-1", "fragment-1"))
                .extracting(Replayable::inputId, Replayable::pipeline)
                .containsExactly(tuple(1L, "pipeline-1"), tuple(2L, "pipeline-2"), tuple(1L, "pipeline-3"));
        outputs.emitted(1L, "pipeline-1", new Produced(DOC, "rule-1", fragment,
                RecordBoundary.ofElement("Event").atDepth(1), 0.95));
        assertThat(outputs.boundBy("rule-1", "fragment-1"))
                .describedAs("and the same pipeline again is the same output, said again")
                .hasSize(3);

        // Reprocessing a stream as it was processed asks what was bound when this pipeline last processed
        // the input, so that the fragment which produced the output produces it again.
        assertThat(outputs.asProcessed(DOC, 2L, "pipeline-2")).get()
                .extracting(Produced::bindingUuid, bindings -> bindings.fragment().getUuid())
                .containsExactly("rule-1", "fragment-1");
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

        // A pipeline may contain two Smart Pipeline elements under different documents, each recording
        // its own output for the same input. The document tells them apart; without it, an element that
        // parses raw input could be given the fragment of an element that transforms XML, which would
        // then be run over raw bytes.
        final DocRef transform = PipelineDoc.buildDocRef().uuid("fragment-t").name("transform").build();
        outputs.emitted(2L, "pipeline-2", new Produced("doc-2", "rule-t", transform, null, 0.97));
        assertThat(outputs.asProcessed(DOC, 2L, "pipeline-2")).get()
                .describedAs("the stage that asked, not the one that recorded last")
                .extracting(bindings -> bindings.fragment().getUuid()).isEqualTo("fragment-1");
        assertThat(outputs.asProcessed("doc-2", 2L, "pipeline-2")).get()
                .extracting(bindings -> bindings.fragment().getUuid()).isEqualTo("fragment-t");

        // Output rows are kept only for the configured retention period, since there is a row for every
        // stream processed.
        assertThat(outputs.prune(now() - 60_000L)).describedAs("nothing old enough yet").isZero();
        assertThat(outputs.prune(now() + 60_000L)).isGreaterThanOrEqualTo(3);
        assertThat(outputs.boundBy("rule-1", "fragment-1")).isEmpty();
    }

    @Test
    void theSupervisorSeesEveryDocumentsAttemptsNarrowedByWhatAPersonKnows() {
        // The Supervisor lists the attempts of every document the person may see, not just one, filtered
        // by things a person looking for an attempt would know.
        final String doc = "doc-" + System.nanoTime();
        final String other = "doc-" + System.nanoTime() + "-other";
        final long first = attempts.opened(new Attempt(new Learner("pipeline-" + doc, "learningFlow"), doc,
                "DOOR-ACCESS", "Raw Events", 1L, "node-1", PromotionMode.AUTOMATIC, now() + 60_000L), now())
                .orElseThrow();
        attempts.closed(first, AttemptStatus.PROMOTED, "Promoted 1.0", "rule-1", 1.0, 10L);
        final long second = attempts.opened(new Attempt(new Learner("pipeline-" + doc + "-other-feed",
                "learningFlow"), doc, "OTHER-FEED", "Raw Events", 2L, "node-2", PromotionMode.REVIEW,
                now() + 60_000L), now()).orElseThrow();
        final long elsewhere = attempts.opened(new Attempt(new Learner("pipeline-" + other, "learningFlow"),
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
        final Page ofOnePipeline = attempts.found(new AttemptCriteria(), List.of(doc, other),
                List.of("pipeline-" + doc));
        assertThat(ofOnePipeline.attempts())
                .extracting(Recorded::id)
                .describedAs("of the pipelines the person may view, and of no other")
                .containsExactly(first);
        assertThat(ofOnePipeline.total())
                .describedAs("counted the same way, so pages don't come back short")
                .isOne();
        assertThat(attempts.found(new AttemptCriteria(), List.of(doc, other), List.of()).total())
                .describedAs("no pipeline the person may view, nothing counted").isZero();
        assertThat(found(new AttemptCriteria(null, null, doc, null, null, null)))
                .containsExactly(second, first);
        assertThat(found(new AttemptCriteria(null, null, doc, "OTHER-FEED", null, null)))
                .containsExactly(second);
        assertThat(found(new AttemptCriteria(null, null, doc, null, PromotionMode.REVIEW, null)))
                .containsExactly(second);
        assertThat(found(new AttemptCriteria(null, null, doc, null, null,
                List.of(AttemptStatus.PROMOTED))))
                .describedAs("and by what it came to").containsExactly(first);

        // A page, and the total number of attempts to page through.
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
        // One row per attempt and one per turn grows faster than anything else this feature writes, so old
        // attempts are pruned. An attempt still learning, waiting for the model, or waiting for a person to
        // decide is never pruned however old it is, because age does not mean it has finished.
        final String doc = "doc-" + System.nanoTime() + "-prune";
        final long finished = attempts.opened(new Attempt(
                new Learner("pipeline-" + doc + "-finished", "learningFlow"),
                doc, "F", "T", 1L, "node-1", PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        attempts.turn(finished, new Turn(1, "chain", 1, QuestionKind.CHAIN, "Chain: choose", "DSParser",
                "local-model", StepOutcome.PASSED));
        attempts.closed(finished, AttemptStatus.PROMOTED, "Promoted", "rule-1", 1.0, 0L);
        final long waiting = attempts.opened(new Attempt(
                new Learner("pipeline-" + doc + "-waiting", "learningFlow"),
                doc, "F", "T", 2L, "node-1", PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        attempts.parked(waiting, AttemptStatus.AWAITING_MODEL, now() + 60_000L, 0L);
        final long reviewing = attempts.opened(new Attempt(
                new Learner("pipeline-" + doc + "-reviewing", "learningFlow"),
                doc, "F", "T", 3L, "node-1", PromotionMode.REVIEW, now() + 60_000L), now()).orElseThrow();
        attempts.closed(reviewing, AttemptStatus.AWAITING_REVIEW, "Drafted", "rule-2", 0.9, 0L);
        final long learning = attempts.opened(new Attempt(
                new Learner("pipeline-" + doc + "-learning", "learningFlow"),
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

    /// Guidance is kept per learner, read back oldest first, and withdrawn by id.
    ///
    /// It is kept per learner rather than per attempt because what a person knows is about the feed, so a
    /// message outlives the attempt that first used it, and a relearning months later uses it too.
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

        guidance.withdraw(LEARNER, DOC, first);
        assertThat(guidance.standing(LEARNER))
                .describedAs("a hint that turned out to be wrong is worse than no hint")
                .extracting(Given::id).containsExactly(second);
        guidance.withdraw(LEARNER, "another-document", second);
        assertThat(guidance.standing(LEARNER))
                .describedAs("and taking one back is by document as well as id, so a mistyped id cannot "
                             + "take back somebody else's")
                .hasSize(1);
        guidance.withdraw(OTHER, DOC, second);
        assertThat(guidance.standing(LEARNER))
                .describedAs("nor by naming another learner under the same document, whose pipeline's "
                             + "permission is not this one's")
                .hasSize(1);

        guidance.withdraw(LEARNER, DOC, second);
        guidance.standing(OTHER).forEach(given -> guidance.withdraw(OTHER, DOC, given.id()));
    }

}
