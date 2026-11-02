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

/**
 * Why a person rejected a draft (A25): the reason is the learner's, and the model is not asked about that
 * learner again until somebody says otherwise.
 * <p>
 * The rejection is offered in the Supervisor view, beside the attempt that drafted it (A28), and this is
 * what it carries.
 */
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
