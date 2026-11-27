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

/// One record that a Smart Pipeline's translation in use was learned and checked on, shown on the Quality
/// Assurance tab beside what it became. It holds the record as it was received (where the parser recorded where
/// it cut the input), the record as the parser made it, and the output it was accepted with.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QualitySample {

    @JsonProperty
    private final String text;
    @JsonProperty
    private final String record;
    @JsonProperty
    private final String output;
    @JsonProperty
    private final RecordKind kind;
    @JsonProperty
    private final double score;
    @JsonProperty
    private final long acceptedMs;
    @JsonProperty
    private final String target;

    @JsonCreator
    public QualitySample(@JsonProperty("text") final String text,
                         @JsonProperty("record") final String record,
                         @JsonProperty("output") final String output,
                         @JsonProperty("kind") final RecordKind kind,
                         @JsonProperty("score") final Double score,
                         @JsonProperty("acceptedMs") final Long acceptedMs,
                         @JsonProperty("target") final String target) {
        this.text = text;
        this.record = record;
        this.output = output;
        this.kind = kind;
        this.score = Objects.requireNonNullElse(score, 0.0);
        this.acceptedMs = Objects.requireNonNullElse(acceptedMs, 0L);
        this.target = target;
    }

    /// @return The record's own text as it was received, or null where the parser did not record where it cut.
    public String getText() {
        return text;
    }

    /// @return The record as the parser made it, as XML.
    public String getRecord() {
        return record;
    }

    /// @return The events it was accepted with, as XML, or null where it yielded none.
    public String getOutput() {
        return output;
    }

    /// @return What it became.
    public RecordKind getKind() {
        return kind;
    }

    /// @return The score the translation achieved on the stream it was accepted from.
    public double getScore() {
        return score;
    }

    /// @return When it was accepted, in milliseconds since the epoch.
    public long getAcceptedMs() {
        return acceptedMs;
    }

    /// @return What the flow said this kind of record should become, or null where it settled none.
    public String getTarget() {
        return target;
    }
}
