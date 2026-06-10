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
import stroom.entity.shared.ExpressionCriteria;
import stroom.util.shared.ResourcePaths;
import stroom.util.shared.RestResource;
import stroom.util.shared.ResultPage;
import stroom.util.shared.TemporalEntry;
import stroom.util.shared.TemporalEntryId;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.fusesource.restygwt.client.DirectRestService;

@Tag(name = "SqlTemporalStore")
@Path(SqlTemporalStoreResource.BASE_PATH)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface SqlTemporalStoreResource extends RestResource, DirectRestService {

    String BASE_PATH = "/sqltemporalstore" + ResourcePaths.V1;

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

    @POST
    @Path("/entry")
    @Operation(summary = "Create an entry",
            description = "Creates a new temporal entry in the store.")
    TemporalEntry create(@Parameter(description = "entry", required = true) TemporalEntry entry);

    @PUT
    @Path("/entry")
    @Operation(summary = "Update an entry",
            description = "Updates an existing temporal entry in the store.")
    TemporalEntry update(@Parameter(description = "entry", required = true) TemporalEntry entry);

    @POST
    @Path("/entry/fetch")
    @Operation(summary = "Fetch an entry",
            description = "Fetches a specific entry from the store.")
    TemporalEntry fetch(@Parameter(description = "id", required = true) TemporalEntryId id);

    @POST
    @Path("/entry/delete")
    @Operation(summary = "Delete an entry",
            description = "Deletes a specific entry from the store.")
    Boolean delete(@Parameter(description = "id", required = true) TemporalEntryId id);

    @POST
    @Path("/find")
    @Operation(summary = "Find entries",
            description = "Finds entries matching criteria.")
    ResultPage<TemporalEntry> find(@Parameter(description = "criteria", required = true) ExpressionCriteria criteria);
}
