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
 * One thing a supervisor has told a learner (A46): a hint, a correction, or a fact about the feed that
 * no sample of it shows.
 * <p>
 * It belongs to the learner and not to a turn or an attempt, so nothing has to be timed and nothing is
 * refused for arriving at the wrong moment. A hint given while an attempt is running is carried by the
 * next question that attempt asks, and outlives it either way.
 */
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
    public SupervisorGuidance(@JsonProperty("id") final long id,
                              @JsonProperty("message") final String message,
                              @JsonProperty("author") final String author,
                              @JsonProperty("timeMs") final long timeMs) {
        this.id = id;
        this.message = message;
        this.author = author;
        this.timeMs = timeMs;
    }

    public long getId() {
        return id;
    }

    public String getMessage() {
        return message;
    }

    /**
     * Who said it. A person reading a hint a year later needs to know whose it was.
     */
    public String getAuthor() {
        return author;
    }

    public long getTimeMs() {
        return timeMs;
    }
}
