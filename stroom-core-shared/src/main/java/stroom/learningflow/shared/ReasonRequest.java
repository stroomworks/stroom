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

/// Why a person is acting on a Smart Pipeline's learning: rejecting a drafted translation (A25), retracting
/// one that is serving (A28), giving a learner up, or sending it back to learn afresh (A115).
///
/// The reason is recorded: a rejection or retraction against the attempt that wrote the translation, and a
/// give-up as what the learner's streams error with.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReasonRequest {

    @JsonProperty
    private final String reason;

    /// @param reason Why; required by reject, retract and give up, optional when sending a learner back.
    @JsonCreator
    public ReasonRequest(@JsonProperty("reason") final String reason) {
        this.reason = reason;
    }

    /// @return Why, as the person wrote it; may be null or blank.
    public String getReason() {
        return reason;
    }
}
