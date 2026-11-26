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

/// Runs the learning in a session: a first learning, a relearning or an improvement, through to a binding.
/// It holds the conversation with the model over the sample, runs the held stream in full and settles it
/// round by round, applies the static check of what a learned stylesheet may use, resumes a session where
/// it stopped, and rebuilds a session's prompts for a person to inspect.
///
/// [Stage] opens a session for a stream, and the deferred worker resumes it through [Stage]. An instance
/// lives in one pipeline scope, like the [Stage] that creates it.
public final class Learning {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(Learning.class);

    private static final long SPEND_WINDOW_MS = SpendBreaker.WINDOW_MS;
    /// The element id that the stage's own diagnostics are reported against.
    private static final ElementId STAGE = new ElementId("Stage");
    private static final ElementId PERMITTED = new ElementId("Permitted");
    /// The XSLT instructions and functions a learned stylesheet may use. It is currently hard-coded; the
    /// intention is to make it maintainable by an administrator.
    private static final AiXsltAllowList ALLOW_LIST = AiXsltAllowList.hardCoded();
    /// The Data Splitter element type, whose output is always `records` containing `record` elements.
    private static final String DATA_SPLITTER = DataSplitterStep.ELEMENT_TYPE;
    /// The element type a record of the chain's output is judged as when it is served. The scorers that
    /// judge content treat it as the output of a transform, i.e. any element that is not a parser.
    private static final String TRANSFORM = XsltStep.ELEMENT_TYPE;
    /// The maximum number of rounds a session runs before it is parked for a person. A session is normally
    /// limited by its token and time budgets; this is a backstop for documents whose budgets are set very
    /// high.
    private static final int MOST_ROUNDS = 10;
    /// How many records that fell short of the bar a full pass keeps as evidence: enough to show the kinds
    /// that need learning, and few enough to put to a model.
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
    /// @param attempts The session records.
    /// @param learnerBindings The bindings.
    /// @param states Each learner's state.
    /// @param spend The cluster-wide count of what each document has spent on its model.
    /// @param breaker The spend breaker.
    /// @param guidance The guidance people have left for each learner.
    /// @param outputs What each binding produced from each stream.
    /// @param regressionSet The corpus records each binding was accepted on.
    /// @param wake Wakes a learner's pipeline.
    /// @param places Where a learner's fragments are kept.
    /// @param writer Writes what a session learned as a fragment.
    /// @param codes Reads a fragment's documents.
    /// @param clock The source of the current time.
    /// @param lease How long a claim on a learner lasts unless it is renewed by a heartbeat.
    /// @param node This node's name.
    /// @param promotion Writes drafts and promotes them.
    /// @param sessions Opens, runs and records sessions.
    /// @param judging Judges what a session learned.
    /// @param fragmentRunner Runs a fragment in the same way the pipeline will.
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

    /// Resumes a session that stopped at a question, with the answers it has been given so far. The
    /// conversation is replayed from the start over the same sample, with each question answered from the
    /// recorded turns until they run out. This re-derives everything those answers produced (the chain, the
    /// record boundary, the records, the targets, and each element's configuration and output), because all
    /// of it follows from the sample and the answers. Questions beyond the recorded turns are put to
    /// `answerer`: the model, when the worker is advancing a deferred session, or an advisor that stops the
    /// session again.
    ///
    /// Whatever happens to the session, the stream is not errored here. It was read back from the store, and
    /// its own task is deferred waiting for the session; that task errors or serves it when it next runs.
    ///
    /// @param doc       The document.
    /// @param attemptId The session's id.
    /// @param input     The stream the session was opened on, which the session's row names, or the
    ///                  reassembled sample for an improvement.
    /// @param answerer  The advisor that answers questions beyond the recorded turns.
    /// @return The outcome of the session.
    public StageRun resume(final LearningFlowDoc doc, final long attemptId, final Input input,
                           final Advisor answerer) {
        final Recorded recorded = attempts.byId(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("No attempt " + attemptId));
        final Learner learner = recorded.attempt().learner();
        // Resuming a session means claiming it. A session that has finished is not resumed, and one that
        // another node has claimed belongs to that node until its claim's lease expires.
        if (!attempts.claimed(attemptId, node, clock.millis(), sessions.claimUntil())) {
            return StageRun.refused(doc, learner, "Attempt " + attemptId + " is not this node's to carry on: it has "
                                         + "finished, or another node holds it");
        }
        // A session may have waited a long time, and the learner may have changed: an operator may have
        // disabled learning, a draft may be awaiting review, or the learner may have been given up. Then
        // whatever the session was learning is not wanted now, so it is closed rather than resumed into a
        // binding nobody asked for.
        final Optional<Binding> matched = promotion.bindingFor(learner);
        final Optional<String> stop = whyNotToCarryOn(doc, learner, matched);
        if (stop.isPresent()) {
            Bookkeeping.record(LOGGER, () -> attempts.closed(attemptId, AttemptStatus.ABANDONED, stop.get(), null,
                    null, 0L));
            return StageRun.refused(doc, learner, stop.get());
        }
        final Scorecard scorecard = Scorecard.of(doc.getChecks(), scorers, targetSchemas.of(doc).orElse(null));
        final RecordedAdvisor replay = new RecordedAdvisor(recorded.turns(), answerer);
        // Who answered each turn the first time, by turn number. A replayed turn is rewritten as it is
        // replayed, and must not then claim that the model answered what a person answered.
        final Map<Integer, String> answeredBefore = recorded.turns().stream()
                .filter(turn -> turn.answeredBy() != null)
                .collect(Collectors.toMap(Attempts.Turn::number, Attempts.Turn::answeredBy,
                        (first, second) -> first));
        // An improvement cannot be recognised from the bindings: its learner is served, as a relearning's
        // is, but a person asked for it, and it opens with "it is not failing: make it better" rather than
        // with what a stream fell short on. So the session records that it is an improvement, and is
        // resumed as one.
        if (recorded.attempt().isImprovement()) {
            final String bindingUuid = recorded.attempt().improving();
            final Optional<Binding> incumbent = learnerBindings.byUuid(bindingUuid)
                    .map(LearnerBindings.Held::binding);
            final List<Accepted> accepted = regressionSet.accepted(bindingUuid);
            final Optional<String> corpusFeed = regressionSet.feedOf(bindingUuid);
            if (incumbent.isEmpty() || accepted.isEmpty() || corpusFeed.isEmpty()) {
                // The binding or its corpus was retracted, relearned or pruned while the session waited.
                // There is then nothing to improve, or nothing to hold a candidate to, and asking the
                // model anyway could promote a candidate that had not been checked against anything.
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
        // Otherwise, what kind of session this is is not stored; it is worked out from the current bindings.
        // A learner that a binding already serves is being relearned, and resuming the session as if it
        // were learning afresh would add a second binding for one learner and orphan a fragment behind the
        // first.
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

    /// Asks for a binding that is already serving to be made **better**.
    ///
    /// This differs from relearning, which marks a learner and waits for its next stream. An improvement does
    /// not wait for a stream: the records each binding was accepted on are kept, with their scores, so a
    /// session has both a sample to learn from and a bar to beat without a stream arriving. A feed that
    /// arrives once a day can be improved at any time.
    ///
    /// It starts from what the serving binding does rather than from nothing: the model is shown what is
    /// serving, what it scores, and what the person wants improved. The serving binding goes on serving
    /// throughout, and a candidate only takes over by passing the ordinary promotion gate, so neither the
    /// streams nor the person have to wait.
    ///
    /// The message is recorded as guidance for the learner before the session opens, which includes it in
    /// every question the session asks, and keeps it for later relearnings, because what a person knows about
    /// a feed stays true.
    ///
    /// The session is only opened here and left waiting at its first question; the worker asks the model.
    ///
    /// @param doc         The document.
    /// @param bindingUuid The binding to improve, which must be serving; a draft must be approved or rejected
    ///                    instead.
    /// @param message     What the person wants improved, in their words. Optional, since asking again from
    ///                    the serving binding can be worthwhile in itself.
    /// @param by          Who asked, recorded with the guidance and the session.
    /// @return The result of the request; a sentinel if the session could not be opened.
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
            // Disabling learning must stop all model calls. Every other path that asks a model checks it,
            // and a button that ignored it would be a way round it.
            throw new IllegalStateException("Learning Flow is disabled for this document");
        }
        // An improvement is learning for the binding's learner.
        final Learner learner = held.learner();
        final Optional<String> awaiting = states.draftAwaiting(learner);
        if (awaiting.isPresent()) {
            // Only one draft per learner: a second would overwrite the pointer to the draft awaiting review,
            // leaving the first in place with no way to approve or reject it.
            throw new IllegalStateException("Draft binding " + awaiting.get() + " is already awaiting review "
                                            + "for this learner; decide it before asking for another");
        }
        final List<Accepted> accepted = regressionSet.accepted(bindingUuid);
        if (accepted.isEmpty()) {
            // Without any record it was accepted on there is nothing to learn from and nothing to beat, and
            // an improvement judged on nothing could promote an unchecked candidate, so it is refused.
            throw new IllegalArgumentException("Binding " + bindingUuid + " has no accepted records to improve "
                                               + "against; learn again instead");
        }
        // The records it learns for are kept on the feed the binding's corpus is already on, since there is
        // no stream here to keep them on.
        final String corpusFeed = regressionSet.feedOf(bindingUuid)
                .orElseThrow(() -> new IllegalStateException("Binding " + bindingUuid + "'s records are kept on no "
                                                             + "feed that can be read"));
        if (!NullSafe.isBlankString(message)) {
            guidance.given(learner, doc.getUuid(), message, by);
        }

        // The stream to learn from is the corpus itself, reassembled: every record's raw text, between the
        // text the stream had before and after its records. A record whose parser did not report where it
        // was split (the JSON and XML parsers) cannot be put back through a parser, so that is refused.
        final String sample = Corpus.stream(accepted)
                .orElseThrow(() -> new IllegalArgumentException("Binding " + bindingUuid + " was accepted on records "
                                                                + "that cannot be put back through its parser; "
                                                                + "learn again instead"));
        // No feed and no type: there is no stream here to read them from, and a learner does not know its
        // feed, so the model is shown the corpus alone.
        final Input input = improvementSample(sample);
        final Scorecard scorecard = Scorecard.of(doc.getChecks(), scorers, targetSchemas.of(doc).orElse(null));
        // The worker asks the model, not this request. A model conversation can last minutes: run inside
        // the web request it would hold an HTTP thread throughout, could not survive the node restarting,
        // and would be lost if the browser gave up. So the session is opened, left waiting at its first
        // question, and resumed by the worker exactly as a deferred learning is. The session records the
        // binding it is improving, so the worker reassembles the corpus to learn from rather than looking
        // for a stream that does not exist.
        //
        // Nothing waits on it meanwhile: the serving binding serves every stream throughout, and a
        // candidate only takes over by passing the ordinary promotion gate.
        return sessions.recording(doc, learner, input, RecordedAdvisor.awaiting(), bindingUuid,
                reason -> StageRun.refused(doc, learner,
                        "Binding " + bindingUuid + " is not being improved: " + reason),
                recorder -> improveUnderClaim(doc, learner, incumbent, input, scorecard, accepted,
                        corpusFeed, recorder));
    }

    /// Wraps an improvement's sample as an input: the corpus records the binding was accepted on,
    /// reassembled, since there is no stream to learn from. It has no feed or type, because there is no
    /// stream to read them from and a learner does not know its feed, so the model is shown the corpus alone.
    ///
    /// @param sample The corpus reassembled as one stream.
    /// @return The sample as an input.
    private static Input improvementSample(final String sample) {
        return new Input(-1L, null, null, Map.of(), sample, null);
    }

    /// Builds the sample the worker resumes an improvement session with: its binding's corpus records,
    /// reassembled. Empty if the binding's records have gone since the person asked, in which case the
    /// worker closes the session rather than asking the model about nothing.
    ///
    /// @param attempt The session, which must be an improvement.
    /// @return The sample to learn from, or empty.
    public Optional<Input> improvementInput(final Attempts.Attempt attempt) {
        final List<Accepted> accepted = regressionSet.accepted(attempt.improving());
        return accepted.isEmpty()
                ? Optional.empty()
                : Corpus.stream(accepted).map(Learning::improvementSample);
    }

    /// Runs the improvement itself, under the session's claim on the learner.
    ///
    /// The conversation opens by telling the model what the serving binding is and what it scored, so that
    /// it improves on it rather than starting again from nothing. The gate is the accepted records
    /// themselves, all of them, since there is no stream to be better on. A candidate that is worse on any
    /// record the binding was accepted on is not an improvement, whatever else it does.
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
        // Every accepted record is checked, because an improvement has no stream to be better on, so the
        // records the binding was accepted on are all the evidence there is. They are only checked if the
        // regression check is a gate, and against the same bar a served record is held to.
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
        // The corpus was the stream, so the records the model was shown of it were learned for again. Each
        // goes into the corpus with what the candidate now makes of it, and the cap drops the oldest.
        final List<Accepted> entries = Corpus.entries(learned.chain(), boundary(learned),
                LearningPrefix.learningPrefix(input.data(), doc), null, learned.targets(), candidate.score(),
                recorder.attemptId(), clock.millis(), doc.getRegressionCap());
        // Always written as a draft, whatever the promotion mode. The result is written the same way for
        // both modes; the only difference is who approves it: a person, or the system at once.
        final DocRef fragment = write(learner, learned);
        // A learner marked to learn again has just done so, at a person's request, so the mark is cleared;
        // otherwise its next stream would relearn the binding somebody has only just improved.
        states.reset(learner);
        // The serving binding goes on serving until this draft is approved.
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

    /// Keeps the serving binding, and records why it was not replaced rather than hiding it.
    private StageRun kept(final LearningFlowDoc doc,
                          final Learner learner,
                          final Binding incumbent,
                          final String reason,
                          final List<Exchange> transcript) {
        return new StageRun(doc, new Kept(incumbent, reason), learner, null, null, List.of(), transcript, null,
                List.of());
    }

    /// Works out why a stopped session should not be resumed, from the learner's current state, read in the
    /// same way a task's decision reads it for a stream.
    ///
    /// @return The reason, or empty if the session may be resumed.
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
                // The flow exited with give-up. This is the default for a parser step, because a stream that
                // cannot be split into records that meet the gates is the one failure nothing can serve,
                // and any step can exit this way if the flow says so. The learner gives up, and the streams
                // waiting on it are woken so that they error with the reason.
                final StageRun givenUp = givenUp(doc, learner, abandoned.reason(),
                        abandoned.reason(), abandoned.diagnostics(), List.of(), outcome.transcript(),
                        abandoned.report());
                wake(learner);
                return givenUp;
            }
            if (recorder.worker()) {
                // In the worker, a session that cannot finish parks for a person rather than giving up. Its
                // streams go on waiting, and nothing is made permanent just because the budget or the
                // candidates ran out.
                return parked(doc, learner, abandoned.reason(), abandoned.report(), outcome.transcript());
            }
            return givenUp(doc, learner, abandoned.reason(), abandoned.reason(),
                    abandoned.diagnostics(), List.of(), outcome.transcript(), abandoned.report());
        }
        final Learned learned = (Learned) outcome;

        // Judge the candidate over the whole stream, including the records held out from the model.
        final Judged judged = Judging.asLearned(Judging.rerun(learned.chain(), input.data(), boundary(learned)),
                scorecard);
        if (!judged.clearsGates()) {
            // Woken, as for the flow's give-up, so that the streams waiting on the learner error with the
            // reason now rather than after the retry delay.
            final StageRun givenUp = givenUp(doc, learner, "A gate failed",
                    "Candidate failed " + judged.failedGates(),
                    judged.verdicts().stream().flatMap(verdict -> verdict.feedback().stream()).toList(),
                    judged.verdicts(), learned.transcript());
            wake(learner);
            return givenUp;
        }
        final DocRef fragment = write(learner, learned);
        // The corpus gets every record of the sample the model was shown, since a first learning learned
        // for all of them.
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
        // Add the fragment run's diagnostics. After a full pass these are the records that fell short, as
        // the parser split them and as the transform output them, which the model needs to see to learn for
        // a record that lay beyond the sample.
        opening.addAll(served.diagnostics());
        final Outcome outcome = learn(doc, learner, input, scorecard, opening, served.held(), recorder);
        // The relearning mark is cleared now that the relearning has happened, and not before. A session
        // that stops at a question is resumed by the worker, which must find the learner as this walk found
        // it, or it would ask something different. It is cleared whatever the candidate came to, or a learner
        // that cannot be fixed would be relearned on every stream.
        states.reset(learner);
        if (outcome instanceof final Abandoned abandoned) {
            // Whatever exit the flow named, a learner that a binding is serving does not give up, because
            // that would error streams that are being served.
            if (recorder.worker()) {
                // Parked, as in a first learning: the serving binding serves what it can, and the held
                // stream waits.
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
        // The candidate's score is not compared with the serving binding's. Instead, the regression corpus
        // check holds a relearning to doing no worse than what it replaces: every record the binding was
        // accepted on, run through the candidate's transform, must clear the bar, and no record that was
        // translated may become an Unknown. The output may otherwise change; the output each record was
        // accepted with is kept so that a person can see what changed, not so that the gate can refuse it.
        // The corpus is only checked if the regression check is a gate, and against the same bar a served
        // record is held to.
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
        // The corpus gets what this session learned for: the records of the stream that fell short under the
        // serving binding, with what the candidate makes of them. If no record was named (the stream fell
        // short on yield or coverage, which measure the whole stream rather than a record), the session
        // learned for the sample it was shown, as a first learning does.
        final Set<Integer> fellShort = served.failingRecords();
        final List<Accepted> entries;
        if (!served.held().isEmpty()) {
            // A full pass kept the records themselves, which may lie beyond the text in memory, so they
            // are the entries.
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
        // Always written as a draft, whatever the promotion mode. The serving binding goes on serving;
        // approval rebinds it to this fragment and carries the draft's history over.
        final DocRef fragment = write(learner, learned);
        final Binding draft = promotion.writeDraft(doc, learner, fragment, candidate.score(), boundary(learned));
        regressionSet.accept(doc.getUuid(), draft.getUuid(), input.feed(), input.type(), entries,
                doc.getRegressionCap());
        if (!approvesItself(doc)) {
            states.awaitReview(learner, doc.getUuid(), draft.getUuid());
            return emit(doc, new Drafted(draft, candidate.score()), learner, input,
                    incumbent, served, learned.transcript());
        }
        // Promoting resets the learner's state again, now that the new transform is in force. A stream that
        // fell short of the serving binding while the session ran will have marked the learner after the
        // reset above, and a mark left standing would relearn what has just been learned. A stream that
        // falls short from now on was judged against a transform since replaced, and does not mark the
        // learner (see `Stage.session`). [Promotion#promote] resets the learner and wakes its pipeline.
        final Binding rebound = promotion.promote(doc, learner, draft);
        return emit(doc, new Rebound(incumbent, rebound, candidate.score()), learner,
                input, incumbent, served, learned.transcript());
    }

    /// Binds a fragment from a first learning that passed the gates. The fragment's stylesheets are checked
    /// against what a learned stylesheet may use, and the learner gives up if they use anything else. It is
    /// written as a draft binding and its corpus records are stored. On an automatic Learning Flow, if the
    /// stream had at least the minimum number of records, the draft is promoted at once, which wakes the
    /// learner's pipeline so its waiting streams are processed, and the stream's output is recorded.
    /// Otherwise (review mode, or too few records) the draft awaits a person's review, and the learner's
    /// streams, this one included, wait for it.
    private StageRun bind(final LearningFlowDoc doc,
                          final Learner learner,
                          final Input input,
                          final DocRef fragment,
                          final Judged judged,
                          final List<Accepted> entries,
                          final List<Exchange> transcript,
                          final RecordBoundary boundary) {
        // What a learned stylesheet may use is checked again here, on the fragment as written, not only by
        // the permitted check when the candidate was judged. There is no enforcement when the stylesheet
        // runs, so this is the last check before a first learning's binding is written. (The relearning
        // and improvement paths, and approval of a draft, do not repeat this check.)
        final List<String> refusals = refusedByTheList(fragment);
        if (!refusals.isEmpty()) {
            return givenUp(doc, learner, "Refused", refusals.get(0),
                    refusals.stream().map(refusal -> new StoredError(Severity.ERROR, null, PERMITTED, refusal))
                            .toList(),
                    judged.verdicts(), transcript,
                    StopReports.refusedByTheList(refusals));
        }
        // Always written as a draft, whatever the promotion mode.
        final Binding draft = promotion.writeDraft(doc, learner, fragment, judged.score(), boundary);
        // The records learned for go into the corpus now, even under a draft: they are what the session put
        // to the model, whether or not a person has yet approved what it made of them. Rejection and
        // retraction remove them again.
        regressionSet.accept(doc.getUuid(), draft.getUuid(), input.feed(), input.type(), entries,
                doc.getRegressionCap());
        states.reset(learner);
        // A first result learned from too few records is never approved by the system, even on an
        // automatic Learning Flow, because there is no meaningful held-out judgement behind it; it waits for
        // a person, as results do in review mode. The minimum record count only applies to a first
        // learning, which is why the relearning paths don't check it.
        if (!approvesItself(doc) || judged.records() < doc.getMinRecordsToLearn()) {
            states.awaitReview(learner, doc.getUuid(), draft.getUuid());
            return new StageRun(doc, new Drafted(draft, judged.score()), learner, null,
                    null, judged.verdicts(), transcript, null, List.of());
        }
        final Binding promoted = promotion.promote(doc, learner, draft);
        return emit(doc, new Promoted(promoted, judged.score()), learner, input, promoted, judged, transcript);
    }

    /// Whether the system approves a draft that has just passed the gate, rather than a person. On an
    /// automatic Learning Flow it does, at once, through the same code a person's approval uses; on one
    /// left on review, which is the default, the draft waits for a person.
    ///
    /// @param doc The document.
    /// @return True if the system approves its own drafts.
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
        // The claim is kept alive while a question is with the model as well as between questions,
        // otherwise one call longer than the lease would let another node take over the session mid-call.
        final Advisor advisor = new HeartbeatingAdvisor(recorder.advisor(), recorder::heartbeat,
                HeartbeatingAdvisor.intervalFor(lease));
        recorder.walkBegins();
        // The session's claim is renewed before every question, and every turn is recorded as it is
        // answered and again as it is judged, so a session that is still running shows how far it got.
        final Conversation conversation = new Conversation(advisor, runners, scorecard, clock,
                recorder::heartbeat, recorder::turn)
                .alreadySpent(recorder.spentSoFar())
                .begunAt(recorder.startedMs)
                .granted(recorder.grant.tokens(), recorder.grant.ms())
                .exhaustFirst(recorder.exhaustNext)
                // The records the held stream fell short on: every candidate for the transform is held to
                // them, even though they are not in the sample.
                .heldTo(held)
                // The guidance left for this learner, read afresh before every question, so that guidance
                // given while the session was parked is included by the turn that resumes it.
                .guidedBy(() -> guidance.standing(learner));
        final Sample sample = sampleOf(input, doc);
        final long before = advisor.tokensUsed();
        final List<Exchange> asked = new ArrayList<>();
        try {
            final Outcome outcome = conversation.run(doc, sample, opening);
            asked.addAll(outcome.transcript());
            return outcome;
        } finally {
            // What the session cost is added to the document's cluster-wide count, whether it learned
            // anything or not and whether it returned or threw. A session that is abandoned still costs what
            // it asked, and a model that fails after charging is exactly the runaway spending the spend
            // breaker exists to catch.
            spend.record(doc.getUuid(), Math.max(0L, advisor.tokensUsed() - before), asked.size(),
                    SPEND_WINDOW_MS);
        }
    }

    /// Builds the sample the model is shown of a stream, for both a learning walk and a prompt rebuild.
    private static Sample sampleOf(final Input input, final LearningFlowDoc doc) {
        return Sample.of(LearningPrefix.learningPrefix(input.data(), doc), input.feed(), input.type())
                .from(input.facts())
                // How much of what was read the model is not shown, so that a stop report can say so: a
                // person told "couldn't write the parser" should know that the model saw eight kilobytes of
                // a two-megabyte stream.
                .cutFrom(Input.bytes(input.data()))
                // Whether the stream is longer than the prefix read as text. A prefix of a single document
                // is not a valid document, so such a stream cannot be sampled at all
                .beyondPrefix(input.opener() != null);
    }

    /// Rebuilds the prompts a session's turns sent to the model (prompts are rebuilt rather than stored, so
    /// that they are not a copy of the stream's data). The session's first walk is replayed over the same
    /// sample, with every question answered from the recorded turns and worded as the session recorded, so
    /// the feedback each prompt included is derived again as it was the first time. Nothing is written and
    /// the model is not asked.
    ///
    /// Only the first walk is rebuilt. A later round of the same session (a relearning after the held stream
    /// fell short) opened with feedback from a full pass over the stream, which this does not repeat, so its
    /// turns are missing from the result and the caller says so. A walk that no longer replays, because the
    /// Learning Flow's steps have changed since, stops where it diverges, and what it reached is returned.
    ///
    /// @param doc       The document.
    /// @param attemptId The session's id.
    /// @param input     The stream it learned from, read back from the store.
    /// @return The prompts by turn number, and how many withdrawn pieces of guidance each included; empty if
    ///         the session's wording was not recorded, in which case the prompts cannot be rebuilt
    ///         without guessing.
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
            // The replay diverged from the record, or stopped where the session stopped; what it reached
            // is returned
            LOGGER.debug(() -> LogUtil.message("Rebuilding attempt {} stopped: {}", attemptId, e.getMessage()));
        }
        return Optional.of(new Rebuilt(capture.prompts(), capture.withdrawn(), askedWith.olderThanBuiltIn()));
    }

    /// Checks the fragment's stylesheets for anything a learned stylesheet may not use.
    ///
    /// @param fragment The fragment about to serve.
    /// @return One sentence per refusal; empty if every stylesheet uses only what it may.
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

    /// Runs a session to its end, using the held stream as the acceptance test. A promotion is followed by a
    /// full pass of the whole stream through the promoted transform, judging every record. If any record
    /// falls short, the records that did (as the parser split them, with what the transform made of them)
    /// open the next round of questions, and the transform is relearned in place. The session ends when the
    /// stream clears, or parks for a person when its budgets or its rounds run out.
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
            // Why the last round kept the serving binding, if it did. The candidate was asked to clear the
            // held stream and was not promoted, so the serving binding's pass still stands, and the next
            // question says what kept the candidate out as well as what the stream needs.
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
                    // A draft, a serving binding kept before any pass, or a park: either nothing is serving
                    // the stream yet, or the session has already reached its outcome.
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

    /// Runs the held stream in full through a binding's fragment, judging every record against the bar and
    /// keeping those that fall short. Nothing is passed downstream and nothing is written.
    ///
    /// What the run made of the **input** is kept as well as what it made of the output. Without it, the
    /// worker would treat a stream the parser could make nothing of as a stream with nothing wrong, and wake
    /// it, and the task would defer it again: a relearning loop that costs money on every round.
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

    /// Parks the session for a person.
    private StageRun parked(final LearningFlowDoc doc,
                            final Learner learner,
                            final String reason,
                            final List<Exchange> transcript) {
        return parked(doc, learner, reason, null, transcript);
    }

    /// Parks the session for a person, with the reason in parts if the session produced a report.
    private StageRun parked(final LearningFlowDoc doc,
                            final Learner learner,
                            final String reason,
                            final StopReport report,
                            final List<Exchange> transcript) {
        return new StageRun(doc, new Parked(reason, report), learner, null, null, List.of(), transcript, null,
                List.of());
    }

    /// Gets the record boundary for a learned chain: the boundary the flow settled, or, for a chain that
    /// begins with the Data Splitter (whose output is always `records` containing `record` elements), one
    /// record per `record` element, one level deep. It is set here rather than left empty because an empty
    /// boundary means a fragment with no `SplitFilter`, whose transform is given the whole parsed stream as
    /// one document and holds it all in memory; a text stream of any size must be split too.
    static RecordBoundary boundary(final Learned learned) {
        if (learned.boundary() != null) {
            return learned.boundary();
        }
        return !learned.chain().isEmpty() && DATA_SPLITTER.equals(learned.chain().get(0).elementType())
                ? RecordBoundary.ofElement("record").atDepth(1)
                : null;
    }

    /// Writes a new fragment in the learner's folder under its name, with the first free number added to
    /// the name if the folder already has a fragment of that name.
    private DocRef write(final Learner learner, final Learned learned) {
        final Places.Place place = places.of(learner);
        return writer.write(place.folder(), place.name(), learned.chain(), boundary(learned));
    }

    /// Records the stream's output against the binding that produced it, and returns the run.
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

    /// Gives the learner up because no candidate passed. This stream gets a sentinel (an error), as every
    /// later stream will until a person sends the learner back.
    private StageRun givenUp(final LearningFlowDoc doc,
                             final Learner learner,
                             final String decision,
                             final String reason,
                             final List<StoredError> diagnostics,
                             final List<Verdict> verdicts,
                             final List<Exchange> transcript) {
        return givenUp(doc, learner, decision, reason, diagnostics, verdicts, transcript, null);
    }

    /// As [#givenUp(LearningFlowDoc, Learner, String, String, List, List, List)], with the reason in parts if
    /// the session produced a report.
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
