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

package stroom.shapeshifter.ai.stage;

import java.util.List;
import java.util.Optional;

/// The corpus (ruling A18; design 04 §4.1): the records a binding was accepted on, per binding, each with the
/// output it was accepted with. Keyed on the binding's uuid so that a binding that is rebound keeps its
/// history. A candidate is held to every entry, to **no worse** (A66): every entry's new output must
/// clear the bar, and a record once translated may never become Unknown. Output may otherwise change.
///
/// An entry is a record and not a stream (A56): only the records a session had to learn for go in — a
/// first stream contributes the sample the model was shown, a relearning the records that fell short —
/// so a stream of ten gigabytes contributes a handful. Appended at promotion, capped per binding by the
/// document (A63), the oldest going first. In-memory in scenarios; in a node, a stream per promotion on
/// the source feed with a row per entry (design 04 §4.1), so that the copy inherits the feed's
/// classification and lives under its retention.
public interface RegressionSet {

    /// What a binding was accepted on, oldest first.
    ///
    /// @param bindingUuid The binding.
    /// @return Its entries.
    List<Accepted> accepted(String bindingUuid);

    /// Append a promotion's entries, dropping the oldest beyond the cap.
    ///
    /// @param docUuid     The document.
    /// @param bindingUuid The binding.
    /// @param feed        The feed the records came from: where a node keeps the copy. Never null: a copy
    ///                    must be kept on some feed.
    /// @param type        The stream type the records came from.
    /// @param entries     The records, in the order they were learned.
    /// @param cap         How many entries the binding may hold (A63).
    void accept(String docUuid, String bindingUuid, String feed, String type, List<Accepted> entries, int cap);

    /// The feed a binding's records are kept on: its newest entry's. What an improvement, which has no stream
    /// of its own, keeps what it learns for on (A126: a learner does not know its feed, but its records
    /// do).
    ///
    /// @param bindingUuid The binding.
    /// @return The feed, or empty where the binding holds no records.
    Optional<String> feedOf(String bindingUuid);

    /// A binding that will never serve — a rejected draft, a retracted binding — takes its records with it.
    ///
    /// @param bindingUuid The binding.
    void discard(String bindingUuid);

    /// Approve rebinds the incumbent to a draft's fragment and the draft's history becomes the
    /// incumbent's: its entries move, appended after what the incumbent already holds.
    ///
    /// @param fromBindingUuid The draft.
    /// @param toBindingUuid   The binding it replaced.
    /// @param cap             How many entries the binding may hold (A63).
    void move(String fromBindingUuid, String toBindingUuid, int cap);

    // --------------------------------------------------------------------------------

    /// What a record became when it was accepted.
    enum Kind {
        /// A real translation: an event with something in its detail.
        TRANSLATION,
        /// An admission: an event whose detail is `Unknown` (A51, A53).
        UNKNOWN,
        /// Nothing: a kind of record that yields no event — a header line, a comment.
        NONE
    }

    /// One record a binding was accepted on.
    ///
    /// @param record     The record as the parser cut it, as one document of its own: the parser's output
    ///                   with this record alone under its ancestors, which is what the transform is given
    ///                   and what a candidate is run over.
    /// @param text       The record's own text as it was received, where the parser knows where it cut —
    ///                   the data splitter does, the JSON and XML parsers do not — else null.
    /// @param preamble   What the stream carried before its first record — a header line, an opening
    ///                   bracket — where the parser knows; else null. What a stream rebuilt from entries
    ///                   begins with.
    /// @param postamble  What the stream carried after its last record, likewise.
    /// @param output     The output the record was accepted with: the transform's events for it, or null
    ///                   where it yielded none.
    /// @param kind       What that output is.
    /// @param score      The weighted score the promoted candidate achieved on the stream it was accepted
    ///                   from.
    /// @param attemptId  The attempt that accepted it, where one was open.
    /// @param acceptedMs When it was accepted.
    /// @param target     What the flow said this kind of record should become (A31), where it settled one.
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

        /// Whether the record can be put back through a parser: it has its own text, and what the stream
        /// wrapped it in.
        public boolean runnable() {
            return text != null;
        }
    }
}
