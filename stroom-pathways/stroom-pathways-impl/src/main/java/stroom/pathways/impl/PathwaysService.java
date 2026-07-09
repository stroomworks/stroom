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

import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.docref.DocRef;
import stroom.docstore.api.DocumentNotFoundException;
import stroom.node.api.NodeCallUtil;
import stroom.node.api.NodeInfo;
import stroom.node.api.NodeService;
import stroom.pathways.impl.events.PathwayEvent;
import stroom.pathways.impl.events.PathwayEventType;
import stroom.pathways.impl.events.PathwayRootDiscoveryEvent;
import stroom.pathways.shared.AddPathway;
import stroom.pathways.shared.DeleteAndReprocessPathway;
import stroom.pathways.shared.DeletePathway;
import stroom.pathways.shared.FindPathwayCriteria;
import stroom.pathways.shared.PathwayResultPage;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.PathwaysResource;
import stroom.pathways.shared.UpdatePathway;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Pathway;
import stroom.planb.impl.db.AbstractDb;
import stroom.planb.impl.db.LmdbWriter;
import stroom.planb.impl.db.trace.NanoTimeUtil;
import stroom.planb.impl.db.trace.PathwaysDb;
import stroom.util.io.PathCreator;
import stroom.util.jersey.WebTargetFactory;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageResponse;
import stroom.util.shared.ResourcePaths;
import stroom.util.shared.ResultPage;

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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

@Singleton
public class PathwaysService {

    protected static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(PathwaysService.class);

    private final PathwaysStore pathwaysStore;
    private final PathwaysProcessor pathwaysProcessor;
    private final Provider<NodeService> nodeServiceProvider;
    private final Provider<NodeInfo> nodeInfoProvider;
    private final Provider<WebTargetFactory> webTargetFactoryProvider;
    final ByteBuffers byteBuffers; /*TEMPORARY TODO 8192*/
    final PathwayEventsSerde pathwayEventsSerde;
    final Path dbPath;

    @Inject
    public PathwaysService(final PathwaysProcessor pathwaysProcessor,
                           final PathwaysStore pathwaysStore,
                           final Provider<NodeService> nodeServiceProvider,
                           final Provider<NodeInfo> nodeInfoProvider,
                           final Provider<WebTargetFactory> webTargetFactoryProvider,
            /*TEMPORARY TODO 8192*/
                           final ByteBuffers byteBuffers,
                           final PathCreator pathCreator,
                           final PathwayEventsSerde pathwayEventsSerde) {
        this.pathwaysProcessor = pathwaysProcessor;
        this.pathwaysStore = pathwaysStore;
        this.nodeServiceProvider = nodeServiceProvider;
        this.nodeInfoProvider = nodeInfoProvider;
        this.webTargetFactoryProvider = webTargetFactoryProvider;
        this.byteBuffers = byteBuffers;
        this.pathwayEventsSerde = pathwayEventsSerde;
        dbPath = pathCreator.toAppPath("${stroom.home}/pathways");
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
        final List<DocRef> docRefs = pathwaysStore.list();
        for (final DocRef docRef : NullSafe.list(docRefs)) {
            final PathwaysDb pathwaysDb = pathwaysProcessor.getPathwaysDb(docRef);

            final byte[] keyBytes = "POST /people".getBytes(StandardCharsets.UTF_8);
            LOGGER.error("Attempting to recover data for " + Arrays.toString(keyBytes));
            LOGGER.error("TRY");
            try (final LmdbWriter writer = pathwaysDb.createWriter()) {
                LOGGER.error("prefixRange");
                final List<PathwayEvent> events = new java.util.ArrayList<>();

                LOGGER.error("--- DEBUG DUMP OF DB KEYS ---");
                final stroom.lmdb.stream.LmdbKeyRange allRange = stroom.lmdb.stream.LmdbKeyRange.all();
                final int[] count = new int[]{0};
                pathwaysDb.getPathwayEvents().iterate(writer.getWriteTxn(), allRange, (k, v) -> {
                    if (count[0]++ < 10) {
                        final byte[] kArr = new byte[k.remaining()];
                        k.duplicate().get(kArr);

                        int zeroIdx = -1;
                        for (int i = 0; i < kArr.length; i++) {
                            if (kArr[i] == 0) {
                                zeroIdx = i;
                                break;
                            }
                        }
                        final String pathName = zeroIdx != -1
                                ? new String(kArr, 0, zeroIdx, StandardCharsets.UTF_8)
                                : new String(kArr, StandardCharsets.UTF_8);
                        LOGGER.error("DB KEY FOUND: " + pathName);
                    }
                });
                LOGGER.error("--- END DEBUG DUMP ---");
                final ByteBuffer prefixBuffer = ByteBuffer.allocateDirect(keyBytes.length + 1);
                prefixBuffer.put(keyBytes);
                prefixBuffer.put((byte) 0);
                prefixBuffer.flip();

                final stroom.lmdb.stream.LmdbKeyRange prefixRange = stroom.lmdb.stream.LmdbKeyRange.builder()
                        .prefix(prefixBuffer)
                        .build();

                LOGGER.error("iterate");
                pathwaysDb.getPathwayEvents().iterate(writer.getWriteTxn(), prefixRange, (keyBb, valueByteBuffer) -> {
                    LOGGER.error("iterate-loop");
                    final byte[] keyArr = new byte[keyBb.remaining()];
                    keyBb.duplicate().get(keyArr);
                    LOGGER.error("keyBb: " + Arrays.toString(keyArr));

                    LOGGER.error("if (valueByteBuffer == null) return;");
                    if (valueByteBuffer == null) {
                        return;
                    }
                    LOGGER.error("no return;");

                    LOGGER.error("valArr");
                    final byte[] valArr = new byte[valueByteBuffer.remaining()];
                    valueByteBuffer.duplicate().get(valArr);
                    LOGGER.error("valueByteBuffer: " + Arrays.toString(valArr));

                    events.add(pathwayEventsSerde.readPathwayEvent(valueByteBuffer, new HashMap<>()));
                });
                events.forEach(pathwayEvent -> {
                    LOGGER.error(pathwayEvent.getDescription());
                });
            }
        }
        throw new UnsupportedOperationException("Not implemented");
    }

    public Boolean deletePathway(final DeletePathway deletePathway) {
        LOGGER.error("deletePathway called: " + deletePathway);
        if (deletePathway == null || deletePathway.getDocRef() == null || deletePathway.getName() == null) {
            LOGGER.error("early return false - null constraint check failed");
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
            LOGGER.error("Opened DB for docRef: " + deletePathway.getDocRef() + ". keyBytes=" + Arrays.toString(keyBytes));

            try (final LmdbWriter writer = pathwaysDb.createWriter()) {
                
                // --- DEBUG DUMP AHEAD OF DELETION ---
                LOGGER.error("--- DEBUG DUMP PATHWAYS MAIN TABLE ---");
                pathwaysDb.getPathways().iterate(writer.getWriteTxn(), stroom.lmdb.stream.LmdbKeyRange.all(), (k, v) -> {
                    final byte[] kArr = new byte[k.remaining()];
                    k.duplicate().get(kArr);
                    LOGGER.error("PATHWAY DB KEY: " + new String(kArr, StandardCharsets.UTF_8) + " (bytes: " + Arrays.toString(kArr) + ")");
                });
                
                LOGGER.error("--- DEBUG DUMP PATHWAY EVENTS ---");
                final int[] evtCount = new int[]{0};
                pathwaysDb.getPathwayEvents().iterate(writer.getWriteTxn(), stroom.lmdb.stream.LmdbKeyRange.all(), (k, v) -> {
                    if (evtCount[0]++ < 100) {
                        final byte[] kArr = new byte[k.remaining()];
                        k.duplicate().get(kArr);
                        int zeroIdx = -1;
                        for(int i=0; i<kArr.length; i++) if(kArr[i]==0) {zeroIdx=i; break;}
                        String name = zeroIdx != -1 ? new String(kArr, 0, zeroIdx, StandardCharsets.UTF_8) : "NO_NULL";
                        LOGGER.error("EVENT DB KEY: name=" + name + " full_bytes=" + Arrays.toString(kArr));
                    }
                });
                LOGGER.error("--- END DEBUG DUMP ---");

                final ByteBuffer pathwayKeyBuf = ByteBuffer.allocateDirect(keyBytes.length);
                pathwayKeyBuf.put(keyBytes).flip();
                LOGGER.error("Deleting from pathwaysDb.getPathways()");
                pathwaysDb.getPathways().delete(writer, pathwayKeyBuf);

                final List<ByteBuffer> keysToDelete = new java.util.ArrayList<>();

                final ByteBuffer prefixBuffer = ByteBuffer.allocateDirect(keyBytes.length + 1);
                prefixBuffer.put(keyBytes);
                prefixBuffer.put((byte) 0);
                prefixBuffer.flip();
                LOGGER.error("Prefix buffer range created: limit=" + prefixBuffer.limit());

                final stroom.lmdb.stream.LmdbKeyRange prefixRange = stroom.lmdb.stream.LmdbKeyRange.builder()
                        .prefix(prefixBuffer)
                        .build();

                LOGGER.error("Iterating pathwayEvents...");
                pathwaysDb.getPathwayEvents().iterate(writer.getWriteTxn(), prefixRange, (keyBb, valueByteBuffer) -> {
                    final ByteBuffer keyCopy = ByteBuffer.allocateDirect(keyBb.remaining());
                    keyCopy.put(keyBb.duplicate()).flip();
                    keysToDelete.add(keyCopy);
                });

                LOGGER.error("Keys collected for deletion from pathwayEvents: " + keysToDelete.size());
                for (final ByteBuffer keyToDelete : keysToDelete) {
                    pathwaysDb.getPathwayEvents().delete(writer, keyToDelete);
                }

                LOGGER.error("Committing transaction...");
                writer.commit();
                LOGGER.error("deletePathway completed successfully");
                return true;
            }
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
            try (final LmdbWriter writer = pathwaysDb.createWriter()) {
                if (request.getName() != null) {
                    final byte[] keyBytes = request.getName().getBytes(StandardCharsets.UTF_8);
                    deletePathwayEntries(writer, pathwaysDb, keyBytes);
                }
                final int cleared = clearProcessingStatus(writer, pathwaysDb);
                writer.commit();
                LOGGER.info("deleteAndReprocessPathway: pathway=" + request.getName()
                            + ", cleared " + cleared + " processing-status marker(s)");
                return true;
            }
        } catch (final Exception e) {
            LOGGER.error("Failed to delete & reprocess pathway " + request.getName(), e);
            return false;
        }
    }

    /**
     * Deletes a single pathway and its associated pathway-events from the (local) pathways database.
     * The pathway is keyed by its name bytes; its events are keyed by {@code <name>\0...}.
     */
    private void deletePathwayEntries(final LmdbWriter writer,
                                      final PathwaysDb pathwaysDb,
                                      final byte[] keyBytes) {
        final ByteBuffer pathwayKeyBuf = ByteBuffer.allocateDirect(keyBytes.length);
        pathwayKeyBuf.put(keyBytes).flip();
        pathwaysDb.getPathways().delete(writer, pathwayKeyBuf);

        final ByteBuffer prefixBuffer = ByteBuffer.allocateDirect(keyBytes.length + 1);
        prefixBuffer.put(keyBytes);
        prefixBuffer.put((byte) 0);
        prefixBuffer.flip();
        final stroom.lmdb.stream.LmdbKeyRange prefixRange = stroom.lmdb.stream.LmdbKeyRange.builder()
                .prefix(prefixBuffer)
                .build();

        final List<ByteBuffer> keysToDelete = new java.util.ArrayList<>();
        pathwaysDb.getPathwayEvents().iterate(writer.getWriteTxn(), prefixRange, (keyBb, valueByteBuffer) -> {
            final ByteBuffer keyCopy = ByteBuffer.allocateDirect(keyBb.remaining());
            keyCopy.put(keyBb.duplicate()).flip();
            keysToDelete.add(keyCopy);
        });
        for (final ByteBuffer keyToDelete : keysToDelete) {
            pathwaysDb.getPathwayEvents().delete(writer, keyToDelete);
        }
    }

    /**
     * Removes every entry from the processing-status DBI so all traces become eligible for
     * reprocessing on the next run. Returns the number of markers cleared.
     */
    private int clearProcessingStatus(final LmdbWriter writer,
                                      final PathwaysDb pathwaysDb) {
        final List<ByteBuffer> keysToDelete = new java.util.ArrayList<>();
        pathwaysDb.getProcessingStatus().iterate(writer.getWriteTxn(),
                stroom.lmdb.stream.LmdbKeyRange.all(), (keyBb, valueByteBuffer) -> {
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
}
