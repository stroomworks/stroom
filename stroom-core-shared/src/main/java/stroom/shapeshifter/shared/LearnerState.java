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

/// Where a learner stands, as the Supervisor's learning pipelines list says it (design 06 A115, A142). A
/// learner is in one at a time, read in this order: given up, then learning off, then waiting for a
/// person, then learning, and serving only where none of those holds.
public enum LearnerState implements HasDisplayValue {

    /// It has given up, by a session's flow or by a person: it learns no more, and its streams with
    /// nothing serving them error, until a person sends it back.
    GIVEN_UP("Given up"),
    /// Its document has learning turned off: nothing is learned, and what serves goes on serving.
    LEARNING_OFF("Learning off"),
    /// A draft awaits review, or a session is parked for a person to answer, grant or exhaust.
    AWAITING_PERSON("Waiting for a person"),
    /// A session is open, or nothing serves it yet and its next stream will open one.
    LEARNING("Learning"),
    /// A binding serves it and nothing else is under way.
    SERVING("Serving");

    private final String displayValue;

    LearnerState(final String displayValue) {
        this.displayValue = displayValue;
    }

    @JsonCreator
    public static LearnerState fromDisplayValue(final String displayValue) {
        for (final LearnerState state : values()) {
            if (state.displayValue.equals(displayValue)) {
                return state;
            }
        }
        throw new IllegalArgumentException("'" + displayValue + "' is not a learner state");
    }

    @Override
    @JsonValue
    public String getDisplayValue() {
        return displayValue;
    }
}
