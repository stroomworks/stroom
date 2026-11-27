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

/// The reason a person gives for rejecting a draft binding, or for retracting one that is serving, sent from
/// the Supervisor.
///
/// Rejecting a draft gives the learner up with this reason, so the model is not asked about that learner
/// again until someone sends it back to learn, and the streams waiting on it error with the reason. Retracting
/// a serving binding records the reason against it, and the learner learns afresh from its next stream.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RejectRequest {

    @JsonProperty
    private final String reason;

    @JsonCreator
    public RejectRequest(@JsonProperty("reason") final String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
