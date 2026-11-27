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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/// The result of one check on one element of a chain, as the stepper's Learning Flow pane shows it: the
/// value, the threshold it was measured against, and whether the check is a gate (so falling short refuses the
/// candidate) or only advice.
@JsonInclude(Include.NON_NULL)
public class StageScore {

    @JsonProperty
    private final Check check;
    @JsonProperty
    private final double value;
    @JsonProperty
    private final double threshold;
    @JsonProperty
    private final boolean gate;
    @JsonProperty
    private final boolean met;

    @JsonCreator
    public StageScore(@JsonProperty("check") final Check check,
                      @JsonProperty("value") final Double value,
                      @JsonProperty("threshold") final Double threshold,
                      @JsonProperty("gate") final Boolean gate,
                      @JsonProperty("met") final Boolean met) {
        this.check = check;
        this.value = Objects.requireNonNullElse(value, 0.0);
        this.threshold = Objects.requireNonNullElse(threshold, 0.0);
        this.gate = Objects.requireNonNullElse(gate, false);
        this.met = Objects.requireNonNullElse(met, false);
    }

    public Check getCheck() {
        return check;
    }

    public double getValue() {
        return value;
    }

    public double getThreshold() {
        return threshold;
    }

    public boolean isGate() {
        return gate;
    }

    public boolean isMet() {
        return met;
    }

    @Override
    public String toString() {
        return check + " " + value + (met
                ? " (met "
                : " (missed ") + threshold + (gate
                ? ", a gate)"
                : ")");
    }
}
