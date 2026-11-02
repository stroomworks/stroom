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
 * When a step of the flow is asked (design 01 §10.2): always; only where the input is raw text and the first
 * element is a parser with a configuration to write; or only where the input is already records.
 */
public enum StepGuard implements HasDisplayValue {
    ALWAYS("always"),
    TEXT("text"),
    XML("xml"),
    JSON("json");

    private final String displayValue;

    StepGuard(final String displayValue) {
        this.displayValue = displayValue;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }

    /**
     * @return The guard written as {@code always}, {@code text}, {@code xml} or {@code json}, or null for anything
     * else.
     */
    public static StepGuard parse(final String word) {
        for (final StepGuard guard : values()) {
            if (guard.displayValue.equalsIgnoreCase(word)) {
                return guard;
            }
        }
        return null;
    }
}
