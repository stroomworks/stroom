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

package stroom.shapeshifter.ai.stage;

import stroom.docref.DocRef;
import stroom.meta.shared.MetaFields;
import stroom.pipeline.xml.event.EventList;
import stroom.processor.api.DeferTaskException;
import stroom.shapeshifter.ai.extraction.PerRecord;
import stroom.shapeshifter.ai.fragment.FragmentCodes;
import stroom.shapeshifter.ai.fragment.FragmentRunner;
import stroom.shapeshifter.ai.fragment.FragmentRunner.FragmentRecord;
import stroom.shapeshifter.ai.fragment.FragmentWriter;
import stroom.shapeshifter.ai.fragment.RecordJudge;
import stroom.shapeshifter.ai.fragment.RecordShortfall;
import stroom.shapeshifter.ai.fragment.ServedStream;
import stroom.shapeshifter.ai.fragment.Shortfalls;
import stroom.shapeshifter.ai.learning.Advisor;
import stroom.shapeshifter.ai.learning.Advisors;
import stroom.shapeshifter.ai.learning.Conversation;
import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.learning.LearnedStep;
import stroom.shapeshifter.ai.learning.Outcome;
import stroom.shapeshifter.ai.learning.Outcome.Abandoned;
import stroom.shapeshifter.ai.learning.Outcome.Learned;
import stroom.shapeshifter.ai.learning.Question;
import stroom.shapeshifter.ai.learning.RecordedAdvisor;
import stroom.shapeshifter.ai.learning.RecordedAdvisor.AwaitingAnswer;
import stroom.shapeshifter.ai.learning.Sample;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.learning.Target;
import stroom.shapeshifter.ai.learning.TargetChecks;
import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.ai.scoring.OutputRecords;
import stroom.shapeshifter.ai.scoring.Records;
import stroom.shapeshifter.ai.scoring.Scorecard;
import stroom.shapeshifter.ai.scoring.Scorer;
import stroom.shapeshifter.ai.scoring.Verdict;
import stroom.shapeshifter.ai.stage.Attempts.Grant;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Deferred;
import stroom.shapeshifter.ai.stage.Decision.Drafted;
import stroom.shapeshifter.ai.stage.Decision.GivenUp;
import stroom.shapeshifter.ai.stage.Decision.Kept;
import stroom.shapeshifter.ai.stage.Decision.Parked;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Provisional;
import stroom.shapeshifter.ai.stage.Decision.Rebound;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.Decision.Would;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.RegressionSet.Accepted;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.DocPath;
import stroom.util.shared.ElementId;
import stroom.util.shared.NullSafe;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;
import stroom.util.shared.TextRange;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import net.sf.saxon.s9api.XdmNode;
import org.xml.sax.ContentHandler;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One supervised stage over one stream, as design 02 §4 spells it out: route, learn, judge, write,
 * emit. This is the supervisor's logic without the pipeline element around it; the element (design 01
 * §12 item 4) will delegate to it. The fragment is run by the step runners here, as it was learned.
 * <p>
 * The seed fixes the held-out split for the run (design 02 §7). Where the input is raw text and records
 * do not yet exist, the learning sample is a prefix and the whole stream is held out, as §7 also says;
 * the seeded shuffle applies once the replay unit is a record and is not needed by the first scenarios.
 * <p>
 * Runtime state is what the stage is given (A26): the learners' states, the outputs' bindings and the
 * regression set. A sentinel is an error stream and nothing more (A131): the attempt that gave up is the
 * record of why. Binding a learner wakes its pipeline, which puts its waiting streams through, and asks
 * for nothing to be reprocessed (A129). In review mode (A25) a binding is a draft until {@link #approve}
 * makes it the promotion or {@link #reject} discards it.
 */
public final class Stage {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(Stage.class);

    /**
     * The window a document's spend is counted over (A44). A24 will make it a setting, with the budget it
     * is measured against; until then it is an hour, which is long enough to see a feed burning spend and
     * short enough that yesterday's does not hide it.
     */
    private static final long SPEND_WINDOW_MS = 3_600_000L;
    private static final JsonFactory JSON = new JsonFactory();
    private static final ElementId STAGE = new ElementId("Stage");

    private final Advisors advisors;
    private final List<StepRunner> runners;
    private final List<Scorer> scorers;
    private final FragmentWriter writer;
    private final FragmentRunner fragmentRunner;
    private final Attempts attempts;
    private final LearnerBindings learnerBindings;
    private final LearnerStates states;
    private final Spend spend;
    private final Guidance guidance;
    private final Outputs outputs;
    private final RegressionSet regressionSet;
    private final Deferrals deferrals;
    private final Learners learners;
    private final Wake wake;
    private final Places places;
    private final Snapshots snapshots;
    private final FragmentCodes codes;
    /// The element whose output is always `records` over `record`.
    private static final String DATA_SPLITTER = "DSParser";
    /// What a record of the chain's output is judged as when it is served: the element type the scorers
    /// of meaning read it as, which is any element that is not a parser.
    private static final String TRANSFORM = "XSLTFilter";
    /// The scorers that judge a record (design 04 §6): the bar, record by record.
    private static final Set<ScorerType> RECORD_SCORERS = Set.of(ScorerType.SCHEMA_CONFORMANCE,
            ScorerType.EXTRACTION_QUALITY, ScorerType.BUSINESS_RULES);
    private final Clock clock;
    /// How long a claim on an attempt lasts from its last renewal (design 04 §4).
    private final Duration lease;
    private final long seed;
    private final String node;

    public Stage(final Advisors advisors,
                 final List<StepRunner> runners,
                 final List<Scorer> scorers,
                 final FragmentWriter writer,
                 final FragmentRunner fragmentRunner,
                 final Attempts attempts,
                 final LearnerBindings learnerBindings,
                 final LearnerStates states,
                 final Spend spend,
                 final Guidance guidance,
                 final Outputs outputs,
                 final RegressionSet regressionSet,
                 final Deferrals deferrals,
                 final Learners learners,
                 final Wake wake,
                 final Places places,
                 final Snapshots snapshots,
                 final FragmentCodes codes,
                 final Clock clock,
                 final Duration lease,
                 final long seed,
                 final String node) {
        this.advisors = advisors;
        this.runners = List.copyOf(runners);
        this.scorers = List.copyOf(scorers);
        this.writer = writer;
        this.fragmentRunner = fragmentRunner;
        this.attempts = attempts;
        this.learnerBindings = learnerBindings;
        this.states = states;
        this.spend = spend;
        this.guidance = guidance;
        this.outputs = outputs;
        this.regressionSet = regressionSet;
        this.deferrals = deferrals;
        this.learners = learners;
        this.wake = wake;
        this.places = places;
        this.snapshots = snapshots;
        this.codes = codes;
        this.clock = clock;
        this.lease = lease;
        this.seed = seed;
        this.node = node;
    }

    /// When a claim taken or renewed now lapses: one lease on, which is a turn's allowance and not the
    /// attempt's whole budget, so that a session whose node has died is noticed within a turn (§4). The
    /// claim is renewed before every question and as every turn is recorded.
    private long claimUntil() {
        return clock.millis() + lease.toMillis();
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
    public StageRun resume(final ShapeshifterAiDoc doc, final long attemptId, final Input input,
                           final Advisor answerer) {
        final Recorded recorded = attempts.byId(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("No attempt " + attemptId));
        final Learner learner = recorded.attempt().learner();
        // Carrying it on is taking it (A45): an attempt that has finished is not resumed, and one another
        // node has taken since is that node's until its claim lapses.
        if (!attempts.claimed(attemptId, node, clock.millis(), claimUntil())) {
            return refused(doc, learner, "Attempt " + attemptId + " is not this node's to carry on: it has "
                                         + "finished, or another node holds it");
        }
        // An attempt may have waited a long time, and the learner is not as it left it: an operator may
        // have turned learning off, a person may have a draft of it in front of them, or it may have been
        // given up. Whatever the attempt was learning towards, it is not wanted
        // now, so it is closed rather than carried on into a binding nobody asked for.
        final Optional<Binding> matched = bindingFor(learner);
        final Optional<String> stop = whyNotToCarryOn(doc, learner, matched);
        if (stop.isPresent()) {
            record(() -> attempts.closed(attemptId, AttemptStatus.ABANDONED, stop.get(), null, null, 0L));
            return refused(doc, learner, stop.get());
        }
        final Scorecard scorecard = new Scorecard(doc.getScorers(), scorers);
        final RecordedAdvisor replay = new RecordedAdvisor(recorded.turns(), answerer);
        // Who answered each turn the first time, by its number: a replayed turn is rewritten as it is
        // re-walked, and it must not come back saying the model answered what a person answered (A28).
        final Map<Integer, String> answeredBefore = recorded.turns().stream()
                .filter(turn -> turn.answeredBy() != null)
                .collect(Collectors.toMap(Attempts.Turn::number, Attempts.Turn::answeredBy,
                        (first, second) -> first));
        // What the attempt is doing is not stored either: it is read from the bindings as they stand. A learner
        // a binding already serves is being relearned (A29), and carrying the attempt on as though it were
        // learning afresh would append a second binding for one learner and orphan a fragment behind the
        // first.
        if (matched.isPresent()) {
            final Binding binding = matched.get();
            final Judged served = judge(scorecard, binding.getPipeline(), input.data(), binding.getRecordBoundary());
            final String marked = states.relearnReason(learner)
                    .orElse("the learner was marked to learn again");
            return carrying(doc, learner, input, attemptId, recorded.tokensSpent(), recorded.grant(),
                    recorded.exhaustNext(), answeredBefore, replay, true,
                    reason -> emit(doc, new Kept(binding, reason), learner, input, binding, served, List.of()),
                    recorder -> settle(doc, learner, input, scorecard, recorder,
                            relearnUnderClaim(doc, learner, binding, input, scorecard, served, marked,
                                    recorder)));
        }
        return carrying(doc, learner, input, attemptId, recorded.tokensSpent(), recorded.grant(),
                recorded.exhaustNext(), answeredBefore, replay, true,
                reason -> refused(doc, learner, reason),
                recorder -> settle(doc, learner, input, scorecard, recorder,
                        learnAndBind(doc, learner, input, scorecard, recorder)));
    }

    /**
     * A person's answer in place of a turn's (A28): *answer instead*, for the question an attempt stopped
     * at, and *edit and re-run from here*, for one it had already been answered. Either way the attempt
     * is left waiting, for the worker to carry on from what it has now been told — the answer is written
     * and nothing is run here, since a person's request must not wait on a model.
     * <p>
     * The turns after the one answered are discarded: what they were is a consequence of an answer that
     * has changed, and re-walking derives them again (A45). An attempt that had finished is opened again
     * and takes its learner's claim back, which fails where another attempt holds it — and an attempt a node
     * is walking right now is refused, since its answers are that walk's to give and a person's answer
     * would be overwritten by the turn it is about to record.
     *
     * @param answeredBy Who answered: recorded against the turn, and not overwritten when the attempt is
     *                   re-walked (A28).
     */
    /// Give a parked session more to spend (A65, A77): tokens and milliseconds beyond the document's
    /// budgets, recorded against the attempt alone, and the attempt opened again for the worker to carry
    /// on with them. Only a parked attempt takes a grant: one still running has budget, and one that
    /// finished has nothing to spend it on.
    ///
    /// @param tokens Tokens to add; zero for none.
    /// @param ms     Milliseconds to add; zero for none.
    public void grant(final ShapeshifterAiDoc doc, final long attemptId, final long tokens, final long ms) {
        final Recorded recorded = attempts.byId(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("No attempt " + attemptId));
        if (!doc.getUuid().equals(recorded.attempt().docUuid())) {
            throw new IllegalArgumentException("Attempt " + attemptId + " belongs to document "
                                               + recorded.attempt().docUuid() + ", not " + doc.getUuid());
        }
        if (recorded.status() != AttemptStatus.PARKED) {
            throw new IllegalStateException("Attempt " + attemptId + " is not parked for a person: it is "
                                            + recorded.status().getDisplayValue());
        }
        if (tokens < 0 || ms < 0 || (tokens == 0 && ms == 0)) {
            throw new IllegalArgumentException("A grant is tokens, milliseconds or both, and not less than nothing");
        }
        attempts.granted(attemptId, tokens, ms);
        if (!attempts.reopened(attemptId, clock.millis(), claimUntil())) {
            throw new IllegalStateException("Attempt " + attemptId + " cannot be carried on: another attempt is "
                                            + "learning for " + recorded.attempt().learner());
        }
    }

    /// Send a parked session to the exhaustion step (A65, A76): the attempt is opened again for the
    /// worker, which carries it on from its recorded turns and, at the first transform question it would
    /// put to the model, takes the flow's spent transition instead — to `ADMIT`, where the flow has one.
    /// Only a parked attempt is sent.
    public void exhaust(final ShapeshifterAiDoc doc, final long attemptId) {
        final Recorded recorded = attempts.byId(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("No attempt " + attemptId));
        if (!doc.getUuid().equals(recorded.attempt().docUuid())) {
            throw new IllegalArgumentException("Attempt " + attemptId + " belongs to document "
                                               + recorded.attempt().docUuid() + ", not " + doc.getUuid());
        }
        if (recorded.status() != AttemptStatus.PARKED) {
            throw new IllegalStateException("Attempt " + attemptId + " is not parked for a person: it is "
                                            + recorded.status().getDisplayValue());
        }
        // The sending first, so that no worker takes the attempt between the reopening and it; on a
        // parked row it stands harmlessly until the attempt is reopened and closed.
        attempts.exhaustNext(attemptId);
        if (!attempts.reopened(attemptId, clock.millis(), claimUntil())) {
            throw new IllegalStateException("Attempt " + attemptId + " cannot be carried on: another attempt is "
                                            + "learning for " + recorded.attempt().learner());
        }
    }

    public void amend(final ShapeshifterAiDoc doc,
                      final long attemptId,
                      final int turnNumber,
                      final String answer,
                      final String answeredBy) {
        final Recorded recorded = attempts.byId(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("No attempt " + attemptId));
        if (!doc.getUuid().equals(recorded.attempt().docUuid())) {
            throw new IllegalArgumentException("Attempt " + attemptId + " belongs to document "
                                               + recorded.attempt().docUuid() + ", not " + doc.getUuid());
        }
        // Before anything is changed: an attempt is not opened again for a turn it has not got, or a
        // person who mistyped a number would reopen a finished attempt, wipe what it came to and be told
        // their request failed.
        if (recorded.turns().stream().noneMatch(turn -> turn.number() == turnNumber)) {
            throw new IllegalArgumentException("Attempt " + attemptId + " has no turn " + turnNumber);
        }
        if (!attempts.reopened(attemptId, clock.millis(), claimUntil())) {
            throw new IllegalStateException("Attempt " + attemptId + " cannot be run again: it is being "
                                            + "carried on now, or another attempt is learning for "
                                            + recorded.attempt().learner());
        }
        attempts.amended(attemptId, turnNumber, answer, answeredBy);
        // What the attempt drafted goes with the answer that produced it. The turns after the one
        // answered are discarded for exactly this reason — they are a consequence of an answer that has
        // changed — and a draft binding is the last of those consequences. Left in place it would be a
        // draft nothing awaits: the reset below clears the pointer that says the learner is waiting for
        // it, so approving or rejecting it would throw, every stream of the learner would be sentinelled
        // by it, and the only way out would be deleting the binding by hand.
        if (recorded.bindingUuid() != null) {
            learnerBindings.byUuid(recorded.bindingUuid())
                    .map(LearnerBindings.Held::binding)
                    .filter(Binding::isDraft)
                    .ifPresent(draft -> {
                        learnerBindings.remove(recorded.attempt().learner(), draft.getUuid());
                        regressionSet.discard(draft.getUuid());
                        snapshots.discard(draft.getUuid());
                    });
        }
        // A person re-running an attempt is saying the learner is not settled: whatever this attempt
        // concluded about it — given up, marked, a draft awaiting someone — is undone, or the re-run would
        // be refused by the state its own first run left behind (A28).
        states.reset(recorded.attempt().learner());
    }

    /// What each of the fragment's own elements made of the stream on the run this stage just made, a
    /// list per record (A30, design 01 §11.7) — what the stepper shows beneath a supervised stage.
    ///
    /// **Taken**, not read, and taken immediately after the run it belongs to: the runner is left
    /// holding nothing, so a later run that decided without running a fragment at all — a draft awaiting
    /// review, a learner that gave up — finds nothing here rather than the chain of the run
    /// before it (see [FragmentRunner#takeRecords]).
    ///
    /// Empty where the fragment was walked by a stand-in rather than run as a pipeline, and where
    /// nothing was bound and so nothing ran.
    public List<FragmentRecord> takeFragment() {
        return fragmentRunner.takeRecords();
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
    public StageRun improve(final ShapeshifterAiDoc doc,
                            final String bindingUuid,
                            final String message,
                            final String by) {
        final LearnerBindings.Held held = held(doc, bindingUuid);
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
            throw new IllegalStateException("Shapeshifter AI is disabled for this document");
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
        final Input input = new Input(-1L, null, null, Map.of(), sample, null);
        final Scorecard scorecard = new Scorecard(doc.getScorers(), scorers);
        // Asked where it was asked from, not by the worker. The worker exists because a model call
        // inside a *processing task* holds a task slot (A5); an improvement is not
        // a processing task, the incumbent is serving throughout, and nothing waits on it but the person
        // who asked. Carrying one on from the worker would need the attempt to remember that it is an
        // improvement and to find its sample in the regression set rather than in the stream store,
        // which is not built.
        return recording(doc, learner, input, advisors.of(doc),
                reason -> refused(doc, learner, "Binding " + bindingUuid + " is not being improved: " + reason),
                recorder -> improveUnderClaim(doc, learner, incumbent, input, scorecard, accepted,
                        corpusFeed, recorder));
    }

    /// The improvement itself, under the attempt's claim (A42, A45).
    ///
    /// The model opens with what the incumbent is and what it scores, so that it improves something
    /// rather than starting again in ignorance; and the gate is the accepted records themselves — every
    /// one of them, since there is no stream to be better on. A candidate that is worse on any record
    /// the binding was accepted on is not an improvement whatever else it does (A18).
    private StageRun improveUnderClaim(final ShapeshifterAiDoc doc,
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
        final Judged candidate = asLearned(rerun(learned.chain(), input.data(), boundary(learned)), scorecard);
        if (!candidate.clearsFloor(doc)) {
            return kept(doc, learner, incumbent, "Candidate scored " + candidate.score() + " against a floor of "
                                               + doc.getPromotionFloor(), learned.transcript());
        }
        // Every accepted record, not one: an improvement asked for out of the blue has no stream to be
        // better on, so the records the binding was accepted on are the whole of the evidence (A18).
        for (int i = 0; i < accepted.size(); i++) {
            final Accepted record = accepted.get(i);
            final List<LearnedStep> transforms = Corpus.transforms(learned.chain());
            final Judged onRecord = asLearned(rerun(transforms, record.record(), boundary(learned)), scorecard);
            final Optional<String> shortfall = Corpus.shortfall(record, onRecord.verdicts(),
                    Corpus.outputFor(transforms, record.record(), boundary(learned)));
            if (shortfall.isPresent()) {
                return kept(doc, learner, incumbent, "Candidate " + shortfall.get() + " — "
                                                   + Corpus.name(i + 1, accepted.size(), record),
                        learned.transcript());
            }
        }
        // The corpus was the stream, so what the model was shown of it was learned for again: each of
        // those records goes in with what the candidate now makes of it, and the cap lets the oldest go
        // (A63).
        final List<Accepted> entries = Corpus.entries(learned.chain(), boundary(learned),
                learningPrefix(input.data(), doc), null, learned.targets(), candidate.score(),
                recorder.attemptId(), clock.millis(), doc.getRegressionCap());
        // In place (A58), unless a person is to review it first: a draft is its own fragment until Approve
        // rebinds the incumbent to it.
        final DocRef fragment = doc.getPromotionMode() == PromotionMode.REVIEW
                ? write(learner, learned)
                : rewrite(learner, incumbent, learned);
        // A learner marked to learn again has just done so, by hand: the mark is spent, or its next stream
        // would relearn the binding somebody has only now improved.
        states.reset(learner);
        if (doc.getPromotionMode() == PromotionMode.REVIEW) {
            // Behind the incumbent, which keeps serving until somebody approves this (A25).
            final String draftUuid = UUID.randomUUID().toString();
            final Binding draft = Binding.builder()
                    .uuid(draftUuid)
                    .pipeline(fragment)
                    .draft(true)
                    .score(candidate.score())
                    .recordBoundary(boundary(learned))
                    .snapshot(snapshot(doc, draftUuid, fragment))
                    .build();
            learnerBindings.append(learner, doc.getUuid(), draft);
            regressionSet.accept(doc.getUuid(), draft.getUuid(), corpusFeed, null, entries,
                    doc.getRegressionCap());
            states.awaitReview(learner, doc.getUuid(), draft.getUuid());
            return new StageRun(doc, new Drafted(draft, candidate.score()), learner, null, candidate.output(),
                    candidate.verdicts(), learned.transcript(), null, List.of());
        }
        final Binding rebound = incumbent.copy()
                .pipeline(fragment)
                .snapshot(snapshot(doc, incumbent.getUuid(), fragment))
                .promotedTimeMs(clock.millis())
                .score(candidate.score())
                .recordBoundary(boundary(learned))
                .build();
        regressionSet.accept(doc.getUuid(), rebound.getUuid(), corpusFeed, null, entries,
                    doc.getRegressionCap());
        learnerBindings.replace(learner, doc.getUuid(), rebound);
        wake(learner);
        return new StageRun(doc, new Rebound(incumbent, rebound, candidate.score()), learner,
                new Produced(doc.getUuid(), rebound.getUuid(), fragment, boundary(learned), false,
                        candidate.score()),
                candidate.output(), candidate.verdicts(), learned.transcript(), null, List.of());
    }

    /// The incumbent goes on serving, and why it was not replaced is said rather than swallowed.
    private StageRun kept(final ShapeshifterAiDoc doc,
                          final Learner learner,
                          final Binding incumbent,
                          final String reason,
                          final List<Exchange> transcript) {
        return new StageRun(doc, new Kept(incumbent, reason), learner, null, null, List.of(), transcript, null,
                List.of());
    }

    /**
     * Send a learner back to learn afresh (A28): what a person does to an attempt they are reading when
     * the binding it wrote is not the binding they want and no particular turn is to blame. The learner's next
     * stream relearns, with the incumbent serving until the relearning settles (A29).
     *
     * @param learner The learner.
     * @param reason  Why, in the words a person gave: the model opens its next attempt with it.
     */
    public void relearn(final ShapeshifterAiDoc doc, final Learner learner, final String reason) {
        if (states.reasonGivenUp(learner).isPresent()) {
            // A learner that gave up stays given up until something says otherwise, and a mark alone
            // says nothing: every path reads the give-up first and sentinels the stream before it ever
            // looks at the mark. Sending a learner back *is* saying otherwise, so the give-up goes — and
            // with it the rest of what was concluded, since it is being started afresh.
            states.reset(learner);
        }
        states.markForRelearning(learner, doc.getUuid(), reason);
    }

    /// A person gives a learner up (design 06 A115): it learns no more, its streams with nothing serving
    /// them error with the reason rather than wait, and an open session is not carried on. What serves it
    /// goes on serving. Sending it back to learn again undoes it ([#relearn]). Its pipeline is woken, so
    /// that the streams waiting on it come to their end now rather than at the retry delay.
    ///
    /// @param learner The learner.
    /// @param reason  Why, in the words a person gave: its streams error with it.
    /// @param by      Who gave it up.
    public void giveUp(final ShapeshifterAiDoc doc, final Learner learner, final String reason, final String by) {
        // A draft is decided before a give-up is read, so its streams would go on waiting for a review
        // after the learner had been given up. Rejecting it is giving the learner up, and discards the draft.
        final Optional<String> draft = states.draftAwaiting(learner);
        if (draft.isPresent()) {
            throw new IllegalStateException("Draft binding " + draft.get() + " awaits review: reject it, which "
                                            + "gives the learner up with the reason");
        }
        states.giveUp(learner, doc.getUuid(), "Given up by " + by + ": " + reason);
        wake(learner);
    }

    /**
     * Why an attempt that stopped is not to be carried on, if it is not: the learner's state as it stands
     * now, read as a task's decision reads it for a stream. Empty where the attempt may go on.
     */
    private Optional<String> whyNotToCarryOn(final ShapeshifterAiDoc doc,
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
            return Optional.of("Shapeshifter AI is disabled for this document");
        }
        return Optional.empty();
    }

    /// The stream through the fragment that produced its output before: an **as-processed** reprocess
    /// (design 01 §7.3), which is what an audit needs and what a person asks for when the question is
    /// "what did this stream become?" rather than "what would it become now?".
    ///
    /// The bindings as they stand are not consulted and nothing is learned, scored or relearned. A binding that has
    /// since been rebound, retracted or given up makes no difference: what ran is what runs, because an
    /// improvement is a new document and the old one cannot have changed (§7.3 rule 1). For the other
    /// question — what serves the learner today — the answer is a task's [#decide], which is what an
    /// ordinary reprocess filter puts a stream through.
    ///
    /// @param learner The learner whose pipeline the input is processed again through.
    /// @param input   The input.
    /// @return The run bound to the recorded fragment, or a sentinel where nothing is recorded for this
    /// input: an input nothing remembers cannot be served as it was, and quietly serving it as-current
    /// would answer a question nobody asked.
    public StageRun reprocess(final ShapeshifterAiDoc doc, final Learner learner, final Input input) {
        final Optional<Produced> recorded = outputs.asProcessed(doc.getUuid(), input.id(), input.pipeline());
        if (recorded.isEmpty()) {
            return refused(doc, learner, "Nothing is recorded for input " + input.id()
                                       + " on this pipeline, so it cannot be processed again as it was; "
                                       + "reprocess it as-current instead");
        }
        final Produced produced = recorded.get();
        final Optional<Binding> binding = learnerBindings.byUuid(produced.bindingUuid())
                .map(LearnerBindings.Held::binding);
        // Under the boundary that produced it, which the row remembers: the binding's boundary is today's,
        // and a rebind may have changed it. The same fragment under another boundary is another chain.
        // And with the code that produced it (A61, A70): the fragment has moved in place since, and the
        // snapshot the row names is what ran. A row from before snapshots existed runs the fragment as
        // it stands, which is all that was ever recorded for it.
        final Map<String, String> code = produced.snapshotId() == null
                ? Map.of()
                : snapshots.byId(produced.snapshotId()).map(Snapshots.Snapshot::code).orElse(Map.of());
        final List<Attempted> attempted = fragmentRunner.run(produced.fragment(), input.data(),
                produced.boundary(), code);
        final EventList events = fragmentRunner.lastOutput().orElse(null);
        final String output = attempted.isEmpty()
                ? null
                : attempted.get(attempted.size() - 1).result().output();
        if (output == null) {
            // The fragment is still there and still immutable, so this is the content having gone — a
            // document deleted by hand, or a fragment that never ran cleanly. Said rather than served.
            return refused(doc, learner, "The fragment " + produced.fragment().getUuid()
                                       + " that produced input " + input.id() + " produced nothing this time");
        }
        // Recorded again, because this output carries the same bindings as the one it replaces.
        outputs.emitted(input.id(), input.pipeline(), produced);
        return new StageRun(doc, new Bound(binding.orElse(null)), learner, produced, output, List.of(), List.of(),
                events, attempted.stream().flatMap(step -> step.result().diagnostics().stream()).toList());
    }

    /// A bound fragment run over an input for its events, for a caller that has a downstream to serve
    /// and a [StageRun] that carried none.
    ///
    /// A chain is judged as it is learned — step by step, over the element runners, before it has been
    /// written anywhere — so the stream a learner *learned* on has an output and no events to serve it
    /// with. The fragment it became is run once here, which is the run that stream would have made had it
    /// already been bound, and not a second one: nothing has run this fragment yet.
    ///
    /// A stream already bound never comes here. Its events are the ones its judgement was made
    /// from, which is what §12 item 2 bought: the run that is judged is the run that is served.
    public Served serve(final Produced produced, final Input input) {
        final List<Attempted> attempted = fragmentRunner.run(produced.fragment(), input.data(),
                produced.boundary());
        return new Served(fragmentRunner.lastOutput().orElse(null),
                attempted.stream().flatMap(step -> step.result().diagnostics().stream()).toList());
    }

    /// What this stage would do with a stream, and what it would produce, without doing any of it
    /// (A30, design 01 §11.7).
    ///
    /// Stepping is a dry run. What serves the learner is found and a bound fragment is run, because a
    /// fragment's output is what the person stepping the pipeline came to see — and nothing else
    /// happens at all: no model is asked, no fragment written, no binding written, promoted, retracted or
    /// relearned, no rolling score, no output row and no wake. A learner no
    /// binding serves is *reported*, as [Decision.Would], rather than learned.
    ///
    /// It is a separate walk rather than a flag through [#decide] because every branch of that walk writes
    /// something, and a flag threaded through all of them would be one `if` away from a stepping
    /// session that learned and bound something.
    ///
    /// @param learner     The learner whose pipeline is being stepped.
    /// @param asProcessed As [#reprocess]: the fragment that produced this input's output before,
    ///                    rather than whatever serves the learner today.
    public StageRun dryRun(final ShapeshifterAiDoc doc,
                           final Learner learner,
                           final Input input,
                           final boolean asProcessed) {
        final Scorecard scorecard = new Scorecard(doc.getScorers(), scorers);

        if (asProcessed) {
            final Optional<Produced> recorded = outputs.asProcessed(doc.getUuid(), input.id(), input.pipeline());
            return recorded
                    .map(bindings -> served(doc, new Bound(null), learner, bindings,
                            judge(scorecard, bindings.fragment(), input.data(), bindings.boundary())))
                    .orElseGet(() -> would(doc, learner, "Refuse the stream: nothing is recorded for input "
                                                       + input.id() + " on this pipeline, so it cannot be "
                                                       + "processed again as it was"));
        }

        final Optional<Binding> matched = bindingFor(learner);
        if (matched.isPresent()) {
            final Binding binding = matched.get();
            if (binding.isDraft()) {
                return would(doc, learner, "Wait: draft binding " + binding.getUuid() + " binds "
                                         + binding.getPipeline().getName() + " and awaits review");
            }
            final Judged judged = judge(scorecard, binding.getPipeline(), input.data(), binding.getRecordBoundary());
            // What serving would go on to do with that judgement: a provisional binding that has brought
            // enough records and clears the floor is promoted. One that does not is served as it stands —
            // the floor no longer retracts it (A101) — and is shown as served below.
            if (binding.isProvisional()
                && judged.records() >= doc.getMinRecordsToLearn() && judged.clearsFloor(doc)) {
                return new StageRun(doc, new Would("Promote binding " + binding.getUuid() + ", which scored "
                                                   + judged.score() + " on the " + judged.records()
                                                   + " records this stream brings"),
                        learner,
                        new Produced(doc.getUuid(), binding.getUuid(), binding.getPipeline(),
                                binding.getRecordBoundary(), binding.isProvisional(), judged.score()),
                        judged.output(), judged.verdicts(), List.of(), judged.events(),
                        judged.diagnostics());
            }
            return served(doc, new Bound(binding), learner,
                    new Produced(doc.getUuid(), binding.getUuid(), binding.getPipeline(), binding.getRecordBoundary(),
                            binding.isProvisional(), judged.score()),
                    judged);
        }

        final Optional<String> givenUp = states.reasonGivenUp(learner);
        if (givenUp.isPresent()) {
            return would(doc, learner, "Sentinel the stream: given up: " + givenUp.get());
        }

        if (doc.getLearningMode() == LearningMode.DISABLED) {
            return would(doc, learner, "Wait: Shapeshifter AI is disabled for this document and nothing "
                                       + "serves this learner");
        }
        return would(doc, learner, "Learn a fragment for this learner");
    }

    /// A bound fragment's run, as a dry run reports it: what it produced and what the scorers made of
    /// it, and nothing recorded anywhere.
    private StageRun served(final ShapeshifterAiDoc doc,
                            final Decision decision,
                            final Learner learner,
                            final Produced produced,
                            final Judged judged) {
        return new StageRun(doc, decision, learner, produced, judged.output(), judged.verdicts(), List.of(),
                judged.events(), judged.diagnostics());
    }

    private StageRun would(final ShapeshifterAiDoc doc,
                           final Learner learner,
                           final String what) {
        return new StageRun(doc, new Would(what), learner, null, null, List.of(), List.of(), null,
                List.of());
    }

    /// The task decision (design 04 §3): emit, defer or error, and never learn. Every task does the same
    /// cheap thing — consult what the learner knows, take one of three outcomes — and a session
    /// is the worker's: the only thing a task does about one is open it and stand aside.
    ///
    /// - A binding serves the learner and every record clears the bar: emitted.
    /// - A binding serves it and any record falls short: deferred, with a row against the transform in force,
    ///   and the session opened if none holds the learner's claim. A row whose stamp is still the
    ///   transform in force defers again without running anything.
    /// - No binding: deferred, with the session opened on this stream.
    /// - The learner has given up: errored with the reason — the only outcome that errors.
    public StageRun decide(final ShapeshifterAiDoc doc, final Learner learner, final Input input) {
        // What the learner keeps goes with it to the document it runs under now, so that this document's
        // Supervisor shows what serves its pipeline and no other document's editors can act on it.
        learners.runsUnder(learner, doc.getUuid());
        final Scorecard scorecard = new Scorecard(doc.getScorers(), scorers);
        final Optional<Binding> matched = bindingFor(learner);

        if (matched.isPresent()) {
            final Binding binding = matched.get();
            if (binding.isDraft()) {
                // A person has it in front of them: the stream waits for their decision rather than
                // erroring behind it, and Approve or Reject is what moves it.
                return deferred(doc, learner, input, null, 0, "Awaiting review: draft binding " + binding.getUuid()
                                                             + " binds " + binding.getPipeline().getName());
            }
            return decideServed(doc, learner, binding, input, scorecard);
        }

        final Optional<String> givenUp = states.reasonGivenUp(learner);
        if (givenUp.isPresent()) {
            // A stream that waited on a session that gave up (A54) has reached its end: its row goes, and
            // it errors with the reason. A person sends the learner back from the attempt that gave up,
            // and reprocesses its error streams as they would any others (A129, A131).
            deferrals.released(learner, input.id());
            return refused(doc, learner, "Given up: " + givenUp.get());
        }
        if (doc.getLearningMode() == LearningMode.DISABLED) {
            // The kill switch: no session can open, so the stream waits for a person to turn learning on
            // and the deferral age (A57) is what raises it if nobody does.
            return deferred(doc, learner, input, null, 0,
                    "Shapeshifter AI is disabled for this document and nothing serves this learner");
        }
        // Learning leaves the task (A60, A64): the session is opened on this stream and parked at its
        // first question for the worker, and the stream waits on it. Where an attempt already holds the
        // learner's claim, this stream waits on that one.
        return recording(doc, learner, input, RecordedAdvisor.awaiting(),
                reason -> deferred(doc, learner, input, null, 0, reason),
                recorder -> learnAndBind(doc, learner, input, scorecard, recorder));
    }

    /// The task decision for a learner a binding serves.
    private StageRun decideServed(final ShapeshifterAiDoc doc,
                                  final Learner learner,
                                  final Binding matched,
                                  final Input input,
                                  final Scorecard scorecard) {
        // As it serves now (A72): a hand edit is snapshotted here, before the stamp is compared, so that
        // a stream deferred against the old snapshot is scored against the edit.
        final Binding binding = freshened(doc, learner, matched);
        final String stamp = stamp(binding);
        final Optional<Deferral> row = deferrals.of(learner, input.id());
        if (row.isPresent() && stamp.equals(row.get().failedAgainst())) {
            // Version-stamped (design 04 §3): the transform it failed against is still the one in force, so
            // nothing is run. The session is opened if none holds the claim — the last may have lapsed or
            // failed — or the stream would be waiting on nothing.
            return session(doc, learner, binding, input, scorecard, null, row.get().reason(), stamp,
                    row.get().failingRecords());
        }
        // Served as it is read (design 04 §3, *streams of any size*): nothing is run here. The caller
        // streams the fragment over the stream with [#stream], which judges each record as the fragment
        // writes it, and what the stream came to is decided there.
        final StageRun bound = new StageRun(doc, new Bound(binding), learner, producedBy(doc, binding), null, List.of(),
                List.of(), null, List.of());
        relearnOnMark(doc, learner, binding, input, scorecard);
        return bound;
    }

    /// A learner a person marked for relearning (A100) relearns on its next stream that brings enough
    /// records to judge a candidate on: the session is opened here and parked for the worker, while the
    /// stream itself is served by the incumbent, as it would be without the mark (A29). A learner behind a
    /// draft waits for the review; one that gave up is a person's to
    /// send back (A80); with learning off nothing opens; and
    /// where a session already holds the claim — one the mark or a short record opened — the stream
    /// simply flows.
    private void relearnOnMark(final ShapeshifterAiDoc doc,
                               final Learner learner,
                               final Binding binding,
                               final Input input,
                               final Scorecard scorecard) {
        final Optional<String> marked = states.relearnReason(learner);
        if (marked.isEmpty()
            || doc.getLearningMode() == LearningMode.DISABLED
            || states.draftAwaiting(learner).isPresent()
            || states.reasonGivenUp(learner).isPresent()
            || attempts.open(learner, clock.millis()).isPresent()) {
            return;
        }
        final Judged judged = judge(scorecard, binding.getPipeline(), input.data(), binding.getRecordBoundary());
        if (judged.records() < doc.getMinRecordsToLearn()) {
            return;
        }
        recording(doc, learner, input, RecordedAdvisor.awaiting(),
                waiting -> null,
                recorder -> relearnUnderClaim(doc, learner, binding, input, scorecard, judged, marked.get(),
                        recorder));
    }

    /// Serve a bound stream, as it is read (design 04 §3): the fragment runs over the whole stream
    /// once, each record of its output is judged by the gates that judge a record as it is produced, and
    /// each that clears the bar is handed on to the downstream. The first record short of the bar stops
    /// the run: the stream is deferred — a row stamped against the fragment in force, the learner marked,
    /// a relearning session opened where none holds the claim — and the deferral is thrown, so that the
    /// task ends deferred and what the downstream wrote of the records before it is discarded.
    ///
    /// A stream that clears in full is served: its row goes, the learner's rolling score is fed, a
    /// provisional binding that has now seen enough records is promoted, and the output is recorded against
    /// the bindings that produced it.
    ///
    /// @param doc        The document.
    /// @param binding    The binding that binds the stream, as [#decide] returned it.
    /// @param input      The stream.
    /// @param downstream Where the output goes.
    /// @return What the run came to, for the caller to say on the error stream (A20).
    public ServedStream stream(final ShapeshifterAiDoc doc,
                               final Learner learner,
                               final Binding binding,
                               final Input input,
                               final ContentHandler downstream) {
        final Scorecard bar = recordBar(doc);
        final RecordJudge judge = (index, record, parsed) -> bar.judge(new Attempted(TRANSFORM, false, null,
                new StepResult(record, List.of()), binding.getRecordBoundary()));
        final ServedStream served = fragmentRunner.serve(binding.getPipeline(), input.open(),
                binding.getRecordBoundary(),
                judge, downstream);
        if (served.shortfall().isPresent()) {
            final RecordShortfall short_ = served.shortfall().get();
            final String reason = "Record " + (short_.index() + 1) + " fell short of the bar: "
                                  + short_.verdict().feedback().stream()
                                          .map(StoredError::getMessage)
                                          .findFirst()
                                          .orElse("a gate failed");
            final StageRun deferred = session(doc, learner, binding, input,
                    new Scorecard(doc.getScorers(), scorers), Judged.shortOf(served, short_), reason, stamp(binding),
                            1);
            throw new DeferTaskException(((Deferred) deferred.decision()).reason());
        }
        deferrals.released(learner, input.id());
        if (binding.isProvisional()) {
            // Promoted the first time the learner brings enough records and they clear the floor (A14,
            // design 01 §6): what [#promote] does, less the output, which the downstream has.
            if (served.records() >= doc.getMinRecordsToLearn() && served.meanTotal() >= doc.getPromotionFloor()) {
                learnerBindings.replace(learner, doc.getUuid(), binding.copy()
                        .provisional(false)
                        .promotedTimeMs(clock.millis())
                        .score(served.meanTotal())
                        .build());
                resetKeepingDraft(doc, learner);
            }
        } else if (served.records() > 0) {
            // The rolling score goes on being kept for the Supervisor's list of what could be better
            // (A46), and triggers nothing here. A run that made nothing is said on the error stream by the
            // caller and scores nothing.
            states.scored(learner, doc.getUuid(), served.meanTotal(), served.records(),
                    doc.getMinRecordsToLearn());
        }
        outputs.emitted(input.id(), input.pipeline(), producedBy(doc, binding), List.of());
        return served;
    }

    /// The bar a record is held to (A55): the document's gates that judge a record. The scorers that
    /// measure a stream — compile, coverage, yield — judged the candidate when it was promoted and have
    /// nothing to say about one record.
    private Scorecard recordBar(final ShapeshifterAiDoc doc) {
        return new Scorecard(doc.getScorers().stream()
                .filter(setting -> RECORD_SCORERS.contains(setting.getType()))
                .toList(), scorers);
    }

    private static Produced producedBy(final ShapeshifterAiDoc doc, final Binding binding) {
        return new Produced(doc.getUuid(), binding.getUuid(), binding.getPipeline(), binding.getRecordBoundary(),
                binding.isProvisional(), binding.getScore() == null
                ? 0.0
                : binding.getScore(), binding.getSnapshot());
    }

    /// A stream under a binding waits for the binding to be learned again: the row is written against the
    /// transform in force, the learner is marked with why, and the session is opened and parked for the
    /// worker where none holds the claim (A69: one session, every waiting stream tried again when it
    /// promotes).
    ///
    /// @param judged The stream as the binding's fragment judged it, or null where the stamp said not to run
    ///               it.
    private StageRun session(final ShapeshifterAiDoc doc,
                             final Learner learner,
                             final Binding binding,
                             final Input input,
                             final Scorecard scorecard,
                             final Judged judged,
                             final String reason,
                             final String stamp,
                             final int failingRecords) {
        // A stream judged against a transform that a relearning has since replaced says nothing about the
        // one in force (A69): marking the learner on its word would relearn what has just been learned. It
        // waits against the stamp it failed, which is no longer in force, so its next try is scored
        // against the new transform rather than deferred again.
        final Optional<Binding> inForce = bindingFor(learner);
        if (inForce.isPresent() && !stamp.equals(stamp(inForce.get()))) {
            return deferred(doc, learner, input, stamp, failingRecords, reason,
                    reason + " (against a transform since replaced; it is tried again against the new one)");
        }
        // A draft awaiting review is the learner's answer already, and a person has it in front of them
        // (A25): the stream waits for their decision, as a marked learner's streams do. A session opened
        // here would relearn behind them, and its reset would forget the draft awaiting them.
        final Optional<String> awaiting = states.draftAwaiting(learner);
        if (awaiting.isPresent()) {
            return deferred(doc, learner, input, stamp, failingRecords, reason,
                    reason + " (Awaiting review: draft binding " + awaiting.get() + ")");
        }
        if (states.relearnReason(learner).isEmpty()) {
            states.markForRelearning(learner, doc.getUuid(), reason);
        }
        if (doc.getLearningMode() == LearningMode.DISABLED) {
            return deferred(doc, learner, input, stamp, failingRecords,
                    reason + "; Shapeshifter AI is disabled for this document, so the stream waits for a person");
        }
        return recording(doc, learner, input, RecordedAdvisor.awaiting(),
                waiting -> deferred(doc, learner, input, stamp, failingRecords, reason,
                        reason + " (" + waiting + ")"),
                recorder -> {
                    // Opened in the task only to be parked: the worker re-walks it as a relearning, since
                    // a binding serves the learner (resume reads that from the bindings as they stand). The stream
                    // was judged once, where it fell short; a stamped stream that found no session open
                    // is judged here, since the session's opening needs to say what fell short.
                    final Judged served = judged != null
                            ? judged
                            : judge(scorecard, binding.getPipeline(), input.data(), binding.getRecordBoundary());
                    return relearnUnderClaim(doc, learner, binding, input, scorecard, served, reason,
                            recorder);
                });
    }

    /// The stream waits: its row is written — or brought up to date — and nothing else is.
    private StageRun deferred(final ShapeshifterAiDoc doc,
                              final Learner learner,
                              final Input input,
                              final String failedAgainst,
                              final int failingRecords,
                              final String reason) {
        return deferred(doc, learner, input, failedAgainst, failingRecords, reason, reason);
    }

    /// The same, where the decision has more to say than the row keeps: the row holds why the stream
    /// waits, which is the same each time it is tried, and not what each try found the session doing.
    private StageRun deferred(final ShapeshifterAiDoc doc,
                              final Learner learner,
                              final Input input,
                              final String failedAgainst,
                              final int failingRecords,
                              final String reason,
                              final String said) {
        deferrals.deferred(learner, doc.getUuid(), input.id(), input.feed(), input.type(), failedAgainst, reason,
                failingRecords);
        return new StageRun(doc, new Deferred(said), learner, null, null, List.of(), List.of(), null, List.of());
    }

    /// What a deferral row is stamped with: the transform in force — the binding's snapshot (A61), or for a
    /// binding written before snapshots existed the fragment it names, until its first serve snapshots it.
    private static String stamp(final Binding binding) {
        return binding.getSnapshot() != null
                ? binding.getSnapshot()
                : binding.getPipeline().getUuid();
    }

    /// A promotion wakes what waits on its learner (design 04 §5.3, design 06 §4.3): the deferred tasks of
    /// every filter on the learner's pipeline go back to the queue ahead of the retry delay. The rows
    /// stay until each stream is served.
    private void wake(final Learner learner) {
        wake.wake(learner.pipelineUuid());
    }

    /// Wake the pipelines whose streams wait against a transform no longer in force: what the learning job
    /// does on every pass (design 04 §5.3, A130). A promotion wakes its learner's pipeline, but a stream
    /// judged against the old transform while the promotion was being made writes its row after that wake,
    /// and its task is not deferred until after the row is written, so nothing the promotion did can reach
    /// it. Such a row is not waiting on anything: the next try scores it against the transform now in
    /// force. A task still running when the pass comes is not woken, and the next pass tries again; a
    /// learner behind a draft waits for the review, as its streams do.
    ///
    /// Read by learner and transform, not by row: as many as there are learners waiting, however many
    /// streams wait on each.
    ///
    /// @return How many tasks were woken.
    public int wakeSuperseded() {
        final Set<String> pipelines = new LinkedHashSet<>();
        for (final Deferrals.Stamp waiting : deferrals.stampsWaiting()) {
            final boolean replaced = bindingFor(waiting.learner())
                    .filter(binding -> !binding.isDraft())
                    .map(binding -> !waiting.failedAgainst().equals(stamp(binding)))
                    .orElse(false);
            if (replaced) {
                pipelines.add(waiting.learner().pipelineUuid());
            }
        }
        return pipelines.stream().mapToInt(wake::wake).sum();
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
    private StageRun recording(final ShapeshifterAiDoc doc,
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
    private StageRun recording(final ShapeshifterAiDoc doc,
                               final Learner learner,
                               final Input input,
                               final Advisor advisor,
                               final Function<String, StageRun> waiting,
                               final Function<Recorder, StageRun> attempt) {
        // What the session learns must have somewhere to go, and that is found out now rather than after
        // the model has been paid: a folder that cannot be made would close every session in error once it
        // had learned, and the learner's next stream would open another.
        final Optional<String> unplaceable = unplaceable(learner);
        if (unplaceable.isPresent()) {
            return waiting.apply(unplaceable.get());
        }
        final Optional<Long> opened = attempts.opened(new Attempts.Attempt(learner, doc.getUuid(),
                input.feed(), input.type(), input.id(), node, doc.getPromotionMode(),
                claimUntil()), clock.millis());
        if (opened.isEmpty()) {
            // Another attempt holds the learner's claim (A45): it is still learning, parked or not, and
            // this stream takes what that attempt comes to rather than learning the same thing beside it.
            return waiting.apply("Another node is learning for this pipeline; this stream waits for what it "
                                 + "learns");
        }
        return carrying(doc, learner, input, opened.get(), 0L, Grant.NONE, false, Map.of(), advisor, false, waiting,
                attempt);
    }

    /**
     * One attempt, carried to whatever it comes to and recorded as it goes (A28): whether this node opened
     * it or picked it up where another stopped.
     */
    private StageRun carrying(final ShapeshifterAiDoc doc,
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
            // to, rather than vanishing. Nothing here may throw over the failure that brought us.
            record(() -> attempts.closed(attemptId, AttemptStatus.ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), null, null,
                    Math.max(0L, advisor.tokensUsed() - before)));
            throw e;
        }
        if (run.decision() instanceof final Parked parked) {
            // Parked for a person (A65): the attempt keeps its claim with a lease that does not lapse, and
            // the worker leaves it alone. Not closed — a person's answer carries it on.
            record(() -> attempts.parked(attemptId, parked.reason(), Math.max(0L, advisor.tokensUsed() - before)));
            return run;
        }
        // The run is done and its binding is written: the record of it must not be what fails the stream.
        record(() -> attempts.closed(attemptId, statusOf(run.decision()), reason(run.decision()),
                bindingOf(run.decision()), scoreOf(run.decision()), Math.max(0L, advisor.tokensUsed() - before)));
        return run;
    }

    /**
     * Bookkeeping, which may not fail what it is a record of: an attempt is written after the fragment, the
     * binding and the output are, so a database that refuses the row must not undo a stream that succeeded.
     */
    private static void record(final Runnable write) {
        try {
            write.run();
        } catch (final RuntimeException e) {
            LOGGER.error(() -> LogUtil.message("The attempt could not be recorded: {}", e.getMessage()), e);
        }
    }

    /**
     * What an attempt writes as it goes: the advisor it is asking, so the tokens it charges are counted
     * once, and a turn for every exchange as it is answered and again as it is judged (A28).
     */
    private final class Recorder {

        private final ShapeshifterAiDoc doc;
        private final Advisor advisor;
        private final long attemptId;
        private final long alreadySpent;
        private final Map<Integer, String> answeredBefore;
        private final String answeredBy;
        /// Whether this is the worker carrying a session (A64): a session that cannot finish parks for a
        /// person (A65) rather than giving up, and runs its held stream in full before it ends.
        private boolean worker;
        /// What a person granted the attempt beyond the document's budgets (A77).
        private final Grant grant;
        /// The advisor's count and the clock as this carrying began: a round that follows another in the
        /// same carrying is under the same budget, counted from here (A5).
        private final long tokensBefore;
        private final long startedMs;
        /// Whether a person sent the attempt to the exhaustion step (A65).
        private final boolean exhaustNext;
        /// The highest turn number recorded so far, and the number the current walk's turns count on from.
        private int highest;
        private int base;

        private Recorder(final ShapeshifterAiDoc doc,
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

        private boolean worker() {
            return worker;
        }

        private Advisor advisor() {
            return advisor;
        }

        private long attemptId() {
            return attemptId;
        }

        /// What this attempt had spent before it was last parked (A5): its budget is the whole of it.
        private long alreadySpent() {
            return alreadySpent;
        }

        /// What this attempt has spent so far: before it was last parked, and in this carrying up to now.
        /// A round that follows another is under the one budget, so it starts from here and not from
        /// nothing.
        private long spentSoFar() {
            return alreadySpent + Math.max(0L, advisor.tokensUsed() - tokensBefore);
        }

        /// The claim pushed out, before every question: an attempt held up by one slow model call would
        /// otherwise run past its expiry and have its learner's work taken up by another node (A45).
        private void heartbeat() {
            record(() -> attempts.heartbeat(attemptId, claimUntil()));
        }

        private void turn(final int number, final Exchange exchange) {
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
        private int numbered(final int number) {
            highest = Math.max(highest, base + number);
            return base + number;
        }

        /// A walk begins: its turns count on from the highest the attempt has.
        private void walkBegins() {
            base = highest;
        }
    }

    /**
     * Where an attempt stands, from what it decided (A28).
     */
    private static AttemptStatus statusOf(final Decision decision) {
        return switch (decision) {
            case Promoted ignored -> AttemptStatus.PROMOTED;
            case Rebound ignored -> AttemptStatus.PROMOTED;
            case Provisional ignored -> AttemptStatus.PROVISIONAL;
            case Drafted ignored -> AttemptStatus.AWAITING_REVIEW;
            case Parked ignored -> AttemptStatus.PARKED;
            default -> AttemptStatus.ABANDONED;
        };
    }

    /**
     * What an attempt came to, in the words the decision itself uses — and only those: a decision's
     * diagnostics quote the stream's own text, which may not be stored until it is redacted (A17, A38),
     * which is the same reason a turn records what it asked rather than the prompt it sent.
     */
    private static String reason(final Decision decision) {
        return switch (decision) {
            case Promoted promoted -> "Promoted " + promoted.score();
            case Rebound rebound -> "Rebound " + rebound.score();
            case Provisional provisional -> "Provisional " + provisional.score() + " on "
                                            + provisional.records() + " of " + provisional.required()
                                            + " records";
            case Drafted drafted -> "Awaiting review, scored " + drafted.score();
            case Kept kept -> "Kept the incumbent: " + kept.reason();
            case GivenUp givenUp -> givenUp.reason();
            case Sentinel sentinel -> sentinel.reason();
            default -> decision.getClass().getSimpleName();
        };
    }

    private static String bindingOf(final Decision decision) {
        return switch (decision) {
            case Promoted promoted -> promoted.binding().getUuid();
            case Provisional provisional -> provisional.binding().getUuid();
            case Drafted drafted -> drafted.binding().getUuid();
            case Rebound rebound -> rebound.binding().getUuid();
            default -> null;
        };
    }

    private static Double scoreOf(final Decision decision) {
        return switch (decision) {
            case Promoted promoted -> promoted.score();
            case Provisional provisional -> provisional.score();
            case Drafted drafted -> drafted.score();
            case Rebound rebound -> rebound.score();
            default -> null;
        };
    }

    /**
     * Who answered a turn: the model this document names, since nothing else answers one yet — a person
     * answering instead is A28's, in a later slice, and writes their own name here.
     */
    private static String answeredBy(final ShapeshifterAiDoc doc) {
        // A DocRef may carry no name — a document stored as type and uuid alone — and the column may not be
        // null, so the uuid stands in: a record that cannot be written is worse than one that names a uuid.
        if (doc.getModel() == null) {
            return "model";
        }
        return doc.getModel().getName() == null
                ? doc.getModel().getUuid()
                : doc.getModel().getName();
    }

    private StageRun learnAndBind(final ShapeshifterAiDoc doc,
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
                        abandoned.reason(), abandoned.diagnostics(), List.of(), outcome.transcript());
                wake(learner);
                return givenUp;
            }
            if (recorder.worker()) {
                // A session that cannot finish parks for a person (A65) rather than giving up: its
                // streams go on waiting, and nothing is baked because money or candidates ran out.
                return parked(doc, learner, abandoned.reason(), outcome.transcript());
            }
            return givenUp(doc, learner, abandoned.reason(), abandoned.reason(),
                    abandoned.diagnostics(), List.of(), outcome.transcript());
        }
        final Learned learned = (Learned) outcome;

        // The candidate over the whole stream: the held-out judgement of A14/A15 where there are enough
        // records for one, and the floor a provisional binding must clear where there are not.
        final Judged judged = asLearned(rerun(learned.chain(), input.data(), boundary(learned)), scorecard);
        if (!judged.clearsFloor(doc)) {
            // Woken, as for the flow's give-up: the streams waiting on the learner error with the reason
            // now, rather than after the retry delay.
            final StageRun givenUp = givenUp(doc, learner, "Below the promotion floor",
                    "Candidate scored " + judged.score() + " against a floor of " + doc.getPromotionFloor(),
                    judged.verdicts().stream().flatMap(verdict -> verdict.feedback().stream()).toList(),
                    judged.verdicts(), learned.transcript());
            wake(learner);
            return givenUp;
        }
        final DocRef fragment = write(learner, learned);
        // What goes in the corpus (A56): the sample the model was shown, every record of it, since a
        // first learning learned for all of them.
        final List<Accepted> entries = Corpus.entries(learned.chain(), boundary(learned),
                learningPrefix(input.data(), doc), null, learned.targets(), judged.score(), recorder.attemptId(),
                clock.millis(), doc.getRegressionCap());
        return bind(doc, learner, input, fragment, judged, entries, learned.transcript(),
                boundary(learned));
    }

    private StageRun relearnUnderClaim(final ShapeshifterAiDoc doc,
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
                return parked(doc, learner, abandoned.reason(), outcome.transcript());
            }
            return emit(doc, new Kept(incumbent, "No candidate: " + abandoned.reason()), learner, input, incumbent,
                    served, outcome.transcript());
        }
        final Learned learned = (Learned) outcome;
        final Judged candidate = asLearned(rerun(learned.chain(), input.data(), boundary(learned)), scorecard);
        if (!candidate.clearsFloor(doc)) {
            return emit(doc, new Kept(incumbent, "Candidate scored " + candidate.score() + " against a floor of "
                                                 + doc.getPromotionFloor()), learner, input, incumbent, served,
                    learned.transcript());
        }
        if (candidate.score() < served.score()) {
            return emit(doc, new Kept(incumbent, "Candidate scored " + candidate.score() + " against the incumbent's "
                                                 + served.score() + " on this stream"), learner, input, incumbent,
                    served, learned.transcript());
        }
        // Held to the corpus, to no worse (A66): every record the binding was accepted on, run through the
        // candidate's transform, must clear the bar, and none that translated may become an Unknown.
        // Output may otherwise change — the output each was accepted with is kept so that a person can
        // see what changed, not so that the gate can refuse it.
        final List<Accepted> corpus = regressionSet.accepted(incumbent.getUuid());
        for (int i = 0; i < corpus.size(); i++) {
            final Accepted accepted = corpus.get(i);
            final List<LearnedStep> transforms = Corpus.transforms(learned.chain());
            final Judged onRecord = asLearned(rerun(transforms, accepted.record(), boundary(learned)), scorecard);
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
            entries = Corpus.entries(learned.chain(), boundary(learned), learningPrefix(input.data(), doc), null,
                    learned.targets(), candidate.score(), recorder.attemptId(), clock.millis(),
                    doc.getRegressionCap());
        } else {
            entries = Corpus.entries(learned.chain(), boundary(learned), input.data(), fellShort,
                    learned.targets(), candidate.score(), recorder.attemptId(), clock.millis(),
                    doc.getRegressionCap());
        }
        // In place (A58), unless a person is to review it first: a draft is its own fragment until Approve
        // rebinds the incumbent to it.
        final DocRef fragment = doc.getPromotionMode() == PromotionMode.REVIEW
                ? write(learner, learned)
                : rewrite(learner, incumbent, learned);
        if (doc.getPromotionMode() == PromotionMode.REVIEW) {
            // Behind the incumbent, which keeps matching first; Approve rebinds the incumbent to this
            // fragment and carries the history over.
            final String draftUuid = UUID.randomUUID().toString();
            final Binding draft = Binding.builder()
                    .uuid(draftUuid)
                    .pipeline(fragment)
                    .draft(true)
                    .score(candidate.score())
                    .recordBoundary(boundary(learned))
                    .snapshot(snapshot(doc, draftUuid, fragment))
                    .build();
            learnerBindings.append(learner, doc.getUuid(), draft);
            regressionSet.accept(doc.getUuid(), draft.getUuid(), input.feed(), input.type(), entries,
                    doc.getRegressionCap());
            states.awaitReview(learner, doc.getUuid(), draft.getUuid());
            return emit(doc, new Drafted(draft, candidate.score()), learner, input,
                    incumbent, served, learned.transcript());
        }
        final Binding rebound = incumbent.copy()
                .pipeline(fragment)
                .snapshot(snapshot(doc, incumbent.getUuid(), fragment))
                .promotedTimeMs(clock.millis())
                .score(candidate.score())
                .recordBoundary(boundary(learned))
                .build();
        regressionSet.accept(doc.getUuid(), rebound.getUuid(), input.feed(), input.type(), entries,
                    doc.getRegressionCap());
        learnerBindings.replace(learner, doc.getUuid(), rebound);
        // Spent again now the new transform is in force: a stream that fell short of the incumbent while
        // the session ran marked the learner after it was spent above, and a mark left standing would
        // relearn what has just been learned. A stream that falls short from here on was judged
        // against a transform since replaced, and does not mark it (see [#session]).
        states.reset(learner);
        wake(learner);
        return emit(doc, new Rebound(incumbent, rebound, candidate.score()), learner,
                input, incumbent, served, learned.transcript());
    }

    /**
     * Bind a fragment that cleared the floor: a new binding for the learner, promoted if the stream had
     * enough records for a held-out judgement and provisional otherwise; the learner's pipeline woken,
     * which is what puts its waiting streams through; and the stream's output emitted. In review mode (A25)
     * the binding is a draft instead, and the learner's streams wait on it, this one with them.
     */
    private StageRun bind(final ShapeshifterAiDoc doc,
                          final Learner learner,
                          final Input input,
                          final DocRef fragment,
                          final Judged judged,
                          final List<Accepted> entries,
                          final List<Exchange> transcript,
                          final RecordBoundary boundary) {
        final boolean provisional = judged.records() < doc.getMinRecordsToLearn();
        final boolean draft = doc.getPromotionMode() == PromotionMode.REVIEW;
        final String bindingUuid = UUID.randomUUID().toString();
        final Binding binding = Binding.builder()
                .uuid(bindingUuid)
                .pipeline(fragment)
                .provisional(provisional)
                .draft(draft)
                .promotedTimeMs(provisional || draft
                        ? null
                        : clock.millis())
                .score(judged.score())
                .recordBoundary(boundary)
                // The configuration it serves with, kept (A61).
                .snapshot(snapshot(doc, bindingUuid, fragment))
                .build();
        learnerBindings.append(learner, doc.getUuid(), binding);
        // What was learned for goes in now, under a draft or a provisional binding too (A56): the records are
        // what the session put to the model whether or not the stream brought enough of them or a person
        // has yet approved what it made of them. Reject and retraction take them out again.
        regressionSet.accept(doc.getUuid(), binding.getUuid(), input.feed(), input.type(), entries,
                doc.getRegressionCap());
        states.reset(learner);
        if (draft) {
            states.awaitReview(learner, doc.getUuid(), binding.getUuid());
            return new StageRun(doc, new Drafted(binding, judged.score()), learner, null,
                    null, judged.verdicts(), transcript, null, List.of());
        }
        wake(learner);
        final Decision decision = provisional
                ? new Provisional(binding, judged.score(), judged.records(), doc.getMinRecordsToLearn())
                : new Promoted(binding, judged.score());
        return emit(doc, decision, learner, input, binding, judged, transcript);
    }

    private Outcome learn(final ShapeshifterAiDoc doc,
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
        final Sample sample = Sample.of(learningPrefix(input.data(), doc), input.feed(), input.type());
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

    /**
     * Approve a draft (A25): the promotion. Where a binding already serves the learner — the draft came of
     * relearning — that binding is rebound to the draft's fragment and keeps its {@code uuid} and its
     * history; otherwise the draft itself goes live. Either way the learner's pipeline is woken, which puts
     * what waited for the review through.
     */
    public void approve(final ShapeshifterAiDoc doc, final String bindingUuid) {
        final LearnerBindings.Held held = draft(doc, bindingUuid);
        final Binding draft = held.binding();
        final Learner learner = awaitingReview(doc, held);
        final Optional<Binding> incumbent = incumbentOf(learnerBindings.forLearner(learner));
        if (incumbent.isPresent()) {
            learnerBindings.replace(learner, doc.getUuid(), incumbent.get().copy()
                    .pipeline(draft.getPipeline())
                    .snapshot(draft.getSnapshot())
                    .provisional(false)
                    .promotedTimeMs(clock.millis())
                    .score(draft.getScore())
                    .recordBoundary(draft.getRecordBoundary())
                    .build());
            learnerBindings.remove(learner, draft.getUuid());
            regressionSet.move(draft.getUuid(), incumbent.get().getUuid(), doc.getRegressionCap());
            snapshots.move(draft.getUuid(), incumbent.get().getUuid());
        } else {
            learnerBindings.replace(learner, doc.getUuid(), draft.copy()
                    .draft(false)
                    .provisional(false)
                    .promotedTimeMs(clock.millis())
                    .build());
        }
        states.reset(learner);
        // The attempt that wrote the draft is no longer awaiting review (A28).
        record(() -> attempts.decided(doc.getUuid(), bindingUuid, AttemptStatus.PROMOTED, "Approved"));
        // And what waited for the review is woken (design 04 §5.3), which is all it needs: its tasks were
        // deferred, not errored.
        wake(learner);
    }

    /// Accept a provisional binding now, rather than waiting for the records that would promote it
    /// (design 01 §6, §11.6).
    ///
    /// A binding is bound provisionally when its candidate cleared the floor on a stream that did not
    /// bring enough records for a held-out judgement (A14): it serves, marked as such, until enough
    /// arrive to promote or retract it. For a feed that ships a handful of records a day that can be a
    /// long wait, and a person who has read what it is producing may not want to wait.
    ///
    /// What it is *not* is a way round the gate. The binding already cleared the promotion floor — that is
    /// what made it bindable at all — and what is being skipped is the wait for more records to judge
    /// it on, not the judgement. Recorded against the attempt as a person's doing, so that a binding
    /// promoted this way is not mistaken for one the gate promoted.
    ///
    /// @param by Who accepted it.
    public void accept(final ShapeshifterAiDoc doc, final String bindingUuid, final String by) {
        final LearnerBindings.Held held = held(doc, bindingUuid);
        final Learner learner = held.learner();
        final Binding binding = held.binding();
        if (!binding.isProvisional()) {
            // Nothing to accept: it is already serving on a judgement, or it is a draft, or it binds
            // nothing. Said rather than quietly doing nothing.
            throw new IllegalArgumentException("Binding " + bindingUuid + " is not a provisional binding");
        }
        final String said = "Accepted by " + by + " on the score it was bound at, without waiting for "
                            + "records to judge it on";
        // What it is *not* is a binding with a record behind it. A provisional binding deliberately puts
        // nothing in the regression set — see `bind`, which records only for a promotion or a draft —
        // and the stream it was bound from is long gone by the time anybody accepts it: there is no
        // text to record and no score measured per record to record it with. So a binding accepted this
        // way has no accepted records, which means `improve` refuses it and asks for a relearning
        // instead, and A18's "no worse on any record it was accepted on" has nothing to hold a later
        // relearning to. Both are the honest consequence of promoting without evidence, and both are
        // said here rather than found later. Design 01 §15: giving a provisional binding its record at
        // bind time would fix it and is a change to the gate, not to this button.
        learnerBindings.replace(learner, doc.getUuid(), binding.copy()
                .provisional(false)
                .promotedTimeMs(clock.millis())
                .build());
        // The rolling score starts from here, as it does for a binding the gate promotes: what it scored
        // while provisional was scored on too few records to mean anything.
        resetKeepingDraft(doc, learner);
        record(() -> attempts.settled(doc.getUuid(), bindingUuid, AttemptStatus.PROMOTED, said));
    }

    /// Take back a binding that is serving, by hand (A28, design 01 §11.6).
    ///
    /// The automatic retraction of §6 is what a provisional binding gets when it fails the gate; this is the
    /// same act for a reason no gate can see — a person has read what the binding is producing and decided
    /// it should not be. The binding goes and the learner knows nothing again. What the binding produced was
    /// produced by a binding that has been withdrawn, and is counted rather than asked for (A129): a
    /// person who wants it as it would be now makes a reprocess filter on the pipeline.
    ///
    /// Not a removal, which is what it says: a row taken out of the table. Removing a learned binding leaves the
    /// learner thinking it is bound and leaves the streams it produced standing as though they were right.
    /// Retracting says they were not.
    ///
    /// Not `reject` either, which is A25's decision about a draft that never served anything, and which
    /// gives the learner up so it does not learn again. A retracted learner *does* learn again: its next
    /// stream starts afresh, which is the point — the binding was wrong, the feed is not.
    ///
    /// @param reason Why, in the person's words. It goes on the attempt, so that a person reading it a week
    ///               later knows what took the binding back.
    /// @param by     Who retracted it, since this overrides what the gate decided.
    /// @return How many streams the binding produced output for: what a person would reprocess, the one
    /// consequence a person retracting a binding cannot see for themselves and the one worth telling them.
    public int retract(final ShapeshifterAiDoc doc, final String bindingUuid, final String reason, final String by) {
        final LearnerBindings.Held held = held(doc, bindingUuid);
        final Learner learner = held.learner();
        final Binding binding = held.binding();
        if (binding.isDraft()) {
            // A draft has served nothing, so there is nothing to take back. Deciding it is Approve or
            // Reject (A25), and saying so beats retracting something that was never bound.
            throw new IllegalArgumentException("Binding " + bindingUuid + " is a draft awaiting review: approve "
                                               + "or reject it rather than retracting it");
        }
        final String said = "Retracted by " + by + ": " + reason;
        learnerBindings.remove(learner, binding.getUuid());
        regressionSet.discard(binding.getUuid());
        snapshots.discard(binding.getUuid());
        // The learner knows nothing again rather than giving up: the binding was wrong, not the feed, and the
        // next stream learns afresh — unless a draft awaits review, which is still the learner's candidate
        // and a person's to decide.
        resetKeepingDraft(doc, learner);
        // By the fragment as well as the binding: a binding keeps its uuid when it is rebound (§7.3 rule 3), and
        // what an earlier generation produced was produced correctly by what was bound then.
        final List<Replayable> produced = outputs.boundBy(binding.getUuid(), binding.getPipeline() == null
                ? null
                : binding.getPipeline().getUuid());
        record(() -> attempts.settled(doc.getUuid(), bindingUuid, AttemptStatus.RETRACTED, said));
        return produced.size();
    }

    /**
     * Reject a draft (A25): the binding goes, with its records; its documents stay, as every document does.
     * The learner gives up with the reason, so the model is not asked the same question again until an
     * operator says otherwise: a learner with no binding sentinels its streams, and a relearned learner's
     * incumbent carries on serving without being scored against or relearned.
     *
     */
    public void reject(final ShapeshifterAiDoc doc, final String bindingUuid, final String reason) {
        final LearnerBindings.Held held = draft(doc, bindingUuid);
        final Binding draft = held.binding();
        final Learner learner = awaitingReview(doc, held);
        learnerBindings.remove(learner, draft.getUuid());
        regressionSet.discard(draft.getUuid());
        snapshots.discard(draft.getUuid());
        states.reset(learner);
        states.giveUp(learner, doc.getUuid(), "Rejected: " + reason);
        record(() -> attempts.decided(doc.getUuid(), bindingUuid, AttemptStatus.REJECTED, "Rejected: " + reason));
        // The streams waiting behind the draft (design 04 §3) are woken to error, as a given-up learner's
        // always are (A52, A80): left waiting, they would wait for a review that is no longer coming.
        wake(learner);
    }

    /// The learner's state starts afresh — not given up, not marked, its rolling score forgotten — but a
    /// draft awaiting review still awaits it: it is a person's to decide (A25), and nothing that calls this
    /// decided it. Forgotten, it would be a draft nothing awaits, serving the learner's streams a wait that
    /// approve and reject both refuse to end.
    private void resetKeepingDraft(final ShapeshifterAiDoc doc, final Learner learner) {
        final Optional<String> draft = states.draftAwaiting(learner);
        states.reset(learner);
        draft.ifPresent(bindingUuid -> states.awaitReview(learner, doc.getUuid(), bindingUuid));
    }

    /// A draft of this document, with whose it is.
    private LearnerBindings.Held draft(final ShapeshifterAiDoc doc, final String bindingUuid) {
        return learnerBindings.byUuid(bindingUuid)
                .filter(row -> row.docUuid().equals(doc.getUuid()) && row.binding().isDraft())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Binding " + bindingUuid + " is not a draft on document " + doc.getName()));
    }

    /// The learner whose review the draft is: the one whose state says it awaits that draft. A draft no
    /// learner awaits is one nothing would release on approval and nothing would give up on rejection.
    private Learner awaitingReview(final ShapeshifterAiDoc doc, final LearnerBindings.Held draft) {
        if (!states.draftAwaiting(draft.learner()).filter(draft.binding().getUuid()::equals).isPresent()) {
            throw new IllegalStateException("Nothing awaits review under draft binding " + draft.binding().getUuid()
                                            + " on document " + doc.getName());
        }
        return draft.learner();
    }

    /// The binding a draft would replace: the one serving the learner.
    private static Optional<Binding> incumbentOf(final List<Binding> table) {
        return table.stream()
                .filter(binding -> !binding.isDraft())
                .findFirst();
    }

    /// A binding of this document, with whose it is: what an action that begins from a binding rather than a
    /// stream acts on (design 06 phase J slice 2b). A binding of another document is not this one's, whatever
    /// its uuid, since the document is what decided the person may act on it.
    private LearnerBindings.Held held(final ShapeshifterAiDoc doc, final String bindingUuid) {
        return learnerBindings.byUuid(bindingUuid)
                .filter(row -> row.docUuid().equals(doc.getUuid()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Binding " + bindingUuid + " is not a binding of this document"));
    }

    /// What serves a learner (A124): its binding, or failing that the draft awaiting review.
    private Optional<Binding> bindingFor(final Learner learner) {
        final List<Binding> bindings = learnerBindings.forLearner(learner);
        return bindings.stream()
                .filter(binding -> !binding.isDraft())
                .findFirst()
                .or(() -> bindings.stream().filter(Binding::isDraft).findFirst());
    }

    /// A snapshot of a fragment as it stands, kept against a binding (A61, A70): the version the binding serves
    /// with, stamps its deferrals with and records its outputs under.
    private String snapshot(final ShapeshifterAiDoc doc, final String bindingUuid, final DocRef fragment) {
        final FragmentCodes.Codes read = codes.read(fragment);
        final String id = UUID.randomUUID().toString();
        snapshots.keep(new Snapshots.Snapshot(id, doc.getUuid(), bindingUuid, fragment.getUuid(), read.structure(),
                read.code(), read.versions(), clock.millis()));
        return id;
    }

    /// The binding as it serves now (A72): where its fragment has been edited by hand since its snapshot
    /// was taken — the documents' versions differ — or it has no snapshot at all, having been written
    /// before snapshots existed, a snapshot is taken and the binding points at it. A promotion by hand.
    private Binding freshened(final ShapeshifterAiDoc doc, final Learner learner, final Binding binding) {
        if (binding.getPipeline() == null) {
            return binding;
        }
        try {
            if (binding.getSnapshot() != null) {
                final Optional<Snapshots.Snapshot> kept = snapshots.byId(binding.getSnapshot());
                if (kept.isPresent() && kept.get().versions().equals(codes.versions(binding.getPipeline()))) {
                    return binding;
                }
            }
            final Binding fresh = binding.copy().snapshot(snapshot(doc, binding.getUuid(), binding.getPipeline()))
                    .build();
            learnerBindings.replace(learner, doc.getUuid(), fresh);
            return fresh;
        } catch (final RuntimeException e) {
            // A fragment whose documents cannot be read — deleted by hand — cannot be snapshotted, and is
            // not what this is about: the binding serves as it stands and the serve says what it finds.
            LOGGER.debug(() -> LogUtil.message("Binding {} could not be snapshotted: {}", binding.getUuid(),
                    e.getMessage()), e);
            return binding;
        }
    }

    /// A session improves a binding's fragment in place (A58): the same pipeline, the same documents, the
    /// text replaced. What ran before is the snapshot's to keep. A document for an element the fragment did
    /// not have goes in the learner's folder, named for the fragment (A113).
    private DocRef rewrite(final Learner learner,
                           final Binding incumbent,
                           final Learned learned) {
        return writer.rewrite(incumbent.getPipeline(), places.of(learner).folder(),
                incumbent.getPipeline().getName(), learned.chain(), boundary(learned));
    }

    /// The most rounds a session runs before it parks regardless: the budgets are what bound a session
    /// (A65), and this is the guard behind them for a document whose budgets are generous.
    private static final int MOST_ROUNDS = 10;

    /// How many records short of the bar a full pass keeps as evidence: enough kinds to learn for, few
    /// enough to put to a model.
    private static final int EVIDENCE = 5;

    /// The session carried to its end (A64, A50): the held stream is the acceptance test. A promotion is
    /// followed by a full pass of the stream through the promoted transform, judging every record; where
    /// any falls short, the records that did — as the parser cut them, with what the transform made of
    /// them — open the next question, and the transform is learned again in place. The session ends when
    /// the stream clears, or parks (A65) when its budgets or its rounds are spent.
    private StageRun settle(final ShapeshifterAiDoc doc,
                            final Learner learner,
                            final Input input,
                            final Scorecard scorecard,
                            final Recorder recorder,
                            final StageRun first) {
        StageRun run = first;
        Shortfalls pass = null;
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
                case Provisional provisional -> binding = provisional.binding();
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
            final Judged served = Judged.shortOf(pass);
            final String reason = pass.shortCount() + " of " + pass.judged()
                                  + " record(s) fell short of the bar on the held stream, round " + round
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
    private Shortfalls fullPass(final ShapeshifterAiDoc doc, final Binding binding, final Input input) {
        final Scorecard bar = recordBar(doc);
        final Shortfalls shortfalls = new Shortfalls((index, record, parsed) -> bar.judge(
                new Attempted(TRANSFORM, false, null, new StepResult(record, List.of()), binding.getRecordBoundary())),
                EVIDENCE);
        fragmentRunner.serve(binding.getPipeline(), input.open(), binding.getRecordBoundary(), shortfalls, null);
        return shortfalls;
    }

    /// The session parks for a person (A65).
    private StageRun parked(final ShapeshifterAiDoc doc,
                            final Learner learner,
                            final String reason,
                            final List<Exchange> transcript) {
        return new StageRun(doc, new Parked(reason), learner, null, null, List.of(), transcript, null, List.of());
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

    /// A new fragment, in the learner's folder under its name (A113), or the first free number after the
    /// name where the folder already has one (A132).
    private DocRef write(final Learner learner, final Learned learned) {
        final Places.Place place = places.of(learner);
        return writer.write(place.folder(), place.name(), learned.chain(), boundary(learned));
    }

    /**
     * The stream's output with the bindings that produced it (design 01 §7.3 rule 3).
     */
    private StageRun emit(final ShapeshifterAiDoc doc,
                          final Decision decision,
                          final Learner learner,
                          final Input input,
                          final Binding binding,
                          final Judged judged,
                          final List<Exchange> transcript) {
        final Produced produced = new Produced(doc.getUuid(), binding.getUuid(), binding.getPipeline(),
                binding.getRecordBoundary(), binding.isProvisional(), judged.score(), binding.getSnapshot());
        outputs.emitted(input.id(), input.pipeline(), produced, judged.spans());
        return new StageRun(doc, decision, learner, produced, judged.output(), judged.verdicts(), transcript,
                judged.events(), judged.diagnostics());
    }

    /// The stream is not processed here: an error naming the reason, and nothing else written (A131).
    private StageRun refused(final ShapeshifterAiDoc doc, final Learner learner, final String reason) {
        return new StageRun(doc, new Sentinel(reason), learner, null, null, List.of(), List.of(), null,
                List.of());
    }

    /**
     * No candidate passed: the learner gives up, and this stream is sentinelled like every later one will
     * be until a person sends it back.
     */
    private StageRun givenUp(final ShapeshifterAiDoc doc,
                             final Learner learner,
                             final String decision,
                             final String reason,
                             final List<StoredError> diagnostics,
                             final List<Verdict> verdicts,
                             final List<Exchange> transcript) {
        states.giveUp(learner, doc.getUuid(), reason);
        return new StageRun(doc, new GivenUp(decision, diagnostics), learner, null, null, verdicts, transcript,
                null, List.of());
    }

    /**
     * The first {@code 1 - heldOutFraction} of the non-blank lines: what the model learns from. The
     * whole stream is what it is judged on. Input that is already markup is not cut — a prefix of a
     * document is not a document, and which elements are its records is the split question's to settle
     * (A35) — so the model learns from the whole of it, within the document's sample size limit.
     */
    static String learningPrefix(final String data, final ShapeshifterAiDoc policy) {
        final int limit = policy.getSampleSizeLimit();
        // Markup that is one document is learned whole; markup that is not — a fragment per line, no root —
        // is lines, held out and cut like any text.
        if (RecordSkeleton.isMarkup(data) && Records.parsed(data) >= 0) {
            return data.length() <= limit
                    ? data
                    : wholeChildren(data, limit);
        }
        final List<String> lines = data.lines().filter(line -> !line.isBlank()).toList();
        if (isJsonDocument(data)) {
            // A prefix of a JSON document is not a document either: cut by lines it would not parse, and the
            // split question would abandon the attempt on a sample the parser cannot read. Over the limit it
            // is cut at its widest array's items and closed again, as wholeChildren closes an XML root.
            return data.length() <= limit
                    ? data
                    : wholeItems(data, limit);
        }
        if (lines.isEmpty()) {
            // A blank stream has nothing to learn from; the compile gate and coverage will say so.
            return "";
        }
        int keep = (int) Math.ceil(lines.size() * (1.0 - policy.getHeldOutFraction()));
        keep = Math.max(1, Math.min(keep, lines.size()));
        return wholeLines(lines, keep, limit);
    }

    /**
     * The first {@code keep} lines, as many of them as the size limit allows; one line is always shown.
     */
    private static String wholeLines(final List<String> lines, final int keep, final int limit) {
        int length = lines.get(0).length();
        int within = 1;
        while (within < keep && length + 1 + lines.get(within).length() <= limit) {
            length += 1 + lines.get(within).length();
            within++;
        }
        return String.join("\n", lines.subList(0, within)) + "\n";
    }

    /**
     * How many records a stream brought, the evidence a judgement rests on (A14), counted on the input so
     * that a variant that drops records is judged, not excused. Where a boundary was settled (A35) it is
     * counted by that — the elements of the name in XML input, the array's items in the XML the parser
     * makes of JSON; otherwise as {@link #recordsBrought(String)} counts the input alone.
     */
    static int recordsBrought(final Attempted first) {
        final RecordBoundary boundary = first.boundary();
        if (boundary != null) {
            // The parser's output is the document a JSON boundary applies to; XML input is its own.
            final String document = first.parser()
                    ? first.result().output()
                    : first.input();
            final int by = document == null
                    ? -1
                    : Records.parsed(document, boundary);
            // A parser whose output holds no records at all did not cut a stream of none: it failed to
            // cut what the stream brought, and the stream is judged as what it brought — not as too few
            // records to judge (A14).
            if (by > 0) {
                return by;
            }
        }
        return recordsBrought(first.input());
    }

    /**
     * How many records a stream brought where no boundary was settled: a document's root's children; the
     * lines of markup that is not one document — a fragment per line — as the yield scorer counts them; one
     * for a JSON document, however many lines it is printed over; the non-blank lines of text.
     */
    static int recordsBrought(final String input) {
        if (RecordSkeleton.isMarkup(input)) {
            final int parsed = Records.parsed(input);
            if (parsed >= 0) {
                return parsed;
            }
        } else if (isJsonDocument(input)) {
            return 1;
        }
        return (int) input.lines().filter(line -> !line.isBlank()).count();
    }

    /**
     * Whether text is one JSON document spanning lines — one object or array, and nothing after it, whose
     * first line does not close it — rather than JSON lines, each a value of its own, which are cut like any
     * text. A bracket is not enough: a log whose lines open with {@code [Mon Sep 21 ...]} is text, so the
     * value is parsed.
     */
    static boolean isJsonDocument(final String data) {
        final String trimmed = RecordSkeleton.withoutBom(data).stripLeading();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return false;
        }
        final String first = trimmed.lines().findFirst().orElse("").stripTrailing();
        if (first.endsWith("}") || first.endsWith("]")) {
            return false;
        }
        try (JsonParser parser = JSON.createParser(trimmed)) {
            parser.nextToken();
            parser.skipChildren();
            return parser.nextToken() == null;
        } catch (final IOException e) {
            return false;
        }
    }

    /**
     * A JSON document over the sample size limit, cut at the items of its widest array — the one a split will
     * name — and closed again, so that what the model is shown is still one JSON document. Where no array can
     * be cut that way, the whole document is shown: a sample the parser cannot read is worse than a long one,
     * and the budget of A5 is what stops a runaway.
     */
    private static String wholeItems(final String data, final int limit) {
        final String trimmed = RecordSkeleton.withoutBom(data).stripLeading();
        final Optional<int[]> widest = widestArray(trimmed);
        if (widest.isEmpty()) {
            return data;
        }
        final int open = widest.get()[0];
        final int close = widest.get()[1];
        final String before = trimmed.substring(0, open + 1);
        final String after = trimmed.substring(close);
        final List<String> items = items(trimmed.substring(open + 1, close));
        final int room = limit - before.length() - after.length();
        final StringBuilder kept = new StringBuilder();
        for (final String item : items) {
            final int cost = kept.isEmpty()
                    ? item.length()
                    : item.length() + 1;
            if (!kept.isEmpty() && kept.length() + cost > room) {
                break;
            }
            if (!kept.isEmpty()) {
                kept.append(',');
            }
            kept.append(item);
        }
        return before + kept + after;
    }

    /**
     * The widest array in a JSON document — the outermost one holding the most — as the offsets of its
     * brackets, ignoring brackets inside strings.
     */
    private static Optional<int[]> widestArray(final String json) {
        int[] widest = null;
        final Deque<Integer> opens = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            final char c = json.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\' && inString) {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '[') {
                opens.push(i);
            } else if (!inString && c == ']' && !opens.isEmpty()) {
                final int open = opens.pop();
                if (widest == null || i - open > widest[1] - widest[0]) {
                    widest = new int[]{open, i};
                }
            }
        }
        return Optional.ofNullable(widest);
    }

    /**
     * An array's items as text, split at the commas between them rather than those inside them.
     */
    private static List<String> items(final String inside) {
        final List<String> items = new ArrayList<>();
        int depth = 0;
        int from = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < inside.length(); i++) {
            final char c = inside.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\' && inString) {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && (c == '{' || c == '[')) {
                depth++;
            } else if (!inString && (c == '}' || c == ']')) {
                depth--;
            } else if (!inString && c == ',' && depth == 0) {
                items.add(inside.substring(from, i).strip());
                from = i + 1;
            }
        }
        final String last = inside.substring(from).strip();
        if (!last.isEmpty()) {
            items.add(last);
        }
        return items;
    }

    /**
     * A document over the sample size limit, cut at a record boundary: the root's start tag, as many of its
     * children as fit — at least one — and its end tag, so that what the model is shown is still a document.
     * What looks like markup but is not one document — fragments, or text beginning with a bracketed word —
     * is cut by whole lines, as text is; the split question will find it what it is.
     */
    private static String wholeChildren(final String data, final int limit) {
        final Optional<OutputRecords> document = OutputRecords.parse(data);
        if (document.isEmpty() || document.get().records().isEmpty()) {
            final List<String> lines = data.lines().filter(line -> !line.isBlank()).toList();
            return lines.isEmpty()
                    ? ""
                    : wholeLines(lines, lines.size(), limit);
        }
        final String rootName = document.get().root().getNodeName().toString();
        final int open = data.indexOf('<' + rootName);
        final int close = open < 0
                ? -1
                : endOfTag(data, open);
        if (close < 0) {
            return data;
        }
        final StringBuilder cut = new StringBuilder(data.substring(open, close + 1)).append('\n');
        int kept = 0;
        for (final XdmNode child : document.get().records()) {
            final String text = child.toString();
            if (kept > 0 && cut.length() + text.length() > limit) {
                break;
            }
            cut.append(text).append('\n');
            kept++;
        }
        return cut.append("</").append(rootName).append('>').toString();
    }

    /**
     * @return The index of the {@code >} that ends the tag opening at {@code from}, outside any quoted
     * attribute value, or -1.
     */
    private static int endOfTag(final String data, final int from) {
        char quote = 0;
        for (int i = from; i < data.length(); i++) {
            final char c = data.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    /**
     * The chain over an input, step by step. A step that produced nothing leaves the rest unrun; one that
     * produced output with errors is followed, as {@link FragmentRunner} follows it.
     */
    static List<Attempted> rerun(final List<LearnedStep> chain,
                                         final String input,
                                         final RecordBoundary boundary) {
        final List<Attempted> attempted = new ArrayList<>();
        // What the fragment's SplitFilter will give the transform (§12 item 25): from the first element
        // that is not a parser, the chain is run one record at a time, because that is how it will run.
        final OptionalInt depth = boundary == null
                ? OptionalInt.empty()
                : boundary.splitDepth();
        boolean split = false;
        String current = input;
        for (final LearnedStep step : chain) {
            final boolean first = !split && !step.runner().parser() && depth.isPresent();
            split = split || first;
            // The first element after the parser is given the parser's records; every element after that
            // is given what the one before it wrote for each record, which is one record deep. A fragment
            // carries one SplitFilter, not one per element.
            final StepResult result = split
                    ? PerRecord.run(step.runner(), step.configuration(), current, first
                            ? depth.getAsInt()
                            : PerRecord.WRITTEN)
                    : step.runner().run(step.configuration(), current);
            final Attempted attempt = Attempted.of(step.runner(), current, result, boundary);
            attempted.add(attempt);
            if (attempt.result().output() == null) {
                break;
            }
            current = attempt.result().output();
        }
        return attempted;
    }

    /**
     * The candidate's score is the product of its steps' weighted totals: a record must survive every
     * step, and what one step discards no later step can recover. A step that did not run scores nothing.
     */
    private static double candidateScore(final List<Verdict> verdicts) {
        double score = 1.0;
        for (final Verdict verdict : verdicts) {
            score *= verdict.gatesPassed()
                    ? verdict.weightedTotal()
                    : 0.0;
        }
        return verdicts.isEmpty()
                ? 0.0
                : score;
    }

    private Judged judge(final Scorecard scorecard,
                         final DocRef fragment,
                         final String input,
                         final RecordBoundary boundary) {
        // The events are taken here, in the call that made the run, and carried with the judgement they
        // belong to. Which of a call's runs is the one being served is not known until the decision is
        // made — a candidate is run and may be discarded, an incumbent is run and may be kept — and the
        // runner keeps only the last, so reading them anywhere else reads somebody else's stream.
        final List<Attempted> attempted = fragmentRunner.run(fragment, input, boundary);
        return Judged.of(attempted, scorecard, fragmentRunner.lastOutput().orElse(null));
    }

    /// A chain judged as it was *learned*: step by step over the element runners, before it has been
    /// written as a fragment and so before anything has run it as a pipeline.
    ///
    /// It carries no events, because there are none to carry. Whoever serves the stream asks for them
    /// with [#stream] once the fragment exists. Taking the runner's last output here instead would hand
    /// this stream the events of whatever ran before it — a variant tried and rejected earlier in this
    /// very call, or an earlier record of the same pipeline scope.
    private static Judged asLearned(final List<Attempted> attempted, final Scorecard scorecard) {
        return Judged.of(attempted, scorecard, null);
    }

    /**
     * A variant's run over a whole stream, scored: what promotion, provisional binding, retraction and
     * relearning all decide on.
     *
     * @param records How many records the stream brought — its lines where it is text, its records by the
     *                boundary where one was settled, else the root's children of markup. The evidence the
     *                judgement rests on (A14), counted on the input so that a variant that drops records is
     *                judged, not excused.
     */
    /// @param held The records that fell short, each as the parser cut it, where a full pass kept them
    ///             (design 04 §4): what the next question's candidates are held to. Empty otherwise.
    private record Judged(String output, List<Verdict> verdicts, double score, int records,
                          List<TextRange> spans, EventList events, List<StoredError> diagnostics,
                          Set<Integer> fellShort, List<String> held) {

        Judged(final String output, final List<Verdict> verdicts, final double score, final int records,
               final List<TextRange> spans, final EventList events, final List<StoredError> diagnostics,
               final Set<Integer> fellShort) {
            this(output, verdicts, score, records, spans, events, diagnostics, fellShort, List.of());
        }

        Judged(final String output, final List<Verdict> verdicts, final double score, final int records,
               final List<TextRange> spans, final EventList events, final List<StoredError> diagnostics) {
            this(output, verdicts, score, records, spans, events, diagnostics, null, List.of());
        }

        /// A stream served as it was read, stopped at the record that fell short (design 04 §3): what is
        /// known of it is the records judged before and including that one and the verdict on the one.
        /// No output and no spans, since nothing was held; the stream is not read again to learn what was
        /// already found. Its score is nothing: a stream with a record short of the bar did not clear it
        /// (A55), and a candidate that clears the floor must not be kept out by the mean of the records
        /// before the one that failed.
        static Judged shortOf(final ServedStream served, final RecordShortfall shortfall) {
            return new Judged(null, List.of(shortfall.verdict()), 0.0, served.records(), List.of(),
                    null, served.diagnostics(), Set.of(shortfall.index()));
        }

        /// A held stream run in full by a session, with the records that fell short kept as evidence
        /// (A64): their verdicts, and as diagnostics what each was and became, which is what the next
        /// question opens with. Scores nothing, as a stream with a record short of the bar does.
        static Judged shortOf(final Shortfalls pass) {
            final List<Verdict> verdicts = new ArrayList<>();
            final List<StoredError> evidence = new ArrayList<>();
            final Set<Integer> indices = new TreeSet<>();
            for (final Shortfalls.Shortfall shortfall : pass.kept()) {
                verdicts.add(shortfall.verdict());
                indices.add(shortfall.index());
                evidence.add(new StoredError(Severity.ERROR, null, STAGE, "Record " + (shortfall.index() + 1)
                                                                        + " of the held stream fell short of the bar."
                                                                        + (shortfall.parsed() == null
                        ? ""
                        : " The record as the parser cut it:\n" + shortfall.parsed())
                                                                        + "\nWhat the transform made of it:\n"
                                                                        + shortfall.output()));
            }
            final List<String> held = pass.kept().stream()
                    .map(Shortfalls.Shortfall::parsed)
                    .filter(Objects::nonNull)
                    .toList();
            return new Judged(null, verdicts, 0.0, pass.judged(), List.of(), null, evidence, indices, held);
        }

        static Judged of(final List<Attempted> attempted, final Scorecard scorecard, final EventList events) {
            final List<Verdict> verdicts = attempted.stream().map(scorecard::judge).toList();
            final String output = attempted.isEmpty()
                    ? null
                    : attempted.get(attempted.size() - 1).result().output();
            final int records = attempted.isEmpty()
                    ? 0
                    : recordsBrought(attempted.get(0));
            // Where each record began and ended in the stream (§12 item 21), from the step that cut it:
            // the parser knows, because its own locator told it, and nothing downstream does.
            final List<TextRange> spans = attempted.isEmpty()
                    ? List.of()
                    : attempted.get(0).result().recordRanges();
            return new Judged(output, verdicts, candidateScore(verdicts), records, spans, events,
                    diagnostics(attempted));
        }


        /// What the elements said, in chain order. A run that is served rather than discarded has these
        /// put on the pipeline's error stream by whoever serves it (A20): the fragment runs under a
        /// receiver of its own, so that a candidate's complaints go to the model rather than to the
        /// operator, and the one run that is kept must not be silent for the same reason.
        private static List<StoredError> diagnostics(final List<Attempted> attempted) {
            return attempted.stream()
                    .flatMap(step -> step.result().diagnostics().stream())
                    .toList();
        }

        boolean clearsFloor(final ShapeshifterAiDoc doc) {
            return !verdicts.isEmpty()
                   && score >= doc.getPromotionFloor()
                   && verdicts.stream().allMatch(Verdict::gatesPassed);
        }

        /// The records of the chain's output that fell short of the bar (design 04 §3, A55): the last
        /// step's, since that is what is emitted, and the scorers that judge records one by one judge a
        /// transform's records and not a parser's. Zero-based, in output order.
        Set<Integer> failingRecords() {
            if (fellShort != null) {
                return fellShort;
            }
            return verdicts.isEmpty()
                    ? Set.of()
                    : verdicts.get(verdicts.size() - 1).failingRecords();
        }
    }


    // --------------------------------------------------------------------------------


    /// One run of a bound fragment, for a caller that means to serve it.
    ///
    /// @param events      What the chain's tail emitted, or null where the fragment produced nothing and
    ///                    where the runner has no pipeline under it to make events with.
    /// @param diagnostics What its elements said making them, for the pipeline's error stream (A20).
    public record Served(EventList events, List<StoredError> diagnostics) {

    }
}
