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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A discovered snapshot of what a Graph DB currently holds - its interned vocabulary plus a few example nodes -
 * so the Data tab can help an analyst who does not yet know a graph's labels, edge types or ids get started.
 * Everything here is enumerated from the store's UID-lookup namespaces and a bounded node sample (see the
 * server-side discovery service); nothing is persisted.
 *
 * <p><b>A class rather than a record</b>, for the reason given on {@link GraphNodeTypeMapping}.
 */
@JsonPropertyOrder({
        "nodeLabels",
        "edgeTypes",
        "propertyKeys",
        "sampleNodes"
})
@JsonInclude(Include.NON_NULL)
public class GraphDbSchema {

    @JsonProperty
    private final List<String> nodeLabels;
    @JsonProperty
    private final List<String> edgeTypes;
    @JsonProperty
    private final List<String> propertyKeys;
    @JsonProperty
    private final List<SampleNode> sampleNodes;

    /**
     * <b>Preconditions:</b> none; a null list is read as empty.
     * <b>Postconditions:</b> every list is an unmodifiable copy.
     * <b>Null status:</b> every parameter is nullable; no accessor returns null.
     *
     * @param nodeLabels   the distinct node labels present, sorted (e.g. {@code Account}, {@code Device}).
     * @param edgeTypes    the distinct edge/relationship types present, sorted (e.g. {@code OWNS}).
     * @param propertyKeys the distinct property keys present, sorted (e.g. {@code id}, {@code balance}).
     * @param sampleNodes  a handful of real nodes (id + labels + properties) to seed drill-down queries.
     */
    @JsonCreator
    public GraphDbSchema(@JsonProperty("nodeLabels") final List<String> nodeLabels,
                         @JsonProperty("edgeTypes") final List<String> edgeTypes,
                         @JsonProperty("propertyKeys") final List<String> propertyKeys,
                         @JsonProperty("sampleNodes") final List<SampleNode> sampleNodes) {
        this.nodeLabels = copyOrEmpty(nodeLabels);
        this.edgeTypes = copyOrEmpty(edgeTypes);
        this.propertyKeys = copyOrEmpty(propertyKeys);
        this.sampleNodes = copyOrEmpty(sampleNodes);
    }

    private static <T> List<T> copyOrEmpty(final List<T> values) {
        return values == null
                ? List.of()
                : List.copyOf(values);
    }

    public List<String> getNodeLabels() {
        return nodeLabels;
    }

    public List<String> getEdgeTypes() {
        return edgeTypes;
    }

    public List<String> getPropertyKeys() {
        return propertyKeys;
    }

    public List<SampleNode> getSampleNodes() {
        return sampleNodes;
    }

    /** Ignored: a derived convenience, not a wire property, and there is no field behind it. */
    @JsonIgnore
    public boolean isEmpty() {
        return nodeLabels.isEmpty() && edgeTypes.isEmpty() && propertyKeys.isEmpty() && sampleNodes.isEmpty();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final GraphDbSchema that = (GraphDbSchema) o;
        return Objects.equals(nodeLabels, that.nodeLabels)
               && Objects.equals(edgeTypes, that.edgeTypes)
               && Objects.equals(propertyKeys, that.propertyKeys)
               && Objects.equals(sampleNodes, that.sampleNodes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeLabels, edgeTypes, propertyKeys, sampleNodes);
    }

    @Override
    public String toString() {
        return "GraphDbSchema{" +
               "nodeLabels=" + nodeLabels +
               ", edgeTypes=" + edgeTypes +
               ", propertyKeys=" + propertyKeys +
               ", sampleNodes=" + sampleNodes.size() +
               '}';
    }

    /**
     * One example node: its external id, its labels, and its properties (values rendered as strings for display).
     */
    @JsonPropertyOrder({
            "id",
            "labels",
            "properties"
    })
    @JsonInclude(Include.NON_NULL)
    public static class SampleNode {

        @JsonProperty
        private final String id;
        @JsonProperty
        private final List<String> labels;
        @JsonProperty
        private final Map<String, String> properties;

        /**
         * <b>Preconditions:</b> none; a null collection is read as empty.
         * <b>Postconditions:</b> both collections are unmodifiable copies.
         * <b>Null status:</b> every parameter is nullable; only {@link #getId()} may return null.
         *
         * @param id         the node's external id (e.g. {@code acc-1}).
         * @param labels     the node's labels.
         * @param properties the node's property map, values as display strings.
         */
        @JsonCreator
        public SampleNode(@JsonProperty("id") final String id,
                          @JsonProperty("labels") final List<String> labels,
                          @JsonProperty("properties") final Map<String, String> properties) {
            this.id = id;
            this.labels = copyOrEmpty(labels);
            this.properties = properties == null
                    ? Map.of()
                    : Map.copyOf(properties);
        }

        public String getId() {
            return id;
        }

        public List<String> getLabels() {
            return labels;
        }

        public Map<String, String> getProperties() {
            return properties;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final SampleNode that = (SampleNode) o;
            return Objects.equals(id, that.id)
                   && Objects.equals(labels, that.labels)
                   && Objects.equals(properties, that.properties);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, labels, properties);
        }

        @Override
        public String toString() {
            return "SampleNode{" +
                   "id='" + id + '\'' +
                   ", labels=" + labels +
                   ", properties=" + properties +
                   '}';
        }
    }
}
