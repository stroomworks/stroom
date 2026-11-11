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

/// Asks for the [DependencyGraph] of a folder.
@JsonInclude(Include.NON_NULL)
@JsonPropertyOrder({
        "root",
        "contextDepth",
        "maxNodes"})
public class DependencyGraphRequest {

    @JsonProperty
    private final DocRef root;
    @JsonProperty
    private final Integer contextDepth;
    @JsonProperty
    private final Integer maxNodes;

    /// @param root         The folder to draw.
    /// @param contextDepth How many edges beyond the folder to follow for context. The server clamps it to
    ///                     between 0 and 2. Null for the server's default.
    /// @param maxNodes     The most nodes to return. The server clamps it to its own ceiling. Null for the
    ///                     server's default.
    @JsonCreator
    public DependencyGraphRequest(@JsonProperty("root") final DocRef root,
                                  @JsonProperty("contextDepth") final Integer contextDepth,
                                  @JsonProperty("maxNodes") final Integer maxNodes) {
        this.root = root;
        this.contextDepth = contextDepth;
        this.maxNodes = maxNodes;
    }

    /// @return the folder to draw.
    public DocRef getRoot() {
        return root;
    }

    /// @return how many edges beyond the folder to follow for context, or null for the server's default.
    public Integer getContextDepth() {
        return contextDepth;
    }

    /// @return the most nodes to return, or null for the server's default.
    public Integer getMaxNodes() {
        return maxNodes;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final DependencyGraphRequest that = (DependencyGraphRequest) o;
        return Objects.equals(root, that.root)
               && Objects.equals(contextDepth, that.contextDepth)
               && Objects.equals(maxNodes, that.maxNodes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(root, contextDepth, maxNodes);
    }

    @Override
    public String toString() {
        return "DependencyGraphRequest{"
               + "root=" + root
               + ", contextDepth=" + contextDepth
               + ", maxNodes=" + maxNodes
               + '}';
    }
}
