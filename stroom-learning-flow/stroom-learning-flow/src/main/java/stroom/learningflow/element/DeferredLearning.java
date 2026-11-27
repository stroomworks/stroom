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
import stroom.learningflow.learning.Advisors;
import stroom.learningflow.stage.Attempts;
import stroom.learningflow.stage.DeferredWorker;
import stroom.learningflow.stage.DeletedPipelines;
import stroom.learningflow.stage.Documents;
import stroom.learningflow.stage.Inputs;
import stroom.security.api.SecurityContext;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.pipeline.scope.PipelineScopeRunnable;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.time.Clock;

/// The scheduled job *Learning Flow Deferred Learning*, which runs the background worker on this node.
///
/// A processing task never calls the model. When a stream cannot be translated, the task opens an
/// attempt that waits at its first question, and defers the stream. This job is what advances those
/// attempts, at whatever pace the model answers, without holding a processing task slot while it does.
///
/// It runs as the processing user, because it reads streams and documents on nobody's behalf, and writes
/// fragments and bindings just as an automatic promotion does.
///
/// Each run takes a bounded batch rather than everything waiting. A job that tried to empty the queue
/// would hold one thread for as long as the model takes multiplied by the length of the queue; the next
/// run takes what is left. Each attempt's claim on its learner stops two nodes working on the same
/// attempt at once.
@Singleton
public class DeferredLearning {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(DeferredLearning.class);

    private final Provider<StageFactory> stageFactory;
    private final PipelineScopeRunnable pipelineScope;
    private final Attempts attempts;
    private final Provider<PipelineStreamHeld> streamHeld;
    private final Documents documents;
    private final Inputs inputs;
    private final DeletedPipelines deletedPipelines;
    private final Advisors advisors;
    private final SecurityContext securityContext;
    private final Provider<LearningFlowConfig> configProvider;

    @Inject
    public DeferredLearning(final Provider<StageFactory> stageFactory,
                            final PipelineScopeRunnable pipelineScope,
                            final Attempts attempts,
                            final Provider<PipelineStreamHeld> streamHeld,
                            final Documents documents,
                            final Inputs inputs,
                            final DeletedPipelines deletedPipelines,
                            final Advisors advisors,
                            final SecurityContext securityContext,
                            final Provider<LearningFlowConfig> configProvider) {
        this.stageFactory = stageFactory;
        this.pipelineScope = pipelineScope;
        this.attempts = attempts;
        this.streamHeld = streamHeld;
        this.documents = documents;
        this.inputs = inputs;
        this.deletedPipelines = deletedPipelines;
        this.advisors = advisors;
        this.securityContext = securityContext;
        this.configProvider = configProvider;
    }

    public void exec() {
        securityContext.asProcessingUser(() -> pipelineScope.scopeRunnable(() -> {
            // Run in a pipeline scope even though there is no pipeline, because the components a stage uses
            // (e.g. the Data Splitter compiler and the schema scorer) report through the pipeline-scoped
            // error receiver. This way a candidate configuration is compiled and run here exactly as it is
            // in a processing task.
            final DeferredWorker worker = new DeferredWorker(stageFactory.get().create(), attempts, documents,
                    inputs, streamHeld.get(), deletedPipelines, advisors, Clock.systemUTC());
            final int carried = worker.advance(configProvider.get().getDeferredLearningBatchSize());
            if (carried > 0) {
                LOGGER.info(() -> LogUtil.message("Carried on {} attempt(s) awaiting the model", carried));
            }
        }));
    }
}
