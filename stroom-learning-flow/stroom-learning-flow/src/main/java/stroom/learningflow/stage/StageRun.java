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

package stroom.learningflow.stage;

import stroom.learningflow.learning.Exchange;
import stroom.learningflow.scoring.Verdict;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.stage.Decision.Sentinel;
import stroom.pipeline.xml.event.EventList;
import stroom.util.shared.StoredError;

import java.util.List;

/// The result of one run of the stage over a stream: what was decided and everything that was produced.
/// The Smart Pipeline element uses it to serve or defer the stream, and tests assert on it.
///
/// @param doc         The document the stage ran under.
/// @param decision    What was decided.
/// @param learner     The learner the stream was processed for.
/// @param produced    The binding and snapshot that produced the output; null when there is no output.
/// @param output      What the bound or promoted fragment produced over the whole stream; null for a
///                    sentinel or a learner that gave up.
/// @param verdicts    The scorecard's verdicts on the fragment over the whole stream, one per step, in
///                    chain order; empty when nothing ran.
/// @param transcript  Every exchange with the model, oldest first; empty when the model was not asked.
/// @param events      The same output as the events the fragment emitted while producing it, for a caller
///                    that has a downstream pipeline to replay them into. This is how the Smart Pipeline
///                    element serves a stream without running the fragment a second time. Null if the
///                    fragment was run with no pipeline under it, as the Tier 1 tests run it, or if
///                    nothing ran at all.
/// @param diagnostics What the fragment's elements reported while producing that output, for a caller that
///                    must put them on the pipeline's error stream. A fragment runs under an error receiver
///                    of its own, so that a candidate's errors are fed back to the model rather than shown
///                    to the operator; but errors from the run that is actually served must be reported,
///                    and these are they. Empty if nothing ran or nothing is served.
public record StageRun(LearningFlowDoc doc,
                       Decision decision,
                       Learner learner,
                       Produced produced,
                       String output,
                       List<Verdict> verdicts,
                       List<Exchange> transcript,
                       EventList events,
                       List<StoredError> diagnostics) {

    /// Creates a run that was refused before anything was decided; the stream gets a sentinel (an error)
    /// with the reason.
    ///
    /// @param doc     The document the stage ran under.
    /// @param learner The learner the stream was processed for.
    /// @param reason  Why the run was refused.
    /// @return The refused run.
    static StageRun refused(final LearningFlowDoc doc, final Learner learner, final String reason) {
        return new StageRun(doc, new Sentinel(reason), learner, null, null, List.of(), List.of(), null,
                List.of());
    }
}
