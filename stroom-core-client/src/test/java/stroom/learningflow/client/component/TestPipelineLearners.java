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

package stroom.learningflow.client.component;

import stroom.learningflow.shared.LearnerRef;
import stroom.learningflow.shared.LearnerState;
import stroom.learningflow.shared.SupervisorLearner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestPipelineLearners {

    @Test
    void anElementsLearnerIsFoundByItsId() {
        final SupervisorLearner first = learner("first");
        final SupervisorLearner second = learner("second");
        assertThat(PipelineLearners.learnerOf(List.of(first, second), "second")).isSameAs(second);
        assertThat(PipelineLearners.learnerOf(List.of(first, second), "first")).isSameAs(first);
    }

    @Test
    void anElementWithNoLearnerHasNone() {
        assertThat(PipelineLearners.learnerOf(List.of(learner("first")), "other")).isNull();
        assertThat(PipelineLearners.learnerOf(List.of(), "first")).isNull();
        assertThat(PipelineLearners.learnerOf(null, "first")).isNull();
    }

    private static SupervisorLearner learner(final String elementId) {
        return new SupervisorLearner(new LearnerRef("p-1", elementId), null, null, LearnerState.LEARNING, null,
                null, null, null, null, 0, 0, null, false, null, 0, false, false);
    }
}
