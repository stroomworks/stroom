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

package stroom.learningflow.element;

import stroom.learningflow.LearningFlowConfig;
import stroom.learningflow.extraction.DataSplitterCompiler;
import stroom.learningflow.extraction.DataSplitterStep;
import stroom.learningflow.extraction.JsonStep;
import stroom.learningflow.extraction.XmlFragmentStep;
import stroom.learningflow.fragment.FragmentCodes;
import stroom.learningflow.fragment.FragmentOutput;
import stroom.learningflow.fragment.FragmentTap;
import stroom.learningflow.fragment.FragmentWriter;
import stroom.learningflow.fragment.PipelineFragmentRunner;
import stroom.learningflow.learning.Advisors;
import stroom.learningflow.learning.StepRunner;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.scoring.BusinessRulesScorer;
import stroom.learningflow.scoring.CompileScorer;
import stroom.learningflow.scoring.CompletenessScorer;
import stroom.learningflow.scoring.ExtractionQualityScorer;
import stroom.learningflow.scoring.InputCoverageScorer;
import stroom.learningflow.scoring.LiteralsScorer;
import stroom.learningflow.scoring.PermittedScorer;
import stroom.learningflow.scoring.SchemaConformanceScorer;
import stroom.learningflow.scoring.Scorer;
import stroom.learningflow.scoring.TimesScorer;
import stroom.learningflow.scoring.YieldScorer;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.stage.Attempts;
import stroom.learningflow.stage.Deferrals;
import stroom.learningflow.stage.Guidance;
import stroom.learningflow.stage.LearnerBindings;
import stroom.learningflow.stage.LearnerStates;
import stroom.learningflow.stage.Learners;
import stroom.learningflow.stage.Outputs;
import stroom.learningflow.stage.Places;
import stroom.learningflow.stage.Promotion;
import stroom.learningflow.stage.RegressionSet;
import stroom.learningflow.stage.Snapshots;
import stroom.learningflow.stage.Spend;
import stroom.learningflow.stage.SpendBreaker;
import stroom.learningflow.stage.Stage;
import stroom.learningflow.stage.SupervisorActions;
import stroom.learningflow.stage.Wake;
import stroom.learningflow.transformation.XsltStep;
import stroom.node.api.NodeInfo;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.factory.ElementRegistryFactory;
import stroom.pipeline.factory.InjectedCode;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.factory.PipelineFactory;
import stroom.pipeline.stepping.capture.HeadlessCapture;
import stroom.task.api.TaskContextFactory;
import stroom.util.shared.BuildInfo;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.time.Clock;
import java.util.List;

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
    // Providers, not instances: both report through the pipeline-scoped ErrorReceiverProxy, so they can only
    // be made inside a pipeline scope, and the breaker, promotion and supervisor actions are asked for outside one.
    private final Provider<DataSplitterCompiler> dataSplitterCompilerProvider;
    private final Provider<SchemaConformanceScorer> schemaConformanceScorerProvider;
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
    private final Provider<BuildInfo> buildInfoProvider;
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
    private final TargetSchemas targetSchemas;
    private final Snapshots snapshots;
    private final FragmentCodes codes;
    private final Provider<LearningFlowConfig> configProvider;

    @Inject
    public StageFactory(final Advisors advisors,
                        final Provider<DataSplitterCompiler> dataSplitterCompilerProvider,
                        final Provider<SchemaConformanceScorer> schemaConformanceScorerProvider,
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
                        final Provider<BuildInfo> buildInfoProvider,
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
                        final TargetSchemas targetSchemas,
                        final Snapshots snapshots,
                        final FragmentCodes codes,
                        final Provider<LearningFlowConfig> configProvider) {
        this.advisors = advisors;
        this.dataSplitterCompilerProvider = dataSplitterCompilerProvider;
        this.schemaConformanceScorerProvider = schemaConformanceScorerProvider;
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
        this.buildInfoProvider = buildInfoProvider;
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
        this.targetSchemas = targetSchemas;
        this.snapshots = snapshots;
        this.codes = codes;
        this.configProvider = configProvider;
    }

    /// The spend breaker (A24), over this node's configured cluster ceilings: what [#create()] gives a stage,
    /// and what the Supervisor asks to say which Smart Pipelines can't learn now (Track 2 step 4).
    ///
    /// @return The breaker.
    public SpendBreaker breaker() {
        return new SpendBreaker(spend, configProvider.get().getSpendTokensPerHour(),
                configProvider.get().getSpendCallsPerHour());
    }

    /// Promotion on this node (simplification option iii): approve, reject and retract, which run no
    /// pipeline and so need no pipeline scope, unlike a stage.
    ///
    /// @return It, under the wall clock.
    public Promotion promotion() {
        return new Promotion(learnerBindings, states, regressionSet, snapshots, outputs, attempts, fragmentWriter,
                codes, places, wake, Clock.systemUTC());
    }

    /// A person's actions on a run or a learner on this node (simplification option iii): grant, exhaust,
    /// amend, relearn and give up, which run no pipeline and so need no pipeline scope.
    ///
    /// @return Them, under the wall clock and this node's lease.
    public SupervisorActions supervisorActions() {
        return new SupervisorActions(attempts, learnerBindings, states, regressionSet, snapshots, targetSchemas,
                wake, Clock.systemUTC(), configProvider.get().getSessionLease().getDuration());
    }

    /// A stage on this node, under the wall clock. Only to be called inside a pipeline scope: the Data Splitter
    /// compiler and the schema scorer it is built from report through the scoped error receiver.
    public Stage create() {
        // A node judges a candidate with the element that will run it (§12 item 2) — but only where the
        // module re-implements one. The parsers here already drive stroom's own factories: the Data
        // Splitter's DS3 factory, the JSON reader, stroom's XML reader, and they additionally report
        // what each record consumed, which input coverage (A11) is scored on and a pipeline does not
        // tell anyone. The transform is the one place the module drives Saxon itself — its own
        // configuration, no pool, no function library — so it is the one wrapped.
        final List<StepRunner> runners = runners(dataSplitterCompilerProvider.get(), pipelineFactoryProvider,
                headlessCaptureProvider, injectedCodeProvider, errorReceiverProvider, taskContextFactory);
        final List<Scorer> scorers = List.of(
                new CompileScorer(), new PermittedScorer(), new InputCoverageScorer(),
                new CompletenessScorer(), new YieldScorer(),
                new TimesScorer(), new LiteralsScorer(),
                schemaConformanceScorerProvider.get(),
                new ExtractionQualityScorer(), new BusinessRulesScorer());
        return new Stage(
                advisors,
                runners,
                scorers,
                targetSchemas,
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
                // The policy over A44's counter (A24): the cluster's ceilings come from the node's
                // configuration, a Learning Flow's from the document
                breaker(),
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
                nodeInfo.getThisNodeName(),
                // Recorded against each attempt's words (N9), so a prompt rebuilt after an upgrade can
                // say the checks' wording may have moved under it.
                NullSafe.get(buildInfoProvider.get(), BuildInfo::getBuildVersion));
    }

    /// The runners a stage learns elements with, one for each of [FlowStep#LEARNABLE_ELEMENTS] and in its
    /// order: that list is what a document chooses from, and anything else it names is passed over when a
    /// run starts. Making one runs nothing, so a test can make them to check the list.
    ///
    /// @param dataSplitterCompiler    Compiles a Data Splitter configuration.
    /// @param pipelineFactoryProvider Makes the pipeline the transform is run in.
    /// @param headlessCaptureProvider Captures what the transform wrote.
    /// @param injectedCodeProvider    Gives the transform its candidate stylesheet.
    /// @param errorReceiverProvider   Collects what went wrong in the pipeline.
    /// @param taskContextFactory      Runs the pipeline as a task.
    /// @return The runners.
    static List<StepRunner> runners(final DataSplitterCompiler dataSplitterCompiler,
                                    final Provider<PipelineFactory> pipelineFactoryProvider,
                                    final Provider<HeadlessCapture> headlessCaptureProvider,
                                    final Provider<InjectedCode> injectedCodeProvider,
                                    final Provider<ErrorReceiverProxy> errorReceiverProvider,
                                    final TaskContextFactory taskContextFactory) {
        return List.of(
                new DataSplitterStep(dataSplitterCompiler),
                new JsonStep(),
                new XmlFragmentStep(),
                new PipelineStepRunner(new XsltStep(), pipelineFactoryProvider, headlessCaptureProvider,
                        injectedCodeProvider, errorReceiverProvider, taskContextFactory));
    }
}
