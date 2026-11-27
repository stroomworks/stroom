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

/// How much a Smart Pipeline translated on one day (UTC), for the volume its Quality Assurance tab shows.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VolumeDay {

    @JsonProperty
    private final long dayMs;
    @JsonProperty
    private final long streams;
    @JsonProperty
    private final long records;
    @JsonProperty
    private final long skips;

    @JsonCreator
    public VolumeDay(@JsonProperty("dayMs") final Long dayMs,
                     @JsonProperty("streams") final Long streams,
                     @JsonProperty("records") final Long records,
                     @JsonProperty("skips") final Long skips) {
        this.dayMs = Objects.requireNonNullElse(dayMs, 0L);
        this.streams = Objects.requireNonNullElse(streams, 0L);
        this.records = Objects.requireNonNullElse(records, 0L);
        this.skips = Objects.requireNonNullElse(skips, 0L);
    }

    /// A day recorded before declared skips were counted.
    ///
    /// @param dayMs   The start of the day.
    /// @param streams How many streams it translated.
    /// @param records How many records those streams held.
    public VolumeDay(final Long dayMs, final Long streams, final Long records) {
        this(dayMs, streams, records, 0L);
    }

    /// @return The start of the day, in milliseconds since the epoch, UTC.
    public long getDayMs() {
        return dayMs;
    }

    /// @return How many streams it translated that day.
    public long getStreams() {
        return streams;
    }

    /// @return How many records those streams held, where it is known; a stream recorded before record counts
    ///         were kept counts as none.
    public long getRecords() {
        return records;
    }

    /// @return How many of those records the translation declared it deliberately skipped rather than
    ///         translated. There is no limit on how many records may be skipped, so this count is shown for a
    ///         person to judge whether the rate is reasonable.
    public long getSkips() {
        return skips;
    }
}
