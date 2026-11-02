/*
 * Copyright 2016-2026 Crown Copyright
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
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Extraction quality, the anti-degeneracy scorer of proposed ruling A16 (§8.3): the ratio of
 * schema-named elements to untyped {@code Data}, the proportion of records whose {@code EventDetail}
 * names a real branch rather than {@code Unknown}, and coverage of the fields the document requires.
 */
@JsonPropertyOrder({"allowUnknownEventDetail", "requiredFields"})
@JsonInclude(Include.NON_NULL)
public final class ExtractionQualityParameters extends ScorerParameters {

    /**
     * Whether {@code Unknown} event detail is tolerated at all. Never counts toward a passing score
     * either way (A16).
     */
    @JsonProperty
    private final boolean allowUnknownEventDetail;
    /**
     * XPaths every record must populate.
     */
    @JsonProperty
    private final List<String> requiredFields;

    @JsonCreator
    public ExtractionQualityParameters(
            @JsonProperty("allowUnknownEventDetail") final Boolean allowUnknownEventDetail,
            @JsonProperty("requiredFields") final List<String> requiredFields) {
        this.allowUnknownEventDetail = Objects.requireNonNullElse(allowUnknownEventDetail, false);
        this.requiredFields = requiredFields == null
                ? Collections.emptyList()
                : List.copyOf(requiredFields);
    }

    @Override
    public ScorerType scorerType() {
        return ScorerType.EXTRACTION_QUALITY;
    }

    public boolean isAllowUnknownEventDetail() {
        return allowUnknownEventDetail;
    }

    public List<String> getRequiredFields() {
        return requiredFields;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ExtractionQualityParameters that = (ExtractionQualityParameters) o;
        return allowUnknownEventDetail == that.allowUnknownEventDetail
               && Objects.equals(requiredFields, that.requiredFields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowUnknownEventDetail, requiredFields);
    }

    @Override
    public String toString() {
        return requiredFields.size() + " required field(s)" + (allowUnknownEventDetail
                ? ", Unknown allowed"
                : "");
    }
}
