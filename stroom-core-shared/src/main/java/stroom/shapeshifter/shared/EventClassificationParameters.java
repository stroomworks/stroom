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
 * Event classification (§8.4): the proportion of records whose {@code TypeId} is one the document
 * recognises. With no list, any {@code TypeId} other than one that resolves to {@code Unknown} counts.
 */
@JsonPropertyOrder({"recognisedTypes"})
@JsonInclude(Include.NON_NULL)
public final class EventClassificationParameters extends ScorerParameters {

    @JsonProperty
    private final List<String> recognisedTypes;

    @JsonCreator
    public EventClassificationParameters(@JsonProperty("recognisedTypes") final List<String> recognisedTypes) {
        this.recognisedTypes = recognisedTypes == null
                ? Collections.emptyList()
                : List.copyOf(recognisedTypes);
    }

    @Override
    public ScorerType scorerType() {
        return ScorerType.EVENT_CLASSIFICATION;
    }

    public List<String> getRecognisedTypes() {
        return recognisedTypes;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return Objects.equals(recognisedTypes, ((EventClassificationParameters) o).recognisedTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recognisedTypes);
    }

    @Override
    public String toString() {
        return recognisedTypes.isEmpty()
                ? "any recognised type"
                : recognisedTypes.size() + " recognised type(s)";
    }
}
