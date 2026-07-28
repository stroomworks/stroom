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

import stroom.docref.DocRef;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.query.api.QueryNodeResolver;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.List;
import java.util.Objects;

/**
 * Routes graph queries to a node that holds graph data.
 *
 * <p>This is the read-side half of clustered correctness. Fragments are replicated to every node in
 * {@link GraphDbConfig#getNodeList()}, so any of them holds the whole graph and can answer completely - but a node
 * that is <b>not</b> in that list holds no graph store at all, and running a query there would answer from an
 * empty or partial store rather than failing. Pinning the query to a configured node prevents that.</p>
 *
 * <p>The first configured node is chosen rather than the least loaded one. Load-balanced routing needs a view of
 * node health and query load that does not exist here yet, and picking the first is deterministic, which makes an
 * incorrect answer reproducible rather than intermittent.</p>
 */
public class GraphQueryNodeResolverImpl implements QueryNodeResolver {

    private final Provider<GraphDbConfig> configProvider;

    @Inject
    public GraphQueryNodeResolverImpl(final Provider<GraphDbConfig> configProvider) {
        this.configProvider = Objects.requireNonNull(configProvider, "configProvider must not be null");
    }

    /**
     * <p><b>Postconditions:</b> returns the node a query against {@code docRef} must run on, or null if
     * {@code docRef} is not a graph or no nodes are configured - in which case the local node is the only node
     * holding graph data and running locally is correct.
     * <b>Null status:</b> {@code docRef} is nullable; the return value is nullable.
     *
     * @param docRef the datasource being queried.
     * @return the node name to run on, or null for no constraint.
     */
    @Override
    public String getNode(final DocRef docRef) {
        if (docRef == null || !GraphDbDoc.TYPE.equals(docRef.getType())) {
            return null;
        }

        final List<String> nodes = configProvider.get().getNodeList();
        if (NullSafe.isEmptyCollection(nodes)) {
            return null;
        }

        return nodes.getFirst();
    }
}
