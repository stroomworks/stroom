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
import stroom.learningflow.extraction.PerRecord;
import stroom.learningflow.fragment.FragmentCodes;
import stroom.learningflow.fragment.FragmentRunner;
import stroom.learningflow.fragment.FragmentRunner.FragmentRecord;
import stroom.learningflow.fragment.FragmentWriter;
import stroom.learningflow.fragment.RecordJudge;
import stroom.learningflow.fragment.RecordShortfall;
import stroom.learningflow.fragment.ServedStream;
import stroom.learningflow.fragment.Shortfalls;
import stroom.learningflow.learning.Advisor;
import stroom.learningflow.learning.Advisors;
import stroom.learningflow.learning.AskedWith;
import stroom.learningflow.learning.Conversation;
import stroom.learningflow.learning.Exchange;
import stroom.learningflow.learning.LearnedStep;
import stroom.learningflow.learning.Outcome;
import stroom.learningflow.learning.Outcome.Abandoned;
import stroom.learningflow.learning.Outcome.Learned;
import stroom.learningflow.learning.PromptCapture;
import stroom.learningflow.learning.Question;
import stroom.learningflow.learning.QuestionText;
import stroom.learningflow.learning.RecordedAdvisor;
import stroom.learningflow.learning.RecordedAdvisor.AwaitingAnswer;
import stroom.learningflow.learning.Sample;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.StepRunner;
import stroom.learningflow.learning.StopReports;
import stroom.learningflow.learning.Target;
import stroom.learningflow.learning.TargetChecks;
import stroom.learningflow.learning.TargetSchema;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.scoring.Attempted;
import stroom.learningflow.scoring.Judgement;
import stroom.learningflow.scoring.OutputRecords;
import stroom.learningflow.scoring.Records;
import stroom.learningflow.scoring.Scorecard;
import stroom.learningflow.scoring.Scorer;
import stroom.learningflow.scoring.Verdict;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckResult;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckSettings;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.shared.StopReport;
import stroom.learningflow.stage.Attempts.Grant;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.Deferred;
import stroom.learningflow.stage.Decision.Drafted;
import stroom.learningflow.stage.Decision.GivenUp;
import stroom.learningflow.stage.Decision.Kept;
import stroom.learningflow.stage.Decision.Parked;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Decision.Rebound;
import stroom.learningflow.stage.Decision.Sentinel;
import stroom.learningflow.stage.Decision.Would;
import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.RegressionSet.Accepted;
import stroom.learningflow.stage.Sessions.Recorder;
import stroom.learningflow.transformation.AiXsltAllowList;
import stroom.learningflow.transformation.LearnedStylesheetCheck;
import stroom.learningflow.transformation.XsltStep;
import stroom.meta.shared.MetaFields;
import stroom.pipeline.xml.event.EventList;
import stroom.processor.api.DeferTaskException;
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

/// The Smart Pipeline element's logic for one stream, without the pipeline element around it; the element
/// delegates to this class. For each stream it decides whether to serve it with the learner's binding, defer
/// it while a learning session runs, or error it because the learner has given up. It also serves a bound
/// stream, reprocesses a stream as it was first processed, performs dry runs for stepping, and is the entry
/// point for the deferred worker and the Supervisor's improve and rebuilt-prompt actions, which it passes on
/// to [Learning].
///
/// The runtime state (learner states, bindings, outputs, deferrals and the regression corpus) is passed in.
/// A sentinel is an error stream and nothing more; the session that gave up records why. Binding a learner
/// wakes its pipeline, which processes its waiting streams; nothing is reprocessed automatically. Under
/// review promotion mode a binding is a draft until [Promotion#approve] promotes it or [Promotion#reject]
/// discards it.
public final class Stage {

    /// The element id that the stage's own diagnostics are reported against.
    static final ElementId STAGE = new ElementId("Stage");

    private final List<Scorer> scorers;
    /// Resolves the target schema that a document's translations must produce.
    private final TargetSchemas targetSchemas;
    private final FragmentRunner fragmentRunner;
    private final Attempts attempts;
    private final LearnerBindings learnerBindings;
    private final LearnerStates states;
    private final Outputs outputs;
    private final Deferrals deferrals;
    private final Learners learners;
    private final Wake wake;
    private final Snapshots snapshots;
    /// The element type a record of the chain's output is judged as when it is served. The scorers that
    /// judge content treat it as the output of a transform, i.e. any element that is not a parser.
    private static final String TRANSFORM = XsltStep.ELEMENT_TYPE;
    private final Clock clock;
    /// Handles drafts, approval, rejection, promotion and retraction.
    private final Promotion promotion;
    /// Records each learning session.
    private final Sessions sessions;
    /// Judges what the stage runs, for both serving and learning.
    private final Judging judging;
    /// Handles learning, relearning, improving and resuming sessions.
    private final Learning learning;

    /// Creates a stage over its runtime state and the elements it can learn.
    ///
    /// @param advisors        Creates the advisors that ask the model.
    /// @param runners         Runners for the pipeline elements it can learn configurations for.
    /// @param scorers         The scorers that judge what it learns and serves.
    /// @param targetSchemas   Resolves a document's target schema; null for none.
    /// @param writer          Writes learned content.
    /// @param fragmentRunner  Runs a fragment.
    /// @param attempts        Where sessions are recorded.
    /// @param learnerBindings What serves each learner.
    /// @param states          Each learner's state.
    /// @param spend           The cluster-wide spend counter.
    /// @param breaker         The spend breaker.
    /// @param guidance        The guidance people have left for learners.
    /// @param outputs         What produced each output.
    /// @param regressionSet   The corpus records each binding was accepted on.
    /// @param deferrals       The waiting streams.
    /// @param learners        Keeps learners' rows under the document they run under.
    /// @param wake            Wakes a pipeline's deferred tasks.
    /// @param places          Where learned content goes.
    /// @param snapshots       The configuration snapshots each binding serves with.
    /// @param codes           Reads a fragment's documents as text, for snapshots.
    /// @param clock           The source of the current time.
    /// @param lease           How long a claim on a learner lasts from its last renewal.
    /// @param node            This node's name.
    /// @param build           The Stroom build version, recorded with each session's question wording so that
    ///                        a prompt rebuilt after an upgrade can warn that the checks' wording may have
    ///                        changed since; null if not known, as in a test harness.
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
                 final String node,
                 final String build) {
        this.scorers = List.copyOf(scorers);
        this.targetSchemas = targetSchemas == null
                ? TargetSchemas.none()
                : targetSchemas;
        this.fragmentRunner = fragmentRunner;
        this.attempts = attempts;
        this.learnerBindings = learnerBindings;
        this.states = states;
        this.outputs = outputs;
        this.deferrals = deferrals;
        this.learners = learners;
        this.wake = wake;
        this.snapshots = snapshots;
        this.clock = clock;
        this.promotion = new Promotion(learnerBindings, states, regressionSet, snapshots, outputs, attempts, writer,
                codes, places, wake, clock);
        this.judging = new Judging(scorers, this.targetSchemas, fragmentRunner);
        this.sessions = new Sessions(advisors, attempts, breaker, writer, places, clock, lease, node, build);
        this.learning = new Learning(List.copyOf(runners), this.scorers, this.targetSchemas, attempts,
                learnerBindings, states, spend, breaker, guidance, outputs, regressionSet, wake, places, writer, codes,
                clock, lease, node, promotion, sessions, judging, fragmentRunner);
    }

    /// Resumes a session; see [Learning#resume].
    ///
    /// @param doc       The document.
    /// @param attemptId The session's id.
    /// @param input     The stream it was opened on, or the reassembled sample for an improvement.
    /// @param answerer  The advisor that answers questions the recorded turns do not.
    /// @return The outcome of the session.
    public StageRun resume(final LearningFlowDoc doc, final long attemptId, final Input input,
                           final Advisor answerer) {
        return learning.resume(doc, attemptId, input, answerer);
    }

    /// Opens an improvement session for a serving binding; see [Learning#improve].
    ///
    /// @param doc         The document.
    /// @param bindingUuid The UUID of the binding to improve.
    /// @param message     What the person wants improved, recorded as guidance; may be null.
    /// @param by          Who asked.
    /// @return The result of the request; a sentinel if the session could not be opened.
    public StageRun improve(final LearningFlowDoc doc,
                            final String bindingUuid,
                            final String message,
                            final String by) {
        return learning.improve(doc, bindingUuid, message, by);
    }

    /// Builds an improvement session's sample; see [Learning#improvementInput].
    ///
    /// @param attempt An improvement session.
    /// @return Its sample, reassembled from the corpus records its binding was accepted on.
    public Optional<Input> improvementInput(final Attempts.Attempt attempt) {
        return learning.improvementInput(attempt);
    }

    /// Rebuilds the prompts a session sent; see [Learning#rebuild].
    ///
    /// @param doc       The document.
    /// @param attemptId The session's id.
    /// @param input     The stream it learned from.
    /// @return The prompts, or empty if the session's question wording was not recorded.
    public Optional<Rebuilt> rebuild(final LearningFlowDoc doc, final long attemptId, final Input input) {
        return learning.rebuild(doc, attemptId, input);
    }

    /// Gets what each of the fragment's elements made of the stream on this stage's last run, a list per
    /// record, which the stepper shows beneath the Smart Pipeline element.
    ///
    /// The records are **taken**, not just read, and should be taken immediately after the run they belong
    /// to. That leaves the runner empty, so a later run that decided without running a fragment (a draft
    /// awaiting review, or a learner that gave up) finds nothing here rather than the previous run's records
    /// (see [FragmentRunner#takeRecords]).
    ///
    /// Empty if the fragment was run by a stand-in rather than as a pipeline, or if nothing was bound and so
    /// nothing ran.
    public List<FragmentRecord> takeFragment() {
        return fragmentRunner.takeRecords();
    }

    /// Runs a stream through the fragment and snapshot that produced its output before: an
    /// **as-processed** reprocess. This is what an audit needs, and what a person asks for when the question
    /// is "what did this stream become?" rather than "what would it become now?".
    ///
    /// The current bindings are not consulted, and nothing is learned, scored or relearned. It makes no
    /// difference if the binding has since been rebound, retracted or given up: the recorded snapshot of the
    /// configuration is run again. For the other question, what serves the learner today, the answer is
    /// [#decide], which is what an ordinary reprocess filter puts a stream through.
    ///
    /// @param doc     The document.
    /// @param learner The learner whose pipeline the input is processed again through.
    /// @param input   The input stream.
    /// @return The run of the recorded fragment, or a sentinel if nothing is recorded for this input. An
    ///         input with no record cannot be processed as it was, and quietly processing it with the current
    ///         binding would answer a question nobody asked.
    public StageRun reprocess(final LearningFlowDoc doc, final Learner learner, final Input input) {
        final Optional<Produced> recorded = outputs.asProcessed(doc.getUuid(), input.id(), input.pipeline());
        if (recorded.isEmpty()) {
            return StageRun.refused(doc, learner, "Nothing is recorded for input " + input.id()
                                       + " on this pipeline, so it cannot be processed again as it was; "
                                       + "reprocess it as-current instead");
        }
        final Produced produced = recorded.get();
        final Optional<Binding> binding = learnerBindings.byUuid(produced.bindingUuid())
                .map(LearnerBindings.Held::binding);
        // Run under the record boundary that produced it, which the row records, because the binding's
        // boundary is today's and a rebind may have changed it; the same fragment under another boundary
        // is a different chain. And run with the code that produced it, from the snapshot the row names,
        // because the fragment may have been rewritten in place since. A row recorded before snapshots
        // existed runs the fragment as it is now, which is all that was recorded for it.
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
            // The fragment produced nothing, which means its content has gone (e.g. a document deleted by
            // hand) or it never ran cleanly. This is reported rather than served.
            return StageRun.refused(doc, learner, "The fragment " + produced.fragment().getUuid()
                                       + " that produced input " + input.id() + " produced nothing this time");
        }
        // Recorded again, because this output was produced by the same binding as the one it replaces.
        outputs.emitted(input.id(), input.pipeline(), produced);
        return new StageRun(doc, new Bound(binding.orElse(null)), learner, produced, output, List.of(), List.of(),
                events, attempted.stream().flatMap(step -> step.result().diagnostics().stream()).toList());
    }

    /// Runs a bound fragment over an input to get its events, for a caller that has a downstream to serve
    /// and a [StageRun] that has no events.
    ///
    /// A chain is judged as it is learned, step by step through the element runners, before it has been
    /// written anywhere, so the stream a learner *learned* on has an output but no events to serve it with.
    /// The resulting fragment is run once here, which is the run that stream would have had if it had
    /// already been bound; it is not a second run, because nothing has run this fragment yet.
    ///
    /// A stream that was already bound never comes here. Its events are the ones its judgement was made
    /// from, so the run that is judged is the run that is served.
    ///
    /// @param produced What produced the stream's output.
    /// @param input    The input stream.
    /// @return The events and the elements' diagnostics.
    public Served serve(final Produced produced, final Input input) {
        final List<Attempted> attempted = fragmentRunner.run(produced.fragment(), input.data(),
                produced.boundary());
        return new Served(fragmentRunner.lastOutput().orElse(null),
                attempted.stream().flatMap(step -> step.result().diagnostics().stream()).toList());
    }

    /// Reports what this stage would do with a stream, and what it would produce, without doing any of it.
    ///
    /// Stepping is a dry run. The binding that serves the learner is found and its fragment is run, because
    /// the fragment's output is what the person stepping the pipeline wants to see, but nothing else happens:
    /// no model is asked, no fragment or binding is written, promoted, retracted or relearned, and no rolling
    /// score, output row or wake is recorded. A learner with no serving binding is *reported*, as a
    /// [Decision.Would], rather than learned for.
    ///
    /// This is a separate method rather than a flag on [#decide], because every branch of [#decide] writes
    /// something, and a flag threaded through all of them would be one missed `if` away from a stepping
    /// session that learned and bound something.
    ///
    /// @param doc         The document.
    /// @param learner     The learner whose pipeline is being stepped.
    /// @param input       The input stream.
    /// @param asProcessed Whether to run the fragment that produced this input's output before, as
    ///                    [#reprocess] does, rather than whatever serves the learner today. Unlike
    ///                    [#reprocess], the fragment is run as it is now, without the recorded snapshot.
    /// @return What would be done and produced.
    public StageRun dryRun(final LearningFlowDoc doc,
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
            return would(doc, learner, "Wait: Learning Flow is disabled for this document and nothing "
                                       + "serves this learner");
        }
        return would(doc, learner, "Learn a fragment for this learner");
    }

    /// Reports a bound fragment's run for a dry run: what it produced and how it was scored, with nothing
    /// recorded anywhere.
    private StageRun served(final LearningFlowDoc doc,
                            final Decision decision,
                            final Learner learner,
                            final Produced produced,
                            final Judged judged) {
        return new StageRun(doc, decision, learner, produced, judged.output(), judged.verdicts(), List.of(),
                judged.events(), judged.diagnostics());
    }

    private StageRun would(final LearningFlowDoc doc,
                           final Learner learner,
                           final String what) {
        return new StageRun(doc, new Would(what), learner, null, null, List.of(), List.of(), null,
                List.of());
    }

    /// Makes the decision for a processing task: serve, defer or error, but never learn. Every task does the
    /// same cheap thing (look at what the learner knows and take one of three outcomes). Learning sessions
    /// are run by the worker; all a task does is open one and step aside.
    ///
    /// - A binding serves the learner: the stream is to be served (see [#stream], which defers it if any
    ///   record falls short of the bar, and opens a session if none holds the learner's claim). A stream
    ///   whose deferral row is stamped with the transform still in force is deferred again without running
    ///   anything.
    /// - The only binding is a draft awaiting review: deferred until a person decides.
    /// - No binding: deferred, with a session opened on this stream.
    /// - The learner has given up: errored with the reason. This is the only outcome that errors.
    ///
    /// @param doc     The document.
    /// @param learner The learner the stream is for.
    /// @param input   The input stream.
    /// @return The decision.
    public StageRun decide(final LearningFlowDoc doc, final Learner learner, final Input input) {
        // The learner's rows are moved to the document it runs under now, so that this document's
        // Supervisor shows what serves its pipeline and no other document's editors can act on it.
        learners.runsUnder(learner, doc.getUuid());
        final Scorecard scorecard = Scorecard.of(doc.getChecks(), scorers, targetSchemas.of(doc).orElse(null));
        final Optional<Binding> matched = promotion.bindingFor(learner);

        if (matched.isPresent()) {
            final Binding binding = matched.get();
            if (binding.isDraft()) {
                // A person is reviewing the draft, so the stream waits for their decision rather than
                // erroring, and approving or rejecting the draft releases it.
                return deferred(doc, learner, input, null, 0, "Awaiting review: draft binding " + binding.getUuid()
                                                             + " binds " + binding.getPipeline().getName());
            }
            return decideServed(doc, learner, binding, input, scorecard);
        }

        final Optional<String> givenUp = states.reasonGivenUp(learner);
        if (givenUp.isPresent()) {
            // A stream that waited on a session that gave up has reached its end: its row is removed and it
            // errors with the reason. A person can send the learner back to learn again, and reprocess its
            // error streams as they would any others.
            deferrals.released(learner, input.id());
            return StageRun.refused(doc, learner, "Given up: " + givenUp.get());
        }
        if (doc.getLearningMode() == LearningMode.DISABLED) {
            // Learning is disabled, so no session can open. The stream waits for a person to enable
            // learning, and is raised in the Supervisor once it exceeds the maximum deferral age.
            return deferred(doc, learner, input, null, 0,
                    "Learning Flow is disabled for this document and nothing serves this learner");
        }
        // Learning never happens in the task: a session is opened on this stream and left waiting at its
        // first question for the worker, and the stream waits on it. If a session already holds the
        // learner's claim, this stream waits on that one.
        return sessions.recording(doc, learner, input, RecordedAdvisor.awaiting(),
                reason -> deferred(doc, learner, input, null, 0, reason),
                recorder -> learning.learnAndBind(doc, learner, input, scorecard, recorder));
    }

    /// Makes the task decision for a learner that a binding serves.
    private StageRun decideServed(final LearningFlowDoc doc,
                                  final Learner learner,
                                  final Binding matched,
                                  final Input input,
                                  final Scorecard scorecard) {
        // Take a new snapshot if the fragment has been edited by hand, before the stamp is compared, so
        // that a stream deferred against the old snapshot is scored against the edit.
        final Binding binding = promotion.freshened(doc, learner, matched);
        final String stamp = stamp(binding);
        final Optional<Deferral> row = deferrals.of(learner, input.id());
        if (row.isPresent() && stamp.equals(row.get().failedAgainst())) {
            // The transform the stream fell short against is still the one in force, so nothing is run. A
            // session is opened if none holds the claim (the last may have expired or failed), or the stream
            // would be waiting on nothing.
            return session(doc, learner, binding, input, scorecard, null, row.get().reason(), stamp,
                    row.get().failingRecords());
        }
        // Served as it is read, so that streams of any size can be handled: nothing is run here. The caller
        // runs the fragment over the stream with [#stream], which judges each record as the fragment
        // writes it and decides the outcome.
        final StageRun bound = new StageRun(doc, new Bound(binding), learner, producedBy(doc, binding), null, List.of(),
                List.of(), null, List.of());
        relearnOnMark(doc, learner, binding, input, scorecard);
        return bound;
    }

    /// Opens a relearning session for a learner a person has marked for relearning, on its next stream that
    /// brings enough records to judge a candidate on. The session is opened here and left for the worker,
    /// while the stream itself is served by the serving binding, as it would be without the mark. Nothing is
    /// opened for a learner with a draft awaiting review, a learner that has given up (a person must send it
    /// back), a document with learning disabled, or a learner whose claim is already held by a session.
    private void relearnOnMark(final LearningFlowDoc doc,
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

    /// Serves a bound stream as it is read. The fragment runs over the whole stream once, each record of its
    /// output is judged by the record gates as it is produced, and each record that clears the bar is passed
    /// downstream. The first record that falls short stops the run: the stream is deferred (a row stamped
    /// against the fragment in force, the learner marked for relearning, and a relearning session opened if
    /// none holds the claim), and a [DeferTaskException] is thrown so that the task ends deferred and
    /// whatever the downstream wrote for the earlier records is discarded. The stream is also deferred if
    /// the parser did not account for all of its input.
    ///
    /// A stream that clears in full is served: its deferral row is removed, the learner's rolling score is
    /// updated, and the output is recorded against the binding that produced it, with this run's score.
    ///
    /// @param doc        The document.
    /// @param learner    The learner the stream is for.
    /// @param binding    The binding that serves the stream, as [#decide] returned it.
    /// @param input      The input stream.
    /// @param downstream Where the output goes.
    /// @return The result of the run, whose diagnostics the caller reports on the error stream.
    public ServedStream stream(final LearningFlowDoc doc,
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
        // The output is only half of it. A record the parser could not read becomes no event, so no check
        // would judge it and the stream would be recorded as clean (the fault described in
        // `docs/learning-flow-parser-drift-not-detected.md`). So the input is checked as well.
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
            // The rolling score is kept for the Supervisor's list, to show what could be improved; it
            // triggers nothing here. A run that produced no records is reported on the error stream by the
            // caller and is not scored.
            states.scored(learner, doc.getUuid(), served.meanTotal(), served.records(), served.skipped(),
                    doc.getMinRecordsToLearn());
        }
        // This run's score, not the binding's promotion score, because an output history that records
        // every stream at the score the translation had when it was promoted cannot show it getting worse.
        outputs.emitted(input.id(), input.pipeline(), producedBy(doc, binding, served),
                served.records(), served.skipped());
        return served;
    }

    private static Produced producedBy(final LearningFlowDoc doc, final Binding binding) {
        return new Produced(doc.getUuid(), binding.getUuid(), binding.getPipeline(), binding.getRecordBoundary(),
                binding.getScore() == null
                        ? 0.0
                        : binding.getScore(), binding.getSnapshot());
    }

    /// Records what produced an output, with the score from **this** run rather than the binding's score at
    /// promotion, so that a run that produced nothing does not appear in the output history with the score
    /// the translation had on the day it was promoted.
    ///
    /// @param doc     The document.
    /// @param binding The binding that served the stream.
    /// @param served  What the run came to.
    /// @return What produced it.
    private static Produced producedBy(final LearningFlowDoc doc,
                                       final Binding binding,
                                       final ServedStream served) {
        return new Produced(doc.getUuid(), binding.getUuid(), binding.getPipeline(), binding.getRecordBoundary(),
                served.meanTotal(), binding.getSnapshot());
    }

    /// Defers a stream that a binding failed to serve, to wait for the binding to be relearned. The deferral
    /// row is written against the transform in force, the learner is marked for relearning with the reason,
    /// and a session is opened and left for the worker if none holds the claim. One session learns for the
    /// learner, and every waiting stream is tried again when it promotes.
    ///
    /// @param judged The stream as the binding's fragment judged it, or null if the stamp meant it was not
    ///               run.
    private StageRun session(final LearningFlowDoc doc,
                             final Learner learner,
                             final Binding binding,
                             final Input input,
                             final Scorecard scorecard,
                             final Judged judged,
                             final String reason,
                             final String stamp,
                             final int failingRecords) {
        // A stream judged against a transform that a relearning has since replaced says nothing about the
        // one in force, and marking the learner because of it would relearn what has just been learned. It
        // waits against the stamp it failed, which is no longer in force, so its next try is scored
        // against the new transform rather than deferred again.
        final Optional<Binding> inForce = promotion.bindingFor(learner);
        if (inForce.isPresent() && !stamp.equals(stamp(inForce.get()))) {
            return deferred(doc, learner, input, stamp, failingRecords, reason,
                    reason + " (against a transform since replaced; it is tried again against the new one)");
        }
        // A draft awaiting review is already the learner's answer, and a person is reviewing it, so the
        // stream waits for their decision. A session opened here would relearn behind their back, and its
        // reset would forget the draft awaiting them.
        final Optional<String> awaiting = states.draftAwaiting(learner);
        if (awaiting.isPresent()) {
            return deferred(doc, learner, input, stamp, failingRecords, reason,
                    reason + " (Awaiting review: draft binding " + awaiting.get() + ")");
        }
        // A stream that has been relearned for and deferred again several times, against the same
        // translation, is not going to be fixed by asking the model once more. The corpus's no-worse rule
        // can keep every candidate out (a feed whose format has changed for good needs a translation that
        // reads both formats, and may not get one), and without this check the stream would open a new
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
                    reason + "; Learning Flow is disabled for this document, so the stream waits for a person");
        }
        return sessions.recording(doc, learner, input, RecordedAdvisor.awaiting(),
                waiting -> deferred(doc, learner, input, stamp, failingRecords, reason,
                        reason + " (" + waiting + ")"),
                recorder -> {
                    // Opened in the task only to be left waiting: the worker replays it as a relearning,
                    // because a binding serves the learner (resume reads that from the current bindings).
                    // The stream was judged once already, where it fell short; a stamped stream that found
                    // no session open is judged here, because the session must start by saying what fell
                    // short.
                    final Judged served = judged != null
                            ? judged
                            : judging.judge(scorecard, binding.getPipeline(), input.data(),
                                    binding.getRecordBoundary());
                    return learning.relearnUnderClaim(doc, learner, binding, input, scorecard, served, reason,
                            recorder);
                });
    }

    /// Defers the stream: its deferral row is written or updated, and nothing else is.
    private StageRun deferred(final LearningFlowDoc doc,
                              final Learner learner,
                              final Input input,
                              final String failedAgainst,
                              final int failingRecords,
                              final String reason) {
        return deferred(doc, learner, input, failedAgainst, failingRecords, reason, reason);
    }

    /// As [#deferred(LearningFlowDoc, Learner, Input, String, int, String)], where the decision has more to
    /// say than the row keeps. The row holds why the stream waits, which is the same each time it is tried,
    /// not what each try found the session doing.
    private StageRun deferred(final LearningFlowDoc doc,
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

    /// The stamp written on a deferral row, identifying the transform in force: the binding's snapshot id,
    /// or, for a binding written before snapshots existed, its fragment's UUID until it is first served and
    /// snapshotted.
    private static String stamp(final Binding binding) {
        return binding.getSnapshot() != null
                ? binding.getSnapshot()
                : binding.getPipeline().getUuid();
    }

    /// Wakes the streams waiting on a learner: the deferred tasks of every filter on the learner's pipeline
    /// go back to the queue without waiting for the retry delay. The deferral rows stay until each stream is
    /// served.
    private void wake(final Learner learner) {
        wake.wake(learner.pipelineUuid());
    }

    /// Wakes the pipelines whose streams are waiting against a transform that is no longer in force. The
    /// learning job does this on every pass. A promotion wakes its learner's pipeline, but a stream judged
    /// against the old transform while the promotion was being made writes its row after that wake, and its
    /// task is only deferred after the row is written, so the promotion's wake cannot reach it. Such a row is
    /// not really waiting for anything: its next try is scored against the transform now in force. A task
    /// still running when the pass comes is not woken, and the next pass tries again. A learner whose only
    /// binding is a draft is not woken, because its streams wait for the review.
    ///
    /// Rows are read by learner and transform, not one by one, so the work grows with the number of
    /// learners waiting, not the number of streams.
    ///
    /// @return How many tasks were woken.
    public int wakeSuperseded() {
        final Set<String> pipelines = new LinkedHashSet<>();
        for (final Deferrals.Stamp waiting : deferrals.stampsWaiting()) {
            final boolean replaced = promotion.bindingFor(waiting.learner())
                    .filter(binding -> !binding.isDraft())
                    // A row with no stamp is a stream waiting for a *first* learning: there was nothing to
                    // fall short against, so anything serving the learner now is new to it. Without this,
                    // a stream deferred just after the promotion's wake would wait the full retry delay for
                    // a translation that already exists.
                    //
                    // A row that is still here is a stream not yet served (the row is removed when it is),
                    // so waking it is right rather than wasteful. Sweeping again before it runs re-wakes
                    // tasks that are already `CREATED`, which costs one update of no rows per pipeline.
                    .map(binding -> waiting.failedAgainst() == null
                                    || !waiting.failedAgainst().equals(stamp(binding)))
                    .orElse(false);
            if (replaced) {
                pipelines.add(waiting.learner().pipelineUuid());
            }
        }
        return pipelines.stream().mapToInt(wake::wake).sum();
    }

    /// How many times one stream may open a relearning session against the **same** translation before it
    /// stops and waits for a person. A translation that has not changed after this many tries will not
    /// change because the stream is retried five minutes later, and every try costs calls to the model.
    private static final int MOST_SESSIONS_PER_STREAM = 3;

    // --------------------------------------------------------------------------------

    // --------------------------------------------------------------------------------

    /// One run of a bound fragment, for a caller that intends to serve it.
    ///
    /// @param events      What the last element of the chain emitted, or null if the fragment produced
    ///                    nothing or the runner has no pipeline under it to produce events with.
    /// @param diagnostics The elements' diagnostics from the run, for the pipeline's error stream.
    public record Served(EventList events, List<StoredError> diagnostics) {

    }
}
