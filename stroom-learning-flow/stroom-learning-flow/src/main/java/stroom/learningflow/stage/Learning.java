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
import stroom.learningflow.extraction.DataSplitterStep;
import stroom.learningflow.fragment.FragmentCodes;
import stroom.learningflow.fragment.FragmentRunner;
import stroom.learningflow.fragment.FragmentWriter;
import stroom.learningflow.fragment.ServedStream;
import stroom.learningflow.fragment.Shortfalls;
import stroom.learningflow.learning.Advisor;
import stroom.learningflow.learning.AskedWith;
import stroom.learningflow.learning.Conversation;
import stroom.learningflow.learning.Exchange;
import stroom.learningflow.learning.LearnedStep;
import stroom.learningflow.learning.Outcome;
import stroom.learningflow.learning.Outcome.Abandoned;
import stroom.learningflow.learning.Outcome.Learned;
import stroom.learningflow.learning.PromptCapture;
import stroom.learningflow.learning.QuestionText;
import stroom.learningflow.learning.RecordedAdvisor;
import stroom.learningflow.learning.Sample;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.StepRunner;
import stroom.learningflow.learning.StopReports;
import stroom.learningflow.learning.TargetSchema;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.scoring.Attempted;
import stroom.learningflow.scoring.Scorecard;
import stroom.learningflow.scoring.Scorer;
import stroom.learningflow.scoring.Verdict;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.shared.StopReport;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Decision.Drafted;
import stroom.learningflow.stage.Decision.GivenUp;
import stroom.learningflow.stage.Decision.Kept;
import stroom.learningflow.stage.Decision.Parked;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Decision.Rebound;
import stroom.learningflow.stage.RegressionSet.Accepted;
import stroom.learningflow.stage.Sessions.Recorder;
import stroom.learningflow.transformation.AiXsltAllowList;
import stroom.learningflow.transformation.LearnedStylesheetCheck;
import stroom.learningflow.transformation.XsltStep;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.ElementId;
import stroom.util.shared.NullSafe;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/// Learning (simplification option iii, the `LearningSessionService` of `simplification-options.md`): a run
/// learned, relearned or improved and carried to a binding — the conversation over the sample, the held
/// stream run in full and settled round by round (design 04 §4), the static check on what was learned
/// (N1), and the run resumed where it stopped (A28, A45) or rebuilt to show what was asked (N9). Serving
/// ([Stage]) opens it for a stream; the worker carries it on. Taken out of [Stage]; per pipeline scope, as
/// the stage that makes it is.
public final class Learning {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(Learning.class);

    private static final long SPEND_WINDOW_MS = SpendBreaker.WINDOW_MS;
    /// The element a stage's own diagnostics are laid at.
    private static final ElementId STAGE = new ElementId("Stage");
    private static final ElementId PERMITTED = new ElementId("Permitted");
    /// What a learned translation may use (decision N1), in code until Track 2 step 6 puts it
    /// on the Egress Policy screen.
    private static final AiXsltAllowList ALLOW_LIST = AiXsltAllowList.hardCoded();
    /// The element whose output is always `records` over `record`.
    private static final String DATA_SPLITTER = DataSplitterStep.ELEMENT_TYPE;
    /// What a record of the chain's output is judged as when it is served: the element type the scorers
    /// of meaning read it as, which is any element that is not a parser.
    private static final String TRANSFORM = XsltStep.ELEMENT_TYPE;
    /// The most rounds a session runs before it parks regardless: the budgets are what bound a session
    /// (A65), and this is the guard behind them for a document whose budgets are generous.
    private static final int MOST_ROUNDS = 10;
    /// How many records short of the bar a full pass keeps as evidence: enough kinds to learn for, few
    /// enough to put to a model.
    private static final int EVIDENCE = 5;

    private final List<StepRunner> runners;
    private final List<Scorer> scorers;
    private final TargetSchemas targetSchemas;
    private final Attempts attempts;
    private final LearnerBindings learnerBindings;
    private final LearnerStates states;
    private final Spend spend;
    private final SpendBreaker breaker;
    private final Guidance guidance;
    private final Outputs outputs;
    private final RegressionSet regressionSet;
    private final Wake wake;
    private final Places places;
    private final FragmentWriter writer;
    private final FragmentCodes codes;
    private final Clock clock;
    private final Duration lease;
    private final String node;
    private final Promotion promotion;
    private final Sessions sessions;
    private final Judging judging;
    private final FragmentRunner fragmentRunner;

    /// @param runners Runs each element of a candidate chain.
    /// @param scorers The installed scorers.
    /// @param targetSchemas Resolves a document's target schema.
    /// @param attempts The runs.
    /// @param learnerBindings The bindings.
    /// @param states Each learner's state.
    /// @param spend What each document has spent on its model (A44).
    /// @param breaker The spend breaker (A24).
    /// @param guidance What supervisors have said to each learner (A46).
    /// @param outputs What each binding produced from each stream.
    /// @param regressionSet The records each binding was accepted on.
    /// @param wake Wakes a learner's pipeline.
    /// @param places Where a learner's fragments are kept.
    /// @param writer Writes what a run learned as a fragment.
    /// @param codes Reads a fragment's documents.
    /// @param clock The time.
    /// @param lease How long a claim lasts unless renewed (A45).
    /// @param node This node.
    /// @param promotion Writes drafts and promotes them.
    /// @param sessions Opens, carries and records runs.
    /// @param judging Judges what a run learned.
    /// @param fragmentRunner Runs a fragment as the pipeline will.
    Learning(final List<StepRunner> runners,
             final List<Scorer> scorers,
             final TargetSchemas targetSchemas,
             final Attempts attempts,
             final LearnerBindings learnerBindings,
             final LearnerStates states,
             final Spend spend,
             final SpendBreaker breaker,
             final Guidance guidance,
             final Outputs outputs,
             final RegressionSet regressionSet,
             final Wake wake,
             final Places places,
             final FragmentWriter writer,
             final FragmentCodes codes,
             final Clock clock,
             final Duration lease,
             final String node,
             final Promotion promotion,
             final Sessions sessions,
             final Judging judging,
             final FragmentRunner fragmentRunner) {
        this.runners = runners;
        this.scorers = scorers;
        this.targetSchemas = targetSchemas;
        this.attempts = attempts;
        this.learnerBindings = learnerBindings;
        this.states = states;
        this.spend = spend;
        this.breaker = breaker;
        this.guidance = guidance;
        this.outputs = outputs;
        this.regressionSet = regressionSet;
        this.wake = wake;
        this.places = places;
        this.writer = writer;
        this.codes = codes;
        this.clock = clock;
        this.lease = lease;
        this.node = node;
        this.promotion = promotion;
        this.sessions = sessions;
        this.judging = judging;
        this.fragmentRunner = fragmentRunner;
    }

    /**
     * Carry on an attempt that stopped at a question, with the answers it has been given (A28): the
     * conversation is re-walked from the start over the same sample, answered from the record until the record
     * runs out, which re-derives everything those answers produced — the chain, the boundary, the records,
     * the targets, each element's configuration and output — because all of it follows from the sample and
     * the answers. What is asked beyond the record is asked of {@code answerer}: the model, for the worker
     * advancing a deferred attempt; nothing, for one that is to stop again.
     *
     * Whatever becomes of the attempt, the stream is not errored here: it was read back out of the store,
     * and its own task is deferred behind the attempt, which errors it or serves it when it next runs.
     *
     * @param input The stream the attempt was raised on, which the attempt's row names.
     */
    public StageRun resume(final LearningFlowDoc doc, final long attemptId, final Input input,
                           final Advisor answerer) {
        final Recorded recorded = attempts.byId(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("No attempt " + attemptId));
        final Learner learner = recorded.attempt().learner();
        // Carrying it on is taking it (A45): an attempt that has finished is not resumed, and one another
        // node has taken since is that node's until its claim lapses.
        if (!attempts.claimed(attemptId, node, clock.millis(), sessions.claimUntil())) {
            return StageRun.refused(doc, learner, "Attempt " + attemptId + " is not this node's to carry on: it has "
                                         + "finished, or another node holds it");
        }
        // An attempt may have waited a long time, and the learner is not as it left it: an operator may
        // have turned learning off, a person may have a draft of it in front of them, or it may have been
        // given up. Whatever the attempt was learning towards, it is not wanted
        // now, so it is closed rather than carried on into a binding nobody asked for.
        final Optional<Binding> matched = promotion.bindingFor(learner);
        final Optional<String> stop = whyNotToCarryOn(doc, learner, matched);
        if (stop.isPresent()) {
            Bookkeeping.record(LOGGER, () -> attempts.closed(attemptId, AttemptStatus.ABANDONED, stop.get(), null,
                    null, 0L));
            return StageRun.refused(doc, learner, stop.get());
        }
        final Scorecard scorecard = Scorecard.of(doc.getChecks(), scorers, targetSchemas.of(doc).orElse(null));
        final RecordedAdvisor replay = new RecordedAdvisor(recorded.turns(), answerer);
        // Who answered each turn the first time, by its number: a replayed turn is rewritten as it is
        // re-walked, and it must not come back saying the model answered what a person answered (A28).
        final Map<Integer, String> answeredBefore = recorded.turns().stream()
                .filter(turn -> turn.answeredBy() != null)
                .collect(Collectors.toMap(Attempts.Turn::number, Attempts.Turn::answeredBy,
                        (first, second) -> first));
        // An improvement is the one thing the bindings cannot tell us (A46): its learner is served, like
        // a relearning's, but it was asked for by a person and opens with "it is not failing: make it
        // better" rather than with what a stream fell short on. So the attempt says so, and is carried
        // on as the improvement it is.
        if (recorded.attempt().isImprovement()) {
            final String bindingUuid = recorded.attempt().improving();
            final Optional<Binding> incumbent = learnerBindings.byUuid(bindingUuid)
                    .map(LearnerBindings.Held::binding);
            final List<Accepted> accepted = regressionSet.accepted(bindingUuid);
            final Optional<String> corpusFeed = regressionSet.feedOf(bindingUuid);
            if (incumbent.isEmpty() || accepted.isEmpty() || corpusFeed.isEmpty()) {
                // Retracted, relearned or pruned while the attempt waited: there is nothing left to
                // improve or nothing to hold a candidate to, and asking the model anyway would be a
                // promotion in disguise.
                final String gone = "The binding this was improving, or the records it was accepted on, "
                                    + "has gone since it was asked for";
                Bookkeeping.record(LOGGER, () -> attempts.closed(attemptId, AttemptStatus.ABANDONED, gone, null,
                        null, 0L));
                return StageRun.refused(doc, learner, gone);
            }
            return sessions.carrying(doc, learner, input, attemptId, recorded.tokensSpent(), recorded.grant(),
                    recorded.exhaustNext(), answeredBefore, replay, true,
                    reason -> StageRun.refused(doc, learner, reason),
                    recorder -> improveUnderClaim(doc, learner, incumbent.get(), input, scorecard, accepted,
                            corpusFeed.get(), recorder));
        }
        // What the attempt is doing is otherwise not stored: it is read from the bindings as they stand. A
        // learner a binding already serves is being relearned (A29), and carrying the attempt on as though
        // it were learning afresh would append a second binding for one learner and orphan a fragment
        // behind the first.
        if (matched.isPresent()) {
            final Binding binding = matched.get();
            final Judged served = judging.judge(scorecard, binding.getPipeline(), input.data(),
                    binding.getRecordBoundary());
            final String marked = states.relearnReason(learner)
                    .orElse("the learner was marked to learn again");
            return sessions.carrying(doc, learner, input, attemptId, recorded.tokensSpent(), recorded.grant(),
                    recorded.exhaustNext(), answeredBefore, replay, true,
                    reason -> emit(doc, new Kept(binding, reason), learner, input, binding, served, List.of()),
                    recorder -> settle(doc, learner, input, scorecard, recorder,
                            relearnUnderClaim(doc, learner, binding, input, scorecard, served, marked,
                                    recorder)));
        }
        return sessions.carrying(doc, learner, input, attemptId, recorded.tokensSpent(), recorded.grant(),
                recorded.exhaustNext(), answeredBefore, replay, true,
                reason -> StageRun.refused(doc, learner, reason),
                recorder -> settle(doc, learner, input, scorecard, recorder,
                        learnAndBind(doc, learner, input, scorecard, recorder)));
    }

    /// Ask for a binding that is already serving to be made **better** (A46, §12 item 29).
    ///
    /// Not `relearn`, which marks a learner and waits for the next stream to carry the work. An
    /// improvement does not wait for traffic: the records the binding was accepted on are kept per binding
    /// (A18) with the score each achieved, so an attempt has both a sample to learn from and the bar to
    /// beat without a stream arriving. A feed that ships once a day can be improved at eleven in the
    /// morning.
    ///
    /// It begins from what the incumbent wrote rather than from nothing — the model is shown what is
    /// serving, what it scores, and what the person wants better — and the incumbent keeps serving
    /// throughout (A29): a candidate takes over only through the ordinary gate (A15, A18, §7.4). So
    /// nothing waits for a person and a person waits for nothing.
    ///
    /// The message is recorded as guidance for the learner (A46) before the attempt opens, which is what
    /// carries it into every question the attempt asks — and leaves it standing for the relearning after
    /// this one, because what a person knows about a feed does not stop being true.
    ///
    /// @param bindingUuid The binding to improve: one that is serving. A draft is decided, not improved.
    /// @param message     What the person wants better, in their words. Optional: asking again from the
    ///                    incumbent is itself worth something.
    /// @param by          Who asked, for the guidance and for the attempt.
    public StageRun improve(final LearningFlowDoc doc,
                            final String bindingUuid,
                            final String message,
                            final String by) {
        final LearnerBindings.Held held = promotion.held(doc, bindingUuid);
        final Binding incumbent = held.binding();
        if (incumbent.isDraft()) {
            throw new IllegalArgumentException("Binding " + bindingUuid + " is a draft awaiting review: approve or "
                                               + "reject it rather than improving it");
        }
        if (incumbent.getPipeline() == null) {
            throw new IllegalArgumentException("Binding " + bindingUuid + " binds nothing, so there is nothing to "
                                               + "improve");
        }
        if (doc.getLearningMode() == LearningMode.DISABLED) {
            // The kill switch is a kill switch. Every other path that asks a model reads it, and a
            // button that asked anyway would be a way round it.
            throw new IllegalStateException("Learning Flow is disabled for this document");
        }
        // The binding's learner, since an improvement is that learner learning better.
        final Learner learner = held.learner();
        final Optional<String> awaiting = states.draftAwaiting(learner);
        if (awaiting.isPresent()) {
            // One draft per learner: a second would overwrite the pointer that says which one is awaiting
            // review, leaving the first in place with nothing to approve or reject it by.
            throw new IllegalStateException("Draft binding " + awaiting.get() + " is already awaiting review "
                                            + "for this learner; decide it before asking for another");
        }
        final List<Accepted> accepted = regressionSet.accepted(bindingUuid);
        if (accepted.isEmpty()) {
            // Without a record it was accepted on there is nothing to learn from and nothing to beat.
            // Said rather than guessed at: an improvement judged on nothing would be a promotion in
            // disguise.
            throw new IllegalArgumentException("Binding " + bindingUuid + " has no accepted records to improve "
                                               + "against; learn again instead");
        }
        // Where what it learns for is kept: on the feed its records already are (A18), since there is no
        // stream here to keep them on.
        final String corpusFeed = regressionSet.feedOf(bindingUuid)
                .orElseThrow(() -> new IllegalStateException("Binding " + bindingUuid + "'s records are kept on no "
                                                             + "feed that can be read"));
        if (!NullSafe.isBlankString(message)) {
            guidance.given(learner, doc.getUuid(), message, by);
        }

        // The stream to learn from is the corpus itself, put back together: every record as it was
        // received, between what the stream wrapped them in. A record whose parser did not say where it
        // cut — JSON's, XML's — cannot be put back through one, and that is said rather than guessed at.
        final String sample = Corpus.stream(accepted)
                .orElseThrow(() -> new IllegalArgumentException("Binding " + bindingUuid + " was accepted on records "
                                                                + "that cannot be put back through its parser; "
                                                                + "learn again instead"));
        // No feed and no type: there is no stream here to read them off, and a learner does not know its
        // feed (A126), so the model is shown the corpus alone.
        final Input input = improvementSample(sample);
        final Scorecard scorecard = Scorecard.of(doc.getChecks(), scorers, targetSchemas.of(doc).orElse(null));
        // Asked by the worker, not here (A46). A model conversation is minutes long: run inside the web
        // request it holds an HTTP thread for all of them, cannot survive the node restarting, and is
        // lost if the browser gives up. So the attempt is opened, parked at its first question, and
        // carried on by the worker exactly as a deferred learning is — which is what the ruling asks
        // for. The attempt remembers the binding it is improving, and the worker puts the corpus back
        // together to ask about rather than looking for a stream that does not exist.
        //
        // Nothing waits on it meanwhile: the incumbent serves every stream throughout, and a candidate
        // takes over only through the ordinary gate.
        return sessions.recording(doc, learner, input, RecordedAdvisor.awaiting(), bindingUuid,
                reason -> StageRun.refused(doc, learner,
                        "Binding " + bindingUuid + " is not being improved: " + reason),
                recorder -> improveUnderClaim(doc, learner, incumbent, input, scorecard, accepted,
                        corpusFeed, recorder));
    }

    /// The stream an improvement learns from: the records the binding was accepted on, put back together
    /// (A18), since there is no stream here to learn from. No feed and no type — there is none to read
    /// them off, and a learner does not know its feed (A126) — so the model is shown the corpus alone.
    ///
    /// @param sample The corpus as one stream.
    /// @return It as an input.
    private static Input improvementSample(final String sample) {
        return new Input(-1L, null, null, Map.of(), sample, null);
    }

    /// The sample an improvement attempt is carried on with, for the worker: its binding's accepted
    /// records put back together. Empty where the binding or its records have gone since the person
    /// asked, which closes the attempt rather than asking the model about nothing.
    ///
    /// @param attempt The attempt, which must be an improvement.
    /// @return The stream to ask about, or empty.
    public Optional<Input> improvementInput(final Attempts.Attempt attempt) {
        final List<Accepted> accepted = regressionSet.accepted(attempt.improving());
        return accepted.isEmpty()
                ? Optional.empty()
                : Corpus.stream(accepted).map(Learning::improvementSample);
    }

    /// The improvement itself, under the attempt's claim (A42, A45).
    ///
    /// The model opens with what the incumbent is and what it scores, so that it improves something
    /// rather than starting again in ignorance; and the gate is the accepted records themselves — every
    /// one of them, since there is no stream to be better on. A candidate that is worse on any record
    /// the binding was accepted on is not an improvement whatever else it does (A18).
    private StageRun improveUnderClaim(final LearningFlowDoc doc,
                                       final Learner learner,
                                       final Binding incumbent,
                                       final Input input,
                                       final Scorecard scorecard,
                                       final List<Accepted> accepted,
                                       final String corpusFeed,
                                       final Recorder recorder) {
        final List<StoredError> opening = new ArrayList<>();
        opening.add(new StoredError(Severity.ERROR, null, STAGE,
                "Improving " + incumbent.getPipeline().getName() + ", which is serving and scored "
                + incumbent.getScore() + " when it was promoted. It is not failing: make it better."));
        final Outcome outcome = learn(doc, learner, input, scorecard, opening, List.of(), recorder);
        if (outcome instanceof final Abandoned abandoned) {
            return kept(doc, learner, incumbent, "No candidate: " + abandoned.reason(), outcome.transcript());
        }
        final Learned learned = (Learned) outcome;
        final Judged candidate = Judging.asLearned(Judging.rerun(learned.chain(), input.data(), boundary(learned)),
                scorecard);
        if (!candidate.clearsGates()) {
            return kept(doc, learner, incumbent, "Candidate failed " + candidate.failedGates(),
                    learned.transcript());
        }
        // Every accepted record, not one: an improvement asked for out of the blue has no stream to be
        // better on, so the records the binding was accepted on are the whole of the evidence (A18). Held
        // to them only where the regression corpus is a gate, and to the same bar a served record is.
        final List<Accepted> held = doc.getCheck(Check.REGRESSION).isGate()
                ? accepted
                : List.of();
        final Scorecard bar = judging.recordBar(doc);
        for (int i = 0; i < held.size(); i++) {
            final Accepted record = held.get(i);
            final List<LearnedStep> transforms = Corpus.transforms(learned.chain());
            final Judged onRecord = Judging.asLearned(Judging.rerun(transforms, record.record(), boundary(learned)),
                    bar);
            final Optional<String> shortfall = Corpus.shortfall(record, onRecord.verdicts(),
                    Corpus.outputFor(transforms, record.record(), boundary(learned)));
            if (shortfall.isPresent()) {
                return kept(doc, learner, incumbent, "Candidate " + shortfall.get() + " — "
                                                   + Corpus.name(i + 1, held.size(), record),
                        learned.transcript());
            }
        }
        // The corpus was the stream, so what the model was shown of it was learned for again: each of
        // those records goes in with what the candidate now makes of it, and the cap lets the oldest go
        // (A63).
        final List<Accepted> entries = Corpus.entries(learned.chain(), boundary(learned),
                LearningPrefix.learningPrefix(input.data(), doc), null, learned.targets(), candidate.score(),
                recorder.attemptId(), clock.millis(), doc.getRegressionCap());
        // Always a draft, whatever the mode (simplification option A): the result is written the same way
        // for both, and the only difference is who approves it — a person, or the system at once.
        final DocRef fragment = write(learner, learned);
        // A learner marked to learn again has just done so, by hand: the mark is spent, or its next stream
        // would relearn the binding somebody has only now improved.
        states.reset(learner);
        // Behind the incumbent, which keeps serving until this is approved (A25).
        final Binding draft = promotion.writeDraft(doc, learner, fragment, candidate.score(), boundary(learned));
        regressionSet.accept(doc.getUuid(), draft.getUuid(), corpusFeed, null, entries,
                doc.getRegressionCap());
        if (!approvesItself(doc)) {
            states.awaitReview(learner, doc.getUuid(), draft.getUuid());
            return new StageRun(doc, new Drafted(draft, candidate.score()), learner, null, candidate.output(),
                    candidate.verdicts(), learned.transcript(), null, List.of());
        }
        final Binding rebound = promotion.promote(doc, learner, draft);
        return new StageRun(doc, new Rebound(incumbent, rebound, candidate.score()), learner,
                new Produced(doc.getUuid(), rebound.getUuid(), fragment, boundary(learned),
                        candidate.score()),
                candidate.output(), candidate.verdicts(), learned.transcript(), null, List.of());
    }

    /// The incumbent goes on serving, and why it was not replaced is said rather than swallowed.
    private StageRun kept(final LearningFlowDoc doc,
                          final Learner learner,
                          final Binding incumbent,
                          final String reason,
                          final List<Exchange> transcript) {
        return new StageRun(doc, new Kept(incumbent, reason), learner, null, null, List.of(), transcript, null,
                List.of());
    }

    /**
     * Why an attempt that stopped is not to be carried on, if it is not: the learner's state as it stands
     * now, read as a task's decision reads it for a stream. Empty where the attempt may go on.
     */
    private Optional<String> whyNotToCarryOn(final LearningFlowDoc doc,
                                             final Learner learner,
                                             final Optional<Binding> matched) {
        if (matched.isPresent() && matched.get().isDraft()) {
            return Optional.of("Awaiting review: draft binding " + matched.get().getUuid()
                               + " already serves this learner");
        }
        final Optional<String> givenUp = states.reasonGivenUp(learner);
        if (givenUp.isPresent()) {
            return Optional.of("Given up: " + givenUp.get());
        }
        if (doc.getLearningMode() == LearningMode.DISABLED) {
            return Optional.of("Learning Flow is disabled for this document");
        }
        return Optional.empty();
    }

    StageRun learnAndBind(final LearningFlowDoc doc,
                                  final Learner learner,
                                  final Input input,
                                  final Scorecard scorecard,
                                  final Recorder recorder) {
        // Learn on a prefix, judge on the whole stream.
        final Outcome outcome = learn(doc, learner, input, scorecard, List.of(), List.of(), recorder);
        if (outcome instanceof final Abandoned abandoned) {
            if (abandoned.givesUp()) {
                // The flow's exit is give-up (A83): a parser's default, since a stream that cannot be cut
                // into records that meet the gates is the one failure nothing can serve (A52, A54), or
                // any step's where the flow says so. The learner gives up, and the streams waiting on it
                // are woken to error with the reason, as a given-up learner's streams do.
                final StageRun givenUp = givenUp(doc, learner, abandoned.reason(),
                        abandoned.reason(), abandoned.diagnostics(), List.of(), outcome.transcript(),
                        abandoned.report());
                wake(learner);
                return givenUp;
            }
            if (recorder.worker()) {
                // A session that cannot finish parks for a person (A65) rather than giving up: its
                // streams go on waiting, and nothing is baked because money or candidates ran out.
                return parked(doc, learner, abandoned.reason(), abandoned.report(), outcome.transcript());
            }
            return givenUp(doc, learner, abandoned.reason(), abandoned.reason(),
                    abandoned.diagnostics(), List.of(), outcome.transcript(), abandoned.report());
        }
        final Learned learned = (Learned) outcome;

        // The candidate over the whole stream: the held-out judgement of A14/A15 where there are enough
        // records for one, and the floor a provisional binding must clear where there are not.
        final Judged judged = Judging.asLearned(Judging.rerun(learned.chain(), input.data(), boundary(learned)),
                scorecard);
        if (!judged.clearsGates()) {
            // Woken, as for the flow's give-up: the streams waiting on the learner error with the reason
            // now, rather than after the retry delay.
            final StageRun givenUp = givenUp(doc, learner, "A gate failed",
                    "Candidate failed " + judged.failedGates(),
                    judged.verdicts().stream().flatMap(verdict -> verdict.feedback().stream()).toList(),
                    judged.verdicts(), learned.transcript());
            wake(learner);
            return givenUp;
        }
        final DocRef fragment = write(learner, learned);
        // What goes in the corpus (A56): the sample the model was shown, every record of it, since a
        // first learning learned for all of them.
        final List<Accepted> entries = Corpus.entries(learned.chain(), boundary(learned),
                LearningPrefix.learningPrefix(input.data(), doc), null, learned.targets(), judged.score(),
                recorder.attemptId(), clock.millis(), doc.getRegressionCap());
        return bind(doc, learner, input, fragment, judged, entries, learned.transcript(),
                boundary(learned));
    }

    StageRun relearnUnderClaim(final LearningFlowDoc doc,
                                       final Learner learner,
                                       final Binding incumbent,
                                       final Input input,
                                       final Scorecard scorecard,
                                       final Judged served,
                                       final String marked,
                                       final Recorder recorder) {
        final List<StoredError> opening = new ArrayList<>();
        opening.add(new StoredError(Severity.ERROR, null, STAGE, "Relearning: " + marked
                                                                 + ". The bound fragment scored " + served.score()
                                                                 + " on this stream"));
        served.verdicts().forEach(verdict -> opening.addAll(verdict.feedback()));
        // And what the fragment's run had to say — for a full pass, the records that fell short, as the
        // parser cut them and as the transform left them (A64): what the model has to see to learn for
        // a record that lay beyond the sample.
        opening.addAll(served.diagnostics());
        final Outcome outcome = learn(doc, learner, input, scorecard, opening, served.held(), recorder);
        // The mark is spent now that the relearning has happened, and not before: an attempt that parks
        // at a question (A5) is carried on by the worker, which must find the learner as this walk found it
        // or ask something else (A45). Spent whatever the candidate came to, or a learner that cannot be
        // fixed would be relearned on every stream.
        states.reset(learner);
        if (outcome instanceof final Abandoned abandoned) {
            // Whatever exit the flow named (A83): a learner a binding is serving does not give up, since that
            // would error streams that are being served.
            if (recorder.worker()) {
                // As a first learning does (A65): the incumbent serves what it can, the held stream waits.
                return parked(doc, learner, abandoned.reason(), abandoned.report(), outcome.transcript());
            }
            return emit(doc, new Kept(incumbent, "No candidate: " + abandoned.reason()), learner, input, incumbent,
                    served, outcome.transcript());
        }
        final Learned learned = (Learned) outcome;
        final Judged candidate = Judging.asLearned(Judging.rerun(learned.chain(), input.data(), boundary(learned)),
                scorecard);
        if (!candidate.clearsGates()) {
            return emit(doc, new Kept(incumbent, "Candidate failed " + candidate.failedGates()), learner, input,
                    incumbent, served, learned.transcript());
        }
        // No comparison with the incumbent's score (the one model of checks, D2): the regression corpus
        // below, a gate, is what holds a relearn to no worse than what it replaces.
        // Held to the corpus, to no worse (A66): every record the binding was accepted on, run through the
        // candidate's transform, must clear the bar, and none that translated may become an Unknown.
        // Output may otherwise change — the output each was accepted with is kept so that a person can
        // see what changed, not so that the gate can refuse it. Held to it only where the regression corpus
        // is a gate, and to the same bar a served record is.
        final List<Accepted> corpus = doc.getCheck(Check.REGRESSION).isGate()
                ? regressionSet.accepted(incumbent.getUuid())
                : List.of();
        final Scorecard bar = judging.recordBar(doc);
        for (int i = 0; i < corpus.size(); i++) {
            final Accepted accepted = corpus.get(i);
            final List<LearnedStep> transforms = Corpus.transforms(learned.chain());
            final Judged onRecord = Judging.asLearned(Judging.rerun(transforms, accepted.record(),
                    boundary(learned)), bar);
            final Optional<String> shortfall = Corpus.shortfall(accepted, onRecord.verdicts(),
                    Corpus.outputFor(transforms, accepted.record(), boundary(learned)));
            if (shortfall.isPresent()) {
                return emit(doc, new Kept(incumbent, "Candidate " + shortfall.get() + " — "
                                                     + Corpus.name(i + 1, corpus.size(), accepted)),
                        learner, input, incumbent, served, learned.transcript());
            }
        }
        // What this session learned for (A56): the records of the stream that fell short under the
        // incumbent, with what the candidate makes of them. A relearning that names no record — the stream
        // fell short on yield or coverage, which are measures of the stream and lay nothing at a record —
        // learned for the sample it was shown, as a first learning does.
        final Set<Integer> fellShort = served.failingRecords();
        final List<Accepted> entries;
        if (!served.held().isEmpty()) {
            // A full pass kept the records themselves (design 04 §4), which may lie beyond the text in
            // hand: they are the entries.
            entries = Corpus.heldEntries(learned.chain(), boundary(learned), served.held(), learned.targets(),
                    candidate.score(), recorder.attemptId(), clock.millis(), doc.getRegressionCap());
        } else if (fellShort.isEmpty()) {
            entries = Corpus.entries(learned.chain(), boundary(learned),
                    LearningPrefix.learningPrefix(input.data(), doc), null,
                    learned.targets(), candidate.score(), recorder.attemptId(), clock.millis(),
                    doc.getRegressionCap());
        } else {
            entries = Corpus.entries(learned.chain(), boundary(learned), input.data(), fellShort,
                    learned.targets(), candidate.score(), recorder.attemptId(), clock.millis(),
                    doc.getRegressionCap());
        }
        // Always a draft, whatever the mode (simplification option A): behind the incumbent, which keeps
        // matching first; approval rebinds the incumbent to this fragment and carries the history over.
        final DocRef fragment = write(learner, learned);
        final Binding draft = promotion.writeDraft(doc, learner, fragment, candidate.score(), boundary(learned));
        regressionSet.accept(doc.getUuid(), draft.getUuid(), input.feed(), input.type(), entries,
                doc.getRegressionCap());
        if (!approvesItself(doc)) {
            states.awaitReview(learner, doc.getUuid(), draft.getUuid());
            return emit(doc, new Drafted(draft, candidate.score()), learner, input,
                    incumbent, served, learned.transcript());
        }
        // Spent again now the new transform is in force: a stream that fell short of the incumbent while
        // the session ran marked the learner after it was spent above, and a mark left standing would
        // relearn what has just been learned. A stream that falls short from here on was judged
        // against a transform since replaced, and does not mark it (see [#session]). [#promoteDraft]
        // resets the learner and wakes it.
        final Binding rebound = promotion.promote(doc, learner, draft);
        return emit(doc, new Rebound(incumbent, rebound, candidate.score()), learner,
                input, incumbent, served, learned.transcript());
    }

    /**
     * Bind a fragment that cleared the floor: a new binding for the learner, promoted if the stream had
     * enough records for a held-out judgement and provisional otherwise; the learner's pipeline woken,
     * which is what puts its waiting streams through; and the stream's output emitted. In review mode (A25)
     * the binding is a draft instead, and the learner's streams wait on it, this one with them.
     */
    private StageRun bind(final LearningFlowDoc doc,
                          final Learner learner,
                          final Input input,
                          final DocRef fragment,
                          final Judged judged,
                          final List<Accepted> entries,
                          final List<Exchange> transcript,
                          final RecordBoundary boundary) {
        // What a learned translation may use is checked again here, not only when the candidate was
        // judged (decision N1). A fragment can reach this point by a route that judged no stylesheet — a
        // draft approved by a person, a binding promoted by hand — and can have been edited between the
        // two. There is no run-time enforcement until Track 2 step 6, so this is the last look.
        final List<String> refusals = refusedByTheList(fragment);
        if (!refusals.isEmpty()) {
            return givenUp(doc, learner, "Refused", refusals.get(0),
                    refusals.stream().map(refusal -> new StoredError(Severity.ERROR, null, PERMITTED, refusal))
                            .toList(),
                    judged.verdicts(), transcript,
                    StopReports.refusedByTheList(refusals));
        }
        // Always a draft, whatever the mode (simplification option A).
        final Binding draft = promotion.writeDraft(doc, learner, fragment, judged.score(), boundary);
        // What was learned for goes in now, under a draft too (A56): the records are what the session put
        // to the model whether or not a person has yet approved what it made of them. Reject and
        // retraction take them out again.
        regressionSet.accept(doc.getUuid(), draft.getUuid(), input.feed(), input.type(), entries,
                doc.getRegressionCap());
        states.reset(learner);
        // A first result learned from too few records is never approved by the system, even on an
        // automatic Learning Flow (SIM-3 option E): it has no held-out judgement behind it, so it waits
        // for a person as review mode's results do. The record minimum is a first learning's bar only,
        // which is why the relearning paths don't ask it (N4).
        if (!approvesItself(doc) || judged.records() < doc.getMinRecordsToLearn()) {
            states.awaitReview(learner, doc.getUuid(), draft.getUuid());
            return new StageRun(doc, new Drafted(draft, judged.score()), learner, null,
                    null, judged.verdicts(), transcript, null, List.of());
        }
        final Binding promoted = promotion.promote(doc, learner, draft);
        return emit(doc, new Promoted(promoted, judged.score()), learner, input, promoted, judged, transcript);
    }

    /// Whether the system approves a draft the gate has just cleared, rather than a person
    /// (simplification option A): on an automatic Learning Flow it does, at once, through the same code a
    /// person's approval takes; on one left on review — the default (SIM-1) — the draft waits.
    ///
    /// @param doc The document.
    /// @return True where the system approves its own drafts.
    private static boolean approvesItself(final LearningFlowDoc doc) {
        return doc.getPromotionMode() == PromotionMode.AUTOMATIC;
    }

    private Outcome learn(final LearningFlowDoc doc,
                          final Learner learner,
                          final Input input,
                          final Scorecard scorecard,
                          final List<StoredError> opening,
                          final List<String> held,
                          final Recorder recorder) {
        // Kept alive while a question is with the model as well as between questions (A45): one call
        // longer than the lease would otherwise let another node take the attempt mid-call.
        final Advisor advisor = new HeartbeatingAdvisor(recorder.advisor(), recorder::heartbeat,
                HeartbeatingAdvisor.intervalFor(lease));
        recorder.walkBegins();
        // The attempt's claim is pushed out before every question (A45), and every turn is recorded as it
        // is answered and again as it is judged, so an attempt still running shows what it had got to
        // (A28).
        final Conversation conversation = new Conversation(advisor, runners, scorecard, clock,
                recorder::heartbeat, recorder::turn)
                .alreadySpent(recorder.spentSoFar())
                .begunAt(recorder.startedMs)
                .granted(recorder.grant.tokens(), recorder.grant.ms())
                .exhaustFirst(recorder.exhaustNext)
                // The records the held stream fell short on (design 04 §4): every candidate for the
                // transform is held to them, beyond the sample they are not in.
                .heldTo(held)
                // What a supervisor has said to this learner (A46), read afresh before every question
                // so that a hint given while the attempt was parked is carried by the turn that
                // resumes it.
                .guidedBy(() -> guidance.standing(learner));
        final Sample sample = sampleOf(input, doc);
        final long before = advisor.tokensUsed();
        final List<Exchange> asked = new ArrayList<>();
        try {
            final Outcome outcome = conversation.run(doc, sample, opening);
            asked.addAll(outcome.transcript());
            return outcome;
        } finally {
            // What the attempt cost, counted for the document across the cluster (A44), whether it learned
            // anything or not and whether it returned or threw: an attempt that abandons costs what it
            // asked, and a model that fails after charging is the runaway A24's breaker exists to see. The
            // policy that reads the count is A24's, in phase E.
            spend.record(doc.getUuid(), Math.max(0L, advisor.tokensUsed() - before), asked.size(),
                    SPEND_WINDOW_MS);
        }
    }

    /// What the model is shown of a stream, as a learning walk and a rebuild both take it.
    private static Sample sampleOf(final Input input, final LearningFlowDoc doc) {
        return Sample.of(LearningPrefix.learningPrefix(input.data(), doc), input.feed(), input.type())
                .from(input.facts())
                // How much of what was read the model is not shown, so that a stop report can say it
                // (supervisor visibility item 3): a person told "couldn't write the parser" is owed the
                // fact that the model saw eight kilobytes of a two-megabyte stream.
                .cutFrom(Input.bytes(input.data()))
                // A stream longer than the prefix read as text: a prefix of a single document is not a
                // document, so such a stream cannot be sampled at all (N2 option E)
                .beyondPrefix(input.opener() != null);
    }

    /// The prompts a run's turns were put to the model with, rebuilt rather than stored (decision N9,
    /// Track 2 step 4): its first walk re-walked over the same sample, every question answered from the
    /// record and rendered in the words the run recorded, so the feedback each carried is derived again as
    /// it was the first time (A45). Nothing is written and nobody is asked.
    ///
    /// Only the first walk is rebuilt. A later round of the same run — a relearning after the held stream
    /// fell short — opened with feedback from a full pass over the stream, which this doesn't repeat; its
    /// turns are absent from what is returned, and the caller says so. A walk that no longer replays —
    /// the Learning Flow's steps have changed since — stops where it diverges, and what it reached is
    /// returned.
    ///
    /// @param doc       The document.
    /// @param attemptId The run.
    /// @param input     The stream it learned from, read back from the store.
    /// @return The prompts by turn number, and the notes since withdrawn that some carried; empty where
    ///         the run's words were not recorded, which is refused rather than guessed at.
    public Optional<Rebuilt> rebuild(final LearningFlowDoc doc, final long attemptId, final Input input) {
        final Recorded recorded = attempts.byId(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("No attempt " + attemptId));
        final AskedWith askedWith = recorded.attempt().askedWith();
        if (!askedWith.known()) {
            return Optional.empty();
        }
        final TargetSchema target = targetSchemas.of(doc).orElse(null);
        final PromptCapture capture = new PromptCapture(recorded.turns(),
                QuestionText.of(doc, askedWith, target), guidance.standing(recorded.attempt().learner()));
        final Scorecard scorecard = Scorecard.of(doc.getChecks(), scorers, target);
        try {
            new Conversation(capture, runners, scorecard, clock).run(doc, sampleOf(input, doc), List.of());
        } catch (final RuntimeException e) {
            // Diverged from the record, or stopped where the run stopped: what it reached stands
            LOGGER.debug(() -> LogUtil.message("Rebuilding attempt {} stopped: {}", attemptId, e.getMessage()));
        }
        return Optional.of(new Rebuilt(capture.prompts(), capture.withdrawn(), askedWith.olderThanBuiltIn()));
    }

    /// What the fragment's stylesheets use that a learned translation may not (decision N1).
    ///
    /// @param fragment The fragment about to serve.
    /// @return One sentence per refusal, and empty where every stylesheet uses only what it may.
    private List<String> refusedByTheList(final DocRef fragment) {
        if (fragment == null) {
            return List.of();
        }
        final List<String> refusals = new ArrayList<>();
        for (final Map.Entry<String, String> stylesheet : codes.stylesheets(fragment).entrySet()) {
            for (final String refusal : LearnedStylesheetCheck.refusals(stylesheet.getValue(), ALLOW_LIST)) {
                refusals.add(stylesheet.getKey() + ": " + refusal);
            }
        }
        return refusals;
    }

    /// The session carried to its end (A64, A50): the held stream is the acceptance test. A promotion is
    /// followed by a full pass of the stream through the promoted transform, judging every record; where
    /// any falls short, the records that did — as the parser cut them, with what the transform made of
    /// them — open the next question, and the transform is learned again in place. The session ends when
    /// the stream clears, or parks (A65) when its budgets or its rounds are spent.
    private StageRun settle(final LearningFlowDoc doc,
                            final Learner learner,
                            final Input input,
                            final Scorecard scorecard,
                            final Recorder recorder,
                            final StageRun first) {
        StageRun run = first;
        HeldPass pass = null;
        for (int round = 1; round <= MOST_ROUNDS; round++) {
            final Binding binding;
            // Why the last round kept the incumbent, where it did: the candidate was asked to clear the
            // held stream and did not get to serve, so the incumbent's pass still stands and the next
            // question says what kept the candidate out as well as what the stream wants.
            String kept = null;
            final boolean passed = pass != null;
            switch (run.decision()) {
                case Promoted promoted -> binding = promoted.binding();
                case Rebound rebound -> binding = rebound.binding();
                case Kept held when passed -> {
                    binding = held.incumbent();
                    kept = held.reason();
                }
                default -> {
                    // A draft, an incumbent kept before any pass, a parking: nothing is serving the stream
                    // yet, or the session has already said what it came to.
                    return run;
                }
            }
            if (kept == null) {
                pass = fullPass(doc, binding, input);
                if (pass.clear()) {
                    return run;
                }
            }
            final Judged served = pass.judged();
            final String reason = pass.reason(round)
                                  + (kept == null
                    ? ""
                    : "; the last candidate was kept out: " + kept);
            if (round == MOST_ROUNDS) {
                return parked(doc, learner, reason + "; " + MOST_ROUNDS + " rounds without the stream clearing",
                        run.transcript());
            }
            run = relearnUnderClaim(doc, learner, binding, input, scorecard, served, reason, recorder);
        }
        return run;
    }

    /// The held stream run in full through a binding's fragment, every record judged by the bar, the ones
    /// that fall short kept (design 04 §4). Nothing is handed on and nothing is written.
    ///
    /// What the run made of the **input** is kept as well as what it made of the output (audit finding
    /// W4-4). Without it the worker reads a stream the parser could make nothing of as a stream with
    /// nothing wrong, wakes it, and the task defers it again — a relearn loop that costs money on every
    /// turn.
    private HeldPass fullPass(final LearningFlowDoc doc, final Binding binding, final Input input) {
        final Scorecard bar = judging.recordBar(doc);
        final Shortfalls shortfalls = new Shortfalls((index, record, parsed) -> bar.judge(
                new Attempted(TRANSFORM, false, null, new StepResult(record, List.of()),
                        binding.getRecordBoundary(), null)),
                EVIDENCE);
        final ServedStream served = fragmentRunner.serve(binding.getPipeline(), input.open(),
                binding.getRecordBoundary(), shortfalls, null);
        return new HeldPass(shortfalls, served, Judging.inputShortfall(doc, served).orElse(null));
    }

    /// The session parks for a person (A65).
    private StageRun parked(final LearningFlowDoc doc,
                            final Learner learner,
                            final String reason,
                            final List<Exchange> transcript) {
        return parked(doc, learner, reason, null, transcript);
    }

    /// The session parks for a person (A65), with why in parts where the run worded it as a report.
    private StageRun parked(final LearningFlowDoc doc,
                            final Learner learner,
                            final String reason,
                            final StopReport report,
                            final List<Exchange> transcript) {
        return new StageRun(doc, new Parked(reason, report), learner, null, null, List.of(), transcript, null,
                List.of());
    }

    /// What one record is for a learned chain: the boundary the flow settled, or for a chain that begins
    /// with the Data Splitter — whose output is always `records` over `record` — one record per `record`,
    /// one deep. Settled here and not left empty because an empty boundary is a fragment with no
    /// `SplitFilter`, whose transform is given the whole parsed stream as one document and holds it
    /// (design 04 §3, *streams of any size*); a text stream of any size has to be cut too.
    static RecordBoundary boundary(final Learned learned) {
        if (learned.boundary() != null) {
            return learned.boundary();
        }
        return !learned.chain().isEmpty() && DATA_SPLITTER.equals(learned.chain().get(0).elementType())
                ? RecordBoundary.ofElement("record").atDepth(1)
                : null;
    }

    /// A new fragment, in the learner's folder under its name (A113), or the first free number after the
    /// name where the folder already has one (A132).
    private DocRef write(final Learner learner, final Learned learned) {
        final Places.Place place = places.of(learner);
        return writer.write(place.folder(), place.name(), learned.chain(), boundary(learned));
    }

    /**
     * The stream's output with the bindings that produced it (design 01 §7.3 rule 3).
     */
    private StageRun emit(final LearningFlowDoc doc,
                          final Decision decision,
                          final Learner learner,
                          final Input input,
                          final Binding binding,
                          final Judged judged,
                          final List<Exchange> transcript) {
        final Produced produced = new Produced(doc.getUuid(), binding.getUuid(), binding.getPipeline(),
                binding.getRecordBoundary(), judged.score(), binding.getSnapshot());
        outputs.emitted(input.id(), input.pipeline(), produced, judged.records());
        return new StageRun(doc, decision, learner, produced, judged.output(), judged.verdicts(), transcript,
                judged.events(), judged.diagnostics());
    }

    /**
     * No candidate passed: the learner gives up, and this stream is sentinelled like every later one will
     * be until a person sends it back.
     */
    private StageRun givenUp(final LearningFlowDoc doc,
                             final Learner learner,
                             final String decision,
                             final String reason,
                             final List<StoredError> diagnostics,
                             final List<Verdict> verdicts,
                             final List<Exchange> transcript) {
        return givenUp(doc, learner, decision, reason, diagnostics, verdicts, transcript, null);
    }

    /// As [#givenUp(LearningFlowDoc, Learner, String, String, List, List, List)], with why in parts where
    /// the run worded it as a report.
    private StageRun givenUp(final LearningFlowDoc doc,
                             final Learner learner,
                             final String decision,
                             final String reason,
                             final List<StoredError> diagnostics,
                             final List<Verdict> verdicts,
                             final List<Exchange> transcript,
                             final StopReport report) {
        states.giveUp(learner, doc.getUuid(), reason);
        return new StageRun(doc, new GivenUp(decision, diagnostics, report), learner, null, null, verdicts,
                transcript, null, List.of());
    }

    private void wake(final Learner learner) {
        wake.wake(learner.pipelineUuid());
    }
}
