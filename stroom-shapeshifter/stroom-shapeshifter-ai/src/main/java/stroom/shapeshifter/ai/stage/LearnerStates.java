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

import java.util.Optional;
import java.util.OptionalDouble;

/// A learner's learning state (A117, A124), one row per learner: whether it has given up and why,
/// whether it is marked to learn again and why, the rolling per-record score of design 01 §5 kept for the
/// Supervisor, and the draft awaiting review (A25). The binding that serves the learner is its one binding
/// that is not a draft, in [LearnerBindings]. A row records the document the learner runs under, for the
/// Supervisor; reads name the learner, and writes name the document as well. In memory in scenarios; the
/// `shapeshifter_learner_state` table in a node.
public interface LearnerStates {

    /// @param learner The learner.
    /// @return Why it gave up, if it has.
    Optional<String> reasonGivenUp(Learner learner);

    /// The learner gives up: its streams error rather than wait, until a person sends it back (A80).
    ///
    /// @param learner The learner.
    /// @param docUuid The document it runs under.
    /// @param reason  Why.
    void giveUp(Learner learner, String docUuid, String reason);

    /// Fold one served stream into the learner's rolling score. The score counts once per record the
    /// stream brought, against a memory of at most `memory` earlier records, so a learner that has been
    /// good for a year is judged on what it has done lately.
    ///
    /// @param learner The learner.
    /// @param docUuid The document it runs under.
    /// @param score   The stream's mean per-record score.
    /// @param records How many records the stream brought.
    /// @param memory  How many records the rolling score remembers.
    /// @return The rolling score, once the learner has brought at least `memory` records since its state
    /// was last reset; empty before that, when it would rest on too little to act on.
    OptionalDouble scored(Learner learner, String docUuid, double score, int records, int memory);

    /// @param learner The learner.
    /// @return Why it is marked to learn again, if it is.
    Optional<String> relearnReason(Learner learner);

    /// Mark the learner to learn again: its next stream that brings enough records opens a session.
    ///
    /// @param learner The learner.
    /// @param docUuid The document it runs under.
    /// @param reason  Why, which opens the session.
    void markForRelearning(Learner learner, String docUuid, String reason);

    /// A draft was written for the learner and waits for Approve or Reject (A25).
    ///
    /// @param learner     The learner.
    /// @param docUuid     The document it runs under.
    /// @param bindingUuid The draft's uuid.
    void awaitReview(Learner learner, String docUuid, String bindingUuid);

    /// @param learner The learner.
    /// @return The draft awaiting review for the learner, if there is one.
    Optional<String> draftAwaiting(Learner learner);

    /// The learner is bound, or unknown again: not given up, not marked, nothing awaiting review, and its
    /// rolling score starts afresh.
    ///
    /// @param learner The learner.
    void reset(Learner learner);

    /// What the learner has been scoring lately and over how many records, for a person reading a list
    /// of what serves (A46). Read straight from the rows, as [#scored] writes them.
    ///
    /// @param learner The learner.
    /// @return Empty where the learner has no state, or one that has never been scored.
    Optional<Rolling> rolling(Learner learner);


    // --------------------------------------------------------------------------------


    /// A learner's rolling score and the traffic behind it.
    ///
    /// @param score   The mean per-record score over the rolling memory (design 01 §5).
    /// @param records How many records that mean rests on, capped at the memory.
    record Rolling(double score, int records) {

    }
}
