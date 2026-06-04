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

package stroom.sqlstore.shared;

import stroom.docref.DocRef;
import stroom.util.shared.ResourcePaths;
import stroom.util.shared.RestResource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.fusesource.restygwt.client.DirectRestService;

@Tag(name = "SqlStore")
@Path(SqlStoreResource.BASE_PATH)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface SqlStoreResource extends RestResource, DirectRestService {

    String BASE_PATH = "/sqlstore" + ResourcePaths.V1;

    @DELETE
    @Path("/clear")
    @Operation(summary = "Clear a store",
            description = "Clears all entries for the specified store.")
    Boolean clear(@Parameter(description = "docRef", required = true) DocRef docRef);

    @POST
    @Path("/count")
    @Operation(summary = "Get store count",
            description = "Returns the number of entries in the specified store.")
    Long count(@Parameter(description = "docRef", required = true) DocRef docRef);
}
