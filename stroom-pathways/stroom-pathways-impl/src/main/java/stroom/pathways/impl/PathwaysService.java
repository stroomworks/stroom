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
                           final ByteBuffers byteBuffers,/*TEMPORARY TODO 8192*/
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
        throw new UnsupportedOperationException("Not implemented");
    }

    public Boolean deletePathway(final DeletePathway deletePathway) {
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
                        String pathName = zeroIdx != -1
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
                    if (valueByteBuffer == null) return;
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
