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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What one scorer made of one step of a chain, as a surface shows it (design 01 §8): the number, what it
 * was measured against, and whether that was a gate it failed or a threshold it merely fell short of.
 */
@JsonInclude(Include.NON_NULL)
public class StageScore {

    @JsonProperty
    private final ScorerType scorer;
    @JsonProperty
    private final double value;
    @JsonProperty
    private final double threshold;
    @JsonProperty
    private final boolean gate;
    @JsonProperty
    private final boolean met;

    @JsonCreator
    public StageScore(@JsonProperty("scorer") final ScorerType scorer,
                      @JsonProperty("value") final double value,
                      @JsonProperty("threshold") final double threshold,
                      @JsonProperty("gate") final boolean gate,
                      @JsonProperty("met") final boolean met) {
        this.scorer = scorer;
        this.value = value;
        this.threshold = threshold;
        this.gate = gate;
        this.met = met;
    }

    public ScorerType getScorer() {
        return scorer;
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
        return scorer + " " + value + (met
                ? " (met "
                : " (missed ") + threshold + (gate
                ? ", a gate)"
                : ")");
    }
}
