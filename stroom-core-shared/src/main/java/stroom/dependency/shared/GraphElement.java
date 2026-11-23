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

package stroom.dependency.shared;

import stroom.docref.DocRef;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/// One element of a pipeline drawn in a [DependencyGraph] (see [GraphNode#getElements()]), e.g. its
/// `translationFilter`, an `XSLTFilter`. An element is not a document: it has no DocRef, and nothing to
/// open. An element the pipeline inherits says which ancestor defines it.
@JsonInclude(Include.NON_NULL)
@JsonPropertyOrder({"id", "name", "type", "inheritedFrom"})
public class GraphElement {

    @JsonProperty
    private final String id;
    @JsonProperty
    private final String name;
    @JsonProperty
    private final String type;
    @JsonProperty
    private final DocRef inheritedFrom;

    /// @param id            The element's id within its pipeline, e.g. `translationFilter`.
    /// @param name          The name the user gave it, or null if it has none.
    /// @param type          The element's type, e.g. `XSLTFilter`.
    /// @param inheritedFrom The ancestor pipeline that defines the element, or null if the pipeline defines
    ///                      it itself.
    @JsonCreator
    public GraphElement(@JsonProperty("id") final String id,
                        @JsonProperty("name") final String name,
                        @JsonProperty("type") final String type,
                        @JsonProperty("inheritedFrom") final DocRef inheritedFrom) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.inheritedFrom = inheritedFrom;
    }

    /// An element the pipeline defines itself.
    ///
    /// @param id   The element's id within its pipeline, e.g. `translationFilter`.
    /// @param name The name the user gave it, or null if it has none.
    /// @param type The element's type, e.g. `XSLTFilter`.
    public GraphElement(final String id, final String name, final String type) {
        this(id, name, type, null);
    }

    /// @return the element's id within its pipeline, e.g. `translationFilter`.
    public String getId() {
        return id;
    }

    /// @return the name the user gave the element, or null if it has none.
    public String getName() {
        return name;
    }

    /// @return the element's type, e.g. `XSLTFilter`.
    public String getType() {
        return type;
    }

    /// @return the ancestor pipeline that defines the element, or null if the pipeline defines it itself.
    public DocRef getInheritedFrom() {
        return inheritedFrom;
    }

    /// @return what the element is called where it is shown: its name if it has one, otherwise its id,
    /// as the pipeline editor shows it.
    @JsonIgnore
    public String getDisplayName() {
        return name == null || name.isBlank()
                ? id
                : name;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final GraphElement that = (GraphElement) o;
        return Objects.equals(id, that.id)
               && Objects.equals(name, that.name)
               && Objects.equals(type, that.type)
               && Objects.equals(inheritedFrom, that.inheritedFrom);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, type, inheritedFrom);
    }

    @Override
    public String toString() {
        return "GraphElement{id='" + id + "', type='" + type + "'}";
    }
}
