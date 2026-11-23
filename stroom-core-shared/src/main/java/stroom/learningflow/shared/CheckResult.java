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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/// How a new translation did on one check, kept beside the run that learned it so a person approving it
/// can see why it passed (Track 2 step 4, approval evidence): the gates it cleared and the advice it was
/// given. Numbers only, never what the check said about a record, as that quotes the stream's own text
/// (A146).
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CheckResult {

    @JsonProperty
    private final Check check;
    @JsonProperty
    private final CheckState state;
    @JsonProperty
    private final double score;
    @JsonProperty
    private final double threshold;

    /// @param check     The check.
    /// @param state     Whether it was advice or a gate.
    /// @param score     What the translation scored on it: the least of the steps it judged.
    /// @param threshold The score it had to reach.
    @JsonCreator
    public CheckResult(@JsonProperty("check") final Check check,
                       @JsonProperty("state") final CheckState state,
                       @JsonProperty("score") final double score,
                       @JsonProperty("threshold") final double threshold) {
        this.check = check;
        this.state = state;
        this.score = score;
        this.threshold = threshold;
    }

    /// @return The check.
    public Check getCheck() {
        return check;
    }

    /// @return Whether it was advice or a gate.
    public CheckState getState() {
        return state;
    }

    /// @return What the translation scored on it.
    public double getScore() {
        return score;
    }

    /// @return The score it had to reach.
    public double getThreshold() {
        return threshold;
    }

    /// @return Whether the score reached the threshold.
    @JsonIgnore
    public boolean isMet() {
        return score >= threshold;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final CheckResult that = (CheckResult) o;
        return Double.compare(score, that.score) == 0
               && Double.compare(threshold, that.threshold) == 0
               && check == that.check
               && state == that.state;
    }

    @Override
    public int hashCode() {
        return Objects.hash(check, state, score, threshold);
    }

    @Override
    public String toString() {
        return check + " " + state + " " + score + "/" + threshold;
    }
}
