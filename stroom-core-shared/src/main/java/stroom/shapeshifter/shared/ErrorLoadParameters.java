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

import stroom.util.shared.Severity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/**
 * Error load (§8.4): diagnostics per record, counted from the given severity upwards.
 */
@JsonPropertyOrder({"minimumSeverity"})
@JsonInclude(Include.NON_NULL)
public final class ErrorLoadParameters extends ScorerParameters {

    @JsonProperty
    private final Severity minimumSeverity;

    @JsonCreator
    public ErrorLoadParameters(@JsonProperty("minimumSeverity") final Severity minimumSeverity) {
        this.minimumSeverity = Objects.requireNonNullElse(minimumSeverity, Severity.ERROR);
    }

    @Override
    public ScorerType scorerType() {
        return ScorerType.ERROR_LOAD;
    }

    public Severity getMinimumSeverity() {
        return minimumSeverity;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return minimumSeverity == ((ErrorLoadParameters) o).minimumSeverity;
    }

    @Override
    public int hashCode() {
        return Objects.hash(minimumSeverity);
    }

    @Override
    public String toString() {
        return minimumSeverity.getDisplayValue() + " and above";
    }
}
