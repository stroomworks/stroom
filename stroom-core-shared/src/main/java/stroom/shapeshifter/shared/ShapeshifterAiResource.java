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

/// A Smart Pipelines Learning Flow document: how its pipelines' AI Translators learn. Reading and writing it,
/// and the built-in templates its Setup tab starts from.
@Tag(name = "Smart Pipelines Learning Flow")
@Path("/smartPipelines" + ResourcePaths.V1)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface ShapeshifterAiResource
        extends RestResource, DirectRestService, FetchWithUuid<ShapeshifterAiDoc> {

    /// @param uuid The Learning Flow's uuid.
    /// @return The Learning Flow.
    @GET
    @Path("/{uuid}")
    @Operation(
            summary = "Fetch a Learning Flow by its UUID",
            operationId = "fetchSmartPipelinesLearningFlow")
    ShapeshifterAiDoc fetch(@PathParam("uuid") String uuid);

    /// @return The built-in text of a Learning Flow's templates, and the version they are at.
    @GET
    @Path("/templates")
    @Operation(
            summary = "The built-in text of a Learning Flow's templates and its version",
            operationId = "fetchSmartPipelinesTemplates")
    BuiltInTemplates fetchTemplates();

    /// @param uuid The Learning Flow's uuid, which must be the document's.
    /// @param doc  The Learning Flow as it should be.
    /// @return The Learning Flow as saved.
    @PUT
    @Path("/{uuid}")
    @Operation(
            summary = "Update a Learning Flow",
            operationId = "updateSmartPipelinesLearningFlow")
    ShapeshifterAiDoc update(
            @PathParam("uuid") String uuid,
            @Parameter(description = "doc", required = true) ShapeshifterAiDoc doc);
}
