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

/// Which of the chain's elements a `CONFIGURE` step configures (A37): the `parser` is the chain's first
/// element where it parses raw input; the `transform` is every element after it. A step with no role
/// configures every element in chain order.
public enum ConfigureRole implements HasDisplayValue {
    PARSER("parser"),
    TRANSFORM("transform");

    private final String displayValue;

    ConfigureRole(final String displayValue) {
        this.displayValue = displayValue;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }

    /// @return The role written as `parser` or `transform`, or null for anything else.
    public static ConfigureRole parse(final String word) {
        for (final ConfigureRole role : values()) {
            if (role.displayValue.equalsIgnoreCase(word)) {
                return role;
            }
        }
        return null;
    }
}
