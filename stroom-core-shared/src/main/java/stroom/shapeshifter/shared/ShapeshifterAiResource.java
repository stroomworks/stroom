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

package stroom.shapeshifter.shared;

import stroom.util.shared.FetchWithUuid;
import stroom.util.shared.ResourcePaths;
import stroom.util.shared.RestResource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.fusesource.restygwt.client.DirectRestService;

import java.util.List;

@Tag(name = "Shapeshifter AI")
@Path("/shapeshifterAi" + ResourcePaths.V1)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface ShapeshifterAiResource
        extends RestResource, DirectRestService, FetchWithUuid<ShapeshifterAiDoc> {

    @GET
    @Path("/{uuid}")
    @Operation(
            summary = "Fetch a Shapeshifter AI document by its UUID",
            operationId = "fetchShapeshifterAi")
    ShapeshifterAiDoc fetch(@PathParam("uuid") String uuid);

    @GET
    @Path("/templates")
    @Operation(
            summary = "The built-in text of the flow's templates and its version",
            operationId = "fetchShapeshifterAiTemplates")
    BuiltInTemplates templates();

    /// The built-in default setups with their steps (Track 2 step 1c). They are defined as JSON on the
    /// server, since the client has neither classpath resources nor Jackson; a document the client opens
    /// carries its own steps, so these are what it needs to name which setup a document matches and to
    /// start a new one from.
    ///
    /// @return Every built-in setup, in the order [FlowExample] declares them.
    @GET
    @Path("/flowExamples")
    @Operation(
            summary = "The built-in default setups and their steps",
            operationId = "fetchShapeshifterAiFlowExamples")
    List<BuiltInFlow> flowExamples();

    @PUT
    @Path("/{uuid}")
    @Operation(
            summary = "Update a Shapeshifter AI document",
            operationId = "updateShapeshifterAi")
    ShapeshifterAiDoc update(
            @PathParam("uuid") String uuid,
            @Parameter(description = "doc", required = true) ShapeshifterAiDoc doc);
}
