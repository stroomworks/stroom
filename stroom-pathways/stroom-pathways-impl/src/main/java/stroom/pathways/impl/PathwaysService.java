/*
 * Copyright 2016-2025 Crown Copyright
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

package stroom.pathways.impl;

import stroom.docstore.api.DocumentNotFoundException;
import stroom.lmdb.stream.LmdbKeyRange;
import stroom.node.api.NodeCallUtil;
import stroom.node.api.NodeInfo;
import stroom.node.api.NodeService;
import stroom.pathways.shared.AddPathway;
import stroom.pathways.shared.DeleteAndReprocessPathway;
import stroom.pathways.shared.DeletePathway;
import stroom.pathways.shared.FindPathwayCriteria;
import stroom.pathways.shared.FindPathwayEventCriteria;
import stroom.pathways.shared.PathwayEventResultPage;
import stroom.pathways.shared.PathwayResultPage;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.PathwaysResource;
import stroom.pathways.shared.UpdatePathway;
import stroom.planb.impl.db.LmdbWriter;
import stroom.planb.impl.db.trace.PathwaysDb;
import stroom.util.jersey.WebTargetFactory;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.PageResponse;
import stroom.util.shared.ResourcePaths;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Singleton
public class PathwaysService {

    protected static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(PathwaysService.class);

    private final PathwaysStore pathwaysStore;
    private final PathwaysProcessor pathwaysProcessor;
    private final Provider<NodeService> nodeServiceProvider;
    private final Provider<NodeInfo> nodeInfoProvider;
    private final Provider<WebTargetFactory> webTargetFactoryProvider;

    @Inject
    public PathwaysService(final PathwaysProcessor pathwaysProcessor,
                           final PathwaysStore pathwaysStore,
                           final Provider<NodeService> nodeServiceProvider,
                           final Provider<NodeInfo> nodeInfoProvider,
                           final Provider<WebTargetFactory> webTargetFactoryProvider) {
        this.pathwaysProcessor = pathwaysProcessor;
        this.pathwaysStore = pathwaysStore;
        this.nodeServiceProvider = nodeServiceProvider;
        this.nodeInfoProvider = nodeInfoProvider;
        this.webTargetFactoryProvider = webTargetFactoryProvider;
    }

    public PathwayResultPage findPathways(final FindPathwayCriteria criteria) {
        final PathwaysDoc pathwaysDoc = pathwaysStore.readDocument(criteria.getDataSourceRef());
        if (pathwaysDoc == null) {
            throw new DocumentNotFoundException(criteria.getDataSourceRef());
        }

        // Find out which node has the pathways database.
        if (pathwaysDoc.getProcessingNode() == null) {
            return new PathwayResultPage(Collections.emptyList(), PageResponse.empty());
        }

        if (pathwaysDoc.getProcessingNode().equals(nodeInfoProvider.get().getThisNodeName())) {
            return pathwaysProcessor.findPathways(criteria);
        }
        return getRemote(pathwaysDoc.getProcessingNode(), criteria);
    }

    public Boolean addPathway(final AddPathway addPathway) {
        throw new UnsupportedOperationException("Not implemented");
    }

    public Boolean updatePathway(final UpdatePathway updatePathway) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Recalls stored pathway events. Pathways data is node-local to the processing node, so this
     * routes to that node (mirroring {@link #findPathways}) which reads the events from the shared
     * filesystem event stores.
     */
    public PathwayEventResultPage findPathwayEvents(final FindPathwayEventCriteria criteria) {
        final PathwaysDoc pathwaysDoc = pathwaysStore.readDocument(criteria.getDataSourceRef());
        if (pathwaysDoc == null) {
            throw new DocumentNotFoundException(criteria.getDataSourceRef());
        }
        if (pathwaysDoc.getProcessingNode() == null) {
            return new PathwayEventResultPage(Collections.emptyList(), PageResponse.empty());
        }
        if (pathwaysDoc.getProcessingNode().equals(nodeInfoProvider.get().getThisNodeName())) {
            return pathwaysProcessor.findPathwayEvents(criteria);
        }
        return getEventsRemote(pathwaysDoc.getProcessingNode(), criteria);
    }

    public Boolean deletePathway(final DeletePathway deletePathway) {
        if (deletePathway == null || deletePathway.getDocRef() == null || deletePathway.getName() == null) {
            return false;
        }

        // Pathways data is node-local, so the delete must run on the node that owns the
        // pathways database for this doc. Route to the processing node if it isn't this one,
        // mirroring findPathways().
        final PathwaysDoc pathwaysDoc = pathwaysStore.readDocument(deletePathway.getDocRef());
        if (pathwaysDoc == null) {
            throw new DocumentNotFoundException(deletePathway.getDocRef());
        }
        if (pathwaysDoc.getProcessingNode() == null) {
            // Nothing has been processed yet, so there is nothing to delete.
            return false;
        }
        if (!pathwaysDoc.getProcessingNode().equals(nodeInfoProvider.get().getThisNodeName())) {
            return deleteRemote(pathwaysDoc.getProcessingNode(), deletePathway);
        }

        try {
            final PathwaysDb pathwaysDb = pathwaysProcessor.getPathwaysDb(deletePathway.getDocRef());
            final byte[] keyBytes = deletePathway.getName().getBytes(StandardCharsets.UTF_8);

            try (final LmdbWriter writer = pathwaysDb.createWriter()) {
                final ByteBuffer pathwayKeyBuf = ByteBuffer.allocateDirect(keyBytes.length);
                pathwayKeyBuf.put(keyBytes).flip();
                pathwaysDb.getPathways().delete(writer, pathwayKeyBuf);
                writer.commit();
            }

            // Events live in separate, per-shard stores; delete them across all shards.
            pathwaysProcessor.deletePathwayEvents(deletePathway.getDocRef(), deletePathway.getName());
            return true;
        } catch (final Exception e) {
            LOGGER.error("Failed to delete pathway " + deletePathway.getName(), e);
            return false;
        }
    }

    /**
     * Debug/test aid. Deletes the pathway (same as {@link #deletePathway}) and additionally clears the
     * entire processing-status for the pathways doc, so the next processing run will reprocess every trace.
     * There is no stored pathway-&gt;trace mapping, so we cannot target only the traces that built this
     * pathway — clearing all processing-status markers for the doc is the only way to force reprocessing.
     */
    public Boolean deleteAndReprocessPathway(final DeleteAndReprocessPathway request) {
        LOGGER.info("deleteAndReprocessPathway called: " + request);
        // A null name is allowed: it means "reset all traces for this doc" without deleting a
        // specific pathway. Only the docRef is required (to locate the pathways database).
        if (request == null || request.getDocRef() == null) {
            return false;
        }

        // Pathways data is node-local, so this must run on the node that owns the pathways database
        // for this doc. Route to the processing node if it isn't this one, mirroring findPathways().
        final PathwaysDoc pathwaysDoc = pathwaysStore.readDocument(request.getDocRef());
        if (pathwaysDoc == null) {
            throw new DocumentNotFoundException(request.getDocRef());
        }
        if (pathwaysDoc.getProcessingNode() == null) {
            // Nothing has been processed yet, so there is nothing to delete or reprocess.
            return false;
        }
        if (!pathwaysDoc.getProcessingNode().equals(nodeInfoProvider.get().getThisNodeName())) {
            return deleteAndReprocessRemote(pathwaysDoc.getProcessingNode(), request);
        }

        try {
            final PathwaysDb pathwaysDb = pathwaysProcessor.getPathwaysDb(request.getDocRef());
            final int cleared;
            try (final LmdbWriter writer = pathwaysDb.createWriter()) {
                if (request.getName() != null) {
                    final byte[] keyBytes = request.getName().getBytes(StandardCharsets.UTF_8);
                    deletePathwayModelEntry(writer, pathwaysDb, keyBytes);
                }
                cleared = clearProcessingStatus(writer, pathwaysDb);
                writer.commit();
            }
            // Events live in separate, per-shard stores; delete them across all shards.
            if (request.getName() != null) {
                pathwaysProcessor.deletePathwayEvents(request.getDocRef(), request.getName());
            }
            LOGGER.info("deleteAndReprocessPathway: pathway=" + request.getName()
                        + ", cleared " + cleared + " processing-status marker(s)");
            return true;
        } catch (final Exception e) {
            LOGGER.error("Failed to delete & reprocess pathway " + request.getName(), e);
            return false;
        }
    }

    /**
     * Deletes a single pathway's model entry from the pathways model database. The pathway is keyed
     * by its name bytes. Its events live in separate per-shard stores and are removed via
     * {@link PathwaysProcessor#deletePathwayEvents}.
     */
    private void deletePathwayModelEntry(final LmdbWriter writer,
                                         final PathwaysDb pathwaysDb,
                                         final byte[] keyBytes) {
        final ByteBuffer pathwayKeyBuf = ByteBuffer.allocateDirect(keyBytes.length);
        pathwayKeyBuf.put(keyBytes).flip();
        pathwaysDb.getPathways().delete(writer, pathwayKeyBuf);
    }

    /**
     * Removes every entry from the processing-status DBI so all traces become eligible for
     * reprocessing on the next run. Returns the number of markers cleared.
     */
    private int clearProcessingStatus(final LmdbWriter writer,
                                      final PathwaysDb pathwaysDb) {
        final List<ByteBuffer> keysToDelete = new ArrayList<>();
        pathwaysDb.getProcessingStatus().iterate(writer.getWriteTxn(),
                LmdbKeyRange.all(), (keyBb, valueByteBuffer) -> {
                    final ByteBuffer keyCopy = ByteBuffer.allocateDirect(keyBb.remaining());
                    keyCopy.put(keyBb.duplicate()).flip();
                    keysToDelete.add(keyCopy);
                });
        for (final ByteBuffer keyToDelete : keysToDelete) {
            pathwaysDb.getProcessingStatus().delete(writer, keyToDelete);
        }
        return keysToDelete.size();
    }

    private Boolean deleteAndReprocessRemote(final String nodeName,
                                             final DeleteAndReprocessPathway request) {
        final String url = NodeCallUtil
                                   .getBaseEndpointUrl(nodeInfoProvider.get(), nodeServiceProvider.get(), nodeName)
                           + ResourcePaths.buildAuthenticatedApiPath(
                PathwaysResource.BASE_PATH, PathwaysResource.DELETE_AND_REPROCESS_PATHWAY_SUB_PATH);
        try {
            // Forward the delete-and-reprocess to the node that owns the pathways database.
            final WebTarget webTarget = webTargetFactoryProvider.get().create(url);
            final Response response = webTarget
                    .request(MediaType.APPLICATION_JSON)
                    .post(Entity.json(request));
            if (response.getStatus() == Status.NOT_FOUND.getStatusCode()) {
                throw new NotFoundException(response);
            } else if (response.getStatus() != Status.OK.getStatusCode()) {
                throw new WebApplicationException(response);
            }

            return response.readEntity(Boolean.class);
        } catch (final Throwable e) {
            throw NodeCallUtil.handleExceptionsOnNodeCall(nodeName, url, e);
        }
    }

    private Boolean deleteRemote(final String nodeName,
                                 final DeletePathway deletePathway) {
        final String url = NodeCallUtil
                                   .getBaseEndpointUrl(nodeInfoProvider.get(), nodeServiceProvider.get(), nodeName)
                           + ResourcePaths.buildAuthenticatedApiPath(
                PathwaysResource.BASE_PATH, PathwaysResource.DELETE_PATHWAY_SUB_PATH);
        try {
            // Forward the delete to the node that owns the pathways database.
            final WebTarget webTarget = webTargetFactoryProvider.get().create(url);
            final Response response = webTarget
                    .request(MediaType.APPLICATION_JSON)
                    .post(Entity.json(deletePathway));
            if (response.getStatus() == Status.NOT_FOUND.getStatusCode()) {
                throw new NotFoundException(response);
            } else if (response.getStatus() != Status.OK.getStatusCode()) {
                throw new WebApplicationException(response);
            }

            return response.readEntity(Boolean.class);
        } catch (final Throwable e) {
            throw NodeCallUtil.handleExceptionsOnNodeCall(nodeName, url, e);
        }
    }

    private PathwayResultPage getRemote(final String nodeName,
                                        final FindPathwayCriteria criteria) {
        final String url = NodeCallUtil
                                   .getBaseEndpointUrl(nodeInfoProvider.get(), nodeServiceProvider.get(), nodeName)
                           + ResourcePaths.buildAuthenticatedApiPath(
                PathwaysResource.BASE_PATH, PathwaysResource.FIND_PATHWAYS_SUB_PATH);
        try {
            // A different node to make a rest call to the required node
            final WebTarget webTarget = webTargetFactoryProvider.get().create(url);
            final Response response = webTarget
                    .request(MediaType.APPLICATION_JSON)
                    .post(Entity.json(criteria));
            if (response.getStatus() == Status.NOT_FOUND.getStatusCode()) {
                throw new NotFoundException(response);
            } else if (response.getStatus() != Status.OK.getStatusCode()) {
                throw new WebApplicationException(response);
            }

            return response.readEntity(PathwayResultPage.class);
        } catch (final Throwable e) {
            throw NodeCallUtil.handleExceptionsOnNodeCall(nodeName, url, e);
        }
    }

    private PathwayEventResultPage getEventsRemote(final String nodeName,
                                                   final FindPathwayEventCriteria criteria) {
        final String url = NodeCallUtil
                                   .getBaseEndpointUrl(nodeInfoProvider.get(), nodeServiceProvider.get(), nodeName)
                           + ResourcePaths.buildAuthenticatedApiPath(
                PathwaysResource.BASE_PATH, PathwaysResource.FIND_PATHWAY_EVENTS_SUB_PATH);
        try {
            final WebTarget webTarget = webTargetFactoryProvider.get().create(url);
            final Response response = webTarget
                    .request(MediaType.APPLICATION_JSON)
                    .post(Entity.json(criteria));
            if (response.getStatus() == Status.NOT_FOUND.getStatusCode()) {
                throw new NotFoundException(response);
            } else if (response.getStatus() != Status.OK.getStatusCode()) {
                throw new WebApplicationException(response);
            }

            return response.readEntity(PathwayEventResultPage.class);
        } catch (final Throwable e) {
            throw NodeCallUtil.handleExceptionsOnNodeCall(nodeName, url, e);
        }
    }
}
