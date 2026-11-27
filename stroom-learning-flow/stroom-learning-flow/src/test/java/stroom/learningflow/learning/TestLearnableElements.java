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

package stroom.learningflow.learning;

import stroom.learningflow.scenario.Scenarios;
import stroom.learningflow.shared.FlowStep;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that the elements a Learning Flow may choose from are exactly the elements a step runner exists for.
///
/// An element offered with no runner would let the chain question offer the model something that could never
/// be run. A runner added without its element being offered is just as much a fault, so the check runs in both
/// directions.
class TestLearnableElements {

    @Test
    void everyLearnableElementHasARunnerAndEveryRunnerIsLearnable() {
        assertThat(new Scenarios().runners())
                .extracting(StepRunner::elementType)
                .containsExactlyInAnyOrderElementsOf(FlowStep.LEARNABLE_ELEMENTS);
    }
}
