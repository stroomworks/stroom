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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/// One edge of a [DependencyGraph], between two [GraphNode]s named by their ids.
@JsonInclude(Include.NON_NULL)
@JsonPropertyOrder({
        "from",
        "to",
        "kind",
        "certainty",
        "element",
        "ambiguous"})
public class GraphEdge {

    @JsonProperty
    private final String from;
    @JsonProperty
    private final String to;
    @JsonProperty
    private final EdgeKind kind;
    @JsonProperty
    private final EdgeCertainty certainty;
    @JsonProperty
    private final String element;
    @JsonProperty
    private final boolean ambiguous;

    /// @param from      The id of the node the edge leaves.
    /// @param to        The id of the node the edge arrives at.
    /// @param kind      How the two nodes relate.
    /// @param certainty How sure the server is that the edge exists. Null if not known.
    /// @param element   The id of the pipeline element the edge comes from, where there is one. Null
    ///                  otherwise.
    /// @param ambiguous Whether `from` names `to` by a name that more than one document has, so `to` is only
    ///                  one of several candidates. Null is treated as false.
    @JsonCreator
    public GraphEdge(@JsonProperty("from") final String from,
                     @JsonProperty("to") final String to,
                     @JsonProperty("kind") final EdgeKind kind,
                     @JsonProperty("certainty") final EdgeCertainty certainty,
                     @JsonProperty("element") final String element,
                     @JsonProperty("ambiguous") final Boolean ambiguous) {
        this.from = from;
        this.to = to;
        this.kind = kind;
        this.certainty = certainty;
        this.element = element;
        this.ambiguous = ambiguous != null && ambiguous;
    }

    /// @return the id of the node the edge leaves.
    public String getFrom() {
        return from;
    }

    /// @return the id of the node the edge arrives at.
    public String getTo() {
        return to;
    }

    /// @return how the two nodes relate.
    public EdgeKind getKind() {
        return kind;
    }

    /// @return how sure the server is that the edge exists, or null if not known.
    public EdgeCertainty getCertainty() {
        return certainty;
    }

    /// @return the id of the pipeline element the edge comes from, or null if there is none.
    public String getElement() {
        return element;
    }

    /// @return true if `from` names `to` by a name that more than one document has, so the runtime cannot
    /// tell which is meant.
    public boolean isAmbiguous() {
        return ambiguous;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final GraphEdge that = (GraphEdge) o;
        return ambiguous == that.ambiguous
               && Objects.equals(from, that.from)
               && Objects.equals(to, that.to)
               && kind == that.kind
               && certainty == that.certainty
               && Objects.equals(element, that.element);
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to, kind, certainty, element, ambiguous);
    }

    @Override
    public String toString() {
        return "GraphEdge{"
               + from + " -" + kind + "-> " + to
               + '}';
    }
}
