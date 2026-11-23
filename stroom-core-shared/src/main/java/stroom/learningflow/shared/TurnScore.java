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

/// The score of the check that decided a turn (design 06 A144): on a shortfall the check that fell short,
/// on a pass the check that passed by the least. A turn judged by no check — a chain chosen, a
/// configuration that did not compile, a refusal — has none.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TurnScore {

    @JsonProperty
    private final Check check;
    @JsonProperty
    private final double score;
    @JsonProperty
    private final double threshold;

    /// @param check     The check that decided the turn.
    /// @param score     What the answer scored on it.
    /// @param threshold The document's threshold for it.
    @JsonCreator
    public TurnScore(@JsonProperty("check") final Check check,
                     @JsonProperty("score") final double score,
                     @JsonProperty("threshold") final double threshold) {
        this.check = check;
        this.score = score;
        this.threshold = threshold;
    }

    /// @return The check that decided the turn.
    public Check getCheck() {
        return check;
    }

    /// @return What the answer scored on it.
    public double getScore() {
        return score;
    }

    /// @return The document's threshold for it.
    public double getThreshold() {
        return threshold;
    }

    /// @return Whether the score met the threshold.
    @JsonIgnore
    public boolean isMet() {
        return score >= threshold;
    }

    /// @return The score as a person reads it beside the turn, e.g. `Coverage 0.41 < 0.8`.
    public String format() {
        return (check == null
                ? ""
                : check.getName()) + " " + rounded(score) + (isMet()
                ? " ≥ "
                : " < ") + rounded(threshold);
    }

    // Three places, by arithmetic rather than a formatter, since the client compiles this too.
    private static String rounded(final double value) {
        return Double.toString(Math.round(value * 1000.0) / 1000.0);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final TurnScore that = (TurnScore) o;
        return Double.compare(score, that.score) == 0
               && Double.compare(threshold, that.threshold) == 0
               && check == that.check;
    }

    @Override
    public int hashCode() {
        return Objects.hash(check, score, threshold);
    }

    @Override
    public String toString() {
        return format();
    }
}
