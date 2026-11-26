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

/// One built-in example flow as the server sends it to the client: which example it is, its display name, and
/// its steps.
///
/// The steps are defined as JSON on the server, read by `FlowExamples`, because the client has neither
/// classpath resources nor Jackson. A document the client opens already carries its own steps; the client
/// needs these to tell which example a document's steps match, and to start a new document from one.
@JsonInclude(Include.NON_NULL)
public class BuiltInFlow {

    @JsonProperty
    private final FlowExample example;
    @JsonProperty
    private final String displayValue;
    @JsonProperty
    private final List<FlowStep> steps;

    @JsonCreator
    public BuiltInFlow(@JsonProperty("example") final FlowExample example,
                       @JsonProperty("displayValue") final String displayValue,
                       @JsonProperty("steps") final List<FlowStep> steps) {
        this.example = example;
        this.displayValue = displayValue;
        this.steps = steps == null
                ? List.of()
                : List.copyOf(steps);
    }

    /// @return Which setup this is.
    public FlowExample getExample() {
        return example;
    }

    /// @return What to call it on screen.
    public String getDisplayValue() {
        return displayValue;
    }

    /// @return Its steps, in order.
    public List<FlowStep> getSteps() {
        return steps;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof final BuiltInFlow that)) {
            return false;
        }
        return example == that.example
               && Objects.equals(displayValue, that.displayValue)
               && Objects.equals(steps, that.steps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(example, displayValue, steps);
    }

    @Override
    public String toString() {
        return "BuiltInFlow{example=" + example + ", steps=" + steps.size() + '}';
    }
}
