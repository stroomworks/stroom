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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/// Why a learning run stopped, in three short parts rather than one long sentence: the **error**, a line
/// saying what happened; the **details**, the evidence; and the **suggestions**, what a person can do about
/// it. The Feeds tab shows them under those headings, and the plain-text places (Error streams, the
/// Monitoring Supervisor's decision) show the same parts labelled ([#lines()]). The **cause** says which
/// kind of stop it was, so that a screen can choose its fixes without reading the words.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StopReport {

    @JsonProperty
    private final StopCause cause;
    @JsonProperty
    private final String step;
    @JsonProperty
    private final String error;
    @JsonProperty
    private final List<String> details;
    @JsonProperty
    private final List<Suggestion> suggestions;

    /// @param cause       Which kind of stop it was.
    /// @param step        The id of the step it stopped at, or null where it stopped at none.
    /// @param error       What happened, in one line.
    /// @param details     The evidence, a line each; null for none.
    /// @param suggestions What a person can do; null for none.
    @JsonCreator
    public StopReport(@JsonProperty("cause") final StopCause cause,
                      @JsonProperty("step") final String step,
                      @JsonProperty("error") final String error,
                      @JsonProperty("details") final List<String> details,
                      @JsonProperty("suggestions") final List<Suggestion> suggestions) {
        this.cause = cause;
        this.step = step;
        this.error = error;
        this.details = details == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(details));
        this.suggestions = suggestions == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(suggestions));
    }

    /// @return Which kind of stop it was.
    public StopCause getCause() {
        return cause;
    }

    /// @return The id of the step it stopped at, or null where it stopped at none.
    public String getStep() {
        return step;
    }

    /// @return What happened, in one line.
    public String getError() {
        return error;
    }

    /// @return The evidence, a line each.
    public List<String> getDetails() {
        return details;
    }

    /// @return What a person can do.
    public List<Suggestion> getSuggestions() {
        return suggestions;
    }

    /// The report as labelled lines, for the places that can only show text: "Error: …", then "Details: …"
    /// for each detail, then "Suggestion: …" for each suggestion.
    ///
    /// @return The lines.
    public List<String> lines() {
        final List<String> lines = new ArrayList<>();
        lines.add("Error: " + error);
        for (final String detail : details) {
            lines.add("Details: " + detail);
        }
        for (final Suggestion suggestion : suggestions) {
            lines.add("Suggestion: " + suggestion.getText());
        }
        return lines;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final StopReport that = (StopReport) o;
        return cause == that.cause && Objects.equals(step, that.step) && Objects.equals(error, that.error)
               && details.equals(that.details) && suggestions.equals(that.suggestions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cause, step, error, details, suggestions);
    }

    @Override
    public String toString() {
        return String.join("\n", lines());
    }
}
