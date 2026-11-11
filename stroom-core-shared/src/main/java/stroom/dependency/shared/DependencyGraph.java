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

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/// The documents in a folder and how they depend on each other, as a graph.
///
/// The server sends a graph, not a drawing: no coordinates, lanes or columns, and one node per document
/// (or per source-to-destination pair, for a pipeline). The client's layout decides everything else.
///
/// Documents the user cannot view are absent. They are not redacted, stubbed or counted.
@JsonInclude(Include.NON_NULL)
@JsonPropertyOrder({
        "root",
        "nodes",
        "edges",
        "truncations",
        "nodeLimitReached",
        "topologyHash",
        "generatedAt"})
public class DependencyGraph {

    @JsonProperty
    private final DocRef root;
    @JsonProperty
    private final List<GraphNode> nodes;
    @JsonProperty
    private final List<GraphEdge> edges;
    @JsonProperty
    private final List<GraphTruncation> truncations;
    @JsonProperty
    private final boolean nodeLimitReached;
    @JsonProperty
    private final String topologyHash;
    @JsonProperty
    private final Long generatedAt;

    /// @param root             The folder the graph was drawn for.
    /// @param nodes            The nodes. Null is treated as empty.
    /// @param edges            The edges, which name their nodes by id. Null is treated as empty.
    /// @param truncations      Where edges were left out. Null is treated as empty.
    /// @param nodeLimitReached Whether the server stopped adding nodes at its ceiling. Null is treated as
    ///                         false.
    /// @param topologyHash     A hash of the nodes and edges, so a client can tell whether the graph has
    ///                         changed without comparing it. Null if not computed.
    /// @param generatedAt      When the graph was built, in milliseconds since the epoch. Null if not known.
    @JsonCreator
    public DependencyGraph(@JsonProperty("root") final DocRef root,
                           @JsonProperty("nodes") final List<GraphNode> nodes,
                           @JsonProperty("edges") final List<GraphEdge> edges,
                           @JsonProperty("truncations") final List<GraphTruncation> truncations,
                           @JsonProperty("nodeLimitReached") final Boolean nodeLimitReached,
                           @JsonProperty("topologyHash") final String topologyHash,
                           @JsonProperty("generatedAt") final Long generatedAt) {
        this.root = root;
        this.nodes = nodes == null
                ? Collections.emptyList()
                : nodes;
        this.edges = edges == null
                ? Collections.emptyList()
                : edges;
        this.truncations = truncations == null
                ? Collections.emptyList()
                : truncations;
        this.nodeLimitReached = nodeLimitReached != null && nodeLimitReached;
        this.topologyHash = topologyHash;
        this.generatedAt = generatedAt;
    }

    /// @return the folder the graph was drawn for.
    public DocRef getRoot() {
        return root;
    }

    /// @return the nodes. Never null.
    public List<GraphNode> getNodes() {
        return nodes;
    }

    /// @return the edges, which name their nodes by id. Never null.
    public List<GraphEdge> getEdges() {
        return edges;
    }

    /// @return where edges were left out. Never null.
    public List<GraphTruncation> getTruncations() {
        return truncations;
    }

    /// @return true if the server stopped adding nodes at its ceiling, so the graph is incomplete.
    public boolean isNodeLimitReached() {
        return nodeLimitReached;
    }

    /// @return a hash of the nodes and edges, or null if not computed.
    public String getTopologyHash() {
        return topologyHash;
    }

    /// @return when the graph was built, in milliseconds since the epoch, or null if not known.
    public Long getGeneratedAt() {
        return generatedAt;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final DependencyGraph that = (DependencyGraph) o;
        return nodeLimitReached == that.nodeLimitReached
               && Objects.equals(root, that.root)
               && Objects.equals(nodes, that.nodes)
               && Objects.equals(edges, that.edges)
               && Objects.equals(truncations, that.truncations)
               && Objects.equals(topologyHash, that.topologyHash)
               && Objects.equals(generatedAt, that.generatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(root, nodes, edges, truncations, nodeLimitReached, topologyHash, generatedAt);
    }

    @Override
    public String toString() {
        return "DependencyGraph{"
               + "root=" + root
               + ", nodes=" + nodes.size()
               + ", edges=" + edges.size()
               + ", truncations=" + truncations.size()
               + ", nodeLimitReached=" + nodeLimitReached
               + '}';
    }
}
