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

package stroom.learningflow.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/// Parameters for the extraction quality check, which catches output that is valid but extracts nothing
/// useful. It measures the share of elements that are typed rather than untyped `Data`, the share of records
/// whose `EventDetail` names a real branch rather than `Unknown`, and how well the required fields are filled.
@JsonPropertyOrder({"allowUnknownEventDetail", "requiredFields"})
@JsonInclude(Include.NON_NULL)
public final class ExtractionQualityParameters extends ScorerParameters {

    /// Whether a bare `Unknown` event detail (one without the learned Unknown marker) is tolerated. When it is,
    /// such records are neither failed nor measured, so they never raise the score.
    @JsonProperty
    private final boolean allowUnknownEventDetail;
    /// XPaths that every record must populate.
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
    public Check check() {
        return Check.QUALITY;
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
