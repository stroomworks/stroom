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
 * What the yield scorer counts output records against (§8.4): input records for a transformation stage,
 * input lines or bytes for an extraction stage, where there are no records yet.
 */
public enum YieldBasis implements HasDisplayValue {
    RECORDS("Input records"),
    LINES("Input lines"),
    BYTES("Input bytes");

    private final String displayValue;

    YieldBasis(final String displayValue) {
        this.displayValue = displayValue;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }
}
