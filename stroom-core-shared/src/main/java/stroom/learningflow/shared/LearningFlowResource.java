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

package stroom.learningflow.shared;

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

@Tag(name = "Learning Flow")
@Path("/learningFlow" + ResourcePaths.V1)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface LearningFlowResource
        extends RestResource, DirectRestService, FetchWithUuid<LearningFlowDoc> {

    @GET
    @Path("/{uuid}")
    @Operation(
            summary = "Fetch a Learning Flow document by its UUID",
            operationId = "fetchLearningFlow")
    LearningFlowDoc fetch(@PathParam("uuid") String uuid);

    @GET
    @Path("/templates")
    @Operation(
            summary = "The built-in text of the flow's templates and its version",
            operationId = "fetchLearningFlowTemplates")
    BuiltInTemplates templates();

    /// Fetches the built-in example flows with their steps. They are defined as JSON on the server, since the
    /// client has neither classpath resources nor Jackson. A document the client opens carries its own steps,
    /// so the client needs these only to tell which example a document matches and to start a new one from.
    ///
    /// @return Every built-in setup, in the order [FlowExample] declares them.
    @GET
    @Path("/flowExamples")
    @Operation(
            summary = "The built-in default setups and their steps",
            operationId = "fetchLearningFlowFlowExamples")
    List<BuiltInFlow> flowExamples();

    /// Fetches what the Setup tab shows about a target schema: whether it is event-logging, and which elements
    /// its root may hold. The caller needs USE permission on the schema, as for the Setup tab's schema picker.
    ///
    /// @param uuid The `XMLSchema` document's UUID.
    /// @return Its summary, or null where it can't be read.
    @GET
    @Path("/targetSchema/{uuid}")
    @Operation(
            summary = "What a Learning Flow's Setup tab says about a target schema",
            operationId = "fetchLearningFlowTargetSchema")
    TargetSchemaSummary targetSchema(@PathParam("uuid") String uuid);

    @PUT
    @Path("/{uuid}")
    @Operation(
            summary = "Update a Learning Flow document",
            operationId = "updateLearningFlow")
    LearningFlowDoc update(
            @PathParam("uuid") String uuid,
            @Parameter(description = "doc", required = true) LearningFlowDoc doc);
}
