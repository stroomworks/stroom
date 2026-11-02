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

import stroom.util.shared.PageRequest;
import stroom.util.shared.ResourcePaths;
import stroom.util.shared.RestResource;
import stroom.util.shared.ResultPage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.fusesource.restygwt.client.DirectRestService;

import java.util.List;

/**
 * The Supervisor of ruling A28: attempts across every Shapeshifter AI document, not a tab on one. A row
 * opens to the conversation turn by turn, and what a person may do to an attempt they are reading — answer a
 * turn instead, edit one and run it again, approve or reject what it drafted, or send its learner back to
 * be learned afresh — is here rather than spread across the documents.
 * <p>
 * A binding outlives the attempt that wrote it, so what is done to a binding that is serving — improving,
 * accepting or retracting it — begins from the binding rather than from an attempt.
 */
@Tag(name = "Shapeshifter AI Supervisor")
@Path("/shapeshifterAiSupervisor" + ResourcePaths.V1)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface SupervisorResource extends RestResource, DirectRestService {

    @POST
    @Path("/find")
    @Operation(summary = "Find attempts across every Shapeshifter AI document",
            operationId = "findShapeshifterAiAttempts")
    ResultPage<SupervisorAttempt> find(AttemptCriteria criteria);

    @GET
    @Path("/{id}")
    @Operation(summary = "Read one attempt with its turns",
            operationId = "fetchShapeshifterAiAttempt")
    SupervisorAttempt fetch(@PathParam("id") long id);

    @POST
    @Path("/{id}/turns/{number}")
    @Operation(summary = "Answer a turn instead, or edit one and run the attempt again from there",
            operationId = "amendShapeshifterAiTurn")
    SupervisorAttempt amend(@PathParam("id") long id,
                            @PathParam("number") int number,
                            AmendTurnRequest request);

    @POST
    @Path("/{id}/approve")
    @Operation(summary = "Approve what an attempt drafted",
            operationId = "approveShapeshifterAiAttempt")
    SupervisorAttempt approve(@PathParam("id") long id);

    @POST
    @Path("/{id}/reject")
    @Operation(summary = "Reject what an attempt drafted, and give its learner up",
            operationId = "rejectShapeshifterAiAttempt")
    SupervisorAttempt reject(@PathParam("id") long id, RejectRequest request);

    @POST
    @Path("/{id}/grant")
    @Operation(summary = "Give a parked attempt more to spend, and carry it on",
            operationId = "grantShapeshifterAiAttempt")
    SupervisorAttempt grant(@PathParam("id") long id, GrantRequest request);

    @POST
    @Path("/{id}/exhaust")
    @Operation(summary = "Send a parked attempt to the exhaustion step, and carry it on",
            operationId = "exhaustShapeshifterAiAttempt")
    SupervisorAttempt exhaust(@PathParam("id") long id);

    @POST
    @Path("/backlog")
    @Operation(
            summary = "What is waiting on a session, a row per stream, the raised ones first",
            operationId = "findShapeshifterAiBacklog")
    ResultPage<BacklogStream> backlog(@QueryParam("docUuid") String docUuid,
                                      @Parameter(description = "page", required = true) PageRequest pageRequest);


    @POST
    @Path("/serving/{docUuid}/{bindingUuid}/improve")
    @Operation(
            summary = "Ask for a binding that is already serving to be made better",
            operationId = "improveShapeshifterAiBinding")
    ImproveOutcome improve(@PathParam("docUuid") String docUuid,
                           @PathParam("bindingUuid") String bindingUuid,
                           ImproveRequest request);

    @POST
    @Path("/serving/{docUuid}/{bindingUuid}/accept")
    @Operation(
            summary = "Accept a provisional binding now, rather than waiting for the records that would "
                      + "promote it",
            operationId = "acceptShapeshifterAiBinding")
    Boolean accept(@PathParam("docUuid") String docUuid, @PathParam("bindingUuid") String bindingUuid);

    @POST
    @Path("/serving/{docUuid}/{bindingUuid}/retract")
    @Operation(
            summary = "Take back a binding that is serving, and say how many streams it "
                      + "produced output for",
            operationId = "retractShapeshifterAiBinding")
    Integer retract(@PathParam("docUuid") String docUuid,
                    @PathParam("bindingUuid") String bindingUuid,
                    RejectRequest request);

    @GET
    @Path("/guidance/{docUuid}")
    @Operation(
            summary = "What a supervisor has told a learner",
            operationId = "fetchShapeshifterAiGuidance")
    List<SupervisorGuidance> guidance(@PathParam("docUuid") String docUuid,
                                      @QueryParam("pipelineUuid") String pipelineUuid,
                                      @QueryParam("elementId") String elementId);

    @POST
    @Path("/guidance/{docUuid}")
    @Operation(
            summary = "Tell a learner something, without asking for anything to be run",
            operationId = "addShapeshifterAiGuidance")
    List<SupervisorGuidance> hint(@PathParam("docUuid") String docUuid, GuidanceRequest request);

    @DELETE
    @Path("/guidance/{docUuid}/{id}")
    @Operation(
            summary = "Take back something said to a learner, so that it stops being carried",
            operationId = "withdrawShapeshifterAiGuidance")
    List<SupervisorGuidance> withdraw(@PathParam("docUuid") String docUuid,
                                      @PathParam("id") long id,
                                      @QueryParam("pipelineUuid") String pipelineUuid,
                                      @QueryParam("elementId") String elementId);

    @POST
    @Path("/learners")
    @Operation(
            summary = "The learning pipelines: each pipeline's Shapeshifter AI element that has learned or is "
                      + "learning, where it stands, what serves it and what waits on it",
            operationId = "findShapeshifterAiLearners")
    ResultPage<SupervisorLearner> learners(LearnerCriteria criteria);

    @POST
    @Path("/learners/{docUuid}/waiting")
    @Operation(
            summary = "The streams waiting on one learner, oldest first",
            operationId = "findShapeshifterAiLearnerWaiting")
    ResultPage<BacklogStream> waiting(@PathParam("docUuid") String docUuid,
                                      @QueryParam("pipelineUuid") String pipelineUuid,
                                      @QueryParam("elementId") String elementId,
                                      @Parameter(description = "page", required = true) PageRequest pageRequest);

    @POST
    @Path("/learners/{docUuid}/relearn")
    @Operation(
            summary = "Send a learner back to learn afresh from its next stream",
            operationId = "relearnShapeshifterAiLearner")
    Boolean relearnLearner(@PathParam("docUuid") String docUuid, LearnerRequest request);

    @POST
    @Path("/learners/{docUuid}/giveUp")
    @Operation(
            summary = "Give a learner up: it learns no more, and its streams nothing serves error",
            operationId = "giveUpShapeshifterAiLearner")
    Boolean giveUp(@PathParam("docUuid") String docUuid, LearnerRequest request);

}
