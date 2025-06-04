/*
 * Copyright 2022 Crown Copyright
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

package stroom.analytics.shared;

import stroom.query.api.ExpressionOperator;
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

@Tag(name = "AnalyticProcess")
@Path("/analyticProcess" + ResourcePaths.V1)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface AnalyticProcessResource
        extends RestResource, DirectRestService {

    @POST
    @Path("/tracker")
    @Operation(
            summary = "Find the analytic process tracker for the specified process",
            operationId = "findAnalyticProcessTracker")
    AnalyticTracker getTracker(@Parameter(description = "analyticUuid", required = true)
                               String analyticUuid);

    @POST
    @Path("/getDefaultProcessingFilterExpression")
    @Operation(
            summary = "Find the default processing filter expression",
            operationId = "getDefaultProcessingFilterExpression")
    ExpressionOperator getDefaultProcessingFilterExpression(@Parameter(description = "query", required = true)
                                            String query);
}
