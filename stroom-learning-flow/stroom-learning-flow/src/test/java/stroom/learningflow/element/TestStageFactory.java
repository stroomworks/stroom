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

import stroom.learningflow.extraction.DataSplitterCompiler;
import stroom.learningflow.learning.StepRunner;
import stroom.learningflow.shared.FlowStep;
import stroom.task.api.TaskContextFactory;

import jakarta.inject.Provider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TestStageFactory {

    @Test
    @SuppressWarnings("unchecked")
    void theLearnableElementsAreThoseOfTheRunners() {
        // Making a runner calls nothing it is given
        final List<StepRunner> runners = StageFactory.runners(
                mock(DataSplitterCompiler.class),
                mock(Provider.class),
                mock(Provider.class),
                mock(Provider.class),
                mock(Provider.class),
                mock(TaskContextFactory.class));

        // What a document chooses from, and the Setup tab offers, is what a run can learn with, in order
        assertThat(runners)
                .extracting(StepRunner::elementType)
                .containsExactlyElementsOf(FlowStep.LEARNABLE_ELEMENTS);
    }
}
