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

package stroom.learningflow.learning;

import stroom.learningflow.scoring.Verdict;

/// One element of a chain a learning session has learned, with the configuration that passed, what it
/// produced, and how it was scored. A [Outcome.Learned] holds one per element of the chain.
///
/// @param runner        The step runner for the element.
/// @param configuration The configuration text that passed, or null for a run-only element.
/// @param result        What the element produced from its input.
/// @param verdict       The scorecard's verdict on that output.
public record LearnedStep(StepRunner runner, String configuration, StepResult result, Verdict verdict) {

    public String elementType() {
        return runner.elementType();
    }
}
