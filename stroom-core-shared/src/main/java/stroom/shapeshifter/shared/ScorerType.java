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
 * The built-in scorers of design §8.4. A Shapeshifter AI document lists the ones it uses with a weight and threshold
 * each.
 */
public enum ScorerType implements HasDisplayValue {
    COMPILE("Compile", true),
    INPUT_COVERAGE("Input coverage", true),
    YIELD("Yield", true),
    SCHEMA_CONFORMANCE("Schema conformance", true),
    EXTRACTION_QUALITY("Extraction quality", true),
    BUSINESS_RULES("Business rules", true),
    // Settings with no scorer yet: a document naming one is refused when it is scored
    ERROR_LOAD("Error load", false),
    EVENT_CLASSIFICATION("Event classification", false);

    private final String displayValue;
    private final boolean built;

    ScorerType(final String displayValue, final boolean built) {
        this.displayValue = displayValue;
        this.built = built;
    }

    /// @return Whether a scorer of this type exists. One that does not is not offered for a document,
    ///         since a document naming it cannot be scored.
    public boolean isBuilt() {
        return built;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }
}
