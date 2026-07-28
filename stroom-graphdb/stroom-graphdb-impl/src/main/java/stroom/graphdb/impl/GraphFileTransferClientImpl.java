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

import stroom.cluster.task.api.TargetNodeSetFactory;
import stroom.node.api.NodeCallUtil;
import stroom.node.api.NodeInfo;
import stroom.node.api.NodeService;
import stroom.planb.impl.data.FileDescriptor;
import stroom.security.api.SecurityContext;
import stroom.task.api.ExecutorProvider;
import stroom.util.jersey.WebTargetFactory;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.PermissionException;
import stroom.util.shared.ResourcePaths;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Replicates each completed graph fragment to every node named in {@link GraphDbConfig#getNodeList()}.
 *
 * <p>Replication is <b>full</b>, not partitioned: every listed node receives every fragment and therefore holds the
 * whole graph. That is what makes a query correct wherever it runs, and it is not merely a simplification - a graph
 * traversal can follow an edge from data ingested by one node to data ingested by another, so an answer assembled
 * from per-node partial traversals would miss paths that cross a partition boundary. Partitioning one graph across
 * nodes needs distributed traversal, which is a different piece of work entirely.</p>
 *
 * <p>A send that fails to any target throws, and every outstanding send is cancelled. The stream task then fails and
 * the stream is reprocessed later. That is deliberately stricter than delivering to some nodes and reporting
 * success, which would leave the cluster permanently disagreeing about the graph's contents with nothing to
 * indicate it.</p>
 *
 * <p>Adapted from {@code stroom.planb.impl.data.FileTransferClientImpl}, which is left untouched: Plan B has its own
 * node list, its own endpoint and its own staging directories, and a fragment crossing between them would be
 * discarded as unresolvable.</p>
 */
@Singleton
public class GraphFileTransferClientImpl implements GraphFileTransferClient {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(GraphFileTransferClientImpl.class);

    private final Provider<GraphDbConfig> configProvider;
    private final NodeService nodeService;
    private final NodeInfo nodeInfo;
    private final TargetNodeSetFactory targetNodeSetFactory;
    private final WebTargetFactory webTargetFactory;
    private final GraphPartDestination graphPartDestination;
    private final SecurityContext securityContext;
    private final Executor executor;

    @Inject
    public GraphFileTransferClientImpl(final Provider<GraphDbConfig> configProvider,
                                       final NodeService nodeService,
                                       @Nullable final NodeInfo nodeInfo,
                                       @Nullable final TargetNodeSetFactory targetNodeSetFactory,
                                       final WebTargetFactory webTargetFactory,
                                       final GraphPartDestination graphPartDestination,
                                       final SecurityContext securityContext,
                                       final ExecutorProvider executorProvider) {
        this.configProvider = Objects.requireNonNull(configProvider, "configProvider must not be null");
        this.nodeService = nodeService;
        this.nodeInfo = nodeInfo;
        this.targetNodeSetFactory = targetNodeSetFactory;
        this.webTargetFactory = webTargetFactory;
        this.graphPartDestination =
                Objects.requireNonNull(graphPartDestination, "graphPartDestination must not be null");
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext must not be null");
        this.executor = Objects.requireNonNull(executorProvider, "executorProvider must not be null").get();
    }

    @Override
    public void storePart(final FileDescriptor fileDescriptor,
                          final Path path,
                          final boolean synchroniseMerge) {
        Objects.requireNonNull(fileDescriptor, "fileDescriptor must not be null");
        Objects.requireNonNull(path, "path must not be null");

        securityContext.asProcessingUser(() -> {
            final List<String> targetNodes = resolveTargetNodes();

            final List<CompletableFuture<?>> futures = new ArrayList<>(targetNodes.size());
            final List<RuntimeException> collectedExceptions = Collections.synchronizedList(new ArrayList<>());
            for (final String nodeName : targetNodes) {
                futures.add(CompletableFuture.runAsync(() ->
                        securityContext.asProcessingUser(() -> {
                            try {
                                LOGGER.debug(() -> LogUtil.message(
                                        "Graph DB sending data {} to {}",
                                        fileDescriptor.getInfo(path),
                                        nodeName));

                                if (nodeInfo == null || NodeCallUtil.shouldExecuteLocally(nodeInfo, nodeName)) {
                                    // The caller deletes the zip once this returns, so it may only be moved when
                                    // this node is the sole target.
                                    final boolean allowMove = targetNodes.size() == 1;
                                    graphPartDestination.receiveLocalPart(
                                            fileDescriptor, path, allowMove, synchroniseMerge);
                                } else {
                                    storePartRemotely(nodeName, fileDescriptor, path, synchroniseMerge);
                                }
                            } catch (final IOException e) {
                                LOGGER.error(e::getMessage, e);
                                final UncheckedIOException uncheckedIOException = new UncheckedIOException(e);
                                collectedExceptions.add(uncheckedIOException);
                                throw uncheckedIOException;
                            }
                        }), executor));
            }

            // Wait for all sends, cancelling the rest as soon as one fails.
            try {
                allOfTerminateOnFailure(futures).join();
            } catch (final RuntimeException e) {
                if (!collectedExceptions.isEmpty()) {
                    throw collectedExceptions.getFirst();
                } else {
                    throw e;
                }
            }
        });
    }

    /**
     * Works out which nodes must receive this fragment.
     *
     * <p>An unconfigured node list means this node only, which is correct for a single-node deployment. A
     * configured node that is not enabled is an error rather than something to skip: skipping it would leave that
     * node's graph permanently short of this fragment, and it would still answer queries.</p>
     */
    private List<String> resolveTargetNodes() {
        final List<String> configuredNodes = configProvider.get().getNodeList();
        if (configuredNodes == null || configuredNodes.isEmpty()) {
            if (nodeInfo == null) {
                // Returning no targets here would discard the fragment with nothing recorded, which is the exact
                // failure this whole mechanism exists to remove. Fail the task instead so the stream is retried.
                throw new RuntimeException("Graph DB has no configured node list and this node has no identity, " +
                                           "so there is nowhere to send graph data");
            }
            LOGGER.debug(() -> "No node list configured for Graph DB, using the local node only");
            return List.of(nodeInfo.getThisNodeName());
        }

        if (targetNodeSetFactory == null) {
            // No cluster state available, so trust the configured list as-is.
            return List.copyOf(configuredNodes);
        }

        final List<String> targetNodes = new ArrayList<>(configuredNodes.size());
        try {
            final Set<String> enabledNodes = targetNodeSetFactory.getEnabledTargetNodeSet();
            for (final String node : configuredNodes) {
                if (!enabledNodes.contains(node)) {
                    throw new RuntimeException("Graph DB target node '" + node + "' is not enabled");
                }
                targetNodes.add(node);
            }
        } catch (final Exception e) {
            LOGGER.error(e::getMessage, e);
            throw new RuntimeException(e.getMessage(), e);
        }
        return targetNodes;
    }

    private static CompletableFuture<?> allOfTerminateOnFailure(final List<CompletableFuture<?>> futures) {
        final CompletableFuture<Void> failure = new CompletableFuture<>();
        for (final CompletableFuture<?> f : futures) {
            f.exceptionally(ex -> {
                failure.completeExceptionally(ex);
                return null;
            });
        }
        failure.exceptionally(ex -> {
            futures.forEach(f -> f.cancel(true));
            return null;
        });
        return CompletableFuture.anyOf(failure, CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])));
    }

    private void storePartRemotely(final String targetNode,
                                   final FileDescriptor fileDescriptor,
                                   final Path path,
                                   final boolean synchroniseMerge) throws IOException {
        final String baseEndpointUrl = NodeCallUtil.getBaseEndpointUrl(nodeInfo, nodeService, targetNode);
        final String url = baseEndpointUrl + ResourcePaths.buildAuthenticatedApiPath(
                GraphFileTransferResource.BASE_PATH,
                GraphFileTransferResource.SEND_PART_PATH_PART);
        final WebTarget webTarget = webTargetFactory.create(url);
        try {
            storePartRemotely(webTarget, fileDescriptor, path, synchroniseMerge);
        } catch (final Exception e) {
            LOGGER.error(e::getMessage, e);
            throw new IOException("Unable to send graph fragment to '" + targetNode + "': " + e.getMessage(), e);
        }
    }

    void storePartRemotely(final WebTarget webTarget,
                           final FileDescriptor fileDescriptor,
                           final Path path,
                           final boolean synchroniseMerge) throws IOException {
        try (final InputStream inputStream = new BufferedInputStream(Files.newInputStream(path))) {
            try (final Response response = webTarget
                    .request()
                    .header("createTime", fileDescriptor.createTimeMs())
                    .header("metaId", fileDescriptor.metaId())
                    .header("fileHash", fileDescriptor.fileHash())
                    .header("fileName", path.getFileName().toString())
                    .header("synchroniseMerge", synchroniseMerge)
                    .post(Entity.entity(inputStream, MediaType.APPLICATION_OCTET_STREAM))) {
                if (response.getStatus() == Status.UNAUTHORIZED.getStatusCode()) {
                    throw new PermissionException(null, response.getStatusInfo().getReasonPhrase());
                } else if (response.getStatus() != Status.OK.getStatusCode()) {
                    throw new RuntimeException(response.getStatusInfo().getReasonPhrase());
                }
            }
        }
    }
}
