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

/// The interface for scorers: one implementation per [Check], each measuring one thing about one step's
/// output. A scorer that has nothing to measure on a step (e.g. input coverage on a transform, or yield per
/// record on raw text) returns empty, and is then neither counted nor failed; the scorecard only uses a
/// scorer's result where it has one.
public interface Scorer {

    Check type();

    /// Measures this check's signal on one step.
    ///
    /// @param parameters The document's parameters for this scorer, of the class that belongs to
    ///                   [#type()]; null for a scorer that takes none.
    /// @param step       The step to measure.
    /// @param target     The schema the document's translations must conform to, or null where it names
    ///                   none. In that case a scorer that needs one assumes event-logging, which was the
    ///                   behaviour before documents had a target schema setting, so that a document saved
    ///                   by an earlier version behaves exactly as it did.
    /// @return The score, or empty where this check has nothing to measure on this step.
    Optional<Score> score(ScorerParameters parameters, Attempted step, TargetSchema target);

    /// Rejects parameters this scorer cannot work with (e.g. an XPath that does not compile) when the
    /// scorecard is built, before the model is asked anything or a stream fails because of them.
    ///
    /// @param parameters The document's parameters for this scorer.
    /// @throws IllegalArgumentException Saying what is wrong with the document's setting.
    default void validate(final ScorerParameters parameters) {
    }
}
