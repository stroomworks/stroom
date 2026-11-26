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

/// Builds a [Stage] wired to this node's services, wherever one is needed: in a processing task, where
/// the Smart Pipeline element runs it over the stream in front of it, and in the background worker, which
/// runs it over attempts waiting for the model. A stage holds nothing between calls, so one is as good as
/// many. What it is built from (the element runners, the scorers, the fragment writer and runner, and the
/// stored state) is the same in both places, and keeping the list here means there is only one place
/// where a scorer could be forgotten.
///
/// It is not a singleton, and a stage it creates should not be kept: the Data Splitter compiler and the
/// schema scorer collect their diagnostics through the pipeline-scoped `ErrorReceiverProxy`, so a stage
/// belongs to one pipeline scope. The element is already inside one; the worker enters one for each run
/// ([DeferredLearning]).
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

    /// Creates the spend breaker, which stops learning when token or call ceilings over the last hour are
    /// reached, using this node's configured cluster-wide ceilings. [#create()] gives it to each stage, and
    /// the Supervisor uses it to show which Smart Pipelines cannot learn at the moment.
    ///
    /// @return The breaker.
    public SpendBreaker breaker() {
        return new SpendBreaker(spend, configProvider.get().getSpendTokensPerHour(),
                configProvider.get().getSpendCallsPerHour());
    }

    /// Creates the promotion actions on this node: approve, reject and retract. Unlike a stage, these run
    /// no pipeline and so need no pipeline scope.
    ///
    /// @return The promotion actions, using the system clock.
    public Promotion promotion() {
        return new Promotion(learnerBindings, states, regressionSet, snapshots, outputs, attempts, fragmentWriter,
                codes, places, wake, Clock.systemUTC());
    }

    /// Creates the actions a person can take on a session or a learner on this node: grant, exhaust,
    /// amend, relearn and give up. These run no pipeline and so need no pipeline scope.
    ///
    /// @return The actions, using the system clock and this node's session lease.
    public SupervisorActions supervisorActions() {
        return new SupervisorActions(attempts, learnerBindings, states, regressionSet, snapshots, targetSchemas,
                wake, Clock.systemUTC(), configProvider.get().getSessionLease().getDuration());
    }

    /// Creates a stage on this node, using the system clock.
    ///
    /// @return The stage.
    public Stage create() {
        // A node judges a candidate with the real element that will run it, but only where the module
        // would otherwise re-implement the element. The parser runners already use Stroom's own
        // factories (the Data Splitter's DS3 factory, the JSON reader, Stroom's XML reader), and they also
        // report what input each record consumed, which input coverage is scored on and which a pipeline
        // does not report. The transform is the one place the module drives Saxon itself (with its own
        // configuration, no pool and no function library), so it is the one wrapped in a real pipeline.
        final List<StepRunner> runners = runners(dataSplitterCompiler, pipelineFactoryProvider,
                headlessCaptureProvider, injectedCodeProvider, errorReceiverProvider, taskContextFactory);
        final List<Scorer> scorers = List.of(
                new CompileScorer(), new PermittedScorer(), new InputCoverageScorer(),
                new CompletenessScorer(), new YieldScorer(),
                new TimesScorer(), new LiteralsScorer(),
                schemaConformanceScorer,
                new ExtractionQualityScorer(), new BusinessRulesScorer());
        return new Stage(
                advisors,
                runners,
                scorers,
                targetSchemas,
                fragmentWriter,
                // A node judges and serves through a real pipeline. The stand-in runner used by the Tier 1
                // tests is only for a test harness with no node.
                new PipelineFragmentRunner(pipelineStore, pipelineDataCache, elementRegistryFactory,
                        pipelineFactoryProvider,
                        headlessCaptureProvider, errorReceiverProvider, fragmentOutputProvider, fragmentTapProvider,
                        injectedCodeProvider, taskContextFactory),
                attempts,
                learnerBindings,
                states,
                spend,
                // The cluster-wide ceilings come from the node's configuration, and a Learning Flow's own
                // ceilings from its document.
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
                // The build version is recorded with each attempt's question wording, so that a prompt
                // rebuilt after an upgrade can warn that the wording of the checks may have changed.
                NullSafe.get(buildInfoProvider.get(), BuildInfo::getBuildVersion));
    }

    /// The step runners a stage learns elements with, one for each of [FlowStep#LEARNABLE_ELEMENTS] and in
    /// the same order. That list is what a document chooses its allowed elements from, and any other
    /// element a document names is ignored when a session starts. Creating the runners runs nothing, so a
    /// test can create them to check the list.
    ///
    /// @param dataSplitterCompiler    Compiles a Data Splitter configuration.
    /// @param pipelineFactoryProvider Creates the pipeline the transform is run in.
    /// @param headlessCaptureProvider Captures what the transform wrote.
    /// @param injectedCodeProvider    Gives the transform its candidate stylesheet.
    /// @param errorReceiverProvider   Collects errors reported in the pipeline.
    /// @param taskContextFactory      Provides the task context the pipeline runs in.
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
