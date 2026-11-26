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

/// A person's answer given in place of the model's for one turn of a session. It serves both "answer
/// instead", for the question a session stopped at, and "edit and re-run from here", for a turn that had
/// already been answered. Who answered is taken from the user making the request, so it is not a field here.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AmendTurnRequest {

    @JsonProperty
    private final String answer;

    @JsonCreator
    public AmendTurnRequest(@JsonProperty("answer") final String answer) {
        this.answer = answer;
    }

    public String getAnswer() {
        return answer;
    }
}
