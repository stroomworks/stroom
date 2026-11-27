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

import java.util.Objects;

/// One note a person has left for a learner, as the Supervisor shows it: a hint, a correction, or a fact
/// about the feed that no sample of it shows.
///
/// Guidance belongs to the learner, not to a turn or a session, so it can be given at any time and is never
/// refused for arriving at the wrong moment. A note given while a session is running is included in the next
/// question that session asks, and stays with the learner after the session ends.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SupervisorGuidance {

    @JsonProperty
    private final long id;
    @JsonProperty
    private final String message;
    @JsonProperty
    private final String author;
    @JsonProperty
    private final long timeMs;

    @JsonCreator
    public SupervisorGuidance(@JsonProperty("id") final Long id,
                              @JsonProperty("message") final String message,
                              @JsonProperty("author") final String author,
                              @JsonProperty("timeMs") final Long timeMs) {
        this.id = Objects.requireNonNullElse(id, 0L);
        this.message = message;
        this.author = author;
        this.timeMs = Objects.requireNonNullElse(timeMs, 0L);
    }

    public long getId() {
        return id;
    }

    public String getMessage() {
        return message;
    }

    /// @return Who left the note, so that a person reading it much later knows whose it was.
    public String getAuthor() {
        return author;
    }

    public long getTimeMs() {
        return timeMs;
    }
}
