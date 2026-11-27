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

import java.util.List;
import java.util.Optional;

/// Records which binding produced each output, so that the stage can look back at them: retracting a
/// binding means finding the inputs it produced output for, and an as-processed reprocess needs to know
/// what produced an input's output. Tests keep this in memory; a node records it in a table and on the
/// output stream's attributes.
public interface Outputs {

    /// Records one output as it is emitted: which input it was made from, which pipeline was running, and
    /// which binding produced it.
    ///
    /// @param inputId  The input stream's meta id.
    /// @param pipeline The UUID of the pipeline that was running.
    /// @param produced The binding, fragment and snapshot that produced it.
    void emitted(long inputId, String pipeline, Produced produced);

    /// As [#emitted(long, String, Produced)], also recording how many records the stream held, for the
    /// volume shown on a Smart Pipeline's Quality Assurance tab. A store that doesn't count records ignores
    /// it.
    ///
    /// @param inputId  The input stream's meta id.
    /// @param pipeline The UUID of the pipeline that was running.
    /// @param produced The binding, fragment and snapshot that produced it.
    /// @param records  How many records the stream held, or null if they were not counted.
    default void emitted(final long inputId,
                         final String pipeline,
                         final Produced produced,
                         final Integer records) {
        emitted(inputId, pipeline, produced, records, null);
    }

    /// As [#emitted(long, String, Produced, Integer)], also recording how many of the stream's records the
    /// translation declared it deliberately skipped (`DeclaredSkips`). The skip rate is deliberately not
    /// capped, so showing the count beside the volume is the only thing that stops a translation quietly
    /// skipping its awkward records. A store that doesn't count records ignores it.
    ///
    /// @param inputId  The input stream's meta id.
    /// @param pipeline The UUID of the pipeline that was running.
    /// @param produced The binding, fragment and snapshot that produced it.
    /// @param records  How many records the stream held, or null if they were not counted.
    /// @param skipped  How many of them were declared skipped, or null if they were not counted.
    default void emitted(final long inputId,
                         final String pipeline,
                         final Produced produced,
                         final Integer records,
                         final Integer skipped) {
        emitted(inputId, pipeline, produced);
    }

    /// Finds the inputs this binding produced output for *with this fragment*, oldest first, each with the
    /// pipeline that processed it. A retraction counts these.
    ///
    /// The fragment is matched as well as the binding, because a binding keeps its UUID when it is rebound
    /// to a new fragment. A retraction is of the binding as it is now, and streams produced by an earlier
    /// fragment of the same binding were produced correctly by what was bound then.
    List<Replayable> boundBy(String bindingUuid, String fragmentUuid);

    /// Gets what produced this input's output when it was last processed by this pipeline, for an
    /// **as-processed** reprocess. The recorded fragment and snapshot are run again, so the result is the
    /// one the input had when it was first processed.
    ///
    /// This is what an audit needs. It is the opposite of an ordinary reprocess, which uses the learner's
    /// binding as it is today, so that waiting streams pick up what has been learned since.
    ///
    /// @param docUuid  The UUID of the document of the stage that is asking. Without it, a pipeline holding
    ///                 two Smart Pipeline elements (e.g. one extracting and one transforming) could answer one
    ///                 element with the other's fragment, which would then be run over the wrong input.
    /// @param inputId  The input stream's meta id.
    /// @param pipeline The UUID of the pipeline.
    /// @return Empty if nothing is recorded for that document, input and pipeline: nothing was produced, it
    ///         was produced before recording began, or the row has been pruned. An as-processed reprocess
    ///         of an input with no record cannot be served, and must say so rather than quietly process it
    ///         with the current binding.
    Optional<Produced> asProcessed(String docUuid, long inputId, String pipeline);

    /// Deletes the records of outputs produced before a given time. There is a row per output stream, so
    /// they must be pruned; a retraction can then only count the outputs still recorded.
    ///
    /// @param producedBeforeMs Records of outputs produced before this time are deleted.
    /// @return How many records were deleted.
    int prune(long producedBeforeMs);
}
