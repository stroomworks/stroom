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

import java.util.List;

/**
 * The scorecard's verdict on one step of a chain, as a surface shows it (design 01 §8.4): every scorer
 * that applied, the weighted total of those that carry weight, and whether the step passed.
 * <p>
 * The steps are in chain order and named by position rather than by element, because what a step is
 * belongs to the fragment and a reader has the fragment in front of them.
 */
@JsonInclude(Include.NON_NULL)
public class StageVerdict {

    @JsonProperty
    private final int step;
    @JsonProperty
    private final double weightedTotal;
    @JsonProperty
    private final boolean passed;
    @JsonProperty
    private final List<StageScore> scores;

    @JsonCreator
    public StageVerdict(@JsonProperty("step") final int step,
                        @JsonProperty("weightedTotal") final double weightedTotal,
                        @JsonProperty("passed") final boolean passed,
                        @JsonProperty("scores") final List<StageScore> scores) {
        this.step = step;
        this.weightedTotal = weightedTotal;
        this.passed = passed;
        this.scores = scores;
    }

    public int getStep() {
        return step;
    }

    public double getWeightedTotal() {
        return weightedTotal;
    }

    public boolean isPassed() {
        return passed;
    }

    public List<StageScore> getScores() {
        return scores;
    }

    @Override
    public String toString() {
        return "step " + step + ": " + weightedTotal + (passed
                ? " (passed)"
                : " (failed)");
    }
}
