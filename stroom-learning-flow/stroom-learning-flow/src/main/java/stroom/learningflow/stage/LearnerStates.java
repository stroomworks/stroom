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

import java.util.Optional;
import java.util.OptionalDouble;

/// Storage for each learner's learning state, one row per learner: whether it has given up and why, whether
/// it is marked to learn again and why, its rolling per-record score (shown in the Supervisor), and the
/// draft awaiting review under review promotion mode.
///
/// The binding that serves the learner is not held here; it is the learner's one non-draft binding in
/// [LearnerBindings]. A row records the document the learner runs under, for the Supervisor, so reads name
/// only the learner but writes name the document as well. Tests use an in-memory implementation; a node uses
/// the `learning_flow_learner_state` table.
public interface LearnerStates {

    /// @param learner The learner to look up.
    /// @return Why it gave up, if it has.
    Optional<String> reasonGivenUp(Learner learner);

    /// Records that the learner has given up. Its streams then error rather than wait, until a person sends
    /// it back to learn again.
    ///
    /// @param learner The learner giving up.
    /// @param docUuid The UUID of the document it runs under.
    /// @param reason  Why it gave up, shown to a person and given as the error for its streams.
    void giveUp(Learner learner, String docUuid, String reason);

    /// Folds one served stream into the learner's rolling score. Each record the stream brought counts once,
    /// against a memory of at most `memory` earlier records, so a learner that has been good for a year is
    /// still judged on what it has done lately.
    ///
    /// @param learner The learner that served the stream.
    /// @param docUuid The UUID of the document it runs under.
    /// @param score   The stream's mean per-record score.
    /// @param records How many records the stream brought.
    /// @param memory  How many records the rolling score remembers.
    /// @return The rolling score, once the learner has brought at least `memory` records since its state
    /// was last reset; empty before that, when it would rest on too little to act on.
    default OptionalDouble scored(final Learner learner,
                                  final String docUuid,
                                  final double score,
                                  final int records,
                                  final int memory) {
        return scored(learner, docUuid, score, records, 0, memory);
    }

    /// As [#scored(Learner, String, double, int, int)], also recording how many of the stream's records
    /// the translation declared it deliberately skipped (`DeclaredSkips`). The skip rate is deliberately
    /// not capped, so making the count visible is the only thing that stops a translation quietly skipping
    /// its awkward records. It is therefore kept beside the rolling score and shown wherever that is shown.
    ///
    /// @param learner The learner that served the stream.
    /// @param docUuid The UUID of the document it runs under.
    /// @param score   The stream's mean per-record score.
    /// @param records How many records the stream brought.
    /// @param skipped How many of them the translation declared it skipped.
    /// @param memory  How many records the rolling score remembers.
    /// @return The rolling score, as above.
    OptionalDouble scored(Learner learner, String docUuid, double score, int records, int skipped, int memory);

    /// @param learner The learner to look up.
    /// @return Why it is marked to learn again, if it is.
    Optional<String> relearnReason(Learner learner);

    /// Marks the learner to learn again: its next stream that brings enough records opens a session.
    ///
    /// @param learner The learner to mark.
    /// @param docUuid The UUID of the document it runs under.
    /// @param reason  Why it should learn again, used as the opening reason of the session.
    void markForRelearning(Learner learner, String docUuid, String reason);

    /// Records that a draft was written for the learner and is waiting for a person to approve or reject
    /// it, under review promotion mode.
    ///
    /// @param learner     The learner the draft is for.
    /// @param docUuid     The UUID of the document it runs under.
    /// @param bindingUuid The draft binding's UUID.
    void awaitReview(Learner learner, String docUuid, String bindingUuid);

    /// @param learner The learner to look up.
    /// @return The UUID of the draft awaiting review for the learner, if there is one.
    Optional<String> draftAwaiting(Learner learner);

    /// Clears the learner's state, when it has been bound or is to start again from nothing: it is no
    /// longer given up or marked, nothing awaits review, and its rolling score starts afresh.
    ///
    /// @param learner The learner to reset.
    void reset(Learner learner);

    /// Gets what the learner has been scoring lately and over how many records, for a person reading the
    /// list of what serves in the Supervisor.
    ///
    /// @param learner The learner to look up.
    /// @return Empty where the learner has no state, or one that has never been scored.
    Optional<Rolling> rolling(Learner learner);


    // --------------------------------------------------------------------------------


    /// A learner's rolling score and the traffic behind it.
    ///
    /// @param score   The mean per-record score over the rolling memory.
    /// @param records How many records that mean rests on, capped at the memory.
    /// @param skips   How many of those records the translation declared it deliberately skipped.
    record Rolling(double score, int records, int skips) {

    }
}
