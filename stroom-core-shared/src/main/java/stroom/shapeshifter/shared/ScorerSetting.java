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

import stroom.util.shared.SerialisationTestConstructor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/**
 * One scorer from the set of design §8.4 as a Shapeshifter AI document applies it: how much it contributes to the
 * weighted
 * total, the score below which the attempt fails, whether it is a gate, and the parameters the scorer
 * itself takes. A gate must pass on its own whatever the weighted total says (§8.3: schema conformance
 * is a gate, not a maximand).
 */
@JsonPropertyOrder({"type", "weight", "threshold", "gate", "parameters"})
@JsonInclude(Include.NON_NULL)
public class ScorerSetting {

    private static final double DEFAULT_WEIGHT = 1.0;
    private static final double DEFAULT_THRESHOLD = 0.0;

    @JsonProperty
    private final ScorerType type;
    @JsonProperty
    private final double weight;
    @JsonProperty
    private final double threshold;
    @JsonProperty
    private final boolean gate;
    /**
     * The scorer's own parameters, of the class that belongs to its type; the type's defaults when
     * absent, null for a scorer that takes none.
     */
    @JsonProperty
    private final ScorerParameters parameters;

    @JsonCreator
    public ScorerSetting(@JsonProperty("type") final ScorerType type,
                         @JsonProperty("weight") final Double weight,
                         @JsonProperty("threshold") final Double threshold,
                         @JsonProperty("gate") final Boolean gate,
                         @JsonProperty("parameters") final ScorerParameters parameters) {
        this.type = Objects.requireNonNull(type, "A scorer setting needs a scorer type");
        this.weight = Objects.requireNonNullElse(weight, DEFAULT_WEIGHT);
        this.threshold = Objects.requireNonNullElse(threshold, DEFAULT_THRESHOLD);
        this.gate = Objects.requireNonNullElse(gate, false);
        if (parameters != null && parameters.scorerType() != type) {
            throw new IllegalArgumentException("Parameters for " + parameters.scorerType()
                                               + " given to the " + type + " scorer");
        }
        this.parameters = parameters == null
                ? ScorerParameters.defaultsFor(type)
                : parameters;
    }

    @SerialisationTestConstructor
    private ScorerSetting() {
        this(ScorerType.COMPILE, null, null, null, null);
    }

    public ScorerType getType() {
        return type;
    }

    public double getWeight() {
        return weight;
    }

    public double getThreshold() {
        return threshold;
    }

    public boolean isGate() {
        return gate;
    }

    public ScorerParameters getParameters() {
        return parameters;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ScorerSetting that = (ScorerSetting) o;
        return Double.compare(weight, that.weight) == 0 &&
               Double.compare(threshold, that.threshold) == 0 &&
               gate == that.gate &&
               type == that.type &&
               Objects.equals(parameters, that.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, weight, threshold, gate, parameters);
    }

    @Override
    public String toString() {
        return "ScorerSetting{" +
               "type=" + type +
               ", weight=" + weight +
               ", threshold=" + threshold +
               ", gate=" + gate +
               ", parameters=" + parameters +
               '}';
    }
}
