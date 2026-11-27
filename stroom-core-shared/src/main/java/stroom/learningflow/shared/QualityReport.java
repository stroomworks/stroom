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

import java.util.List;
import java.util.Objects;

/// What a Smart Pipeline's Quality Assurance tab shows of its volume and cardinality: how many streams and records
/// it translated each day lately, and, of the records the translation in use was learned and checked on, how many
/// became a real translation, a marked Unknown event or nothing, and how many kinds of record it settled.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QualityReport {

    @JsonProperty
    private final List<VolumeDay> days;
    @JsonProperty
    private final String servingBindingUuid;
    @JsonProperty
    private final int translated;
    @JsonProperty
    private final int unknown;
    @JsonProperty
    private final int noOutput;
    @JsonProperty
    private final int distinctTargets;

    @JsonCreator
    public QualityReport(@JsonProperty("days") final List<VolumeDay> days,
                         @JsonProperty("servingBindingUuid") final String servingBindingUuid,
                         @JsonProperty("translated") final Integer translated,
                         @JsonProperty("unknown") final Integer unknown,
                         @JsonProperty("noOutput") final Integer noOutput,
                         @JsonProperty("distinctTargets") final Integer distinctTargets) {
        this.days = days;
        this.servingBindingUuid = servingBindingUuid;
        this.translated = Objects.requireNonNullElse(translated, 0);
        this.unknown = Objects.requireNonNullElse(unknown, 0);
        this.noOutput = Objects.requireNonNullElse(noOutput, 0);
        this.distinctTargets = Objects.requireNonNullElse(distinctTargets, 0);
    }

    /// @return Each day of the window, oldest first, including days it translated nothing.
    public List<VolumeDay> getDays() {
        return days;
    }

    /// @return The translation in use, whose records are counted; null where none is.
    public String getServingBindingUuid() {
        return servingBindingUuid;
    }

    /// @return How many of the kept records became a real translation.
    public int getTranslated() {
        return translated;
    }

    /// @return How many became a marked Unknown event.
    public int getUnknown() {
        return unknown;
    }

    /// @return How many became nothing, e.g. a header line.
    public int getNoOutput() {
        return noOutput;
    }

    /// @return How many kinds of record were settled (the targets the flow named), or 0 where none was named.
    public int getDistinctTargets() {
        return distinctTargets;
    }
}
