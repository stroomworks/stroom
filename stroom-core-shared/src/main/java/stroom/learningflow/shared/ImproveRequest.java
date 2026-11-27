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

/// A person's request to improve a binding that is already serving, with what they want done better.
///
/// The message is optional: asking the model again, starting from the serving binding with the records it was
/// accepted on and the score it achieved on them, can be worthwhile on its own. A message is kept as guidance
/// for the learner rather than for this session alone, so it is carried into every later question about that
/// learner, including any relearning long afterwards.
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
