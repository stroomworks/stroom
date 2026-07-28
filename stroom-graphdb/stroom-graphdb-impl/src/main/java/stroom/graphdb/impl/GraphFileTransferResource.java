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

import stroom.util.shared.ResourcePaths;
import stroom.util.shared.RestResource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;

/**
 * Receives graph fragments sent by other nodes.
 *
 * <p>Deliberately a different path from Plan B's {@code /fileTransfer/v1}. The two features stage fragments in
 * different directories and each deletes fragments it cannot resolve to a document of its own type, so a fragment
 * delivered to the wrong endpoint would be silently discarded rather than rejected. Distinct paths make that
 * mis-delivery impossible, and make an older node without this endpoint fail the sending task with a 404 - which
 * is the correct outcome during a rolling upgrade, because the alternative is losing the data quietly.</p>
 *
 * <p>There is no snapshot endpoint. Graph fragments are replicated in full to every node that holds graph data, so
 * no node needs to fetch a whole store from another.</p>
 */
@Tag(name = "Graph File Transfer")
@Path(GraphFileTransferResource.BASE_PATH)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface GraphFileTransferResource extends RestResource {

    String BASE_PATH = "/graphFileTransfer" + ResourcePaths.V1;
    String SEND_PART_PATH_PART = "/sendPart";

    @POST
    @Path(SEND_PART_PATH_PART)
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(
            summary = "Send Graph DB fragment",
            operationId = "sendGraphPart",
            responses = {
                    @ApiResponse(description = "Returns: " +
                                               "200 if the fragment was received ok, " +
                                               "401 if unauthorised, " +
                                               "500 for any other error")
            })
    Response sendPart(@HeaderParam("createTime") long createTime,
                      @HeaderParam("metaId") long metaId,
                      @HeaderParam("fileHash") String fileHash,
                      @HeaderParam("fileName") String fileName,
                      @HeaderParam("synchroniseMerge") boolean synchroniseMerge,
                      InputStream inputStream);
}
