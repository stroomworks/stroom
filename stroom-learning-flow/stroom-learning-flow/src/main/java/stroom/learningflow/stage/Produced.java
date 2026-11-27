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

/// What produced an output, recorded alongside it so that the output can be reprocessed as it was first
/// processed, and so that a retracted binding's outputs can be found. The Smart Pipeline element also writes
/// these values to the output stream's attributes ([#asAttributes]).
///
/// @param docUuid     The UUID of the Learning Flow document.
/// @param bindingUuid The UUID of the binding that served the stream.
/// @param fragment    The fragment the binding named.
/// @param boundary    What one record was in this stream when it was processed. The fragment alone does not
///                    say how its chain is run: the same fragment under another boundary produces other
///                    events, and the binding's boundary today may not be the one that produced this
///                    output. Null if the binding had none, which is the case for every chain over raw text,
///                    since there the parser's configuration splits the records.
/// @param score       The fragment's score over this stream.
/// @param snapshotId  The id of the configuration snapshot that ran; null if there was none.
public record Produced(String docUuid,
                       String bindingUuid,
                       DocRef fragment,
                       RecordBoundary boundary,
                       double score,
                       String snapshotId) {

    /// Creates a record with no snapshot, for a run recorded before snapshots existed or a test that has
    /// none.
    public Produced(final String docUuid,
                    final String bindingUuid,
                    final DocRef fragment,
                    final RecordBoundary boundary,
                    final double score) {
        this(docUuid, bindingUuid, fragment, boundary, score, null);
    }

    /// Returns a copy with the score from the run that has just happened rather than the binding's score at
    /// promotion. This is what the stream's attributes record, so that a stream the translation did badly on
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
    /// How many records the translation declared it deliberately skipped, written only if it skipped any.
    /// Not part of [#asAttributes], because the count is only known once the stream has been served.
    public static final String SKIPPED_ATTRIBUTE = "LearningFlowSkipped";
    public static final String SNAPSHOT_ATTRIBUTE = "LearningFlowSnapshot";

    /// Gets these values as output stream attributes, under the plain attribute names.
    public Map<String, String> asAttributes() {
        return asAttributes(null);
    }

    /// Gets these values as output stream attributes, named for one Smart Pipeline element of a pipeline
    /// that holds more than one.
    ///
    /// A pipeline may hold two Smart Pipeline elements (e.g. one extracting and one transforming), and they
    /// share one set of stream attributes. The element nearest the source uses the plain names, because
    /// that is the binding a reader means when they do not say which element. Every element after it uses
    /// the same names suffixed with its element id, so that its values are recorded rather than clashing
    /// with the first element's.
    ///
    /// @param elementId The element id to suffix the names with, or null for the plain names.
    /// @return The attributes, by name.
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
            // Which configuration of the fragment produced it. A session can rewrite the fragment in place,
            // so the fragment's UUID alone does not say what ran.
            attributes.put(SNAPSHOT_ATTRIBUTE + suffix, snapshotId);
        }
        if (boundary != null) {
            // What one record was, for a person reading the stream's attributes. An as-processed reprocess
            // reads the database row instead, but the attribute is what a person can see.
            attributes.put(RECORD_ATTRIBUTE + suffix, boundary.toString());
        }
        return attributes;
    }
}
