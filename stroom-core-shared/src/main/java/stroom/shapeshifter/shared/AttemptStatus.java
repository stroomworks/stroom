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

package stroom.shapeshifter.shared;

import stroom.docref.HasDisplayValue;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/// Where an attempt stands (A28): the states the Supervisor view filters by. An attempt is in one of them
/// at a time, and the ones that end it — everything but [#IN_PROGRESS], [#AWAITING_MODEL], [#PARKED] and
/// [#AWAITING_REVIEW] — are final.
public enum AttemptStatus implements HasDisplayValue {

    /// A node is working on it now.
    IN_PROGRESS("In progress"),
    /// Parked until the deferred worker puts its next question to the model (A5).
    AWAITING_MODEL("Awaiting model"),
    /// Learned, and waiting for a person to approve or reject what it wrote (A25).
    AWAITING_REVIEW("Awaiting review"),
    /// Out of budget, or out of candidates, before its stream cleared the bar: parked for a person (A65),
    /// holding its learner with a lease that does not lapse, and left alone by the worker until somebody
    /// answers it, gives it more to spend, or sends it to the exhaustion step.
    PARKED("Parked"),
    /// Bound on too few records to judge it on (A14); it promotes or retracts when enough arrive.
    PROVISIONAL("Provisional"),
    /// Bound, and judged on enough records to mean it.
    PROMOTED("Promoted"),
    /// A person refused what it wrote (A25).
    REJECTED("Rejected"),
    /// What it bound has been taken back, by a person or by the gate (design 01 §6, §11.6). The learner
    /// knows nothing again rather than giving up: the binding was wrong, not the feed, so the learner's
    /// next stream is learned afresh.
    RETRACTED("Retracted"),
    /// It ran out of candidates, budget or sense, and wrote nothing.
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
