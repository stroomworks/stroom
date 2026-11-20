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

package stroom.shapeshifter.shared;

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

/// What a QA person needs to judge one Smart Pipeline's translated output on its Quality Assurance tab: its
/// volume and cardinality, samples of the records its translation was learned and checked on beside what they
/// became.
///
/// Held to the pipeline's permissions, as its Learning Status is: anyone who may view the pipeline reads
/// these. A deleted pipeline's are read by its Learning Flow's viewers.
@Tag(name = "Shapeshifter AI Quality")
@Path("/shapeshifterAiQuality" + ResourcePaths.V1)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface QualityResource extends RestResource, DirectRestService {

    /// @param pipelineUuid The pipeline.
    /// @param elementId    Its AI Translator element.
    /// @return Its volume, day by day lately, and the cardinality of what its translation in use was learned on.
    @GET
    @Path("/pipelines/{pipelineUuid}/elements/{elementId}")
    @Operation(
            summary = "The volume a Smart Pipeline translated lately, and the cardinality of its translation",
            operationId = "fetchShapeshifterAiQuality")
    QualityReport report(@PathParam("pipelineUuid") String pipelineUuid,
                         @PathParam("elementId") String elementId);

    /// @param pipelineUuid The pipeline.
    /// @param elementId    Its AI Translator element.
    /// @return The records its translation in use was learned and checked on, newest first, each beside what it
    ///         became; empty where none is in use.
    @GET
    @Path("/pipelines/{pipelineUuid}/elements/{elementId}/samples")
    @Operation(
            summary = "Samples of a Smart Pipeline's source records beside its translated output",
            operationId = "fetchShapeshifterAiQualitySamples")
    List<QualitySample> samples(@PathParam("pipelineUuid") String pipelineUuid,
                                @PathParam("elementId") String elementId);

}
