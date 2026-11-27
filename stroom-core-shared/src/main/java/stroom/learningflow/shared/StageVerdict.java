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

import java.util.List;
import java.util.Objects;

/// The checks' verdict on one element of a chain, as the stepper's Learning Flow pane shows it: the score of
/// every check that applied, their average, and whether the element passed, meaning that no gate fell below
/// its threshold.
///
/// Verdicts are in chain order and identified by position (from 1) rather than by element, because the
/// reader has the fragment, and so its elements, in front of them.
@JsonInclude(Include.NON_NULL)
public class StageVerdict {

    @JsonProperty
    private final int step;
    @JsonProperty
    private final double score;
    @JsonProperty
    private final boolean passed;
    @JsonProperty
    private final List<StageScore> scores;

    @JsonCreator
    public StageVerdict(@JsonProperty("step") final Integer step,
                        @JsonProperty("score") final Double score,
                        @JsonProperty("passed") final Boolean passed,
                        @JsonProperty("scores") final List<StageScore> scores) {
        this.step = Objects.requireNonNullElse(step, 0);
        this.score = Objects.requireNonNullElse(score, 0.0);
        this.passed = Objects.requireNonNullElse(passed, false);
        this.scores = scores;
    }

    public int getStep() {
        return step;
    }

    public double getScore() {
        return score;
    }

    public boolean isPassed() {
        return passed;
    }

    public List<StageScore> getScores() {
        return scores;
    }

    @Override
    public String toString() {
        return "step " + step + ": " + score + (passed
                ? " (passed)"
                : " (failed)");
    }
}
