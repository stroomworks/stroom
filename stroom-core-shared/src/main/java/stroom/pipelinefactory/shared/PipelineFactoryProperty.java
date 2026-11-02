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

package stroom.pipelinefactory.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/// A value a Pipeline Factory writes onto an element of every pipeline it makes (design 06 §3.1, A113),
/// with the key's values substituted into it. This is how a made pipeline is told something about its
/// own combination that its template cannot say, such as where the Shapeshifter AI element puts what it
/// learns. Only string-valued properties are written: a pattern is text, and the properties this exists
/// for are folder and document names.
@JsonPropertyOrder({"elementId", "name", "value"})
@JsonInclude(Include.NON_NULL)
public final class PipelineFactoryProperty {

    @JsonProperty
    private final String elementId;
    @JsonProperty
    private final String name;
    @JsonProperty
    private final String value;

    /// @param elementId The id of the element in the template's chain.
    /// @param name      The property's name on that element.
    /// @param value     The pattern the value is made from, e.g. `${feed} ${type}`.
    @JsonCreator
    public PipelineFactoryProperty(@JsonProperty("elementId") final String elementId,
                                   @JsonProperty("name") final String name,
                                   @JsonProperty("value") final String value) {
        this.elementId = elementId;
        this.name = name;
        this.value = value;
    }

    /// @return The id of the element in the template's chain.
    public String getElementId() {
        return elementId;
    }

    /// @return The property's name on the element.
    public String getName() {
        return name;
    }

    /// @return The pattern the value is made from.
    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final PipelineFactoryProperty that = (PipelineFactoryProperty) o;
        return Objects.equals(elementId, that.elementId)
               && Objects.equals(name, that.name)
               && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(elementId, name, value);
    }

    @Override
    public String toString() {
        return elementId + "." + name + "=" + value;
    }
}
