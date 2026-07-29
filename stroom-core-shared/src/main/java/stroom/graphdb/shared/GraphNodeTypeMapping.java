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

package stroom.graphdb.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/**
 * Declares that a field carrying a given domain type (see {@code stroom.domaintype.shared.DomainType}) should be
 * ingested as a graph node of the given label — the ingest-side half of the design doc's
 * &sect;5.6 "Catalogue-driven node/edge mapping".
 *
 * <p>Only node mapping is modelled here. Edge (relationship) mapping is intentionally absent: the design doc's
 * open question D5 (how relationship semantics attach to the domain-type catalogue) is not yet resolved, so no
 * edge-mapping shape is baked into the wire format ahead of that decision.
 *
 * <p><b>A class rather than a record</b>, as every other type in this module is. The annotation on a record
 * component lands on its accessor as well as its field, which the shared-class serialisation contract does not
 * permit - it requires the mapping to be declared on fields alone so that the client-side generator and Jackson
 * cannot disagree about where a property comes from.
 */
@JsonPropertyOrder({
        "label",
        "domainType"
})
@JsonInclude(Include.NON_NULL)
public class GraphNodeTypeMapping {

    @JsonProperty
    private final String label;
    @JsonProperty
    private final String domainType;

    /**
     * <b>Preconditions:</b> both {@code label} and {@code domainType} must be non-null and non-blank.
     * <b>Postconditions:</b> {@link #getLabel()} and {@link #getDomainType()} return the exact strings supplied.
     * <b>Null status:</b> neither parameter is nullable.
     *
     * @param label      the node label to assign, e.g. {@code "User"}.
     * @param domainType the {@code class.attribute} domain type that identifies this node, e.g. {@code "User.id"}.
     */
    @JsonCreator
    public GraphNodeTypeMapping(@JsonProperty("label") final String label,
                                @JsonProperty("domainType") final String domainType) {
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(domainType, "domainType must not be null");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (domainType.isBlank()) {
            throw new IllegalArgumentException("domainType must not be blank");
        }
        this.label = label;
        this.domainType = domainType;
    }

    public String getLabel() {
        return label;
    }

    public String getDomainType() {
        return domainType;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final GraphNodeTypeMapping that = (GraphNodeTypeMapping) o;
        return Objects.equals(label, that.label) && Objects.equals(domainType, that.domainType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(label, domainType);
    }

    @Override
    public String toString() {
        return "GraphNodeTypeMapping{" +
               "label='" + label + '\'' +
               ", domainType='" + domainType + '\'' +
               '}';
    }
}
