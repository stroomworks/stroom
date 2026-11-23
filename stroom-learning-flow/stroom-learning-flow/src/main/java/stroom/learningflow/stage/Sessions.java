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

package stroom.learningflow.stage;

import stroom.docref.DocRef;
import stroom.learningflow.fragment.FragmentWriter;
import stroom.learningflow.learning.Advisor;
import stroom.learningflow.learning.Advisors;
import stroom.learningflow.learning.AskedWith;
import stroom.learningflow.learning.Exchange;
import stroom.learningflow.learning.Question;
import stroom.learningflow.learning.RecordedAdvisor.AwaitingAnswer;
import stroom.learningflow.learning.StopReports;
import stroom.learningflow.scoring.Judgement;
import stroom.learningflow.scoring.Verdict;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckResult;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.StopReport;
import stroom.learningflow.stage.Attempts.Grant;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Decision.Drafted;
import stroom.learningflow.stage.Decision.GivenUp;
import stroom.learningflow.stage.Decision.Kept;
import stroom.learningflow.stage.Decision.Parked;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Decision.Rebound;
import stroom.learningflow.stage.Decision.Sentinel;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.DocPath;
import stroom.util.shared.NullSafe;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/// A learning run's record (A28, A45), apart from what it learns: opening the run when a node commits to
/// learning, refusing to where its learner has nowhere to put what it learns or a spend ceiling is reached
/// (A24), carrying it under its claim with a [Recorder] that writes each turn and heartbeats as it goes, and
/// writing what it came to — its status, decision, binding, score, stop report and evidence. Serving opens
/// runs and the worker carries them on, so both come here. Taken out of [Stage] (simplification option iii);
/// needs no pipeline scope.
public final class Sessions {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(Sessions.class);

    private final Advisors advisors;
    private final Attempts attempts;
    private final SpendBreaker breaker;
    private final FragmentWriter writer;
    private final Places places;
    private final Clock clock;
    private final Duration lease;
    private final String node;
    private final String build;

    /// @param advisors Asks the model a document names.
    /// @param attempts The runs.
    /// @param breaker  The spend breaker, read before a run opens (A24).
    /// @param writer   Makes the folder a learner's fragments go in.
    /// @param places   Where a learner's fragments are kept.
    /// @param clock    The time.
    /// @param lease    How long a claim lasts unless renewed (A45).
    /// @param node     This node, which a run's claim names.
    /// @param build    The build of Stroom running it, kept with the words a run is asked in (N9).
    public Sessions(final Advisors advisors,
                    final Attempts attempts,
                    final SpendBreaker breaker,
                    final FragmentWriter writer,
                    final Places places,
                    final Clock clock,
                    final Duration lease,
                    final String node,
                    final String build) {
        this.advisors = advisors;
        this.attempts = attempts;
        this.breaker = breaker;
        this.writer = writer;
        this.places = places;
        this.clock = clock;
        this.lease = lease;
        this.node = node;
        this.build = build;
    }

    /// When a claim taken or renewed now lapses: one lease on, which is a turn's allowance and not the
    /// attempt's whole budget, so that a session whose node has died is noticed within a turn (§4). The
    /// claim is renewed before every question and as every turn is recorded.
    long claimUntil() {
        return clock.millis() + lease.toMillis();
    }

    /// One attempt, recorded (A28): a row when this node commits to learning, a turn for every
    /// question as the transcript has them, and what it came to. The record outlives the node that made
    /// it, which is what lets a person read back what was said and another node pick the attempt up (A45).
    ///
    /// @param waiting What becomes of *this stream* where the attempt is not this stage's to carry to an
    ///                end — another node holds the claim, or the attempt has parked awaiting an answer.
    ///                In a task where nothing serves the learner the stream is deferred; in the worker it
    ///                is refused, since its own task is the one deferred; where a binding
    ///                already serves it, it is that binding serving the stream as it serves every other.
    StageRun recording(final LearningFlowDoc doc,
                               final Learner learner,
                               final Input input,
                               final Function<String, StageRun> waiting,
                               final Function<Recorder, StageRun> attempt) {
        // One advisor for the attempt, since the node's makes a new one per call and each counts its own
        // tokens: asking twice would read two fresh counters and record nothing spent. A task never
        // learns (A60, A99): it decides, and a session is opened and carried by the worker, or by a
        // person's improve (A102), which asks as it goes.
        return recording(doc, learner, input, advisors.of(doc), waiting, attempt);
    }

    /// The same, for a caller that has already decided who answers.
    StageRun recording(final LearningFlowDoc doc,
                               final Learner learner,
                               final Input input,
                               final Advisor advisor,
                               final Function<String, StageRun> waiting,
                               final Function<Recorder, StageRun> attempt) {
        return recording(doc, learner, input, advisor, null, waiting, attempt);
    }

    /// The same, for an improvement: the attempt remembers which binding it is improving (A46), so that
    /// the worker carrying it on knows to find its sample in the regression set rather than in the
    /// stream store.
    StageRun recording(final LearningFlowDoc doc,
                               final Learner learner,
                               final Input input,
                               final Advisor advisor,
                               final String improving,
                               final Function<String, StageRun> waiting,
                               final Function<Recorder, StageRun> attempt) {
        // What the session learns must have somewhere to go, and that is found out now rather than after
        // the model has been paid: a folder that cannot be made would close every session in error once it
        // had learned, and the learner's next stream would open another.
        final Optional<String> unplaceable = unplaceable(learner);
        if (unplaceable.isPresent()) {
            return waiting.apply(unplaceable.get());
        }
        // And what it would cost, before anything is asked (A24). An attempt's own budgets bound one
        // session; this is what bounds a feed that keeps opening them. The stream waits and is tried
        // again, so nothing is lost by refusing here.
        final Optional<String> tripped = breaker.tripped(doc);
        if (tripped.isPresent()) {
            return waiting.apply(tripped.get());
        }
        final Optional<Long> opened = attempts.opened(new Attempts.Attempt(learner, doc.getUuid(),
                input.feed(), input.type(),
                // An improvement has no stream behind it (A46): its sample is the corpus, so the attempt
                // says it has none rather than naming one that was never there.
                improving == null
                        ? input.id()
                        : null,
                node, doc.getPromotionMode(),
                // The words it is asked with, kept with it (N9): a parked run resumes in the words it
                // began in, however the Learning Flow is edited while it waits.
                claimUntil(), AskedWith.of(doc, build), improving), clock.millis());
        if (opened.isEmpty()) {
            // Another attempt holds the learner's claim (A45): it is still learning, parked or not, and
            // this stream takes what that attempt comes to rather than learning the same thing beside it.
            return waiting.apply(waitingFor(attempts.open(learner, clock.millis())));
        }
        return carrying(doc, learner, input, opened.get(), 0L, Grant.NONE, false, Map.of(), advisor, false, waiting,
                attempt);
    }

    /**
     * One attempt, carried to whatever it comes to and recorded as it goes (A28): whether this node opened
     * it or picked it up where another stopped.
     */
    StageRun carrying(final LearningFlowDoc doc,
                              final Learner learner,
                              final Input input,
                              final long attemptId,
                              final long alreadySpent,
                              final Grant grant,
                              final boolean exhaustNext,
                              final Map<Integer, String> answeredBefore,
                              final Advisor advisor,
                              final boolean worker,
                              final Function<String, StageRun> waiting,
                              final Function<Recorder, StageRun> attempt) {
        final long before = advisor.tokensUsed();
        final Recorder recorder = new Recorder(doc, advisor, attemptId, alreadySpent, grant, exhaustNext,
                answeredBefore, answeredBy(doc));
        recorder.worker = worker;
        final StageRun run;
        try {
            run = attempt.apply(recorder);
        } catch (final AwaitingAnswer awaiting) {
            // The attempt has reached a question nobody present can answer: it stops here, keeps its claim
            // on the learner, and waits for the worker or a person (A28, A45). The stream is sentinelled, as
            // an unknown learner's is, and released when the attempt finishes.
            // The question it stopped at is recorded unanswered (A28), so that a person can see what is
            // being waited for and answer it instead, and so that the replay has something to check its
            // re-walk against when they do.
            record(() -> attempts.turn(attemptId, new Attempts.Turn(recorder.numbered(awaiting.number()),
                    awaiting.stepId(), awaiting.candidate(), Question.kindOf(awaiting.question()),
                    awaiting.question().summary(), null, null, null)));
            record(() -> attempts.parked(attemptId, AttemptStatus.AWAITING_MODEL, claimUntil(),
                    Math.max(0L, advisor.tokensUsed() - before)));
            return waiting.apply("Awaiting the model: attempt " + attemptId + " stopped at "
                                 + awaiting.question().summary());
        } catch (final RuntimeException e) {
            // The model, the node or the database failed: the attempt says so, with the turns it had got
            // to, rather than vanishing, and in words a person can act on. Nothing here may throw over the
            // failure that brought us.
            record(() -> {
                final StopReport report = StopReports.failure(e, NullSafe.get(doc.getModel(), DocRef::getName));
                attempts.closed(attemptId, AttemptStatus.ERROR, report.getError(), null, null, report,
                        Math.max(0L, advisor.tokensUsed() - before));
            });
            throw e;
        }
        if (run.decision() instanceof final Parked parked) {
            // Parked for a person (A65): the attempt keeps its claim with a lease that does not lapse, and
            // the worker leaves it alone. Not closed — a person's answer carries it on.
            record(() -> attempts.parked(attemptId, parked.reason(), parked.report(),
                    Math.max(0L, advisor.tokensUsed() - before)));
            return run;
        }
        // The run is done and its binding is written: the record of it must not be what fails the stream.
        record(() -> attempts.closed(attemptId, statusOf(run.decision()), reason(run.decision()),
                bindingOf(run.decision()), scoreOf(run.decision()), reportOf(run.decision()),
                Math.max(0L, advisor.tokensUsed() - before)));
        // What a person approving it is shown (Track 2 step 4): kept for whatever wrote a translation,
        // draft or not, so that what went live without a person can be read the same way
        if (bindingOf(run.decision()) != null) {
            record(() -> attempts.evidence(attemptId, evidence(run.verdicts())));
        }
        return run;
    }

    /// Why a stream waits on the attempt that holds its learner's claim, by what that attempt is doing: a
    /// stream that waits on a session paused for a person waits until someone acts, which is not what
    /// "learning" would say.
    static String waitingFor(final Optional<Recorded> holder) {
        if (holder.isEmpty()) {
            return "Waiting for the learning run that holds this pipeline, which has just finished; this stream "
                   + "is tried again.";
        }
        final Recorded attempt = holder.get();
        final String run = "learning run " + attempt.id();
        return switch (attempt.status()) {
            case PARKED -> "Waiting for " + run + ", which is paused for a person" + why(attempt.decision())
                           + ". This stream carries on once it is answered, given more, or stopped, on the "
                           + "pipeline's Learning Status tab.";
            case AWAITING_MODEL -> "Waiting for " + run + ", which is waiting for the model to answer.";
            case AWAITING_REVIEW -> "Waiting for a new translation, learned by " + run + ", to be approved or "
                                    + "rejected on the pipeline's Learning Status tab.";
            default -> "Waiting for " + run + ", which is learning" + (attempt.attempt().node() == null
                    ? ""
                    : " on node " + attempt.attempt().node()) + "; this stream takes what it learns.";
        };
    }

    private static String why(final String decision) {
        if (NullSafe.isBlankString(decision)) {
            return "";
        }
        final String line = decision.strip();
        return " (" + Character.toLowerCase(line.charAt(0)) + (line.endsWith(".")
                ? line.substring(1, line.length() - 1)
                : line.substring(1)) + ")";
    }

    /**
     * Where an attempt stands, from what it decided (A28).
     */
    private static AttemptStatus statusOf(final Decision decision) {
        return switch (decision) {
            case Promoted ignored -> AttemptStatus.PROMOTED;
            case Rebound ignored -> AttemptStatus.PROMOTED;
            case Drafted ignored -> AttemptStatus.AWAITING_REVIEW;
            case Parked ignored -> AttemptStatus.PARKED;
            default -> AttemptStatus.ABANDONED;
        };
    }

    /**
     * What an attempt came to, in the words the decision itself uses — and only those: a decision's
     * diagnostics quote the stream's own text, which would be a second copy of real data under the
     * attempt's retention rather than the stream's (A146: samples are never redacted). That is the same
     * reason a turn records what it asked rather than the prompt it sent.
     */
    private static String reason(final Decision decision) {
        return switch (decision) {
            case Promoted promoted -> "Promoted " + promoted.score();
            case Rebound rebound -> "Rebound " + rebound.score();
            case Drafted drafted -> "Awaiting review, scored " + drafted.score();
            case Kept kept -> "Kept the incumbent: " + kept.reason();
            case GivenUp givenUp -> givenUp.reason();
            case Sentinel sentinel -> sentinel.reason();
            default -> decision.getClass().getSimpleName();
        };
    }

    /// How a translation did on each check that ran, across the steps it was judged on: a check that judged
    /// several steps is shown at its least score, since the least is what the gate held to. Numbers only,
    /// as the diagnostics quote the stream (A146).
    ///
    /// @param verdicts One per step judged.
    /// @return One result per check, in the order the checks are declared.
    static List<CheckResult> evidence(final List<Verdict> verdicts) {
        final Map<Check, CheckResult> least = new EnumMap<>(Check.class);
        for (final Verdict verdict : NullSafe.list(verdicts)) {
            for (final Judgement judgement : verdict.judgements()) {
                final CheckSetting setting = judgement.setting();
                final CheckResult result = new CheckResult(setting.getCheck(), setting.getState(),
                        judgement.score().value(), setting.getPassingValue());
                least.merge(setting.getCheck(), result, (was, now) -> now.getScore() < was.getScore()
                        ? now
                        : was);
            }
        }
        return List.copyOf(least.values());
    }

    private static StopReport reportOf(final Decision decision) {
        return decision instanceof final GivenUp givenUp
                ? givenUp.report()
                : null;
    }

    private static String bindingOf(final Decision decision) {
        return switch (decision) {
            case Promoted promoted -> promoted.binding().getUuid();
            case Drafted drafted -> drafted.binding().getUuid();
            case Rebound rebound -> rebound.binding().getUuid();
            default -> null;
        };
    }

    private static Double scoreOf(final Decision decision) {
        return switch (decision) {
            case Promoted promoted -> promoted.score();
            case Drafted drafted -> drafted.score();
            case Rebound rebound -> rebound.score();
            default -> null;
        };
    }

    /**
     * Who answered a turn: the model this document names, since nothing else answers one yet — a person
     * answering instead is A28's, in a later slice, and writes their own name here.
     */
    private static String answeredBy(final LearningFlowDoc doc) {
        // A DocRef may carry no name — a document stored as type and uuid alone — and the column may not be
        // null, so the uuid stands in: a record that cannot be written is worse than one that names a uuid.
        if (doc.getModel() == null) {
            return "model";
        }
        return doc.getModel().getName() == null
                ? doc.getModel().getUuid()
                : doc.getModel().getName();
    }

    /// Why what the learner learns could not be put where its element says, if it could not: the folder is
    /// made, or found, before a session is opened.
    private Optional<String> unplaceable(final Learner learner) {
        final DocPath folder = places.of(learner).folder();
        try {
            writer.ensure(folder);
            return Optional.empty();
        } catch (final RuntimeException e) {
            LOGGER.debug(() -> LogUtil.message("Learner {} cannot be placed in {}: {}", learner, folder,
                    e.getMessage()), e);
            // Most often a name the explorer will not have for a folder, but not always: the reason
            // says what it was, and where the folder is set.
            return Optional.of("What this learner learns cannot be put in the folder " + folder + ": "
                               + Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName())
                               + ". The element's learnedFolder says where it goes; a folder's name may "
                               + "hold letters, digits, spaces and - _ . ( )");
        }
    }

    /// A run's record is written beside what the run did: failing to write it must not undo that, so it
    /// is logged rather than thrown.
    private static void record(final Runnable write) {
        try {
            write.run();
        } catch (final RuntimeException e) {
            LOGGER.error(() -> LogUtil.message("The attempt could not be recorded: {}", e.getMessage()), e);
        }
    }


    // --------------------------------------------------------------------------------


    /**
     * What an attempt writes as it goes: the advisor it is asking, so the tokens it charges are counted
     * once, and a turn for every exchange as it is answered and again as it is judged (A28).
     */
    final class Recorder {

        final LearningFlowDoc doc;
        final Advisor advisor;
        final long attemptId;
        final long alreadySpent;
        final Map<Integer, String> answeredBefore;
        final String answeredBy;
        /// Whether this is the worker carrying a session (A64): a session that cannot finish parks for a
        /// person (A65) rather than giving up, and runs its held stream in full before it ends.
        boolean worker;
        /// What a person granted the attempt beyond the document's budgets (A77).
        final Grant grant;
        /// The advisor's count and the clock as this carrying began: a round that follows another in the
        /// same carrying is under the same budget, counted from here (A5).
        final long tokensBefore;
        final long startedMs;
        /// Whether a person sent the attempt to the exhaustion step (A65).
        final boolean exhaustNext;
        /// The highest turn number recorded so far, and the number the current walk's turns count on from.
        int highest;
        int base;

        Recorder(final LearningFlowDoc doc,
                         final Advisor advisor,
                         final long attemptId,
                         final long alreadySpent,
                         final Grant grant,
                         final boolean exhaustNext,
                         final Map<Integer, String> answeredBefore,
                         final String answeredBy) {
            this.doc = doc;
            this.advisor = advisor;
            this.attemptId = attemptId;
            this.alreadySpent = alreadySpent;
            this.grant = grant;
            this.exhaustNext = exhaustNext;
            this.tokensBefore = advisor.tokensUsed();
            this.startedMs = clock.millis();
            this.answeredBefore = Map.copyOf(answeredBefore);
            this.answeredBy = answeredBy;
        }

        boolean worker() {
            return worker;
        }

        Advisor advisor() {
            return advisor;
        }

        long attemptId() {
            return attemptId;
        }

        /// What this attempt had spent before it was last parked (A5): its budget is the whole of it.
        long alreadySpent() {
            return alreadySpent;
        }

        /// What this attempt has spent so far: before it was last parked, and in this carrying up to now.
        /// A round that follows another is under the one budget, so it starts from here and not from
        /// nothing.
        long spentSoFar() {
            return alreadySpent + Math.max(0L, advisor.tokensUsed() - tokensBefore);
        }

        /// The claim pushed out, before every question: an attempt held up by one slow model call would
        /// otherwise run past its expiry and have its learner's work taken up by another node (A45).
        void heartbeat() {
            record(() -> attempts.heartbeat(attemptId, claimUntil()));
        }

        void turn(final int number, final Exchange exchange) {
            final int numbered = numbered(number);
            // Renewed as the turn completes as well as before it is asked, so a live session is never
            // mistaken for a dead one however long the model took.
            heartbeat();
            record(() -> attempts.turn(attemptId, new Attempts.Turn(numbered, exchange.step(),
                    exchange.candidate(), Question.kindOf(exchange.question()), exchange.question().summary(),
                    exchange.reply(), answeredBefore.getOrDefault(numbered, answeredBy),
                    exchange.outcome(), exchange.score(), exchange.carried())));
        }

        /// A turn's number in the attempt, from its number in the walk that asked it. A session runs its
        /// walks one after another — a learning, then a relearning for each round that did not clear the
        /// held stream (A64) — and each walk numbers its own turns from one; the attempt numbers them on
        /// from where the last walk left off, so that no round's turns are written over an earlier
        /// round's and a re-walk replays them all, in order. A turn is recorded twice under one number,
        /// as it is answered and as it is judged, which is why a walk's beginning is said and not inferred.
        int numbered(final int number) {
            highest = Math.max(highest, base + number);
            return base + number;
        }

        /// A walk begins: its turns count on from the highest the attempt has.
        void walkBegins() {
            base = highest;
        }
    }
}
