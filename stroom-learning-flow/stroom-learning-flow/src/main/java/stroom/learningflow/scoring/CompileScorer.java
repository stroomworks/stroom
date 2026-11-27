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

package stroom.learningflow.scoring;

import stroom.learningflow.learning.TargetSchema;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.ScorerParameters;

import java.util.Optional;

/// The compile check as a scorer. A step that produced output scores one. A step that could not run (e.g.
/// refused by the Data Splitter schema or rejected by Saxon) scores zero, and its diagnostics are the
/// feedback, which is the most useful feedback the model can receive.
public final class CompileScorer implements Scorer {

    @Override
    public Check type() {
        return Check.COMPILE;
    }

    @Override
    public Optional<Score> score(final ScorerParameters parameters,
                                 final Attempted step,
                                 final TargetSchema target) {
        return Optional.of(new Score(type(), step.result().output() == null
                ? 0.0
                : 1.0, step.result().diagnostics()));
    }
}
