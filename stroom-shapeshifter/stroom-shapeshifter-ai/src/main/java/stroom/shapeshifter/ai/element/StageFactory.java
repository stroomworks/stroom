/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.ai.element;

import stroom.node.api.NodeInfo;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.factory.ElementRegistryFactory;
import stroom.pipeline.factory.InjectedCode;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.factory.PipelineFactory;
import stroom.pipeline.stepping.capture.HeadlessCapture;
import stroom.shapeshifter.ai.ShapeshifterAiConfig;
import stroom.shapeshifter.ai.extraction.DataSplitterCompiler;
import stroom.shapeshifter.ai.extraction.DataSplitterStep;
import stroom.shapeshifter.ai.extraction.JsonStep;
import stroom.shapeshifter.ai.extraction.XmlFragmentStep;
import stroom.shapeshifter.ai.fragment.FragmentCodes;
import stroom.shapeshifter.ai.fragment.FragmentOutput;
import stroom.shapeshifter.ai.fragment.FragmentTap;
import stroom.shapeshifter.ai.fragment.FragmentWriter;
import stroom.shapeshifter.ai.fragment.PipelineFragmentRunner;
import stroom.shapeshifter.ai.learning.Advisors;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.scoring.BusinessRulesScorer;
import stroom.shapeshifter.ai.scoring.CompileScorer;
import stroom.shapeshifter.ai.scoring.ExtractionQualityScorer;
import stroom.shapeshifter.ai.scoring.InputCoverageScorer;
import stroom.shapeshifter.ai.scoring.SchemaConformanceScorer;
import stroom.shapeshifter.ai.scoring.Scorer;
import stroom.shapeshifter.ai.scoring.YieldScorer;
import stroom.shapeshifter.ai.stage.Attempts;
import stroom.shapeshifter.ai.stage.Deferrals;
import stroom.shapeshifter.ai.stage.Guidance;
import stroom.shapeshifter.ai.stage.LearnerBindings;
import stroom.shapeshifter.ai.stage.LearnerStates;
import stroom.shapeshifter.ai.stage.Learners;
import stroom.shapeshifter.ai.stage.Outputs;
import stroom.shapeshifter.ai.stage.Places;
import stroom.shapeshifter.ai.stage.RegressionSet;
import stroom.shapeshifter.ai.stage.Snapshots;
import stroom.shapeshifter.ai.stage.Spend;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.Wake;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.task.api.TaskContextFactory;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/// A stage over this node's state, wherever one is wanted: in a pipeline task, where the supervisor
/// element runs it over the stream in front of it, and in the worker (A5), which runs it
/// over an attempt that stopped. A stage holds nothing between calls, so one is as good as many; what it
/// is built from — the element runners, the scorers, the fragment writer and runner, and the runtime
/// state of A26 — is the same in either place, and a second copy of this list in the worker would be a
/// second place to forget a scorer.
///
/// Not a singleton, and not to be held: two of the things a stage runs on — the Data Splitter compiler
/// and the schema scorer — collect their diagnostics through the pipeline-scoped `ErrorReceiverProxy`,
/// so a stage belongs to one pipeline scope. The element is inside one already; the worker enters one
/// for each attempt it carries on ([DeferredLearning]).
public class StageFactory {

    private final Advisors advisors;
    private final DataSplitterCompiler dataSplitterCompiler;
    private final SchemaConformanceScorer schemaConformanceScorer;
    private final FragmentWriter fragmentWriter;
    private final PipelineStore pipelineStore;
    private final PipelineDataCache pipelineDataCache;
    private final ElementRegistryFactory elementRegistryFactory;
    private final Provider<PipelineFactory> pipelineFactoryProvider;
    private final Provider<HeadlessCapture> headlessCaptureProvider;
    private final Provider<ErrorReceiverProxy> errorReceiverProvider;
    private final Provider<InjectedCode> injectedCodeProvider;
    private final Provider<FragmentOutput> fragmentOutputProvider;
    private final Provider<FragmentTap> fragmentTapProvider;
    private final TaskContextFactory taskContextFactory;
    private final NodeInfo nodeInfo;
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
    private final Provider<ShapeshifterAiConfig> configProvider;

    @Inject
    public StageFactory(final Advisors advisors,
                        final DataSplitterCompiler dataSplitterCompiler,
                        final SchemaConformanceScorer schemaConformanceScorer,
                        final FragmentWriter fragmentWriter,
                        final PipelineStore pipelineStore,
                        final PipelineDataCache pipelineDataCache,
                        final ElementRegistryFactory elementRegistryFactory,
                        final Provider<PipelineFactory> pipelineFactoryProvider,
                        final Provider<HeadlessCapture> headlessCaptureProvider,
                        final Provider<ErrorReceiverProxy> errorReceiverProvider,
                        final Provider<InjectedCode> injectedCodeProvider,
                        final Provider<FragmentOutput> fragmentOutputProvider,
                        final Provider<FragmentTap> fragmentTapProvider,
                        final TaskContextFactory taskContextFactory,
                        final NodeInfo nodeInfo,
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
                        final Provider<ShapeshifterAiConfig> configProvider) {
        this.advisors = advisors;
        this.dataSplitterCompiler = dataSplitterCompiler;
        this.schemaConformanceScorer = schemaConformanceScorer;
        this.fragmentWriter = fragmentWriter;
        this.pipelineStore = pipelineStore;
        this.pipelineDataCache = pipelineDataCache;
        this.elementRegistryFactory = elementRegistryFactory;
        this.pipelineFactoryProvider = pipelineFactoryProvider;
        this.headlessCaptureProvider = headlessCaptureProvider;
        this.errorReceiverProvider = errorReceiverProvider;
        this.injectedCodeProvider = injectedCodeProvider;
        this.fragmentOutputProvider = fragmentOutputProvider;
        this.fragmentTapProvider = fragmentTapProvider;
        this.taskContextFactory = taskContextFactory;
        this.nodeInfo = nodeInfo;
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
        this.configProvider = configProvider;
    }

    /// A stage on this node, under the wall clock and a fresh seed for the held-out split (A14).
    public Stage create() {
        // A node judges a candidate with the element that will run it (§12 item 2) — but only where the
        // module re-implements one. The parsers here already drive stroom's own factories: the Data
        // Splitter's DS3 factory, the JSON reader, stroom's XML reader, and they additionally report
        // what each record consumed, which input coverage (A11) is scored on and a pipeline does not
        // tell anyone. The transform is the one place the module drives Saxon itself — its own
        // configuration, no pool, no function library — so it is the one wrapped.
        final List<StepRunner> runners = List.of(
                new DataSplitterStep(dataSplitterCompiler),
                new JsonStep(),
                new XmlFragmentStep(),
                new PipelineStepRunner(new XsltStep(), pipelineFactoryProvider, headlessCaptureProvider,
                        injectedCodeProvider, errorReceiverProvider, taskContextFactory));
        final List<Scorer> scorers = List.of(
                new CompileScorer(), new InputCoverageScorer(), new YieldScorer(), schemaConformanceScorer,
                new ExtractionQualityScorer(), new BusinessRulesScorer());
        return new Stage(
                advisors,
                runners,
                scorers,
                fragmentWriter,
                // A node judges and serves through the real pipeline (§12 item 2): the stand-in the Tier 1
                // scenarios run on is for a harness with no node under it.
                new PipelineFragmentRunner(pipelineStore, pipelineDataCache, elementRegistryFactory,
                        pipelineFactoryProvider,
                        headlessCaptureProvider, errorReceiverProvider, fragmentOutputProvider, fragmentTapProvider,
                        injectedCodeProvider, taskContextFactory),
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
                codes,
                Clock.systemUTC(),
                configProvider.get().getSessionLease().getDuration(),
                ThreadLocalRandom.current().nextLong(),
                nodeInfo.getThisNodeName());
    }
}
