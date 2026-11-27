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

/// What a check does on a Learning Flow document: nothing, advise, or act as a gate.
public enum CheckState implements HasDisplayValue {
    /// The check is neither run nor shown.
    OFF("Off"),
    /// Run, shown beside a draft and fed back to the model. A step's transition that names it still fires,
    /// but it never refuses a candidate.
    ADVICE("Advice"),
    /// Refuses a candidate that falls below its threshold; one that judges records also holds back a served
    /// record that fails it.
    GATE("Gate");

    private final String displayValue;

    CheckState(final String displayValue) {
        this.displayValue = displayValue;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }
}
