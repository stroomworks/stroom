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

/// What a record a translation was learned and checked on became.
public enum RecordKind implements HasDisplayValue {
    /// A real translation: an event with something in its detail.
    TRANSLATION("Translated"),
    /// A marked Unknown event, written for a kind of record the model could not translate.
    UNKNOWN("Marked Unknown"),
    /// Nothing: a kind of record that yields no event, e.g. a header line.
    NONE("Nothing");

    private final String displayValue;

    RecordKind(final String displayValue) {
        this.displayValue = displayValue;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }
}
