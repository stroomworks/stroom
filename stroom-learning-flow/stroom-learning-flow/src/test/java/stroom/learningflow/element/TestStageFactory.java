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
import stroom.learningflow.fragment.FragmentCodes;
import stroom.learningflow.fragment.FragmentWriter;
import stroom.learningflow.learning.Advisors;
import stroom.learningflow.learning.StepRunner;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.scoring.SchemaConformanceScorer;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.stage.Attempts;
import stroom.learningflow.stage.Deferrals;
import stroom.learningflow.stage.Guidance;
import stroom.learningflow.stage.LearnerBindings;
import stroom.learningflow.stage.LearnerStates;
import stroom.learningflow.stage.Learners;
import stroom.learningflow.stage.Outputs;
import stroom.learningflow.stage.Places;
import stroom.learningflow.stage.RegressionSet;
import stroom.learningflow.stage.Snapshots;
import stroom.learningflow.stage.Spend;
import stroom.learningflow.stage.Wake;
import stroom.node.api.NodeInfo;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.factory.ElementRegistryFactory;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.task.api.TaskContextFactory;

import com.google.inject.OutOfScopeException;
import jakarta.inject.Provider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TestStageFactory {

    @Test
    @SuppressWarnings("unchecked")
    void theLearnableElementsAreThoseOfTheRunners() {
        // Creating the runners calls none of their dependencies, so mocks with no behaviour are enough
        final List<StepRunner> runners = StageFactory.runners(
                mock(DataSplitterCompiler.class),
                mock(Provider.class),
                mock(Provider.class),
                mock(Provider.class),
                mock(Provider.class),
                mock(TaskContextFactory.class));

        // The elements a document can choose from, and the Setup tab offers, must be exactly those a runner
        // exists for, in the same order
        assertThat(runners)
                .extracting(StepRunner::elementType)
                .containsExactlyElementsOf(FlowStep.LEARNABLE_ELEMENTS);
    }

    @Test
    @SuppressWarnings("unchecked")
    void whatTheSupervisorAsksForNeedsNoPipelineScope() {
        // The Data Splitter compiler and the schema scorer report through the pipeline-scoped error receiver,
        // so outside a pipeline scope Guice can't make them. The Learning Status list asks for the breaker on
        // every refresh, outside one, and used to fail with an OutOfScopeException.
        final Provider<DataSplitterCompiler> compilerOutOfScope = () -> {
            throw new OutOfScopeException("No pipeline scope");
        };
        final Provider<SchemaConformanceScorer> scorerOutOfScope = () -> {
            throw new OutOfScopeException("No pipeline scope");
        };
        final StageFactory stageFactory = new StageFactory(
                mock(Advisors.class),
                compilerOutOfScope,
                scorerOutOfScope,
                mock(FragmentWriter.class),
                mock(PipelineStore.class),
                mock(PipelineDataCache.class),
                mock(ElementRegistryFactory.class),
                mock(Provider.class),
                mock(Provider.class),
                mock(Provider.class),
                mock(Provider.class),
                mock(Provider.class),
                mock(Provider.class),
                mock(TaskContextFactory.class),
                mock(NodeInfo.class),
                mock(Provider.class),
                mock(Attempts.class),
                mock(LearnerBindings.class),
                mock(LearnerStates.class),
                mock(Spend.class),
                mock(Guidance.class),
                mock(Outputs.class),
                mock(RegressionSet.class),
                mock(Deferrals.class),
                mock(Learners.class),
                mock(Wake.class),
                mock(Places.class),
                mock(TargetSchemas.class),
                mock(Snapshots.class),
                mock(FragmentCodes.class),
                LearningFlowConfig::new);

        assertThat(stageFactory.breaker()).isNotNull();
        assertThat(stageFactory.promotion()).isNotNull();
        assertThat(stageFactory.supervisorActions()).isNotNull();

        // A stage is still made from them, so it still needs the scope
        assertThatThrownBy(stageFactory::create)
                .isInstanceOf(OutOfScopeException.class);
    }
}
