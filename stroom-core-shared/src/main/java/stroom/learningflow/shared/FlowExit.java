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

/// Where a session goes when it leaves the flow without producing a binding: parked for a person, or the
/// learner given up and its streams errored. A transition names one by going to `park` or `give-up`; one that
/// names no step takes the step's own default, which is give-up for a parser (a stream that cannot be parsed
/// is the one real failure) and park for any other step.
public enum FlowExit implements HasDisplayValue {
    /// The session stops and waits for a person, who can grant more budget, answer a turn, or send it to the
    /// fallback step that writes marked Unknowns.
    PARK("park"),
    /// The learner is given up and the streams waiting on it error with the reason.
    GIVE_UP("give-up");

    private final String displayValue;

    FlowExit(final String displayValue) {
        this.displayValue = displayValue;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }
}
