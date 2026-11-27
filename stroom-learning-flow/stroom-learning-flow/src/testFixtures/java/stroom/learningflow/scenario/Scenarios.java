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

package stroom.learningflow.scenario;

import stroom.learningflow.extraction.DataSplitterStep;
import stroom.learningflow.extraction.ExtractionCorpus;
import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.extraction.JsonStep;
import stroom.learningflow.extraction.NodeFixture;
import stroom.learningflow.extraction.XmlFragmentStep;
import stroom.learningflow.fragment.ContentStores;
import stroom.learningflow.fragment.FragmentRunner;
import stroom.learningflow.fragment.StandInFragmentRunner;
import stroom.learningflow.learning.Advisor;
import stroom.learningflow.learning.Advisors;
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.learning.StepRunner;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.scoring.BusinessRulesScorer;
import stroom.learningflow.scoring.CompileScorer;
import stroom.learningflow.scoring.CompletenessScorer;
import stroom.learningflow.scoring.ExtractionQualityScorer;
import stroom.learningflow.scoring.InputCoverageScorer;
import stroom.learningflow.scoring.LiteralsScorer;
import stroom.learningflow.scoring.PermittedScorer;
import stroom.learningflow.scoring.Scorer;
import stroom.learningflow.scoring.TimesScorer;
import stroom.learningflow.scoring.YieldScorer;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.Deferred;
import stroom.learningflow.stage.DeferredWorker;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerBindings;
import stroom.learningflow.stage.Promotion;
import stroom.learningflow.stage.SpendBreaker;
import stroom.learningflow.stage.Stage;
import stroom.learningflow.stage.StageRun;
import stroom.learningflow.stage.StreamHeld;
import stroom.learningflow.stage.SupervisorActions;
import stroom.learningflow.state.InMemoryAttempts;
import stroom.learningflow.state.InMemoryDeferrals;
import stroom.learningflow.state.InMemoryDeletedPipelines;
import stroom.learningflow.state.InMemoryDocuments;
import stroom.learningflow.state.InMemoryGuidance;
import stroom.learningflow.state.InMemoryInputs;
import stroom.learningflow.state.InMemoryLearnerBindings;
import stroom.learningflow.state.InMemoryLearnerStates;
import stroom.learningflow.state.InMemoryLearners;
import stroom.learningflow.state.InMemoryLearningPipelines;
import stroom.learningflow.state.InMemoryOutputs;
import stroom.learningflow.state.InMemoryPlaces;
import stroom.learningflow.state.InMemoryRegressionSet;
import stroom.learningflow.state.InMemorySnapshots;
import stroom.learningflow.state.InMemorySpend;
import stroom.learningflow.state.InMemoryWake;
import stroom.learningflow.transformation.StandInXsltStep;
import stroom.processor.api.DeferTaskException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// The shared harness for the in-memory scenario tests: the corpus, the reply resources, the step runners
/// and scorers, the in-memory stores and state, and a [Stage] built over them with a fixed clock.
///
/// Each scenario creates its own instance, so the stores and state are new each time.
public final class Scenarios {

    /// The fixed time of the scenario clock, so that promotion times can be asserted.
    public static final Instant NOW = Instant.parse("2026-09-17T09:00:00Z");
    /// How long a node's claim on a session lasts unless renewed, as a node's configuration defaults it.
    public static final Duration LEASE = Duration.ofMinutes(10);
    /// The learner a scenario's stage learns as: one pipeline's Smart Pipeline element. A scenario sends
    /// every feed through this one learner, so everything the stage keeps belongs to it. Its pipeline is
    /// the one the scenario's streams name, since a learner's released streams are processed again by its
    /// own pipeline.
    public static final Learner LEARNER = new Learner("pipeline-1", "learningFlow");

    private static final NodeFixture NODE = new NodeFixture();

    public final ContentStores stores = new ContentStores();
    /// Target schemas backed by the node fixture's schema store. A scenario's document names no target
    /// schema, so the conformance check falls back to the schema group. A scenario about the target sets
    /// `targetSchema` to [NodeFixture#eventLoggingSchemaRef()].
    public final TargetSchemas targetSchemas = NODE.targetSchemas();
    public final InMemoryDeletedPipelines deletedPipelines = new InMemoryDeletedPipelines();
    public final InMemoryAttempts attempts = new InMemoryAttempts(deletedPipelines);
    public final InMemoryLearnerBindings learnerBindings = new InMemoryLearnerBindings();
    public final InMemorySpend spend = new InMemorySpend();
    /// The spend breaker, which stops learning when spending passes its limits. It is off by default; a
    /// scenario that wants it sets its limits through the document, or replaces this.
    public SpendBreaker breaker = new SpendBreaker(spend, 0L, 0);
    public final InMemoryLearnerStates states = new InMemoryLearnerStates();
    public final InMemoryGuidance guidance = new InMemoryGuidance();
    public final InMemoryOutputs outputs = new InMemoryOutputs();
    public final InMemoryRegressionSet regressionSet = new InMemoryRegressionSet();
    public final InMemoryDeferrals deferrals = new InMemoryDeferrals();
    public final InMemoryWake wake = new InMemoryWake(deferrals);
    public final InMemoryPlaces places = new InMemoryPlaces();
    public final InMemorySnapshots snapshots = new InMemorySnapshots();
    public final InMemoryDocuments documents = new InMemoryDocuments();
    public final InMemoryInputs inputs = new InMemoryInputs();
    /// The Supervisor's list of learning pipelines, built from the in-memory state above, as a node reads
    /// it with one query.
    public final InMemoryLearningPipelines learningPipelines = new InMemoryLearningPipelines(states, learnerBindings,
            attempts, deferrals, guidance, deletedPipelines);
    /// Updates a learner's rows above to the document it runs under.
    public final InMemoryLearners learners = new InMemoryLearners(learnerBindings, guidance, deferrals, states);
    /// More attempts than a scenario could open, used as the limit when looking for the attempt a stream
    /// opened.
    private static final int CARRY_AT_MOST = 100;

    /// Returns a document builder set up for a scenario.
    ///
    /// **Promotion is automatic, whereas the product's default is review.** A scenario is about the
    /// learning machinery (what is asked, what is judged, what is bound), and under review every scenario
    /// would stop at a draft waiting for a person. Only the scenarios about review need that, and they set
    /// it themselves.
    ///
    /// **The flow has the `Direct` example's steps**, because a document built in shared code has no
    /// steps. The built-in flows are JSON on the server, and the document store gives a new document its
    /// steps; a scenario does not go through the store, so the harness gives it the flow the store would.
    /// A scenario about another flow sets it.
    ///
    /// @return The builder.
    public static LearningFlowDoc.Builder document() {
        return LearningFlowDoc.builder()
                .promotionMode(PromotionMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.DIRECT));
    }

    public List<StepRunner> runners() {
        return List.of(new DataSplitterStep(NODE.compiler()), new JsonStep(), new XmlFragmentStep(),
                new StandInXsltStep());
    }

    public List<Scorer> scorers() {
        return List.of(new CompileScorer(), new PermittedScorer(), new InputCoverageScorer(),
                new CompletenessScorer(),
                new YieldScorer(), NODE.schemaConformanceScorer(), new ExtractionQualityScorer(),
                new BusinessRulesScorer(), new TimesScorer(), new LiteralsScorer());
    }


    /// Returns a script that answers the split and target questions from the configurations it will
    /// give, so that the scenario states only what it is about.
    public Script script(final String splitter, final String stylesheet) {
        return Script.of().structure(new Structure(runners(), splitter, stylesheet));
    }

    /// Returns a script for input that is already XML: the split question is answered with the element
    /// that is one record, and the target questions from the stylesheet.
    public Script xmlScript(final String recordElement, final String stylesheet) {
        return Script.of().structure(Structure.ofXml(runners(), recordElement, stylesheet));
    }

    /// Returns a script for JSON input: the split question is answered with the array's key (or the root),
    /// and the target questions from the stylesheet run over the parser's XML.
    public Script jsonScript(final String arrayKey, final String stylesheet) {
        return Script.of().structure(Structure.ofJson(runners(), arrayKey, stylesheet));
    }

    /// The node name the stage claims sessions under. A scenario is one node unless it says otherwise; a
    /// scenario about two nodes processing one learner's streams gives each its own name.
    public String node = "node-1";

    public Stage stage(final Advisor advisor) {
        return stage(advisor, learnerBindings);
    }

    /// Returns a stage over a given binding store, for a scenario that watches when a binding is written,
    /// which is a moment when a second node must still be kept out.
    public Stage stage(final Advisor advisor, final LearnerBindings learnerBindings) {
        return stage(document -> advisor, learnerBindings);
    }

    /// Returns a stage over given advisors, for a scenario about a node that makes a new advisor per call,
    /// each counting its own tokens, as a real node does.
    public Stage stage(final Advisors advisors, final LearnerBindings learnerBindings) {
        return stage(advisors, learnerBindings, null);
    }

    /// Returns a stage over a given fragment runner, for a scenario that checks how a written fragment is
    /// run (with which record boundary, for example) rather than what it produced.
    ///
    /// @param fragmentRunner Null for the stand-in the scenarios use.
    public Stage stage(final Advisors advisors, final LearnerBindings learnerBindings,
            final FragmentRunner fragmentRunner) {
        final List<StepRunner> runners = runners();
        return new Stage(
                advisors,
                runners,
                scorers(),
                targetSchemas,
                stores.writer(),
                fragmentRunner != null
                        ? fragmentRunner
                        : new StandInFragmentRunner(stores.pipelines, stores.stackLoader, stores.textConverters,
                                stores.xslts, runners),
                attempts,
                learnerBindings,
                states,
                spend,
                // No spending limits in the harness unless a scenario sets them, since a scenario may
                // legitimately ask the model a hundred times.
                breaker,
                guidance,
                outputs,
                regressionSet,
                deferrals,
                learners,
                wake,
                places,
                snapshots,
                stores.codes(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                LEASE,
                node,
                // The Stroom build is not known in a test. It is only used to warn, when a prompt is
                // rebuilt after an upgrade, that the wording may have changed.
                null);
    }

    /// Returns the promotion actions (approve, reject and retract) over this scenario's state, as the
    /// Supervisor gets them from a node's stage factory.
    ///
    /// @return The promotion actions, using the scenario's clock.
    public Promotion promotion() {
        return new Promotion(learnerBindings, states, regressionSet, snapshots, outputs, attempts, stores.writer(),
                stores.codes(), places, wake, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /// Returns the actions a person can take on a session or a learner (grant, exhaust, amend, relearn and
    /// give up) over this scenario's state, as the Supervisor gets them from a node's stage factory.
    ///
    /// @return The actions, using the scenario's clock and lease.
    public SupervisorActions supervisorActions() {
        return new SupervisorActions(attempts, learnerBindings, states, regressionSet, snapshots, targetSchemas,
                wake, Clock.fixed(NOW, ZoneOffset.UTC), LEASE);
    }

    /// Returns the background worker over this scenario's state, answering with a given advisor. The
    /// worker is the job that carries on the attempts that processing tasks leave waiting for the model.
    /// It uses a new stage from this scenario, since a stage holds nothing about a stream between calls.
    public DeferredWorker worker(final Advisor answerer) {
        return new DeferredWorker(stage(answerer), attempts, documents, inputs, StreamHeld.NONE, deletedPipelines,
                document -> answerer,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /// Runs a person's *Improve* request, then carries it on as the worker would.
    ///
    /// The request opens an attempt and leaves it waiting at its first question, because a model
    /// conversation takes minutes and does not belong in a web request; the worker then asks the
    /// questions. This does both, the same way the worker does: the attempt records which binding it is
    /// improving, and its sample is that binding's accepted records put back together, rather than a
    /// stream.
    ///
    /// @param advisors    Who answers the questions.
    /// @param doc         The document.
    /// @param bindingUuid The binding to improve.
    /// @param message     What the person wants improved, or null.
    /// @param by          Who asked.
    /// @return The result of carrying on the improvement.
    public StageRun improved(final Advisors advisors,
                             final LearningFlowDoc doc,
                             final String bindingUuid,
                             final String message,
                             final String by) {
        stage(advisors, learnerBindings).improve(doc, bindingUuid, message, by);
        final Recorded attempt = attempts.open(LEARNER, NOW.toEpochMilli())
                .orElseThrow(() -> new IllegalStateException("Improve opened no attempt"));
        final Stage carrying = stage(advisors, learnerBindings);
        return carrying.resume(doc, attempt.id(),
                carrying.improvementInput(attempt.attempt())
                        .orElseThrow(() -> new IllegalStateException("The improvement has no sample")),
                advisors.of(doc, attempt.attempt().askedWith()));
    }

    /// Processes a stream as production does. The processing task decides to serve, defer or fail the
    /// stream, and never learns itself. If it defers the stream and opens a session, the session is then
    /// carried on as the worker would ([DeferredWorker] calls [Stage#resume]), asking the given advisor.
    /// The document and the stream are stored where the worker reads them.
    ///
    /// @return What the task decided, the attempt it opened if any, and the result of carrying on that
    ///         attempt (the learning run, with its decision, verdicts and output), or the task's decision
    ///         again if nothing was carried on.
    public Processed process(final Advisor advisor, final LearningFlowDoc doc, final Input input) {
        return process(advisor, learnerBindings, doc, input);
    }

    /// The same over a given binding store, for a scenario that watches when a binding is written.
    public Processed process(final Advisor advisor,
                             final LearnerBindings learnerBindings,
                             final LearningFlowDoc doc,
                             final Input input) {
        return process(document -> advisor, learnerBindings, null, doc, input);
    }

    /// The same as another learner: a second pipeline, or a second element of one pipeline, processing
    /// its streams.
    public Processed process(final Advisor advisor,
                             final Learner learner,
                             final LearningFlowDoc doc,
                             final Input input) {
        return process(document -> advisor, learnerBindings, null, learner, doc, input);
    }

    /// The same over given advisors, binding store and fragment runner, as [#stage(Advisors, LearnerBindings,
    /// FragmentRunner)] builds a stage over them; a null runner means the scenario's own.
    public Processed process(final Advisors advisors,
                             final LearnerBindings learnerBindings,
                             final FragmentRunner fragmentRunner,
                             final LearningFlowDoc doc,
                             final Input input) {
        return process(advisors, learnerBindings, fragmentRunner, LEARNER, doc, input);
    }

    private Processed process(final Advisors advisors,
                              final LearnerBindings learnerBindings,
                              final FragmentRunner fragmentRunner,
                              final Learner learner,
                              final LearningFlowDoc doc,
                              final Input input) {
        documents.put(doc);
        inputs.put(input);
        final Stage stage = stage(advisors, learnerBindings, fragmentRunner);
        final StageRun first = stage.decide(doc, learner, input);
        // A bound stream is served straight away, by the current binding even while its learner is
        // learning again.
        final TextSink servedAtOnce = served(stage, learner, doc, input, first);
        final Recorded opened = attempts.forDocument(doc.getUuid(), CARRY_AT_MOST).stream()
                .filter(recorded -> Objects.equals(recorded.attempt().inputId(), input.id()))
                .filter(recorded -> recorded.status() == AttemptStatus.AWAITING_MODEL)
                .max(Comparator.comparingLong(Recorded::id))
                .orElse(null);
        if (opened == null) {
            // No session was opened for this stream: it was deferred behind another stream's session,
            // learning is off, or it was served with nothing to learn. The returned run carries what the
            // stream was served as, so that what a scenario reads from a bound stream is what the task
            // wrote.
            final StageRun run = servedAtOnce == null
                    ? first
                    : new StageRun(first.doc(), first.decision(), first.learner(), first.produced(),
                            servedAtOnce.text(), first.verdicts(), first.transcript(), first.events(),
                            first.diagnostics());
            return new Processed(first, null, run, servedAtOnce);
        }
        final StageRun learned = stage.resume(doc, opened.id(), input, advisors.of(doc));
        // What happens when a waiting stream is woken: its task decides again, and if the stream is now
        // bound, it is served.
        final TextSink served = servedAtOnce != null || learned.produced() == null
                ? servedAtOnce
                : served(stage, learner, doc, input, stage.decide(doc, learner, input));
        return new Processed(first, attempts.byId(opened.id()).orElseThrow(), learned, served);
    }

    private static TextSink served(final Stage stage,
                                   final Learner learner,
                                   final LearningFlowDoc doc,
                                   final Input input,
                                   final StageRun decided) {
        if (!(decided.decision() instanceof final Bound bound)) {
            return null;
        }
        final TextSink sink = new TextSink();
        try {
            stage.stream(doc, learner, bound.binding(), input, sink);
        } catch (final DeferTaskException e) {
            // A record fell short of the bar, so the stream waits on the session that was opened, as a
            // task's stream would.
            return null;
        }
        return sink;
    }

    /// Serves a stream as a task serves a bound one: the task decides, and the stream is run through the
    /// bound fragment into a sink. Returns null if the task does not bind the stream.
    public TextSink served(final LearningFlowDoc doc, final Input input) {
        inputs.put(input);
        final Stage stage = stage(Script.of());
        return served(stage, LEARNER, doc, input, stage.decide(doc, LEARNER, input));
    }

    public static Golden corpus(final String stem) {
        return ExtractionCorpus.goldens().stream()
                .filter(golden -> golden.stem().equals(stem))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No golden corpus case " + stem));
    }

    public static ExtractionCorpus.Failing failingCorpus(final String stem) {
        return ExtractionCorpus.failing().stream()
                .filter(failing -> failing.stem().equals(stem))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No failing corpus case " + stem));
    }

    /// Reads a resource from this package: the stylesheets, inputs and expected outputs that scenarios
    /// are built on.
    public static String resource(final String name) {
        try (InputStream in = Scenarios.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing scenario resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Normalises XML whitespace so that the same events compare equal however they are spaced. A
    /// transform run one record at a time writes the same events as one run over the whole document, but
    /// the whitespace between them depends on the serialiser, which is not what a scenario tests.
    public static String canonical(final String xml) {
        if (xml == null) {
            return null;
        }
        // The XML declaration and the whitespace between elements are removed, and whitespace inside a
        // tag (where a stylesheet's line breaks between attributes end up) becomes one space.
        final String tagsNormalised = Pattern.compile("<[^>]*>")
                .matcher(xml)
                .replaceAll(match -> Matcher.quoteReplacement(match.group().replaceAll("\\s+", " ")));
        return tagsNormalised.replaceAll("<\\?xml[^>]*\\?>", "").replaceAll(">\\s+<", "><").strip();
    }

    /// Wraps a document as a model reply in the form `ConfigurationReply` expects: one fenced block.
    public static String fenced(final String document) {
        return "Here is the configuration:\n```xml\n" + document + "\n```\n";
    }


    // --------------------------------------------------------------------------------


    /// A stream processed as production processes it: what the task decided, the attempt it opened (if
    /// any), the result of carrying on that attempt, and what the stream was served as (straight away if it
    /// was bound, or after the wake made its task decide again), or null if it was not served.
    public record Processed(StageRun first, Recorded attempt, StageRun learned, TextSink served) {

        /// The events the stream was served as, or null if it was not served.
        public String output() {
            return served == null
                    ? null
                    : served.text();
        }

        /// The attempt's final status, or null if the stream opened no attempt.
        public AttemptStatus status() {
            return attempt == null
                    ? null
                    : attempt.status();
        }
    }
}
