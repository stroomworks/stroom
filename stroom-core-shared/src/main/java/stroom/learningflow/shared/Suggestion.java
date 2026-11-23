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
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/// One thing a person can do about a stopped learning run, e.g. "Raise 'Time for each run' on the Setup
/// tab", with the action that does it where a screen can.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Suggestion {

    @JsonProperty
    private final String text;
    @JsonProperty
    private final SuggestionAction action;

    /// @param text   What to do, as a sentence.
    /// @param action What does it, or null where nothing on a screen can.
    @JsonCreator
    public Suggestion(@JsonProperty("text") final String text,
                      @JsonProperty("action") final SuggestionAction action) {
        this.text = text;
        this.action = action;
    }

    /// @return What to do, as a sentence.
    public String getText() {
        return text;
    }

    /// @return What does it, or null where nothing on a screen can.
    public SuggestionAction getAction() {
        return action;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final Suggestion that = (Suggestion) o;
        return Objects.equals(text, that.text) && action == that.action;
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, action);
    }

    @Override
    public String toString() {
        return text;
    }
}
