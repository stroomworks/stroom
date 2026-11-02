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
 * What a person wants better about a binding that is already serving (A46).
 * <p>
 * The message is optional: asking again from the incumbent, with the records it was accepted on and the
 * score it achieved on them, is itself worth something. When there is one it is kept as guidance for the
 * learner rather than for this attempt, so it is carried into every question asked about that learner from
 * then on — including the relearning of A29 months later.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImproveRequest {

    @JsonProperty
    private final String message;

    @JsonCreator
    public ImproveRequest(@JsonProperty("message") final String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
