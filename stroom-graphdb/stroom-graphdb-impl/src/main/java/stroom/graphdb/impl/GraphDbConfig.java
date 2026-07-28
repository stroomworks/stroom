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

import stroom.util.config.annotations.RequiresRestart;
import stroom.util.shared.AbstractConfig;
import stroom.util.shared.IsStroomConfig;
import stroom.util.time.StroomDuration;

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
    private static final long DEFAULT_MAX_STORE_SIZE = 10L * 1024 * 1024 * 1024;
    private static final int DEFAULT_MAX_VAR_LENGTH_HOPS = 50;
    private static final long DEFAULT_MAX_VAR_LENGTH_PATH_STATES = 200_000L;
    private static final StroomDuration DEFAULT_MAX_TRAVERSAL_DURATION = StroomDuration.ofSeconds(30);
    private static final long DEFAULT_MAX_ACCUMULATED_ROWS = 1_000_000L;
    private static final int DEFAULT_WHOLE_GRAPH_NODE_CAP = 100;

    private final String path;
    private final List<String> nodeList;
    private final long maxStoreSize;
    private final int maxVarLengthHops;
    private final long maxVarLengthPathStates;
    private final StroomDuration maxTraversalDuration;
    private final long maxAccumulatedRows;
    private final int wholeGraphNodeCap;

    public GraphDbConfig() {
        this(DEFAULT_PATH,
                Collections.emptyList(),
                DEFAULT_MAX_STORE_SIZE,
                DEFAULT_MAX_VAR_LENGTH_HOPS,
                DEFAULT_MAX_VAR_LENGTH_PATH_STATES,
                DEFAULT_MAX_TRAVERSAL_DURATION,
                DEFAULT_MAX_ACCUMULATED_ROWS,
                DEFAULT_WHOLE_GRAPH_NODE_CAP);
    }

    @SuppressWarnings("unused")
    @JsonCreator
    public GraphDbConfig(@JsonProperty("path") final String path,
                         @JsonProperty("nodeList") final List<String> nodeList,
                         @JsonProperty("maxStoreSize") final Long maxStoreSize,
                         @JsonProperty("maxVarLengthHops") final Integer maxVarLengthHops,
                         @JsonProperty("maxVarLengthPathStates") final Long maxVarLengthPathStates,
                         @JsonProperty("maxTraversalDuration") final StroomDuration maxTraversalDuration,
                         @JsonProperty("maxAccumulatedRows") final Long maxAccumulatedRows,
                         @JsonProperty("wholeGraphNodeCap") final Integer wholeGraphNodeCap) {
        this.path = Objects.requireNonNullElse(path, DEFAULT_PATH);
        this.nodeList = Objects.requireNonNullElse(nodeList, Collections.emptyList());
        this.maxStoreSize = Objects.requireNonNullElse(maxStoreSize, DEFAULT_MAX_STORE_SIZE);
        this.maxVarLengthHops = Objects.requireNonNullElse(maxVarLengthHops, DEFAULT_MAX_VAR_LENGTH_HOPS);
        this.maxVarLengthPathStates =
                Objects.requireNonNullElse(maxVarLengthPathStates, DEFAULT_MAX_VAR_LENGTH_PATH_STATES);
        this.maxTraversalDuration =
                Objects.requireNonNullElse(maxTraversalDuration, DEFAULT_MAX_TRAVERSAL_DURATION);
        this.maxAccumulatedRows = Objects.requireNonNullElse(maxAccumulatedRows, DEFAULT_MAX_ACCUMULATED_ROWS);
        this.wholeGraphNodeCap = Objects.requireNonNullElse(wholeGraphNodeCap, DEFAULT_WHOLE_GRAPH_NODE_CAP);
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

    @RequiresRestart(RequiresRestart.RestartScope.SYSTEM)
    @JsonProperty
    @JsonPropertyDescription("The maximum on-disk size a single graph may reach, in bytes. Default 10GiB. " +
                             "A graph that outgrows this fails with MDB_MAP_FULL; the remedies are to raise this, " +
                             "split the data across several GraphDb documents, or enable retention. This is the " +
                             "LMDB map size, which is fixed when a store's environment is created, so a change " +
                             "takes effect for graphs opened after a restart and not for ones already on disk.")
    public long getMaxStoreSize() {
        return maxStoreSize;
    }

    @JsonProperty
    @JsonPropertyDescription("The widest variable-length hop range a query may request, e.g. the 8 in " +
                             "[:KNOWS*1..8]. Rejected before any traversal work begins. Default 50.")
    public int getMaxVarLengthHops() {
        return maxVarLengthHops;
    }

    @JsonProperty
    @JsonPropertyDescription("The most path states a single variable-length expansion may hold at once, per " +
                             "anchor. A traversal exceeding this fails with an error naming the limit rather " +
                             "than returning a partial result. Default 200000.")
    public long getMaxVarLengthPathStates() {
        return maxVarLengthPathStates;
    }

    @JsonProperty
    @JsonPropertyDescription("How long a single graph traversal may run before it is abandoned. A query " +
                             "exceeding this fails rather than returning what it had found so far. Note a " +
                             "traversal runs on the calling thread, so this also bounds how long a request " +
                             "thread can be occupied. Default 30s.")
    public StroomDuration getMaxTraversalDuration() {
        return maxTraversalDuration;
    }

    @JsonProperty
    @JsonPropertyDescription("The most rows a query may accumulate in memory before sorting, de-duplication or " +
                             "aggregation. Exceeding it fails the query; the rows accumulated so far are " +
                             "discarded rather than returned as a partial answer. Default 1000000.")
    public long getMaxAccumulatedRows() {
        return maxAccumulatedRows;
    }

    @JsonProperty
    @JsonPropertyDescription("The most nodes an unanchored RETURN GRAPH preview will draw. Unlike the other " +
                             "limits this one truncates rather than failing, because it exists to keep a " +
                             "browse of an unknown graph usable. Default 100.")
    public int getWholeGraphNodeCap() {
        return wholeGraphNodeCap;
    }

    @Override
    public String toString() {
        return "GraphDbConfig{" +
               "path='" + path + '\'' +
               ", nodeList=" + nodeList +
               ", maxStoreSize=" + maxStoreSize +
               ", maxVarLengthHops=" + maxVarLengthHops +
               ", maxVarLengthPathStates=" + maxVarLengthPathStates +
               ", maxTraversalDuration=" + maxTraversalDuration +
               ", maxAccumulatedRows=" + maxAccumulatedRows +
               ", wholeGraphNodeCap=" + wholeGraphNodeCap +
               '}';
    }
}
