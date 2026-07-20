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

import stroom.docref.DocRef;
import stroom.docs.shared.Description;
import stroom.docstore.shared.AbstractDoc;
import stroom.planb.shared.RetentionSettings;
import stroom.planb.shared.TemporalPrecision;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
//import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * The single document a user creates for a temporal Cypher graph (see
 * {@code docs/temporal-cypher-graph-implementation-plan.md}, Task PoC.0; design doc &sect;2.1). It owns and
 * encapsulates every internal store the graph needs; none of those stores has its own {@link DocRef}, explorer
 * node, or permissions — they are addressed only through this document.
 *
 * <p>By design this type carries <em>only</em> the genuine user-configurable choices (design doc D8): a
 * description, a temporal precision policy, and an optional node-type mapping. Every physical detail — byte
 * layouts, interning scheme, sharding, the anchor-index technology — is an internal default of
 * {@code stroom.graphdb.impl.GraphStores} and is deliberately absent from this class.
 */
@Description("Defines a temporal Cypher graph")
@JsonPropertyOrder({
        "type",
        "uuid",
        "name",
        "version",
        "createTimeMs",
        "updateTimeMs",
        "createUser",
        "updateUser",
        "description",
        "temporalPrecision",
        "nodeTypeMappings",
        "retention"
})
@JsonInclude(Include.NON_NULL)
public class GraphDbDoc extends AbstractDoc {

    public static final String TYPE = "GraphDb";

    @JsonProperty
    private final String description;
    @JsonProperty
    private final TemporalPrecision temporalPrecision;
    @JsonProperty
    private final List<GraphNodeTypeMapping> nodeTypeMappings;
    @JsonProperty
    private final RetentionSettings retention;

    /**
     * <b>Preconditions:</b> {@code uuid} must be non-null (enforced by the {@link AbstractDoc} superclass
     * constructor); every other parameter may be null.
     * <b>Postconditions:</b> the corresponding getter returns exactly the value supplied.
     * <b>Null status:</b> {@code description}, {@code temporalPrecision}, {@code nodeTypeMappings} and
     * {@code retention} are all nullable — null means "use the internal default" (respectively: no description,
     * the frozen model's default precision, a schema derived at ingest time from the domain-type catalogue with
     * no explicit mapping, and retention disabled - the graph keeps every version forever).
     */
    @JsonCreator
    public GraphDbDoc(
            @JsonProperty("uuid") final String uuid,
            @JsonProperty("name") final String name,
            @JsonProperty("version") final String version,
            @JsonProperty("createTimeMs") final Long createTimeMs,
            @JsonProperty("updateTimeMs") final Long updateTimeMs,
            @JsonProperty("createUser") final String createUser,
            @JsonProperty("updateUser") final String updateUser,
            @JsonProperty("description") final String description,
            @JsonProperty("temporalPrecision") final TemporalPrecision temporalPrecision,
            @JsonProperty("nodeTypeMappings") final List<GraphNodeTypeMapping> nodeTypeMappings,
            @JsonProperty("retention") final RetentionSettings retention) {
        super(TYPE, uuid, name, version, createTimeMs, updateTimeMs, createUser, updateUser);
        this.description = description;
        this.temporalPrecision = temporalPrecision;
        this.nodeTypeMappings = nodeTypeMappings;
        this.retention = retention;
    }

    public String getDescription() {
        return description;
    }

    /**
     * @return the configured temporal precision, or {@code null} to use the internal default
     * (millisecond precision, per the P0.1 frozen model).
     */
    public TemporalPrecision getTemporalPrecision() {
        return temporalPrecision;
    }

    /**
     * @return the configured node-type mappings, or {@code null}/empty if the graph's node schema is derived
     * entirely from the domain-type catalogue at ingest time (design doc &sect;5.6).
     */
    public List<GraphNodeTypeMapping> getNodeTypeMappings() {
        return nodeTypeMappings;
    }

    /**
     * @return the configured retention policy (Task P1.4), or {@code null} to keep every version forever
     * (the internal default - no automatic deletion).
     */
    public RetentionSettings getRetention() {
        return retention;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        final GraphDbDoc doc = (GraphDbDoc) o;
        return Objects.equals(description, doc.description) &&
               temporalPrecision == doc.temporalPrecision &&
               Objects.equals(nodeTypeMappings, doc.nodeTypeMappings) &&
               Objects.equals(retention, doc.retention);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(),
                description,
                temporalPrecision,
                nodeTypeMappings,
                retention);
    }

    @Override
    public String toString() {
        return "GraphDbDoc{" +
               "type='" + getType() + '\'' +
               ", uuid='" + getUuid() + '\'' +
               ", name='" + getName() + '\'' +
               ", description='" + description + '\'' +
               ", temporalPrecision=" + temporalPrecision +
               ", nodeTypeMappings=" + nodeTypeMappings +
               ", retention=" + retention +
               '}';
    }

    /**
     * @return A new builder for creating a {@link DocRef} for this document's type.
     */
    public static DocRef.TypedBuilder buildDocRef() {
        return DocRef.builder(TYPE);
    }

    public Builder copy() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder extends AbstractBuilder<GraphDbDoc, Builder> {

        private String description;
        private TemporalPrecision temporalPrecision;
        private List<GraphNodeTypeMapping> nodeTypeMappings;
        private RetentionSettings retention;

        public Builder() {
        }

        public Builder(final GraphDbDoc doc) {
            super(doc);
            this.description = doc.description;
            this.temporalPrecision = doc.temporalPrecision;
            this.nodeTypeMappings = doc.nodeTypeMappings;
            this.retention = doc.retention;
        }

        public Builder description(final String description) {
            this.description = description;
            return self();
        }

        public Builder temporalPrecision(final TemporalPrecision temporalPrecision) {
            this.temporalPrecision = temporalPrecision;
            return self();
        }

        public Builder nodeTypeMappings(final List<GraphNodeTypeMapping> nodeTypeMappings) {
            this.nodeTypeMappings = nodeTypeMappings;
            return self();
        }

        public Builder retention(final RetentionSettings retention) {
            this.retention = retention;
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public GraphDbDoc build() {
            return new GraphDbDoc(
                    uuid,
                    name,
                    version,
                    createTimeMs,
                    updateTimeMs,
                    createUser,
                    updateUser,
                    description,
                    temporalPrecision,
                    nodeTypeMappings,
                    retention);
        }
    }
}
