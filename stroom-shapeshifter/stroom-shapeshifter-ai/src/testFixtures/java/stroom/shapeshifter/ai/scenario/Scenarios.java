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

package stroom.shapeshifter.ai.scenario;

import stroom.processor.api.DeferTaskException;
import stroom.shapeshifter.ai.extraction.DataSplitterStep;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.extraction.JsonStep;
import stroom.shapeshifter.ai.extraction.NodeFixture;
import stroom.shapeshifter.ai.extraction.XmlFragmentStep;
import stroom.shapeshifter.ai.fragment.ContentStores;
import stroom.shapeshifter.ai.fragment.FragmentRunner;
import stroom.shapeshifter.ai.fragment.StandInFragmentRunner;
import stroom.shapeshifter.ai.learning.Advisor;
import stroom.shapeshifter.ai.learning.Advisors;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.scoring.BusinessRulesScorer;
import stroom.shapeshifter.ai.scoring.CompileScorer;
import stroom.shapeshifter.ai.scoring.ExtractionQualityScorer;
import stroom.shapeshifter.ai.scoring.InputCoverageScorer;
import stroom.shapeshifter.ai.scoring.Scorer;
import stroom.shapeshifter.ai.scoring.YieldScorer;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Deferred;
import stroom.shapeshifter.ai.stage.DeferredWorker;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearnerBindings;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.ai.state.InMemoryAttempts;
import stroom.shapeshifter.ai.state.InMemoryDeferrals;
import stroom.shapeshifter.ai.state.InMemoryDeletedPipelines;
import stroom.shapeshifter.ai.state.InMemoryDocuments;
import stroom.shapeshifter.ai.state.InMemoryGuidance;
import stroom.shapeshifter.ai.state.InMemoryInputs;
import stroom.shapeshifter.ai.state.InMemoryLearnerBindings;
import stroom.shapeshifter.ai.state.InMemoryLearnerStates;
import stroom.shapeshifter.ai.state.InMemoryLearners;
import stroom.shapeshifter.ai.state.InMemoryLearningPipelines;
import stroom.shapeshifter.ai.state.InMemoryOutputs;
import stroom.shapeshifter.ai.state.InMemoryPlaces;
import stroom.shapeshifter.ai.state.InMemoryRegressionSet;
import stroom.shapeshifter.ai.state.InMemorySnapshots;
import stroom.shapeshifter.ai.state.InMemorySpend;
import stroom.shapeshifter.ai.state.InMemoryWake;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

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

/**
 * What every scenario shares: the corpus, the reply resources, the step runners and scorers, the
 * in-memory stores and state, and a stage built over them under a fixed clock and seed (design 02 §7).
 * One instance per scenario; the stores and state are fresh each time.
 */
public final class Scenarios {

    /**
     * Every promotion in a scenario happens at this instant, so promotion times are assertable.
     */
    public static final Instant NOW = Instant.parse("2026-09-17T09:00:00Z");
    /// A node's lease on a session (design 04 §4), as a node's configuration defaults it.
    public static final Duration LEASE = Duration.ofMinutes(10);
    public static final long SEED = 42L;
    /// What a scenario's stage learns as (A114): one pipeline's Shapeshifter AI element. A scenario sends
    /// every feed through the one stage, as a supervising pipeline did before design 06, and everything the
    /// stage keeps is this learner's (design 06 phase J slice 2b). Its pipeline is the one a scenario's
    /// streams name, since what a learner releases is put through again by its own pipeline.
    public static final Learner LEARNER = new Learner("pipeline-1", "shapeshifterAi");

    private static final NodeFixture NODE = new NodeFixture();

    public final ContentStores stores = new ContentStores();
    public final InMemoryDeletedPipelines deletedPipelines = new InMemoryDeletedPipelines();
    public final InMemoryAttempts attempts = new InMemoryAttempts(deletedPipelines);
    public final InMemoryLearnerBindings learnerBindings = new InMemoryLearnerBindings();
    public final InMemorySpend spend = new InMemorySpend();
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
    /// The serving view of A46 over the three seams above it, as a node reads it from one query.
    public final InMemoryLearningPipelines learningPipelines = new InMemoryLearningPipelines(states, learnerBindings,
            attempts, deferrals, guidance, deletedPipelines);
    /// What brings a learner's rows to the document it runs under, over the rows above.
    public final InMemoryLearners learners = new InMemoryLearners(learnerBindings, guidance, deferrals, states);
    /// As many attempts as a scenario could open, which is what one carrying takes on.
    private static final int CARRY_AT_MOST = 100;

    /// A document as a scenario means it: **inline**, so that the stage learns in front of the test. The
    /// document's own default is deferred (A5), which is right for a node — an LLM call in a processing
    /// task holds a task slot — and wrong for a scenario about what learning does rather than about when
    /// it happens. Scenario 30, which *is* about when, says deferred for itself.
    public static ShapeshifterAiDoc.Builder document() {
        return ShapeshifterAiDoc.builder();
    }

    public List<StepRunner> runners() {
        return List.of(new DataSplitterStep(NODE.compiler()), new JsonStep(), new XmlFragmentStep(),
                new XsltStep());
    }

    public List<Scorer> scorers() {
        return List.of(new CompileScorer(), new InputCoverageScorer(), new YieldScorer(),
                NODE.schemaConformanceScorer(), new ExtractionQualityScorer(), new BusinessRulesScorer());
    }


    /**
     * A script that answers the split and target questions from the configurations it will give, so the
     * scenario states only what it is about.
     */
    public Script script(final String splitter, final String stylesheet) {
        return Script.of().structure(new Structure(runners(), splitter, stylesheet));
    }

    /// A script for input that is already XML: the split question is answered with the element that is one
    /// record, the targets from the stylesheet (A35).
    public Script xmlScript(final String recordElement, final String stylesheet) {
        return Script.of().structure(Structure.ofXml(runners(), recordElement, stylesheet));
    }

    /// A script for JSON: the split question is answered with the array's key, or root, the targets from the
    /// stylesheet over the parser's XML.
    public Script jsonScript(final String arrayKey, final String stylesheet) {
        return Script.of().structure(Structure.ofJson(runners(), arrayKey, stylesheet));
    }

    /// The node a stage says it is, for the learning lease of A42: one scenario is one node unless it says
    /// otherwise, and a scenario about two nodes meeting a learner gives each its own name.
    public String node = "node-1";

    public Stage stage(final Advisor advisor) {
        return stage(advisor, learnerBindings);
    }

    /// A stage over a given binding store: for a scenario that watches when a binding is written — the moment a
    /// second node must still be excluded (A42).
    public Stage stage(final Advisor advisor, final LearnerBindings learnerBindings) {
        return stage(document -> advisor, learnerBindings);
    }

    /// A stage over given advisors: for a scenario about a node that makes a new advisor per call, each
    /// counting its own tokens, as the node's own does (A28).
    public Stage stage(final Advisors advisors, final LearnerBindings learnerBindings) {
        return stage(advisors, learnerBindings, null);
    }

    /// A stage over a given fragment runner: for a scenario that watches *how* a written fragment is
    /// run — with which record boundary, say — rather than what it produced.
    ///
    /// @param fragmentRunner Null for the stand-in the scenarios use.
    public Stage stage(final Advisors advisors, final LearnerBindings learnerBindings,
            final FragmentRunner fragmentRunner) {
        final List<StepRunner> runners = runners();
        return new Stage(
                advisors,
                runners,
                scorers(),
                stores.writer(),
                fragmentRunner != null
                        ? fragmentRunner
                        : new StandInFragmentRunner(stores.pipelines, stores.stackLoader, stores.textConverters,
                                stores.xslts, runners),
                attempts,
                learnerBindings,
                states,
                spend,
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
                SEED,
                node);
    }

    /// The worker over this scenario's state (A5, A28), answering with a given script: the job that
    /// carries on the attempts a processing task parks. The stage is one of this scenario's,
    /// since a stage holds nothing of a stream between calls.
    public DeferredWorker worker(final Advisor answerer) {
        return new DeferredWorker(stage(answerer), attempts, documents, inputs, deletedPipelines,
                document -> answerer,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /// What production does with a stream (design 04 §3; A60, A99): the task decides — serve, defer or
    /// error, and never learn — and where it defers and opens a session, the session is carried as the
    /// worker carries it ([DeferredWorker] calls [Stage#resume]), asking the given advisor. The document
    /// and the stream are put where the worker reads them.
    ///
    /// @return What the task decided, the attempt it opened if it opened one, and what carrying that
    ///         attempt came to — the learning's own run, with its decision, verdicts and output — or the
    ///         task's decision again where nothing was carried.
    public Processed process(final Advisor advisor, final ShapeshifterAiDoc doc, final Input input) {
        return process(advisor, learnerBindings, doc, input);
    }

    /// The same over a given binding store: for a scenario that watches when a binding is written (A42).
    public Processed process(final Advisor advisor,
                             final LearnerBindings learnerBindings,
                             final ShapeshifterAiDoc doc,
                             final Input input) {
        return process(document -> advisor, learnerBindings, null, doc, input);
    }

    /// The same as another learner: a second pipeline, or a second element of one, meeting its streams
    /// (design 06 phase J slice 2b).
    public Processed process(final Advisor advisor,
                             final Learner learner,
                             final ShapeshifterAiDoc doc,
                             final Input input) {
        return process(document -> advisor, learnerBindings, null, learner, doc, input);
    }

    /// The same over given advisors, binding store and fragment runner, as [#stage(Advisors, LearnerBindings,
    /// FragmentRunner)] builds a stage over them; a null runner is the scenario's own.
    public Processed process(final Advisors advisors,
                             final LearnerBindings learnerBindings,
                             final FragmentRunner fragmentRunner,
                             final ShapeshifterAiDoc doc,
                             final Input input) {
        return process(advisors, learnerBindings, fragmentRunner, LEARNER, doc, input);
    }

    private Processed process(final Advisors advisors,
                              final LearnerBindings learnerBindings,
                              final FragmentRunner fragmentRunner,
                              final Learner learner,
                              final ShapeshifterAiDoc doc,
                              final Input input) {
        documents.put(doc);
        inputs.put(input);
        final Stage stage = stage(advisors, learnerBindings, fragmentRunner);
        final StageRun first = stage.decide(doc, learner, input);
        // A bound stream is served as it is read, by the incumbent where its learner is being
        // relearned (A29, A100).
        final TextSink servedAtOnce = served(stage, learner, doc, input, first);
        final Recorded opened = attempts.forDocument(doc.getUuid(), CARRY_AT_MOST).stream()
                .filter(recorded -> Objects.equals(recorded.attempt().inputId(), input.id()))
                .filter(recorded -> recorded.status() == AttemptStatus.AWAITING_MODEL)
                .max(Comparator.comparingLong(Recorded::id))
                .orElse(null);
        if (opened == null) {
            // Nothing opened on this stream: deferred on another stream's session, with learning off, or
            // served with nothing to learn. The run returned carries what the stream was served as, so
            // that what a scenario reads off a bound stream is what the task wrote.
            final StageRun run = servedAtOnce == null
                    ? first
                    : new StageRun(first.doc(), first.decision(), first.learner(), first.produced(),
                            servedAtOnce.text(), first.verdicts(), first.transcript(), first.events(),
                            first.diagnostics());
            return new Processed(first, null, run, servedAtOnce);
        }
        final StageRun learned = stage.resume(doc, opened.id(), input, advisors.of(doc));
        // What the wake makes of a stream that waited (design 04 §5.3): its task decides again, and a
        // stream now bound is served.
        final TextSink served = servedAtOnce != null || learned.produced() == null
                ? servedAtOnce
                : served(stage, learner, doc, input, stage.decide(doc, learner, input));
        return new Processed(first, attempts.byId(opened.id()).orElseThrow(), learned, served);
    }

    private static TextSink served(final Stage stage,
                                   final Learner learner,
                                   final ShapeshifterAiDoc doc,
                                   final Input input,
                                   final StageRun decided) {
        if (!(decided.decision() instanceof final Bound bound)) {
            return null;
        }
        final TextSink sink = new TextSink();
        try {
            stage.stream(doc, learner, bound.binding(), input, sink);
        } catch (final DeferTaskException e) {
            // A record short of the bar: the stream waits on the session that opened, as a task would.
            return null;
        }
        return sink;
    }

    /// A stream served as a task serves a bound one (design 04 §3): decided, and streamed through
    /// the bound fragment into a sink. Null where the task does not bind it.
    public TextSink served(final ShapeshifterAiDoc doc, final Input input) {
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

    /**
     * A reply resource from this package: the stylesheets and expected outputs scenarios are built on.
     */
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

    /// The same events however they are spaced: a transform run one record at a time writes the same
    /// events as one run over the whole document (§12 item 25), and what lies between them is the
    /// serialiser's business and not the scenario's.
    public static String canonical(final String xml) {
        if (xml == null) {
            return null;
        }
        // The declaration goes, the space between elements goes, and the space inside a start tag — which
        // is where a stylesheet's own line breaks put its attributes — becomes one space.
        final String tagsNormalised = Pattern.compile("<[^>]*>")
                .matcher(xml)
                .replaceAll(match -> Matcher.quoteReplacement(match.group().replaceAll("\\s+", " ")));
        return tagsNormalised.replaceAll("<\\?xml[^>]*\\?>", "").replaceAll(">\\s+<", "><").strip();
    }

    /**
     * A model reply as the grammar of {@code ConfigurationReply} wants it: one fenced block.
     */
    public static String fenced(final String document) {
        return "Here is the configuration:\n```xml\n" + document + "\n```\n";
    }


    // --------------------------------------------------------------------------------


    /// A stream processed as production processes it: what the task decided, the attempt it opened
    /// where it opened one, what carrying that attempt came to, and what the stream was served as —
    /// at once where it was bound, or once the wake made its task decide again — or null where it
    /// was not served.
    public record Processed(StageRun first, Recorded attempt, StageRun learned, TextSink served) {

        /// The events the stream was served as, or null where it was not served.
        public String output() {
            return served == null
                    ? null
                    : served.text();
        }

        /// The status the attempt came to, or null where the stream opened none.
        public AttemptStatus status() {
            return attempt == null
                    ? null
                    : attempt.status();
        }
    }
}
