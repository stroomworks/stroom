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

/// REST resource for the Supervisor, which shows learning across every Learning Flow document rather than on
/// a tab of one. It lists learners and their sessions; a session opens to its conversation turn by turn. The
/// actions a person may take are here rather than spread across the documents: answer a turn instead, edit
/// one and run it again, approve or reject a draft, give a parked session more budget or send it to the
/// fallback step, leave guidance, and send a learner back to learn afresh or give it up.
///
/// A binding outlives the session that wrote it, so actions on a binding that is serving (improving or
/// retracting it) start from the binding rather than from a session.
@Tag(name = "Learning Flow Supervisor")
@Path("/learningFlowSupervisor" + ResourcePaths.V1)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface SupervisorResource extends RestResource, DirectRestService {

    @POST
    @Path("/find")
    @Operation(summary = "Find attempts across every Learning Flow document",
            operationId = "findLearningFlowAttempts")
    ResultPage<SupervisorAttempt> find(AttemptCriteria criteria);

    @GET
    @Path("/{id}")
    @Operation(summary = "Read one attempt with its turns",
            operationId = "fetchLearningFlowAttempt")
    SupervisorAttempt fetch(@PathParam("id") long id);

    /// Rebuilds the prompt a turn sent to the model, from what the session recorded and its stream. Only
    /// someone with permission to view data may see it, since it contains the stream's text.
    ///
    /// @param id     The session (attempt) id.
    /// @param number The turn number.
    /// @return The prompt, or why it can't be shown.
    @GET
    @Path("/{id}/turns/{number}/prompt")
    @Operation(summary = "Rebuild the prompt a turn was put to the model with",
            operationId = "fetchLearningFlowTurnPrompt")
    RebuiltPrompt prompt(@PathParam("id") long id, @PathParam("number") int number);

    @POST
    @Path("/{id}/turns/{number}")
    @Operation(summary = "Answer a turn instead, or edit one and run the attempt again from there",
            operationId = "amendLearningFlowTurn")
    SupervisorAttempt amend(@PathParam("id") long id,
                            @PathParam("number") int number,
                            AmendTurnRequest request);

    @POST
    @Path("/{id}/approve")
    @Operation(summary = "Approve what an attempt drafted",
            operationId = "approveLearningFlowAttempt")
    SupervisorAttempt approve(@PathParam("id") long id);

    @POST
    @Path("/{id}/reject")
    @Operation(summary = "Reject what an attempt drafted, and give its learner up",
            operationId = "rejectLearningFlowAttempt")
    SupervisorAttempt reject(@PathParam("id") long id, RejectRequest request);

    @POST
    @Path("/{id}/grant")
    @Operation(summary = "Give a parked attempt more to spend, and carry it on",
            operationId = "grantLearningFlowAttempt")
    SupervisorAttempt grant(@PathParam("id") long id, GrantRequest request);

    @POST
    @Path("/{id}/exhaust")
    @Operation(summary = "Send a parked attempt to the exhaustion step, and carry it on",
            operationId = "exhaustLearningFlowAttempt")
    SupervisorAttempt exhaust(@PathParam("id") long id);

    @POST
    @Path("/serving/{docUuid}/{bindingUuid}/improve")
    @Operation(
            summary = "Ask for a binding that is already serving to be made better",
            operationId = "improveLearningFlowBinding")
    ImproveOutcome improve(@PathParam("docUuid") String docUuid,
                           @PathParam("bindingUuid") String bindingUuid,
                           ImproveRequest request);

    @POST
    @Path("/serving/{docUuid}/{bindingUuid}/retract")
    @Operation(
            summary = "Take back a binding that is serving, and say how many streams it "
                      + "produced output for",
            operationId = "retractLearningFlowBinding")
    Integer retract(@PathParam("docUuid") String docUuid,
                    @PathParam("bindingUuid") String bindingUuid,
                    RejectRequest request);

    @GET
    @Path("/guidance/{docUuid}")
    @Operation(
            summary = "What a supervisor has told a learner",
            operationId = "fetchLearningFlowGuidance")
    List<SupervisorGuidance> guidance(@PathParam("docUuid") String docUuid,
                                      @QueryParam("pipelineUuid") String pipelineUuid,
                                      @QueryParam("elementId") String elementId);

    @POST
    @Path("/guidance/{docUuid}")
    @Operation(
            summary = "Tell a learner something, without asking for anything to be run",
            operationId = "addLearningFlowGuidance")
    List<SupervisorGuidance> hint(@PathParam("docUuid") String docUuid, GuidanceRequest request);

    @DELETE
    @Path("/guidance/{docUuid}/{id}")
    @Operation(
            summary = "Take back something said to a learner, so that it stops being carried",
            operationId = "withdrawLearningFlowGuidance")
    List<SupervisorGuidance> withdraw(@PathParam("docUuid") String docUuid,
                                      @PathParam("id") long id,
                                      @QueryParam("pipelineUuid") String pipelineUuid,
                                      @QueryParam("elementId") String elementId);

    @POST
    @Path("/learners")
    @Operation(
            summary = "The learning pipelines: each pipeline's Learning Flow element that has learned or is "
                      + "learning, where it stands, what serves it and what waits on it",
            operationId = "findLearningFlowLearners")
    ResultPage<SupervisorLearner> learners(LearnerCriteria criteria);

    @GET
    @Path("/pipelines/{pipelineUuid}/learners")
    @Operation(
            summary = "The learners of one pipeline: each of its Learning Flow elements that has learned or is "
                      + "learning, where it stands, what serves it and what waits on it",
            operationId = "findLearningFlowPipelineLearners")
    List<SupervisorLearner> pipelineLearners(@PathParam("pipelineUuid") String pipelineUuid);

    @GET
    @Path("/learners/{docUuid}/settings")
    @Operation(
            summary = "The settings of the Learning Flow document a learner runs under, for its pipeline's editor",
            operationId = "fetchLearningFlowLearnerSettings")
    LearningFlowDoc settings(@PathParam("docUuid") String docUuid,
                               @QueryParam("pipelineUuid") String pipelineUuid,
                               @QueryParam("elementId") String elementId);

    @POST
    @Path("/learners/{docUuid}/waiting")
    @Operation(
            summary = "The streams waiting on one learner, oldest first",
            operationId = "findLearningFlowLearnerWaiting")
    ResultPage<BacklogStream> waiting(@PathParam("docUuid") String docUuid,
                                      @QueryParam("pipelineUuid") String pipelineUuid,
                                      @QueryParam("elementId") String elementId,
                                      @Parameter(description = "page", required = true) PageRequest pageRequest);

    @POST
    @Path("/learners/{docUuid}/relearn")
    @Operation(
            summary = "Send a learner back to learn afresh from its next stream",
            operationId = "relearnLearningFlowLearner")
    Boolean relearnLearner(@PathParam("docUuid") String docUuid, LearnerRequest request);

    @POST
    @Path("/learners/{docUuid}/giveUp")
    @Operation(
            summary = "Give a learner up: it learns no more, and its streams nothing serves error",
            operationId = "giveUpLearningFlowLearner")
    Boolean giveUp(@PathParam("docUuid") String docUuid, LearnerRequest request);

}
