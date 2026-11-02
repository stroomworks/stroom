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

package stroom.shapeshifter.shared;

import stroom.docref.HasDisplayValue;

/**
 * Whether the stage may call the model at all. Design §11 makes Shapeshifter AI opt-in per document and ruling A9
 * removed the human-approval mode, so the only choices are on and off.
 */
public enum LearningMode implements HasDisplayValue {
    AUTOMATIC("Automatic"),
    DISABLED("Disabled");

    private final String displayValue;

    LearningMode(final String displayValue) {
        this.displayValue = displayValue;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }
}
