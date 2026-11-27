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

import java.util.List;
import java.util.Objects;

/// The prompt that one turn of a learning session sent to the model, rebuilt on request for the Supervisor.
///
/// Prompts are not stored, because a stored prompt would be a copy of the stream's text that people without
/// permission to view data could read, and it would outlive the stream under retention. Instead the prompt is
/// rebuilt from what the session recorded and the sample read back from its stream. This holds either the
/// prompt, with notes a reader should know about it, or the reason it can't be shown.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RebuiltPrompt {

    @JsonProperty
    private final String prompt;
    @JsonProperty
    private final List<String> notes;
    @JsonProperty
    private final String refusal;

    /// @param prompt  The system text with the guidance the turn carried, and the question; null where
    ///                it can't be shown.
    /// @param notes   What a reader should know about it, e.g. that Stroom has been upgraded since.
    /// @param refusal Why it can't be shown, where it can't; null where it is.
    @JsonCreator
    public RebuiltPrompt(@JsonProperty("prompt") final String prompt,
                         @JsonProperty("notes") final List<String> notes,
                         @JsonProperty("refusal") final String refusal) {
        this.prompt = prompt;
        this.notes = notes == null
                ? List.of()
                : List.copyOf(notes);
        this.refusal = refusal;
    }

    /// @param refusal Why the prompt can't be shown.
    /// @return A rebuild refused, for that reason.
    public static RebuiltPrompt refused(final String refusal) {
        return new RebuiltPrompt(null, List.of(), refusal);
    }

    /// @return The prompt; null where it can't be shown.
    public String getPrompt() {
        return prompt;
    }

    /// @return What a reader should know about it.
    public List<String> getNotes() {
        return notes;
    }

    /// @return Why it can't be shown; null where it is.
    public String getRefusal() {
        return refusal;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final RebuiltPrompt that = (RebuiltPrompt) o;
        return Objects.equals(prompt, that.prompt)
               && Objects.equals(notes, that.notes)
               && Objects.equals(refusal, that.refusal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(prompt, notes, refusal);
    }

    @Override
    public String toString() {
        return refusal == null
                ? "RebuiltPrompt{" + notes + '}'
                : "RebuiltPrompt{refused: " + refusal + '}';
    }
}
