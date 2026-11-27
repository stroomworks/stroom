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

package stroom.learningflow.client.component;

import stroom.docref.HasDisplayValue;

/// The sections of a Learning Flow document's Setup tab ([SetupTab]).
///
/// They are listed in the order a translation passes through them: how it is learned, what a good one looks
/// like, when it is put to use, and how it is monitored once in use.
public enum SetupSection implements HasDisplayValue {
    /// Whether the document learns at all, which AI model it uses, and the instructions the model is given.
    LEARNING("AI model and instructions"),
    /// The steps a session takes, the parsers and filters it may choose from, and what a run may spend.
    APPROACH("Learning steps"),
    /// The checks a translation is scored against, both while it is learned and once it is in use.
    QUALITY("Quality checks"),
    /// When a new translation is put to use, and what happens to a feed when learning cannot finish.
    GOING_LIVE("Approving new translations"),
    /// How a translation is monitored once it is in use.
    IN_SERVICE("Monitoring translations in use");

    private final String displayValue;

    SetupSection(final String displayValue) {
        this.displayValue = displayValue;
    }

    /// @return The section's heading, e.g. "Quality checks".
    @Override
    public String getDisplayValue() {
        return displayValue;
    }
}
