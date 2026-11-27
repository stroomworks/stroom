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

/// Manages the record of a learning session, separately from what the session learns. It opens the session
/// when a node commits to learning, and refuses to if the learner has nowhere to put what it learns or a
/// spend ceiling has been reached. It runs the session under its claim with a [Recorder] that writes each
/// turn and sends heartbeats as it goes, and it records the outcome: status, decision, binding, score, stop
/// report and approval evidence.
///
/// Serving opens sessions and the worker resumes them, so both use this class. It needs no pipeline
/// scope.
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

    /// @param advisors Creates an advisor that asks the model a document names.
    /// @param attempts The session records.
    /// @param breaker  The spend breaker, checked before a session opens.
    /// @param writer   Creates the folder a learner's fragments go in.
    /// @param places   Where a learner's fragments are kept.
    /// @param clock    The source of the current time.
    /// @param lease    How long a claim on a learner lasts unless it is renewed by a heartbeat.
    /// @param node     This node's name, which a session's claim records.
    /// @param build    The Stroom build version, recorded with the question wording a session uses.
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

    /// The time at which a claim taken or renewed now expires: one lease from now. The lease is a turn's
    /// allowance rather than the session's whole budget, so that a session whose node has died is noticed
    /// within a turn. The claim is renewed before every question and as every turn is recorded.
    long claimUntil() {
        return clock.millis() + lease.toMillis();
    }

    /// Opens and runs a new session, recording it as it goes: a row when this node commits to learning, a
    /// turn for every question, and the outcome. The record outlives the node that made it, which lets a
    /// person read back what was said and another node pick up the session.
    ///
    /// @param waiting Decides what becomes of *this stream* if this stage cannot run the session to its end,
    ///                because another session holds the claim or the session is left waiting for an answer.
    ///                In a task where nothing serves the learner the stream is deferred; in the worker it is
    ///                refused, since its own task is the one deferred; and where a binding already serves the
    ///                learner, that binding serves the stream as it serves every other.
    /// @param attempt Runs the session with the recorder.
    StageRun recording(final LearningFlowDoc doc,
                               final Learner learner,
                               final Input input,
                               final Function<String, StageRun> waiting,
                               final Function<Recorder, StageRun> attempt) {
        // One advisor for the whole session, because `advisors.of` makes a new one per call and each counts
        // its own tokens: asking twice would read two fresh counters and record nothing spent. A processing
        // task never asks the model itself; it opens a session, which the worker then runs.
        return recording(doc, learner, input, advisors.of(doc), waiting, attempt);
    }

    /// As [#recording(LearningFlowDoc, Learner, Input, Function, Function)], for a caller that has already
    /// chosen the advisor that answers.
    StageRun recording(final LearningFlowDoc doc,
                               final Learner learner,
                               final Input input,
                               final Advisor advisor,
                               final Function<String, StageRun> waiting,
                               final Function<Recorder, StageRun> attempt) {
        return recording(doc, learner, input, advisor, null, waiting, attempt);
    }

    /// As [#recording(LearningFlowDoc, Learner, Input, Advisor, Function, Function)], for an improvement:
    /// the session records which binding it is improving, so that the worker resuming it knows to build its
    /// sample from the regression corpus rather than read it from the stream store.
    StageRun recording(final LearningFlowDoc doc,
                               final Learner learner,
                               final Input input,
                               final Advisor advisor,
                               final String improving,
                               final Function<String, StageRun> waiting,
                               final Function<Recorder, StageRun> attempt) {
        // What the session learns must have somewhere to go, and that is checked now rather than after the
        // model has been paid. A folder that cannot be created would close every session in error once it
        // had learned, and the learner's next stream would open another.
        final Optional<String> unplaceable = unplaceable(learner);
        if (unplaceable.isPresent()) {
            return waiting.apply(unplaceable.get());
        }
        // Check the spend ceilings before anything is asked. A session's own budgets limit one session;
        // this limits a feed that keeps opening them. The stream waits and is tried again, so nothing is
        // lost by refusing here.
        final Optional<String> tripped = breaker.tripped(doc);
        if (tripped.isPresent()) {
            return waiting.apply(tripped.get());
        }
        final Optional<Long> opened = attempts.opened(new Attempts.Attempt(learner, doc.getUuid(),
                input.feed(), input.type(),
                // An improvement has no stream behind it (its sample is the corpus), so the session records
                // no stream rather than naming one that was never used.
                improving == null
                        ? input.id()
                        : null,
                node, doc.getPromotionMode(),
                // The question wording is recorded with the session, so that a resumed session continues
                // with the wording it began with, however the Learning Flow is edited while it waits.
                claimUntil(), AskedWith.of(doc, build), improving), clock.millis());
        if (opened.isEmpty()) {
            // Another session holds the learner's claim and is still learning, parked or not. This stream
            // waits for what that session learns rather than learning the same thing beside it.
            return waiting.apply(waitingFor(attempts.open(learner, clock.millis())));
        }
        return carrying(doc, learner, input, opened.get(), 0L, Grant.NONE, false, Map.of(), advisor, false, waiting,
                attempt);
    }

    /// Runs a session until it finishes or has to wait, recording it as it goes, whether this node opened
    /// it or picked it up where another stopped.
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
            // The session has reached a question that nobody here can answer (e.g. a processing task, which
            // must not ask the model). It stops here, keeps its claim on the learner, and waits for the
            // worker or a person. `waiting` decides what becomes of the stream.
            // The question it stopped at is recorded unanswered, so that a person can see what is being
            // waited for and answer it instead, and so that the replay has something to check against.
            Bookkeeping.record(LOGGER, () -> attempts.turn(attemptId,
                    new Attempts.Turn(recorder.numbered(awaiting.number()),
                            awaiting.stepId(), awaiting.candidate(), Question.kindOf(awaiting.question()),
                            awaiting.question().summary(), null, null, null)));
            Bookkeeping.record(LOGGER, () -> attempts.parked(attemptId, AttemptStatus.AWAITING_MODEL,
                    claimUntil(), Math.max(0L, advisor.tokensUsed() - before)));
            return waiting.apply("Awaiting the model: attempt " + attemptId + " stopped at "
                                 + awaiting.question().summary());
        } catch (final RuntimeException e) {
            // The model, the node or the database failed. The session is closed with an error a person can
            // act on, keeping the turns it got through, rather than vanishing. Recording it must not throw
            // and hide the original failure.
            Bookkeeping.record(LOGGER, () -> {
                final StopReport report = StopReports.failure(e, NullSafe.get(doc.getModel(), DocRef::getName));
                attempts.closed(attemptId, AttemptStatus.ERROR, report.getError(), null, null, report,
                        Math.max(0L, advisor.tokensUsed() - before));
            });
            throw e;
        }
        if (run.decision() instanceof final Parked parked) {
            // Parked for a person: the session keeps its claim with a lease that does not expire, and the
            // worker leaves it alone. It is not closed, because a person's action resumes it.
            Bookkeeping.record(LOGGER, () -> attempts.parked(attemptId, parked.reason(), parked.report(),
                    Math.max(0L, advisor.tokensUsed() - before)));
            return run;
        }
        // The session is done and its binding is written, so a failure to record it must not fail the
        // stream.
        Bookkeeping.record(LOGGER, () -> attempts.closed(attemptId, statusOf(run.decision()), reason(run.decision()),
                bindingOf(run.decision()), scoreOf(run.decision()), reportOf(run.decision()),
                Math.max(0L, advisor.tokensUsed() - before)));
        // The approval evidence a person is shown. It is kept whenever a translation was written, draft or
        // not, so that one that went live without review can be inspected in the same way
        if (bindingOf(run.decision()) != null) {
            Bookkeeping.record(LOGGER, () -> attempts.evidence(attemptId, evidence(run.verdicts())));
        }
        return run;
    }

    /// Words why a stream is waiting on the session that holds its learner's claim, according to what that
    /// session is doing. A stream waiting on a session paused for a person waits until someone acts, which
    /// "learning" would not convey.
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

    /// Maps a session's decision to the status it is closed with.
    private static AttemptStatus statusOf(final Decision decision) {
        return switch (decision) {
            case Promoted ignored -> AttemptStatus.PROMOTED;
            case Rebound ignored -> AttemptStatus.PROMOTED;
            case Drafted ignored -> AttemptStatus.AWAITING_REVIEW;
            case Parked ignored -> AttemptStatus.PARKED;
            default -> AttemptStatus.ABANDONED;
        };
    }

    /// Words a session's outcome using only the decision's own reason. A decision's diagnostics quote the
    /// stream's text, and storing them would keep a second copy of real data under the session's retention
    /// rather than the stream's (samples are never redacted). For the same reason a turn records a summary
    /// of what it asked rather than the prompt it sent.
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

    /// Summarises how a translation scored on each check that ran, across the steps it was judged on. A
    /// check that judged several steps is shown at its lowest score, since the lowest is what the gate was
    /// held to. Only numbers are kept, because the diagnostics quote the stream.
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

    /// The name recorded as having answered a turn by default: the model the document names. Turns a person
    /// answered keep that person's name instead (see `answeredBefore`).
    private static String answeredBy(final LearningFlowDoc doc) {
        // A DocRef may have no name (a document stored as type and UUID only), and the column may not be
        // null, so the UUID is used instead: a record that cannot be written is worse than one naming a UUID.
        if (doc.getModel() == null) {
            return "model";
        }
        return doc.getModel().getName() == null
                ? doc.getModel().getUuid()
                : doc.getModel().getName();
    }

    /// Creates, or finds, the folder the learner's content goes in, before a session is opened.
    ///
    /// @return Why the folder could not be created, or empty if it is in place.
    private Optional<String> unplaceable(final Learner learner) {
        final DocPath folder = places.of(learner).folder();
        try {
            writer.ensure(folder);
            return Optional.empty();
        } catch (final RuntimeException e) {
            LOGGER.debug(() -> LogUtil.message("Learner {} cannot be placed in {}: {}", learner, folder,
                    e.getMessage()), e);
            // Usually a folder name the explorer will not accept, but not always, so the reason gives the
            // underlying error and says where the folder is set.
            return Optional.of("What this learner learns cannot be put in the folder " + folder + ": "
                               + Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName())
                               + ". The element's learnedFolder says where it goes; a folder's name may "
                               + "hold letters, digits, spaces and - _ . ( )");
        }
    }


    // --------------------------------------------------------------------------------


    /// Records a session as it runs. It holds the advisor being asked, so that the tokens it charges are
    /// counted once, and writes a turn for every exchange as it is answered and again as it is judged.
    final class Recorder {

        final LearningFlowDoc doc;
        final Advisor advisor;
        final long attemptId;
        final long alreadySpent;
        final Map<Integer, String> answeredBefore;
        final String answeredBy;
        /// Whether this is the worker running a session. In the worker, a session that cannot finish parks
        /// for a person rather than giving up, and runs its held stream in full before it ends.
        boolean worker;
        /// What a person granted the session beyond the document's budgets.
        final Grant grant;
        /// The advisor's token count and the time when this run of the session began. A round that follows
        /// another in the same run is under the same budget, counted from here.
        final long tokensBefore;
        final long startedMs;
        /// Whether a person sent the session to the exhaustion step.
        final boolean exhaustNext;
        /// The highest turn number recorded so far, and the number the current walk's turns are numbered
        /// on from.
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

        /// What this session had spent before this run; its budget covers its whole cost.
        long alreadySpent() {
            return alreadySpent;
        }

        /// What this session has spent so far: before this run, plus this run up to now. A round that
        /// follows another is under the same budget, so it starts from this rather than from zero.
        long spentSoFar() {
            return alreadySpent + Math.max(0L, advisor.tokensUsed() - tokensBefore);
        }

        /// Renews the claim, before every question. Otherwise a session held up by one slow model call would
        /// run past its expiry and another node would take over its learner's work.
        void heartbeat() {
            Bookkeeping.record(LOGGER, () -> attempts.heartbeat(attemptId, claimUntil()));
        }

        void turn(final int number, final Exchange exchange) {
            final int numbered = numbered(number);
            // Renewed as the turn completes as well as before it is asked, so that a live session is never
            // mistaken for a dead one, however long the model took.
            heartbeat();
            Bookkeeping.record(LOGGER, () -> attempts.turn(attemptId, new Attempts.Turn(numbered, exchange.step(),
                    exchange.candidate(), Question.kindOf(exchange.question()), exchange.question().summary(),
                    exchange.reply(), answeredBefore.getOrDefault(numbered, answeredBy),
                    exchange.outcome(), exchange.score(), exchange.carried())));
        }

        /// Converts a turn's number in the walk that asked it to its number in the session. A session runs
        /// its walks of the flow one after another (a learning, then a relearning for each round that did
        /// not clear the held stream), and each walk numbers its own turns from one. The session numbers them
        /// on from where the last walk left off, so that no round's turns overwrite an earlier round's and a
        /// replay replays them all, in order. A turn is recorded twice under one number, as it is answered and
        /// as it is judged, which is why the start of a walk is signalled explicitly ([#walkBegins]) rather
        /// than inferred.
        int numbered(final int number) {
            highest = Math.max(highest, base + number);
            return base + number;
        }

        /// Marks the start of a walk: its turns are numbered on from the highest the session has so far.
        void walkBegins() {
            base = highest;
        }
    }
}
