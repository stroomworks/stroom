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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/// One node of a [DependencyGraph].
///
/// A node carries no position. Columns, lanes, coordinates and the copies of a referenced document drawn
/// beneath each lane that uses it are all decided by the client's layout.
///
/// A node's `id` is not always its document's UUID. A pipeline is drawn once per source-to-destination pair,
/// so its nodes have synthetic ids. Anything that acts on a document uses `docRef`, and anything that acts on
/// a position in the graph uses `id`.
@JsonInclude(Include.NON_NULL)
@JsonPropertyOrder({
        "id",
        "docRef",
        "role",
        "streamType",
        "inScope",
        "hop",
        "elementCount",
        "unresolvedRefs"})
public class GraphNode {

    @JsonProperty
    private final String id;
    @JsonProperty
    private final DocRef docRef;
    @JsonProperty
    private final NodeRole role;
    @JsonProperty
    private final String streamType;
    @JsonProperty
    private final boolean inScope;
    @JsonProperty
    private final Integer hop;
    @JsonProperty
    private final Integer elementCount;
    @JsonProperty
    private final Integer unresolvedRefs;

    /// @param id             Unique within the graph. The document's UUID, or a synthetic id for a pipeline
    ///                       drawn once per source-to-destination pair.
    /// @param docRef         The document this node draws.
    /// @param role           Which row of a lane the node belongs on.
    /// @param streamType     For a feed, its stream type. Null otherwise.
    /// @param inScope        Whether the document is inside the folder the graph was drawn for, rather than
    ///                       context around it. Null is treated as false.
    /// @param hop            For context, how many edges away from the folder it is. Null for a node in scope.
    /// @param elementCount   For a pipeline, how many elements it has. Null otherwise.
    /// @param unresolvedRefs How many of the document's references could not be resolved. Null if none.
    @JsonCreator
    public GraphNode(@JsonProperty("id") final String id,
                     @JsonProperty("docRef") final DocRef docRef,
                     @JsonProperty("role") final NodeRole role,
                     @JsonProperty("streamType") final String streamType,
                     @JsonProperty("inScope") final Boolean inScope,
                     @JsonProperty("hop") final Integer hop,
                     @JsonProperty("elementCount") final Integer elementCount,
                     @JsonProperty("unresolvedRefs") final Integer unresolvedRefs) {
        this.id = id;
        this.docRef = docRef;
        this.role = role;
        this.streamType = streamType;
        this.inScope = inScope != null && inScope;
        this.hop = hop;
        this.elementCount = elementCount;
        this.unresolvedRefs = unresolvedRefs;
    }

    /// @return the node's id, unique within the graph. Not necessarily the document's UUID.
    public String getId() {
        return id;
    }

    /// @return the document this node draws.
    public DocRef getDocRef() {
        return docRef;
    }

    /// @return which row of a lane the node belongs on.
    public NodeRole getRole() {
        return role;
    }

    /// @return for a feed, its stream type; null otherwise.
    public String getStreamType() {
        return streamType;
    }

    /// @return true if the document is inside the folder the graph was drawn for, false if it is context.
    public boolean isInScope() {
        return inScope;
    }

    /// @return for context, how many edges away from the folder the node is; null for a node in scope.
    public Integer getHop() {
        return hop;
    }

    /// @return for a pipeline, how many elements it has; null otherwise.
    public Integer getElementCount() {
        return elementCount;
    }

    /// @return how many of the document's references could not be resolved; null if none.
    public Integer getUnresolvedRefs() {
        return unresolvedRefs;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final GraphNode that = (GraphNode) o;
        return inScope == that.inScope
               && Objects.equals(id, that.id)
               && Objects.equals(docRef, that.docRef)
               && role == that.role
               && Objects.equals(streamType, that.streamType)
               && Objects.equals(hop, that.hop)
               && Objects.equals(elementCount, that.elementCount)
               && Objects.equals(unresolvedRefs, that.unresolvedRefs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, docRef, role, streamType, inScope, hop, elementCount, unresolvedRefs);
    }

    @Override
    public String toString() {
        return "GraphNode{"
               + "id='" + id + '\''
               + ", docRef=" + docRef
               + ", role=" + role
               + '}';
    }
}
