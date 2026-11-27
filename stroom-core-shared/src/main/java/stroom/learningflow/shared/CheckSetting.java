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

import stroom.util.shared.SerialisationTestConstructor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/// How a Learning Flow document applies one check: whether it is Off, Advice or a Gate, the lowest value that
/// passes, and the check's own parameters. Checks have no weights; a candidate passes when every gate passes.
@JsonPropertyOrder({"check", "state", "threshold", "parameters"})
@JsonInclude(Include.NON_NULL)
public class CheckSetting {

    @JsonProperty
    private final Check check;
    @JsonProperty
    private final CheckState state;
    /// The lowest passing value, for a check that has a threshold; null for one that only passes or fails.
    @JsonProperty
    private final Double threshold;
    /// The check's own parameters, of the class that belongs to it; its defaults when absent, null for a
    /// check that takes none.
    @JsonProperty
    private final ScorerParameters parameters;

    /// @param check      The check.
    /// @param state      What it does; Off when null.
    /// @param threshold  The lowest passing value; the check's default when null, and ignored for a check
    ///                   that only passes or fails.
    /// @param parameters The check's own parameters; the check's defaults when null.
    @JsonCreator
    public CheckSetting(@JsonProperty("check") final Check check,
                        @JsonProperty("state") final CheckState state,
                        @JsonProperty("threshold") final Double threshold,
                        @JsonProperty("parameters") final ScorerParameters parameters) {
        this.check = Objects.requireNonNull(check, "A check setting needs a check");
        this.state = Objects.requireNonNullElse(state, CheckState.OFF);
        this.threshold = check.hasThreshold()
                ? Objects.requireNonNullElse(threshold, check.getDefaultThreshold())
                : null;
        if (parameters != null && parameters.check() != check) {
            throw new IllegalArgumentException("Parameters for " + parameters.check().getName()
                                               + " given to the " + check.getDisplayValue() + " check");
        }
        this.parameters = parameters == null && check.isScored()
                ? ScorerParameters.defaultsFor(check)
                : parameters;
    }

    @SerialisationTestConstructor
    private CheckSetting() {
        this(Check.COMPILE, CheckState.GATE, null, null);
    }

    /// @param check The check.
    /// @param state What it does.
    /// @return The check with its default threshold and parameters.
    public static CheckSetting of(final Check check, final CheckState state) {
        return new CheckSetting(check, state, null, null);
    }

    public Check getCheck() {
        return check;
    }

    public CheckState getState() {
        return state;
    }

    /// @return The lowest passing value, or null for a check that only passes or fails.
    public Double getThreshold() {
        return threshold;
    }

    public ScorerParameters getParameters() {
        return parameters;
    }

    /// @return The lowest passing value, as a number: 1 for a check that only passes or fails.
    @JsonIgnore
    public double getPassingValue() {
        return threshold == null
                ? 1.0
                : threshold;
    }

    /// @return Whether the check runs at all.
    @JsonIgnore
    public boolean isOn() {
        return state != CheckState.OFF;
    }

    /// @return Whether the check refuses a candidate that falls short.
    @JsonIgnore
    public boolean isGate() {
        return state == CheckState.GATE;
    }

    /// @param state What the check should do.
    /// @return This setting with that state.
    public CheckSetting withState(final CheckState state) {
        return new CheckSetting(check, state, threshold, parameters);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final CheckSetting that = (CheckSetting) o;
        return check == that.check
               && state == that.state
               && Objects.equals(threshold, that.threshold)
               && Objects.equals(parameters, that.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(check, state, threshold, parameters);
    }

    @Override
    public String toString() {
        return "CheckSetting{"
               + "check=" + check
               + ", state=" + state
               + ", threshold=" + threshold
               + ", parameters=" + parameters
               + '}';
    }
}
