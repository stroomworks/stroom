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
import stroom.docstore.api.DocumentResourceHelper;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.event.logging.rs.api.AutoLogged.OperationType;
import stroom.graphdb.impl.pipeline.GraphMutationSchema;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.graphdb.shared.GraphDbResource;
import stroom.graphdb.shared.GraphDbSchema;
import stroom.graphdb.shared.GraphElementTable;
import stroom.security.api.SecurityContext;
import stroom.security.shared.AppPermission;
import stroom.util.shared.EntityServiceException;
import stroom.util.shared.FetchWithUuid;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

/**
 * Task P5.2: mirrors {@code stroom.planb.impl.PlanBDocResourceImpl} exactly, plus {@link #fetchSchema} for the
 * Data tab's graph-discovery help.
 */
@AutoLogged
class GraphDbResourceImpl implements GraphDbResource, FetchWithUuid<GraphDbDoc> {

    /** A small handful of example nodes - enough to illustrate the graph's shape without a heavy scan. */
    private static final int SAMPLE_NODE_LIMIT = 20;

    private final Provider<GraphDbDocStore> graphDbDocStoreProvider;
    private final Provider<DocumentResourceHelper> documentResourceHelperProvider;
    private final Provider<GraphSchemaService> graphSchemaServiceProvider;
    private final Provider<GraphExpandService> graphExpandServiceProvider;
    private final Provider<GraphBackfillService> graphBackfillServiceProvider;
    private final Provider<SecurityContext> securityContextProvider;

    @Inject
    GraphDbResourceImpl(final Provider<GraphDbDocStore> graphDbDocStoreProvider,
                       final Provider<DocumentResourceHelper> documentResourceHelperProvider,
                       final Provider<GraphSchemaService> graphSchemaServiceProvider,
                       final Provider<GraphExpandService> graphExpandServiceProvider,
                       final Provider<GraphBackfillService> graphBackfillServiceProvider,
                       final Provider<SecurityContext> securityContextProvider) {
        this.graphDbDocStoreProvider = graphDbDocStoreProvider;
        this.documentResourceHelperProvider = documentResourceHelperProvider;
        this.graphSchemaServiceProvider = graphSchemaServiceProvider;
        this.graphExpandServiceProvider = graphExpandServiceProvider;
        this.graphBackfillServiceProvider = graphBackfillServiceProvider;
        this.securityContextProvider = securityContextProvider;
    }

    /**
     * Returns the packaged schema source. Unlogged: it is a static, non-sensitive constant of the build, so an
     * audit entry per fetch would be noise.
     */
    @AutoLogged(OperationType.UNLOGGED)
    @Override
    public String fetchMutationSchema() {
        return GraphMutationSchema.text();
    }

    @Override
    public GraphDbDoc fetch(final String uuid) {
        return documentResourceHelperProvider.get().read(graphDbDocStoreProvider.get(), getDocRef(uuid));
    }

    @Override
    public GraphDbDoc update(final String uuid, final GraphDbDoc doc) {
        if (doc.getUuid() == null || !doc.getUuid().equals(uuid)) {
            throw new EntityServiceException("The document UUID must match the update UUID");
        }
        return documentResourceHelperProvider.get().update(graphDbDocStoreProvider.get(), doc);
    }

    @Override
    public GraphDbSchema fetchSchema(final String uuid) {
        final GraphDbDoc doc = fetch(uuid);
        return graphSchemaServiceProvider.get().discover(doc, SAMPLE_NODE_LIMIT);
    }

    /**
     * Sends this node's whole copy of the graph to every node in {@code graphdb.nodeList}.
     *
     * <p>Restricted to node management rather than document permissions. It is part of changing the cluster's
     * shape, and it moves an entire graph between machines, so it belongs with the operations that add and
     * remove nodes rather than with the ones that read a graph.</p>
     *
     * <p>Runs to completion before returning, because copying a large store is not instant and an operator
     * following the add-a-node procedure needs to know it finished rather than that it started.</p>
     *
     * @return {@code true} - a value only so the REST client has something to wait on.
     */
    @Override
    public boolean backfill(final String uuid) {
        return securityContextProvider.get().secureResult(AppPermission.MANAGE_NODES_PERMISSION, () -> {
            graphBackfillServiceProvider.get().backfill(fetch(uuid));
            return true;
        });
    }

    @Override
    public GraphElementTable expandNode(final String uuid, final String nodeId, final String query) {
        final GraphDbDoc doc = fetch(uuid);
        return graphExpandServiceProvider.get().expand(doc, nodeId, query);
    }

    private DocRef getDocRef(final String uuid) {
        return DocRef.builder()
                .uuid(uuid)
                .type(GraphDbDoc.TYPE)
                .build();
    }
}
