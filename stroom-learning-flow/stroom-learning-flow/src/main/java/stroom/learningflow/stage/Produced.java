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

import stroom.docref.DocRef;
import stroom.learningflow.shared.RecordBoundary;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What produced an output (design 01 §7.3 rule 3), recorded alongside it so that reprocessing can be
 * as-processed, and so that a retracted binding's outputs can be found. The fragment's and its documents'
 * version ids, and the candidate count, join these when the supervisor element writes them to the
 * output stream (§12 item 7).
 *
 * @param docUuid     The Learning Flow document.
 * @param bindingUuid The binding that served the stream.
 * @param fragment    The fragment the binding named.
 * @param boundary    What one record was in this stream when it was processed (A35), because the
 *                    fragment alone does not say how its chain is run: the same fragment under another
 *                    boundary produces other events, and the binding's boundary today is not necessarily
 *                    the one that produced this output. Null where the binding had none, which is every
 *                    chain over raw text, since there the parser's configuration cuts the records.
 * @param score       The fragment's score over this stream.
 */
public record Produced(String docUuid,
                       String bindingUuid,
                       DocRef fragment,
                       RecordBoundary boundary,
                       double score,
                       String snapshotId) {

    /// Produced with no snapshot: a run before snapshots existed, or a scenario that has none.
    public Produced(final String docUuid,
                    final String bindingUuid,
                    final DocRef fragment,
                    final RecordBoundary boundary,
                    final double score) {
        this(docUuid, bindingUuid, fragment, boundary, score, null);
    }

    /// The same, scored on the run that has just happened rather than on the binding's promotion (audit
    /// finding W4-6): what the stream's attributes carry, so that a stream the translation did badly on
    /// does not record the score the translation had on the day it was promoted.
    ///
    /// @param runScore What this run scored.
    /// @return A copy with that score.
    public Produced scoring(final double runScore) {
        return new Produced(docUuid, bindingUuid, fragment, boundary, runScore, snapshotId);
    }

    public static final String DOC_ATTRIBUTE = "LearningFlowDoc";
    public static final String BINDING_ATTRIBUTE = "LearningFlowBinding";
    public static final String FRAGMENT_ATTRIBUTE = "LearningFlowFragment";
    public static final String SCORE_ATTRIBUTE = "LearningFlowScore";
    public static final String RECORD_ATTRIBUTE = "LearningFlowRecord";
    /// How many records the translation declared it deliberately skipped, written only where it skipped
    /// any. Not part of [#asAttributes], because the count is only known once the stream has been served.
    public static final String SKIPPED_ATTRIBUTE = "LearningFlowSkipped";
    public static final String SNAPSHOT_ATTRIBUTE = "LearningFlowSnapshot";

    /**
     * The bindings as the output stream's attributes carry them.
     */
    public Map<String, String> asAttributes() {
        return asAttributes(null);
    }

    /// The bindings as the output stream's attributes carry them, named for one stage of a pipeline that
    /// holds more than one.
    ///
    /// A pipeline may hold two supervised stages — the extract-then-transform pair of design 01 §3 —
    /// and they have one set of stream attributes between them. The stage nearest the source takes the
    /// plain names, because that is the binding a reader means when they do not say which stage; every
    /// stage behind it takes the same names suffixed with its element id, so that what it bound is on
    /// the stream rather than lost to whoever wrote first.
    ///
    /// @param elementId The element to name, or null for the plain names.
    public Map<String, String> asAttributes(final String elementId) {
        final String suffix = elementId == null
                ? ""
                : "." + elementId;
        final Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(DOC_ATTRIBUTE + suffix, docUuid);
        attributes.put(BINDING_ATTRIBUTE + suffix, bindingUuid);
        attributes.put(FRAGMENT_ATTRIBUTE + suffix, fragment.getUuid());
        attributes.put(SCORE_ATTRIBUTE + suffix, Double.toString(score));
        if (snapshotId != null) {
            // Which configuration of the fragment produced it (A61): the fragment moves in place under a
            // session, and the uuid alone no longer says what ran.
            attributes.put(SNAPSHOT_ATTRIBUTE + suffix, snapshotId);
        }
        if (boundary != null) {
            // What one record was, for a person reading the stream's attributes: the row in the A26
            // table is what an as-processed reprocess reads, but an attribute is what is visible.
            attributes.put(RECORD_ATTRIBUTE + suffix, boundary.toString());
        }
        return attributes;
    }
}
