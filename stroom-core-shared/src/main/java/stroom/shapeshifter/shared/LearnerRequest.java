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

package stroom.shapeshifter.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/// A person acting on a learner from its row in the learning pipelines list (design 06 A115): sending it
/// back to learn again, or giving it up. Says why, since the reason is what the learner's next session
/// opens with, or what its streams error with.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LearnerRequest {

    @JsonProperty
    private final LearnerRef learner;
    @JsonProperty
    private final String reason;

    @JsonCreator
    public LearnerRequest(@JsonProperty("learner") final LearnerRef learner,
                          @JsonProperty("reason") final String reason) {
        this.learner = learner;
        this.reason = reason;
    }

    /// The learner, as the row named it (A128).
    public LearnerRef getLearner() {
        return learner;
    }

    public String getReason() {
        return reason;
    }
}
