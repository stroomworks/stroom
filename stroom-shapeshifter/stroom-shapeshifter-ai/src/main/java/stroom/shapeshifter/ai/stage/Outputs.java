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

package stroom.shapeshifter.ai.stage;

import stroom.util.shared.TextRange;

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
    void emitted(long inputId, String pipeline, Produced produced, List<TextRange> spans);

    /// The same, for an output whose records nobody can say the span of: a chain with no parser was
    /// given records that already existed, and where they came from in some earlier stream is not this
    /// stream's business.
    default void emitted(final long inputId, final String pipeline, final Produced produced) {
        emitted(inputId, pipeline, produced, List.of());
    }

    /// Where one record of an input began and ended in it (§12 item 21, design 01 §10.1), so that a
    /// fault found at an event — by a scorer, by a reviewer, by a person — can be relearned with the
    /// record's own text in hand rather than by running the parser again over a stream that may be
    /// thirty gigabytes.
    ///
    /// @param docUuid     Whose stage is asking. A pipeline may hold two supervised stages and each
    ///                     records what it produced, so an input on a pipeline names two outputs; a
    ///                     document belongs to one stage, because what it may learn has to match where
    ///                     it stands (A1), so it is what tells them apart.
    /// @param recordIndex  Counted from zero, in the order the parser emitted them.
    /// @return Empty where nothing was recorded for that record: an output with no parser to ask, a row
    /// written before spans were kept, or a record past what was kept.
    Optional<TextRange> span(String docUuid, long inputId, String pipeline, int recordIndex);

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
    /// @param docUuid Whose stage is asking, as [#span] means it: without it, a pipeline holding both
    ///                stages of §3 would answer the extraction stage with the transformation stage's
    ///                fragment, which would then be run over raw bytes.
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
