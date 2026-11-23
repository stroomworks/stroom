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

/// The sections of a Learning Flow document's Setup tab ([SetupTab]), in the order a translation meets
/// them: how it is learned, what a good one looks like, when it is put to use, and how it is watched in use.
public enum SetupSection implements HasDisplayValue {
    /// Whether the document learns, with what model, and what the AI is told.
    LEARNING("AI model and instructions"),
    /// The steps it takes, the parsers and filters it may choose from, and what a run may spend.
    APPROACH("Learning steps"),
    /// The checks a translation is scored against, while it is learned and once it is in use.
    QUALITY("Quality checks"),
    /// When a new translation is put to use, and what a feed gets when learning can't finish.
    GOING_LIVE("Approving new translations"),
    /// How a translation in use is watched.
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
