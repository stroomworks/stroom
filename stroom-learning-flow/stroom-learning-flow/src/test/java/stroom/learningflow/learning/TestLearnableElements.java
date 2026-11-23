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

/// Audit finding W5-7: **the elements a Learning Flow may choose from are the elements something can
/// run.**
///
/// `XMLParser` was in the default list with no runner behind it, so the chain question offered the model
/// something it could never be given a configuration for. It has gone; this is what stops the two
/// drifting apart again, in either direction — a runner added without being offered is as much a fault
/// as an element offered with no runner.
class TestLearnableElements {

    @Test
    void everyLearnableElementHasARunnerAndEveryRunnerIsLearnable() {
        assertThat(new Scenarios().runners())
                .extracting(StepRunner::elementType)
                .containsExactlyInAnyOrderElementsOf(FlowStep.LEARNABLE_ELEMENTS);
    }
}
