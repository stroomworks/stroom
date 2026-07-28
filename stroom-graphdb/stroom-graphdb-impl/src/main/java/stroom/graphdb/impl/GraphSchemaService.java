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

import stroom.graphdb.shared.GraphDbDoc;
import stroom.graphdb.shared.GraphDbSchema;
import stroom.planb.impl.dao.UidLookupDb;
import stroom.query.language.functions.Val;

import jakarta.inject.Inject;
import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Discovers a Graph DB's current vocabulary - node labels, edge types and property keys - plus a bounded sample of
 * real nodes, so the Data tab can help an analyst who does not yet know a graph's shape get started (see
 * {@code GraphDbResource#fetchSchema}). Everything is read from the store's interned UID-lookup namespaces and a
 * capped node walk ({@link GraphNodeDb#forEachDistinctNodeUid}); nothing is written.
 */
class GraphSchemaService {

    // Mirrors GraphTraversalEngine.LATEST - the far-future instant that selects each node's newest version.
    private static final Instant LATEST = Instant.ofEpochMilli((1L << 48) - 1);

    private final GraphStoreManager graphStoreManager;

    @Inject
    GraphSchemaService(final GraphStoreManager graphStoreManager) {
        this.graphStoreManager = Objects.requireNonNull(graphStoreManager, "graphStoreManager");
    }

    /**
     * @param doc         the Graph DB to introspect.
     * @param sampleLimit the maximum number of example nodes to return (a small handful).
     * @return the graph's labels/edge types/property keys (each sorted) and up to {@code sampleLimit} sample nodes.
     */
    GraphDbSchema discover(final GraphDbDoc doc, final int sampleLimit) {
        Objects.requireNonNull(doc, "doc");
        final GraphStores stores = graphStoreManager.getForQuery(doc);
        return stores.read(readTxn -> new GraphDbSchema(
                sortedNames(readTxn, stores.getLabelUids()),
                sortedNames(readTxn, stores.getEdgeTypeUids()),
                sortedNames(readTxn, stores.getPropertyKeyUids()),
                sampleNodes(readTxn, stores, sampleLimit)));
    }

    private static List<String> sortedNames(final Txn<ByteBuffer> readTxn, final UidLookupDb db) {
        final List<String> names = new ArrayList<>();
        db.forEachName(readTxn, buffer ->
                names.add(StandardCharsets.UTF_8.decode(buffer.duplicate()).toString()));
        names.sort(Comparator.naturalOrder());
        return names;
    }

    private static List<GraphDbSchema.SampleNode> sampleNodes(final Txn<ByteBuffer> readTxn,
                                                              final GraphStores stores,
                                                              final int sampleLimit) {
        final List<GraphDbSchema.SampleNode> samples = new ArrayList<>();
        if (sampleLimit <= 0) {
            return samples;
        }
        stores.getNodes().forEachDistinctNodeUid(readTxn, nodeUid -> {
            if (samples.size() >= sampleLimit) {
                return false;
            }
            final Optional<GraphNodeDb.NodeVersion> node = stores.getNodes().getNode(readTxn, nodeUid, LATEST);
            if (node.isPresent()) {
                final String id = GraphTraversalEngine.decodeUidName(readTxn, stores.getNodeUids(), nodeUid);
                final List<String> labels = new ArrayList<>(node.get().labelUids().size());
                for (final long labelUid : node.get().labelUids()) {
                    labels.add(GraphTraversalEngine.decodeUidName(readTxn, stores.getLabelUids(), labelUid));
                }
                final Map<String, String> properties = new TreeMap<>();
                for (final Map.Entry<String, Val> entry : node.get().properties().entrySet()) {
                    properties.put(entry.getKey(), entry.getValue().toString());
                }
                samples.add(new GraphDbSchema.SampleNode(id, labels, properties));
            }
            return true;
        });
        return samples;
    }
}
