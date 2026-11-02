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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/**
 * Yield (§8.4): output records per unit of input, scored by how close the ratio comes to what the document
 * expects. One record per input record is the transformation default; an extraction stage sets the
 * basis to lines or bytes and the ratio to what its data yields.
 */
@JsonPropertyOrder({"expectedRatio", "basis"})
@JsonInclude(Include.NON_NULL)
public final class YieldParameters extends ScorerParameters {

    private static final double DEFAULT_EXPECTED_RATIO = 1.0;

    @JsonProperty
    private final double expectedRatio;
    @JsonProperty
    private final YieldBasis basis;

    @JsonCreator
    public YieldParameters(@JsonProperty("expectedRatio") final Double expectedRatio,
                           @JsonProperty("basis") final YieldBasis basis) {
        this.expectedRatio = Objects.requireNonNullElse(expectedRatio, DEFAULT_EXPECTED_RATIO);
        this.basis = Objects.requireNonNullElse(basis, YieldBasis.RECORDS);
    }

    @Override
    public ScorerType scorerType() {
        return ScorerType.YIELD;
    }

    public double getExpectedRatio() {
        return expectedRatio;
    }

    public YieldBasis getBasis() {
        return basis;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final YieldParameters that = (YieldParameters) o;
        return Double.compare(expectedRatio, that.expectedRatio) == 0 && basis == that.basis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(expectedRatio, basis);
    }

    @Override
    public String toString() {
        return expectedRatio + " per " + basis.getDisplayValue().toLowerCase();
    }
}
