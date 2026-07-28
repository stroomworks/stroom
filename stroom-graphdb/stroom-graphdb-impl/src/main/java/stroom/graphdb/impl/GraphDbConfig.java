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

package stroom.graphdb.impl;

import stroom.util.shared.AbstractConfig;
import stroom.util.shared.IsStroomConfig;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Deployment-level configuration for Graph DB.
 *
 * <p>Graph DB previously had no configuration at all: its storage location was hard-coded and there was no way
 * to tell it which nodes hold graph data. This is the surface that fixes both, and the place later
 * administrator-facing settings belong.</p>
 *
 * <p>Modelled on {@code stroom.planb.impl.PlanBConfig}, deliberately: the two features share a storage layer and
 * a fragment-merge model, so an operator who understands one should not have to learn a second vocabulary for the
 * other.</p>
 */
@JsonPropertyOrder(alphabetic = true)
public class GraphDbConfig extends AbstractConfig implements IsStroomConfig {

    private static final String DEFAULT_PATH = "graphdb";

    private final String path;
    private final List<String> nodeList;

    public GraphDbConfig() {
        this(DEFAULT_PATH, Collections.emptyList());
    }

    @SuppressWarnings("unused")
    @JsonCreator
    public GraphDbConfig(@JsonProperty("path") final String path,
                         @JsonProperty("nodeList") final List<String> nodeList) {
        this.path = Objects.requireNonNullElse(path, DEFAULT_PATH);
        this.nodeList = Objects.requireNonNullElse(nodeList, Collections.emptyList());
    }

    @JsonProperty
    @JsonPropertyDescription("The root path, relative to stroom's home directory unless absolute, under which " +
                             "graph stores, incoming fragments and merge working directories are kept. " +
                             "Default: " + DEFAULT_PATH + ".")
    public String getPath() {
        return path;
    }

    @JsonProperty
    @JsonPropertyDescription("The nodes that hold graph data. Every node named here receives a copy of every " +
                             "fragment, so each holds the whole graph, and graph queries are routed to one of " +
                             "them. If no nodes are named, only the local node is used - which is correct on a " +
                             "single node deployment and incorrect on a cluster, because each node would then " +
                             "accumulate only the fragments it happened to process.")
    public List<String> getNodeList() {
        return nodeList;
    }

    @Override
    public String toString() {
        return "GraphDbConfig{" +
               "path='" + path + '\'' +
               ", nodeList=" + nodeList +
               '}';
    }
}
