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

/// Edges the server left out of a [DependencyGraph] at one node, so the client can say that more exist.
///
/// Counted after permission filtering, so `omitted` never includes a document the user cannot see.
@JsonInclude(Include.NON_NULL)
@JsonPropertyOrder({
        "anchor",
        "kind",
        "docType",
        "omitted"})
public class GraphTruncation {

    @JsonProperty
    private final String anchor;
    @JsonProperty
    private final EdgeKind kind;
    @JsonProperty
    private final String docType;
    @JsonProperty
    private final int omitted;

    /// @param anchor  The id of the node whose edges were left out.
    /// @param kind    The kind of the edges left out.
    /// @param docType The document type at the far end of the edges left out.
    /// @param omitted How many edges were left out. Null is treated as zero.
    @JsonCreator
    public GraphTruncation(@JsonProperty("anchor") final String anchor,
                           @JsonProperty("kind") final EdgeKind kind,
                           @JsonProperty("docType") final String docType,
                           @JsonProperty("omitted") final Integer omitted) {
        this.anchor = anchor;
        this.kind = kind;
        this.docType = docType;
        this.omitted = omitted == null
                ? 0
                : omitted;
    }

    /// @return the id of the node whose edges were left out.
    public String getAnchor() {
        return anchor;
    }

    /// @return the kind of the edges left out.
    public EdgeKind getKind() {
        return kind;
    }

    /// @return the document type at the far end of the edges left out.
    public String getDocType() {
        return docType;
    }

    /// @return how many edges were left out.
    public int getOmitted() {
        return omitted;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final GraphTruncation that = (GraphTruncation) o;
        return omitted == that.omitted
               && Objects.equals(anchor, that.anchor)
               && kind == that.kind
               && Objects.equals(docType, that.docType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(anchor, kind, docType, omitted);
    }

    @Override
    public String toString() {
        return "GraphTruncation{"
               + "anchor='" + anchor + '\''
               + ", kind=" + kind
               + ", docType='" + docType + '\''
               + ", omitted=" + omitted
               + '}';
    }
}
