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
import stroom.shapeshifter.ai.learning.AskedWith;
import stroom.shapeshifter.ai.learning.Conversation;
import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.learning.LearnedStep;
import stroom.shapeshifter.ai.learning.Outcome;
import stroom.shapeshifter.ai.learning.Outcome.Abandoned;
import stroom.shapeshifter.ai.learning.Outcome.Learned;
import stroom.shapeshifter.ai.learning.PromptCapture;
import stroom.shapeshifter.ai.learning.Question;
import stroom.shapeshifter.ai.learning.QuestionText;
import stroom.shapeshifter.ai.learning.RecordedAdvisor;
import stroom.shapeshifter.ai.learning.RecordedAdvisor.AwaitingAnswer;
import stroom.shapeshifter.ai.learning.Sample;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.learning.StopReports;
import stroom.shapeshifter.ai.learning.Target;
import stroom.shapeshifter.ai.learning.TargetChecks;
import stroom.shapeshifter.ai.learning.TargetSchema;
import stroom.shapeshifter.ai.learning.TargetSchemas;
import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.ai.scoring.Judgement;
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
import stroom.shapeshifter.ai.stage.Decision.Rebound;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.Decision.Would;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.RegressionSet.Accepted;
import stroom.shapeshifter.ai.stage.Sessions.Recorder;
import stroom.shapeshifter.ai.transformation.AiXsltAllowList;
import stroom.shapeshifter.ai.transformation.LearnedStylesheetCheck;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.CheckResult;
import stroom.shapeshifter.shared.CheckSetting;
import stroom.shapeshifter.shared.CheckSettings;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.StopReport;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.DocPath;
import stroom.util.shared.ElementId;
import stroom.util.shared.NullSafe;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;
import stroom.util.shared.TextRange;

import net.sf.saxon.s9api.XdmNode;
import org.xml.sax.ContentHandler;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
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
    /// The element a stage's own diagnostics are laid at.
    static final ElementId STAGE = new ElementId("Stage");

    private final Advisors advisors;
    private final List<StepRunner> runners;
    private final List<Scorer> scorers;
    /// Where the one schema a document's translations must write is read from (task 7).
    private final TargetSchemas targetSchemas;
    private final FragmentWriter writer;
    private final FragmentRunner fragmentRunner;
    private final Attempts attempts;
    private final LearnerBindings learnerBindings;
    private final LearnerStates states;
    private final Spend spend;
    private final SpendBreaker breaker;
    private final Guidance guidance;
    private final Outputs outputs;
    private final RegressionSet regressionSet;
    private final Deferrals deferrals;
    private final Learners learners;
    private final Wake wake;
    private final Places places;
    private final Snapshots snapshots;
    private final FragmentCodes codes;
    /// What a record of the chain's output is judged as when it is served: the element type the scorers
    /// of meaning read it as, which is any element that is not a parser.
    private static final String TRANSFORM = "XSLTFilter";
    private final Clock clock;
    /// How long a claim on an attempt lasts from its last renewal (design 04 §4).
    private final Duration lease;
    private final long seed;
    private final String node;
    /// The build of Stroom running, recorded against each attempt's words (N9) so that a prompt rebuilt
    /// after an upgrade can say the checks' wording may have moved under it.
    private final String build;
    /// Drafts, approval, rejection, promotion and retraction (simplification option iii).
    private final Promotion promotion;
    /// A person's actions on a run or a learner (simplification option iii).
    private final SupervisorActions actions;
    /// The record of each learning run (simplification option iii).
    private final Sessions sessions;
    /// How what a stage runs is judged, shared by serving and learning (simplification option iii).
    private final Judging judging;
    /// Learning, relearning, improving and resuming (simplification option iii).
    private final Learning learning;

    /// A stage that does not know its build: a harness, where a rebuilt prompt's caveat has no reader.
    ///
    /// @param advisors        How the model is asked.
    /// @param runners         The elements it can learn.
    /// @param scorers         What judges what it learns.
    /// @param writer          Where learned content is written.
    /// @param fragmentRunner  How a fragment is run.
    /// @param attempts        Where attempts are recorded.
    /// @param learnerBindings What serves each learner.
    /// @param states          Each learner's state.
    /// @param spend           What has been spent.
    /// @param breaker         The spend breaker.
    /// @param guidance        What people have said to learners.
    /// @param outputs         What each output was produced by.
    /// @param regressionSet   The records each binding was accepted on.
    /// @param deferrals       The streams waiting.
    /// @param learners        The learners there are.
    /// @param wake            How a pipeline is woken.
    /// @param places          Where learned content goes.
    /// @param snapshots       What each binding serves with.
    /// @param codes           A fragment as text.
    /// @param clock           The clock.
    /// @param lease           How long a claim lasts.
    /// @param seed            The seed for anything random.
    /// @param node            Which node this is.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public Stage(final Advisors advisors,
                 final List<StepRunner> runners,
                 final List<Scorer> scorers,
                 final TargetSchemas targetSchemas,
                 final FragmentWriter writer,
                 final FragmentRunner fragmentRunner,
                 final Attempts attempts,
                 final LearnerBindings learnerBindings,
                 final LearnerStates states,
                 final Spend spend,
                 final SpendBreaker breaker,
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
        this(advisors, runners, scorers, targetSchemas, writer, fragmentRunner, attempts, learnerBindings,
                states, spend,
                breaker, guidance, outputs, regressionSet, deferrals, learners, wake, places, snapshots,
                codes, clock, lease, seed, node, null);
    }

    public Stage(final Advisors advisors,
                 final List<StepRunner> runners,
                 final List<Scorer> scorers,
                 final TargetSchemas targetSchemas,
                 final FragmentWriter writer,
                 final FragmentRunner fragmentRunner,
                 final Attempts attempts,
                 final LearnerBindings learnerBindings,
                 final LearnerStates states,
                 final Spend spend,
                 final SpendBreaker breaker,
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
                 final String node,
                 final String build) {
        this.advisors = advisors;
        this.runners = List.copyOf(runners);
        this.scorers = List.copyOf(scorers);
        this.targetSchemas = targetSchemas == null
                ? TargetSchemas.none()
                : targetSchemas;
        this.writer = writer;
        this.fragmentRunner = fragmentRunner;
        this.attempts = attempts;
        this.learnerBindings = learnerBindings;
        this.states = states;
        this.spend = spend;
        this.breaker = breaker;
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
        this.build = build;
        this.promotion = new Promotion(learnerBindings, states, regressionSet, snapshots, outputs, attempts, writer,
                codes, places, wake, clock);
        this.judging = new Judging(scorers, this.targetSchemas, fragmentRunner);
        this.sessions = new Sessions(advisors, attempts, breaker, writer, places, clock, lease, node, build);
        this.learning = new Learning(this.runners, this.scorers, this.targetSchemas, attempts, learnerBindings,
                states, spend, breaker, guidance, outputs, regressionSet, wake, places, writer, codes, clock, lease,
                node, promotion, sessions, judging, fragmentRunner);
        this.actions = new SupervisorActions(attempts, learnerBindings, states, regressionSet, snapshots,
                this.targetSchemas, wake, clock, lease);
    }

    /// As [Learning#resume].
    ///
    /// @param doc       The document.
    /// @param attemptId The run.
    /// @param input     The stream it was raised on.
    /// @param answerer  Who answers what the record does not.
    /// @return What the run came to.
    public StageRun resume(final ShapeshifterAiDoc doc, final long attemptId, final Input input,
                           final Advisor answerer) {
        return learning.resume(doc, attemptId, input, answerer);
    }

    /// As [Learning#improve].
    ///
    /// @param doc         The document.
    /// @param bindingUuid The binding to improve.
    /// @param message     What a person said to improve it with.
    /// @param by          Who asked.
    /// @return What the ask came to.
    public StageRun improve(final ShapeshifterAiDoc doc,
                            final String bindingUuid,
                            final String message,
                            final String by) {
        return learning.improve(doc, bindingUuid, message, by);
    }

    /// As [Learning#improvementInput].
    ///
    /// @param attempt An improvement.
    /// @return Its sample, put back together from the records its binding was accepted on.
    public Optional<Input> improvementInput(final Attempts.Attempt attempt) {
        return learning.improvementInput(attempt);
    }

    /// As [Learning#rebuild].
    ///
    /// @param doc       The document.
    /// @param attemptId The run.
    /// @param input     The stream it learned from.
    /// @return The prompts, or empty where the run's words were not kept.
    public Optional<Rebuilt> rebuild(final ShapeshifterAiDoc doc, final long attemptId, final Input input) {
        return learning.rebuild(doc, attemptId, input);
    }

    /// As [SupervisorActions#grant].
    ///
    /// @param doc       The document.
    /// @param attemptId The run.
    /// @param tokens    Tokens to add; zero for none.
    /// @param ms        Milliseconds to add; zero for none.
    public void grant(final ShapeshifterAiDoc doc, final long attemptId, final long tokens, final long ms) {
        actions.grant(doc, attemptId, tokens, ms);
    }

    /// As [SupervisorActions#exhaust].
    ///
    /// @param doc       The document.
    /// @param attemptId The run.
    public void exhaust(final ShapeshifterAiDoc doc, final long attemptId) {
        actions.exhaust(doc, attemptId);
    }

    /// As [SupervisorActions#amend].
    ///
    /// @param doc        The document.
    /// @param attemptId  The run.
    /// @param turnNumber The turn answered.
    /// @param answer     The answer.
    /// @param answeredBy Who answered.
    public void amend(final ShapeshifterAiDoc doc,
                      final long attemptId,
                      final int turnNumber,
                      final String answer,
                      final String answeredBy) {
        actions.amend(doc, attemptId, turnNumber, answer, answeredBy);
    }

    /// As [SupervisorActions#relearn].
    ///
    /// @param doc     The document.
    /// @param learner The learner.
    /// @param reason  Why.
    public void relearn(final ShapeshifterAiDoc doc, final Learner learner, final String reason) {
        actions.relearn(doc, learner, reason);
    }

    /// As [SupervisorActions#giveUp].
    ///
    /// @param doc     The document.
    /// @param learner The learner.
    /// @param reason  Why.
    /// @param by      Who gave it up.
    public void giveUp(final ShapeshifterAiDoc doc, final Learner learner, final String reason, final String by) {
        actions.giveUp(doc, learner, reason, by);
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
            return StageRun.refused(doc, learner, "Nothing is recorded for input " + input.id()
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
            return StageRun.refused(doc, learner, "The fragment " + produced.fragment().getUuid()
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
        final Scorecard scorecard = Scorecard.of(doc.getChecks(), scorers, targetSchemas.of(doc).orElse(null));

        if (asProcessed) {
            final Optional<Produced> recorded = outputs.asProcessed(doc.getUuid(), input.id(), input.pipeline());
            return recorded
                    .map(bindings -> served(doc, new Bound(null), learner, bindings,
                            judging.judge(scorecard, bindings.fragment(), input.data(), bindings.boundary())))
                    .orElseGet(() -> would(doc, learner, "Refuse the stream: nothing is recorded for input "
                                                       + input.id() + " on this pipeline, so it cannot be "
                                                       + "processed again as it was"));
        }

        final Optional<Binding> matched = promotion.bindingFor(learner);
        if (matched.isPresent()) {
            final Binding binding = matched.get();
            if (binding.isDraft()) {
                return would(doc, learner, "Wait: draft binding " + binding.getUuid() + " binds "
                                         + binding.getPipeline().getName() + " and awaits review");
            }
            final Judged judged = judging.judge(scorecard, binding.getPipeline(), input.data(),
                    binding.getRecordBoundary());
            return served(doc, new Bound(binding), learner,
                    new Produced(doc.getUuid(), binding.getUuid(), binding.getPipeline(), binding.getRecordBoundary(),
                            judged.score()),
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
        final Scorecard scorecard = Scorecard.of(doc.getChecks(), scorers, targetSchemas.of(doc).orElse(null));
        final Optional<Binding> matched = promotion.bindingFor(learner);

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
            return StageRun.refused(doc, learner, "Given up: " + givenUp.get());
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
        return sessions.recording(doc, learner, input, RecordedAdvisor.awaiting(),
                reason -> deferred(doc, learner, input, null, 0, reason),
                recorder -> learning.learnAndBind(doc, learner, input, scorecard, recorder));
    }

    /// The task decision for a learner a binding serves.
    private StageRun decideServed(final ShapeshifterAiDoc doc,
                                  final Learner learner,
                                  final Binding matched,
                                  final Input input,
                                  final Scorecard scorecard) {
        // As it serves now (A72): a hand edit is snapshotted here, before the stamp is compared, so that
        // a stream deferred against the old snapshot is scored against the edit.
        final Binding binding = promotion.freshened(doc, learner, matched);
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
        final Judged judged = judging.judge(scorecard, binding.getPipeline(), input.data(),
                binding.getRecordBoundary());
        if (judged.records() < doc.getMinRecordsToLearn()) {
            return;
        }
        sessions.recording(doc, learner, input, RecordedAdvisor.awaiting(),
                waiting -> null,
                recorder -> learning.relearnUnderClaim(doc, learner, binding, input, scorecard, judged, marked.get(),
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
        final Scorecard bar = judging.recordBar(doc);
        final RecordJudge judge = (index, record, parsed) -> bar.judge(new Attempted(TRANSFORM, false, null,
                new StepResult(record, List.of()), binding.getRecordBoundary(), null));
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
                    Scorecard.of(doc.getChecks(), scorers, targetSchemas.of(doc).orElse(null)),
                    Judged.shortOf(served, short_), reason, stamp(binding),
                            1);
            throw new DeferTaskException(((Deferred) deferred.decision()).reason());
        }
        // What came out is only half of it. A record the parser could not read becomes no event, so no
        // check judges it and the stream is recorded as clean — the fault this task exists to fix
        // (`docs/shapeshifter-ai-parser-drift-not-detected.md`). The serving run now says what went in.
        final Optional<String> lost = Judging.inputShortfall(doc, served);
        if (lost.isPresent()) {
            final StageRun deferred = session(doc, learner, binding, input,
                    Scorecard.of(doc.getChecks(), scorers, targetSchemas.of(doc).orElse(null)),
                    Judged.lostFrom(served, lost.get()), lost.get(),
                    stamp(binding), served.lost());
            throw new DeferTaskException(((Deferred) deferred.decision()).reason());
        }
        deferrals.released(learner, input.id());
        if (served.records() > 0) {
            // The rolling score goes on being kept for the Supervisor's list of what could be better
            // (A46), and triggers nothing here. A run that made nothing is said on the error stream by the
            // caller and scores nothing.
            states.scored(learner, doc.getUuid(), served.meanTotal(), served.records(), served.skipped(),
                    doc.getMinRecordsToLearn());
        }
        // This run's score, not the binding's promotion score: an output history that records every
        // stream at the score the translation had when it was promoted cannot show it getting worse.
        outputs.emitted(input.id(), input.pipeline(), producedBy(doc, binding, served),
                served.records(), served.skipped());
        return served;
    }

    private static Produced producedBy(final ShapeshifterAiDoc doc, final Binding binding) {
        return new Produced(doc.getUuid(), binding.getUuid(), binding.getPipeline(), binding.getRecordBoundary(),
                binding.getScore() == null
                        ? 0.0
                        : binding.getScore(), binding.getSnapshot());
    }

    /// What produced an output, scored on **this** run rather than on the binding's promotion (audit
    /// finding W4-6): a run that made nothing must not appear in the output history at the score the
    /// translation had on the day it was promoted.
    ///
    /// @param doc     The document.
    /// @param binding The binding that served the stream.
    /// @param served  What the run came to.
    /// @return What produced it.
    private static Produced producedBy(final ShapeshifterAiDoc doc,
                                       final Binding binding,
                                       final ServedStream served) {
        return new Produced(doc.getUuid(), binding.getUuid(), binding.getPipeline(), binding.getRecordBoundary(),
                served.meanTotal(), binding.getSnapshot());
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
        final Optional<Binding> inForce = promotion.bindingFor(learner);
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
        // A stream that has been relearned for and put back again, against the same translation, is not
        // going to be fixed by asking the model a fourth time (audit finding W4-5). The corpus's
        // no-worse rule can keep every candidate out — a feed whose format has changed for good needs a
        // translation that reads both, and may not get one — and without this the stream opens a new
        // paid session on every retry of the deferred task, for as long as it waits.
        final Optional<Deferral> tried = deferrals.of(learner, input.id());
        if (tried.isPresent()
            && stamp.equals(tried.get().failedAgainst())
            && tried.get().attempts() >= MOST_SESSIONS_PER_STREAM) {
            return deferred(doc, learner, input, stamp, failingRecords, reason,
                    reason + " (relearned for " + tried.get().attempts()
                    + " times against this translation without it changing; it now waits for a person)");
        }
        if (states.relearnReason(learner).isEmpty()) {
            states.markForRelearning(learner, doc.getUuid(), reason);
        }
        if (doc.getLearningMode() == LearningMode.DISABLED) {
            return deferred(doc, learner, input, stamp, failingRecords,
                    reason + "; Shapeshifter AI is disabled for this document, so the stream waits for a person");
        }
        return sessions.recording(doc, learner, input, RecordedAdvisor.awaiting(),
                waiting -> deferred(doc, learner, input, stamp, failingRecords, reason,
                        reason + " (" + waiting + ")"),
                recorder -> {
                    // Opened in the task only to be parked: the worker re-walks it as a relearning, since
                    // a binding serves the learner (resume reads that from the bindings as they stand). The stream
                    // was judged once, where it fell short; a stamped stream that found no session open
                    // is judged here, since the session's opening needs to say what fell short.
                    final Judged served = judged != null
                            ? judged
                            : judging.judge(scorecard, binding.getPipeline(), input.data(),
                                    binding.getRecordBoundary());
                    return learning.relearnUnderClaim(doc, learner, binding, input, scorecard, served, reason,
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
            final boolean replaced = promotion.bindingFor(waiting.learner())
                    .filter(binding -> !binding.isDraft())
                    // A row with no stamp is a stream waiting for a *first* learning (W7-4): there was
                    // nothing to fail against, so anything serving the learner now is new to it. Such a
                    // row used to be left out of this sweep entirely, and a stream deferred just after
                    // the promotion's wake waited the full retry delay for a translation that already
                    // existed.
                    //
                    // A row still here is a stream not yet served — the row goes when it is — so waking
                    // it is right rather than wasteful. Sweeping again before it runs re-wakes tasks
                    // that are already `CREATED`, which costs one update of no rows per pipeline.
                    .map(binding -> waiting.failedAgainst() == null
                                    || !waiting.failedAgainst().equals(stamp(binding)))
                    .orElse(false);
            if (replaced) {
                pipelines.add(waiting.learner().pipelineUuid());
            }
        }
        return pipelines.stream().mapToInt(wake::wake).sum();
    }

    // What a paused attempt stopped on, in brackets, without its full stop; empty where it says nothing
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
     * Approve a draft (A25): the promotion. Where a binding already serves the learner — the draft came of
     * relearning — that binding is rebound to the draft's fragment and keeps its {@code uuid} and its
     * history; otherwise the draft itself goes live. Either way the learner's pipeline is woken, which puts
     * what waited for the review through.
     */
    public void approve(final ShapeshifterAiDoc doc, final String bindingUuid) {
        promotion.approve(doc, bindingUuid, null);
    }

    /// As [Promotion#approve], saying who approved it.
    ///
    /// @param doc         The document.
    /// @param bindingUuid The draft.
    /// @param reviewedBy  Who approved it; null where nobody is named.
    public void approve(final ShapeshifterAiDoc doc, final String bindingUuid, final String reviewedBy) {
        promotion.approve(doc, bindingUuid, reviewedBy);
    }

    /// As [Promotion#retract].
    ///
    /// @param doc         The document.
    /// @param bindingUuid The binding.
    /// @param reason      Why, in the person's words.
    /// @param by          Who retracted it.
    /// @return How many streams the binding produced output for.
    public int retract(final ShapeshifterAiDoc doc, final String bindingUuid, final String reason, final String by) {
        return promotion.retract(doc, bindingUuid, reason, by);
    }

    /**
     * Reject a draft (A25): the binding goes, with its records; its documents stay, as every document does.
     * The learner gives up with the reason, so the model is not asked the same question again until an
     * operator says otherwise: a learner with no binding sentinels its streams, and a relearned learner's
     * incumbent carries on serving without being scored against or relearned.
     *
     */
    public void reject(final ShapeshifterAiDoc doc, final String bindingUuid, final String reason) {
        promotion.reject(doc, bindingUuid, reason, null);
    }

    /// As [Promotion#reject], saying who rejected it.
    ///
    /// @param doc         The document.
    /// @param bindingUuid The draft.
    /// @param reason      Why.
    /// @param reviewedBy  Who rejected it; null where nobody is named.
    public void reject(final ShapeshifterAiDoc doc,
                       final String bindingUuid,
                       final String reason,
                       final String reviewedBy) {
        promotion.reject(doc, bindingUuid, reason, reviewedBy);
    }

    /// How many times one stream may open a relearning session against the **same** translation before it
    /// stops asking and waits for a person (audit finding W4-5). A translation that has not moved after
    /// this many tries is not going to move because the stream was retried again five minutes later, and
    /// every try costs a call to the model.
    private static final int MOST_SESSIONS_PER_STREAM = 3;

    // --------------------------------------------------------------------------------

    // --------------------------------------------------------------------------------

    /// One run of a bound fragment, for a caller that means to serve it.
    ///
    /// @param events      What the chain's tail emitted, or null where the fragment produced nothing and
    ///                    where the runner has no pipeline under it to make events with.
    /// @param diagnostics What its elements said making them, for the pipeline's error stream (A20).
    public record Served(EventList events, List<StoredError> diagnostics) {

    }
}
