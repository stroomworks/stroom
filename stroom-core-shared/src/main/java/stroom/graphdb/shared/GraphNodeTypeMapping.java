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
import com.fasterxml.jackson.annotation.JsonProperty;

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
 * <p><b>Preconditions:</b> both {@code label} and {@code domainType} must be non-null and non-blank.
 * <b>Postconditions:</b> {@link #label()} and {@link #domainType()} return the exact strings supplied.
 * <b>Null status:</b> neither component is nullable.
 *
 * @param label      the node label to assign, e.g. {@code "User"}.
 * @param domainType the {@code class.attribute} domain type that identifies this node, e.g. {@code "User.id"}.
 */
public record GraphNodeTypeMapping(
        @JsonProperty("label") String label,
        @JsonProperty("domainType") String domainType) {

    @JsonCreator
    public GraphNodeTypeMapping {
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(domainType, "domainType must not be null");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (domainType.isBlank()) {
            throw new IllegalArgumentException("domainType must not be blank");
        }
    }
}
