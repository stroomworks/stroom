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

import java.util.List;
import java.util.Map;

/**
 * A discovered snapshot of what a Graph DB currently holds - its interned vocabulary plus a few example nodes -
 * so the Data tab can help an analyst who does not yet know a graph's labels, edge types or ids get started.
 * Everything here is enumerated from the store's UID-lookup namespaces and a bounded node sample (see the
 * server-side discovery service); nothing is persisted.
 *
 * @param nodeLabels   the distinct node labels present, sorted (e.g. {@code Account}, {@code Device}).
 * @param edgeTypes    the distinct edge/relationship types present, sorted (e.g. {@code OWNS}).
 * @param propertyKeys the distinct property keys present, sorted (e.g. {@code id}, {@code balance}).
 * @param sampleNodes  a handful of real nodes (id + labels + properties) to seed drill-down queries.
 */
public record GraphDbSchema(
        @JsonProperty("nodeLabels") List<String> nodeLabels,
        @JsonProperty("edgeTypes") List<String> edgeTypes,
        @JsonProperty("propertyKeys") List<String> propertyKeys,
        @JsonProperty("sampleNodes") List<SampleNode> sampleNodes) {

    @JsonCreator
    public GraphDbSchema {
        nodeLabels = nodeLabels == null ? List.of() : List.copyOf(nodeLabels);
        edgeTypes = edgeTypes == null ? List.of() : List.copyOf(edgeTypes);
        propertyKeys = propertyKeys == null ? List.of() : List.copyOf(propertyKeys);
        sampleNodes = sampleNodes == null ? List.of() : List.copyOf(sampleNodes);
    }

    public boolean isEmpty() {
        return nodeLabels.isEmpty() && edgeTypes.isEmpty() && propertyKeys.isEmpty() && sampleNodes.isEmpty();
    }

    /**
     * One example node: its external id, its labels, and its properties (values rendered as strings for display).
     *
     * @param id         the node's external id (e.g. {@code acc-1}).
     * @param labels     the node's labels.
     * @param properties the node's property map, values as display strings.
     */
    public record SampleNode(
            @JsonProperty("id") String id,
            @JsonProperty("labels") List<String> labels,
            @JsonProperty("properties") Map<String, String> properties) {

        @JsonCreator
        public SampleNode {
            labels = labels == null ? List.of() : List.copyOf(labels);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }
    }
}
