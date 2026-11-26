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

package stroom.learningflow.stage;

import java.util.List;
import java.util.Optional;

/// Storage for the regression corpus: for each binding, the records it was accepted on, each with the output
/// it was accepted with. A new candidate must do no worse than this before it can replace the binding.
///
/// The corpus is keyed on the binding's UUID, so a binding that is rebound to a new fragment keeps its
/// history. A candidate is held to every entry: each entry's new output must clear the bar, and a record that
/// was once translated may never become Unknown. The output may otherwise change.
///
/// An entry is a single record, not a stream, and only the records a session had to learn for are added: a
/// first learning adds the sample the model was shown, a relearning adds the records that fell short. A
/// stream of ten gigabytes therefore contributes only a handful of entries. Entries are appended at
/// promotion and capped per binding by the document's setting, the oldest being dropped first. Tests keep the
/// corpus in memory; a node writes one stream per promotion on the source feed, with an index row per entry,
/// so that the copy inherits the feed's classification and is subject to its retention rules.
public interface RegressionSet {

    /// Gets the records a binding was accepted on, oldest first.
    ///
    /// @param bindingUuid The binding's UUID.
    /// @return Its entries; any the current user cannot read, or that no longer exist, are left out.
    List<Accepted> accepted(String bindingUuid);

    /// Appends a promotion's entries, dropping the oldest beyond the cap.
    ///
    /// @param docUuid     The UUID of the Learning Flow document the learner runs under.
    /// @param bindingUuid The UUID of the promoted binding.
    /// @param feed        The feed the records came from, which is where a node keeps the copy. Never null,
    ///                    because the copy must be kept on some feed.
    /// @param type        The stream type the records came from.
    /// @param entries     The records, in the order they were learned.
    /// @param cap         The maximum number of entries the binding may hold, from the document.
    void accept(String docUuid, String bindingUuid, String feed, String type, List<Accepted> entries, int cap);

    /// Gets the feed a binding's records are kept on, taken from its newest entry. An improvement session has
    /// no stream of its own, and a learner does not know its feed, so this is where an improvement keeps the
    /// records it learns for.
    ///
    /// @param bindingUuid The binding's UUID.
    /// @return The feed, or empty where the binding holds no records.
    Optional<String> feedOf(String bindingUuid);

    /// Discards the records of a binding that will never serve, such as a rejected draft or a retracted
    /// binding.
    ///
    /// @param bindingUuid The binding's UUID.
    void discard(String bindingUuid);

    /// Moves a draft's entries to the serving binding when the draft is approved. Approval rebinds the
    /// serving binding to the draft's fragment, so the draft's entries are appended after those the serving
    /// binding already holds.
    ///
    /// @param fromBindingUuid The UUID of the approved draft.
    /// @param toBindingUuid   The UUID of the serving binding it replaces.
    /// @param cap             The maximum number of entries the binding may hold, from the document.
    void move(String fromBindingUuid, String toBindingUuid, int cap);

    // --------------------------------------------------------------------------------

    /// What a record became when it was accepted.
    enum Kind {
        /// A real translation: an event with something in its detail.
        TRANSLATION,
        /// A marked Unknown: an event whose detail is `Unknown`, learned for a kind of record nothing better
        /// could be done with.
        UNKNOWN,
        /// No event: a kind of record that yields no event, such as a header line or a comment.
        NONE
    }

    /// One record a binding was accepted on.
    ///
    /// @param record     The record as the parser cut it, as one document of its own: the parser's output
    ///                   with this record alone under its ancestors, which is what the transform is given
    ///                   and what a candidate is run over.
    /// @param text       The record's raw text as it was received, if the parser reports where it split
    ///                   the record (the Data Splitter does; the JSON and XML parsers do not); else null.
    /// @param preamble   The text the stream had before its first record (e.g. a header line or an opening
    ///                   bracket), if the parser reports it; else null. A stream rebuilt from entries
    ///                   begins with this.
    /// @param postamble  What the stream carried after its last record, likewise.
    /// @param output     The output the record was accepted with: the transform's events for it, or null
    ///                   where it yielded none.
    /// @param kind       What that output is.
    /// @param score      The weighted score the promoted candidate achieved on the stream it was accepted
    ///                   from.
    /// @param attemptId  The session that accepted it, if one was open.
    /// @param acceptedMs When it was accepted.
    /// @param target     The target event the flow settled on for this kind of record, if it settled one.
    record Accepted(String record,
                    String text,
                    String preamble,
                    String postamble,
                    String output,
                    Kind kind,
                    double score,
                    Long attemptId,
                    long acceptedMs,
                    String target) {

        /// Whether the record can be run through a parser again, which needs its raw text.
        public boolean runnable() {
            return text != null;
        }
    }
}
