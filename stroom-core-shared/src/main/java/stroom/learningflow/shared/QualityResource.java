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

package stroom.learningflow.shared;

import stroom.util.shared.ResourcePaths;
import stroom.util.shared.RestResource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.fusesource.restygwt.client.DirectRestService;

import java.util.List;

/// REST resource for a Smart Pipeline's Quality Assurance tab, which gives a person what they need to judge
/// the pipeline's translated output: its volume and cardinality, and samples of the records its translation
/// was learned and checked on beside what they became.
///
/// Access follows the pipeline's permissions, as the Learning Status tab's does: anyone who may view the
/// pipeline may read these. For a deleted pipeline, anyone who may view its Learning Flow document may.
@Tag(name = "Learning Flow Quality")
@Path("/learningFlowQuality" + ResourcePaths.V1)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface QualityResource extends RestResource, DirectRestService {

    /// @param pipelineUuid The pipeline.
    /// @param elementId    The id of its Smart Pipeline element.
    /// @return Its recent daily volume, and the cardinality of what its translation in use was learned on.
    @GET
    @Path("/pipelines/{pipelineUuid}/elements/{elementId}")
    @Operation(
            summary = "The volume a Smart Pipeline translated lately, and the cardinality of its translation",
            operationId = "fetchLearningFlowQuality")
    QualityReport report(@PathParam("pipelineUuid") String pipelineUuid,
                         @PathParam("elementId") String elementId);

    /// @param pipelineUuid The pipeline.
    /// @param elementId    The id of its Smart Pipeline element.
    /// @return The records its translation in use was learned and checked on, newest first, each beside what it
    ///         became; empty where no translation is in use.
    @GET
    @Path("/pipelines/{pipelineUuid}/elements/{elementId}/samples")
    @Operation(
            summary = "Samples of a Smart Pipeline's source records beside its translated output",
            operationId = "fetchLearningFlowQualitySamples")
    List<QualitySample> samples(@PathParam("pipelineUuid") String pipelineUuid,
                                @PathParam("elementId") String elementId);

}
