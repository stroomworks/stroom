/*
 * Copyright 2026 Crown Copyright
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

package stroom.dependency.shared;

import stroom.util.shared.ResourcePaths;
import stroom.util.shared.RestResource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.fusesource.restygwt.client.DirectRestService;

/// The documents in a folder and how they depend on each other, as a graph for the Structure view.
///
/// Served by `DependencyGraphResourceImpl`, in `stroom-explorer-impl`.
@Tag(name = "Dependencies")
@Path(DependencyGraphResource.BASE_PATH)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface DependencyGraphResource extends RestResource, DirectRestService {

    /// The resource's base path.
    String BASE_PATH = "/dependency" + ResourcePaths.V1;
    /// The path of [#fetchGraph], below [#BASE_PATH].
    String GRAPH_PATH_PART = "/graph";

    /// Fetches a folder's dependency graph. Documents the user cannot view are absent from it, not
    /// redacted.
    ///
    /// @param request The folder, and how much context and how many nodes to include.
    /// @return the graph.
    @POST
    @Path(GRAPH_PATH_PART)
    @Operation(
            summary = "Fetch the dependency graph of a folder's contents",
            operationId = "fetchDependencyGraph")
    DependencyGraph fetchGraph(@Parameter(description = "request", required = true)
                               DependencyGraphRequest request);
}
