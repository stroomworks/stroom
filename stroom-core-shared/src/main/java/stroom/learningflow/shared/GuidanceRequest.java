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

/// A note a person leaves for a learner without asking for anything to be run, such as the feed's own
/// explanation of a field, a correction to something the model assumed, or a fact the sample does not show.
///
/// It is separate from [ImproveRequest], which carries the same kind of message and also starts a session. A
/// person who has just learned something about a feed can write it down at once, and it is carried into
/// whatever the model is asked next about that learner, however much later that is. The learner it is for is
/// named by the request's path.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GuidanceRequest {

    @JsonProperty
    private final String message;

    /// @param message What to tell the learner.
    @JsonCreator
    public GuidanceRequest(@JsonProperty("message") final String message) {
        this.message = message;
    }

    /// @return What to tell the learner.
    public String getMessage() {
        return message;
    }
}
