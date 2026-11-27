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

package stroom.learningflow.shared;

import stroom.docref.HasDisplayValue;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/// Where a learning session (attempt) stands, as recorded on its row and shown in the Supervisor. A session is
/// in one status at a time. Every status except [#IN_PROGRESS], [#AWAITING_MODEL], [#PARKED] and
/// [#AWAITING_REVIEW] ends the session and is final.
public enum AttemptStatus implements HasDisplayValue {

    /// A node is working on it now.
    IN_PROGRESS("In progress"),
    /// Waiting for the background learning worker to put its next question to the model.
    AWAITING_MODEL("Awaiting model"),
    /// Learned, and waiting for a person to approve or reject the draft it wrote.
    AWAITING_REVIEW("Awaiting review"),
    /// Out of budget, or out of candidates, before its stream cleared the bar, so stopped for a person. It
    /// keeps its claim on its learner with a lease that does not expire, and the worker leaves it alone until
    /// somebody answers it, gives it more to spend, or sends it to the fallback step.
    PARKED("Parked"),
    /// Its candidate passed the promotion gate and became the learner's binding.
    PROMOTED("Promoted"),
    /// A person rejected the draft it wrote.
    REJECTED("Rejected"),
    /// A person took back the binding it promoted. The learner is reset rather than given up, because the
    /// binding was wrong, not the feed, so the learner's next stream is learned afresh.
    RETRACTED("Retracted"),
    /// It ended without writing a binding: its flow gave the learner up, or it was no longer wanted when the
    /// worker came to resume it (for example, learning had been turned off or the learner given up).
    ABANDONED("Abandoned"),
    /// Something outside the conversation failed: the model, the node, the database.
    ERROR("Error");

    private final String displayValue;

    AttemptStatus(final String displayValue) {
        this.displayValue = displayValue;
    }

    @JsonCreator
    public static AttemptStatus fromDisplayValue(final String displayValue) {
        for (final AttemptStatus status : values()) {
            if (status.displayValue.equals(displayValue)) {
                return status;
            }
        }
        throw new IllegalArgumentException("'" + displayValue + "' is not an attempt status");
    }

    @Override
    @JsonValue
    public String getDisplayValue() {
        return displayValue;
    }
}
