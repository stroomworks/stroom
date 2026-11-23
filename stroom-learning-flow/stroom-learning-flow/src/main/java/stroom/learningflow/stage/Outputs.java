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

/**
 * The bindings every output carries (design 01 §7.3 rule 3), as far as the stage needs to look back at
 * them: retracting a binding means finding the inputs whose outputs it produced. In-memory in scenarios; in
 * a node the bindings are the output stream's attributes and this is a meta search over them.
 */
public interface Outputs {

    /// One output, as it is emitted: which input it was made from, which pipeline was running, and what
    /// bound it (design 01 §7.3 rule 3).
    ///
    /// @param inputId  The stream's meta id.
    /// @param pipeline The pipeline that was running.
    /// @param produced What bound it.
    void emitted(long inputId, String pipeline, Produced produced);

    /// The same, with how many records the stream held, for the volume a Smart Pipeline's Quality
    /// Assurance tab shows. A store that doesn't count records ignores it.
    ///
    /// @param inputId  The stream's meta id.
    /// @param pipeline The pipeline that was running.
    /// @param produced What bound it.
    /// @param records  How many records the stream held, or null where nobody counted them.
    default void emitted(final long inputId,
                         final String pipeline,
                         final Produced produced,
                         final Integer records) {
        emitted(inputId, pipeline, produced, records, null);
    }

    /// The same, with how many of the stream's records the translation declared it deliberately skipped
    /// (`DeclaredSkips`, ruling A148). There is no cap on the skip rate by decision, so the count being
    /// visible beside the volume is the whole of what stops a translation quietly skipping its awkward
    /// records. A store that doesn't count records ignores it.
    ///
    /// @param inputId  The stream's meta id.
    /// @param pipeline The pipeline that was running.
    /// @param produced What bound it.
    /// @param records  How many records the stream held, or null where nobody counted them.
    /// @param skipped  How many of them were declared skipped, or null where nobody counted them.
    default void emitted(final long inputId,
                         final String pipeline,
                         final Produced produced,
                         final Integer records,
                         final Integer skipped) {
        emitted(inputId, pipeline, produced);
    }

    /**
     * What one binding produced: the inputs whose output this binding made *with this fragment*, oldest
     * first, each with the pipeline that made it — what a retraction counts (§6, A129).
     * <p>
     * By fragment as well as binding, because a binding keeps its uuid when it is rebound (§7.3 rule 3): a
     * retraction is of the binding in front of us, and the streams an earlier generation of the same
     * binding produced were produced correctly by what was then bound.
     */
    List<Replayable> boundBy(String bindingUuid, String fragmentUuid);

    /// What was bound when this input was last processed by this pipeline, for an **as-processed**
    /// reprocess (design 01 §7.3): the same fragment runs again and the result is the result it had,
    /// because rule 1 makes a written document immutable — an improvement is a new document, never an
    /// edit to one that has run.
    ///
    /// This is what an audit needs, and it is the opposite of the release A12 performs, which resolves
    /// the learner's binding as it is today so that a backlog picks up what was learned since.
    ///
    /// @param docUuid Whose stage is asking: without it, a pipeline holding both stages of §3 would
    ///                answer the extraction stage with the transformation stage's fragment, which would
    ///                then be run over raw bytes.
    /// @return Empty where nothing is recorded for that document, input and pipeline: nothing was
    /// produced, or it was produced before this node began recording, or the row has been pruned. An
    /// as-processed reprocess of an input nothing remembers cannot be served and must say so rather than
    /// quietly serving as-current.
    Optional<Produced> asProcessed(String docUuid, long inputId, String pipeline);

    /**
     * Forget what was produced before a given time (design 01 §12 item 8): a row per output stream is a
     * row per stream, so what a retraction can still reach is what a node is told to keep.
     *
     * @return How many were forgotten.
     */
    int prune(long producedBeforeMs);
}
