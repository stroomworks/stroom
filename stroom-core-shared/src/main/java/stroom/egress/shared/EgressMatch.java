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

package stroom.egress.shared;

import stroom.docref.HasDisplayValue;

/// How a rule's destination is compared with a request.
public enum EgressMatch implements HasDisplayValue {
    /// The normalised destination must equal the rule's destination.
    EXACT("Exact"),
    /// The rule's destination must be a prefix of the request's, compared whole component by whole component, so
    /// `/stroom/output` never matches `/stroom/output-old`.
    PREFIX("Prefix");

    private final String displayValue;

    EgressMatch(final String displayValue) {
        this.displayValue = displayValue;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }
}
