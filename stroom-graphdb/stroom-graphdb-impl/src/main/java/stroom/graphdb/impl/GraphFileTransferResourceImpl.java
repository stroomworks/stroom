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

import stroom.event.logging.rs.api.AutoLogged;
import stroom.event.logging.rs.api.AutoLogged.OperationType;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.PermissionException;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import java.io.InputStream;

/**
 * Lands a fragment sent by another node into this node's receive directory and queues it for merging.
 *
 * <p>A failure is returned as a status code rather than swallowed, so the sending node's stream task fails and the
 * stream is reprocessed. Reporting success for a fragment that was not received is the one outcome that loses data
 * with nothing to show for it.</p>
 */
@AutoLogged(OperationType.UNLOGGED)
public class GraphFileTransferResourceImpl implements GraphFileTransferResource {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(GraphFileTransferResourceImpl.class);

    private final Provider<GraphPartDestination> graphPartDestinationProvider;

    @Inject
    public GraphFileTransferResourceImpl(final Provider<GraphPartDestination> graphPartDestinationProvider) {
        this.graphPartDestinationProvider = graphPartDestinationProvider;
    }

    @AutoLogged(OperationType.UNLOGGED)
    @Override
    public Response sendPart(final long createTime,
                             final long metaId,
                             final String fileHash,
                             final String fileName,
                             final boolean synchroniseMerge,
                             final InputStream inputStream) {
        try {
            LOGGER.debug(() -> "Receiving graph fragment: " + fileName);
            graphPartDestinationProvider.get().receiveRemotePart(
                    createTime,
                    metaId,
                    fileHash,
                    fileName,
                    synchroniseMerge,
                    inputStream);
            LOGGER.debug(() -> "Successfully received graph fragment: " + fileName);
            return Response
                    .ok()
                    .build();
        } catch (final PermissionException e) {
            LOGGER.error(LogUtil.message("Permission exception receiving graph fragment: {}", fileName), e);
            return Response
                    .status(Status.UNAUTHORIZED.getStatusCode(), e.getMessage())
                    .build();
        } catch (final Exception e) {
            LOGGER.error(LogUtil.message("Exception receiving graph fragment: {}", fileName), e);
            return Response
                    .status(Status.INTERNAL_SERVER_ERROR.getStatusCode(), e.getMessage())
                    .build();
        }
    }
}
