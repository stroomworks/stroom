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
import jakarta.ws.rs.core.MediaType;
import org.fusesource.restygwt.client.DirectRestService;

import java.util.List;

/// The learning of Smart Pipelines (ruling A28): their learners, the attempts each made to learn a
/// translation, the translations (bindings) serving them, and the notes (guidance) people have left them.
///
/// Three kinds of thing are addressed:
///
/// - **A learner** is one pipeline's Smart Pipeline element, at `/pipelines/{pipelineUuid}/elements/{elementId}`.
///   The Learning Flow it runs under is the one its element names (or, for a pipeline since deleted, the one
///   its learning is listed under), and is never sent.
/// - **An attempt** is one run at learning a feed, at `/attempts/{id}`.
/// - **A binding** is one learned translation, at `/bindings/{bindingUuid}`.
///
/// Everything is held to the learner's pipeline's permissions: VIEW to read, EDIT to change. A pipeline since
/// deleted has none to ask; its learning is read by its Learning Flow's VIEW, and nothing is changed for it
/// (A137). The lists across Learning Flows show only the learners whose pipelines the person may view, under
/// the Learning Flows they may view.
@Tag(name = "Learning Flow Supervisor")
@Path("/learningFlowSupervisor" + ResourcePaths.V1)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface SupervisorResource extends RestResource, DirectRestService {

    // Learners

    /// @param criteria Which learners: by Learning Flow, state, and whether deleted pipelines' are shown.
    /// @return A page of learners, most waiting streams first, of the pipelines the person may view.
    @POST
    @Path("/learners/find")
    @Operation(
            summary = "Find the learners of Smart Pipelines: each pipeline's Smart Pipeline element that has "
                      + "learned or is learning, where it stands, what serves it and what waits on it",
            operationId = "findLearningFlowLearners")
    ResultPage<SupervisorLearner> findLearners(LearnerCriteria criteria);

    /// @param pipelineUuid The pipeline.
    /// @return Each of its Smart Pipeline elements that has learned or is learning, under whichever Learning
    ///         Flow it runs under.
    @GET
    @Path("/pipelines/{pipelineUuid}/learners")
    @Operation(
            summary = "The learners of one Smart Pipeline, for its own editor",
            operationId = "fetchLearningFlowPipelineLearners")
    List<SupervisorLearner> fetchPipelineLearners(@PathParam("pipelineUuid") String pipelineUuid);

    /// @param pipelineUuid The learner's pipeline.
    /// @param elementId    Its Smart Pipeline element.
    /// @return The Learning Flow the learner runs under, for its pipeline's editor to show where it stands.
    @GET
    @Path("/pipelines/{pipelineUuid}/elements/{elementId}/settings")
    @Operation(
            summary = "The Learning Flow a learner runs under, for its pipeline's viewers",
            operationId = "fetchLearningFlowLearnerSettings")
    LearningFlowDoc fetchSettings(@PathParam("pipelineUuid") String pipelineUuid,
                                    @PathParam("elementId") String elementId);

    /// @param pipelineUuid The learner's pipeline.
    /// @param elementId    Its Smart Pipeline element.
    /// @param pageRequest  The page.
    /// @return The streams waiting on the learner, oldest first.
    @POST
    @Path("/pipelines/{pipelineUuid}/elements/{elementId}/waiting")
    @Operation(
            summary = "Find the streams waiting on one learner, oldest first",
            operationId = "findLearningFlowLearnerWaiting")
    ResultPage<BacklogStream> findWaiting(@PathParam("pipelineUuid") String pipelineUuid,
                                          @PathParam("elementId") String elementId,
                                          @Parameter(description = "page", required = true) PageRequest pageRequest);

    /// @param pipelineUuid The learner's pipeline.
    /// @param elementId    Its Smart Pipeline element.
    /// @param request      Why, optionally.
    /// @return The learner as it now stands.
    @POST
    @Path("/pipelines/{pipelineUuid}/elements/{elementId}/relearn")
    @Operation(
            summary = "Send a learner back to learn afresh from its next stream",
            operationId = "relearnLearningFlowLearner")
    SupervisorLearner relearn(@PathParam("pipelineUuid") String pipelineUuid,
                              @PathParam("elementId") String elementId,
                              ReasonRequest request);

    /// @param pipelineUuid The learner's pipeline.
    /// @param elementId    Its Smart Pipeline element.
    /// @param request      Why, which is required: its streams error with it.
    /// @return The learner as it now stands.
    @POST
    @Path("/pipelines/{pipelineUuid}/elements/{elementId}/giveUp")
    @Operation(
            summary = "Give a learner up: it learns no more, and its streams nothing serves error",
            operationId = "giveUpLearningFlowLearner")
    SupervisorLearner giveUp(@PathParam("pipelineUuid") String pipelineUuid,
                             @PathParam("elementId") String elementId,
                             ReasonRequest request);

    // Guidance

    /// @param pipelineUuid The learner's pipeline.
    /// @param elementId    Its Smart Pipeline element.
    /// @return What people have told the learner, oldest first.
    @GET
    @Path("/pipelines/{pipelineUuid}/elements/{elementId}/guidance")
    @Operation(
            summary = "What people have told a learner",
            operationId = "fetchLearningFlowGuidance")
    List<SupervisorGuidance> fetchGuidance(@PathParam("pipelineUuid") String pipelineUuid,
                                           @PathParam("elementId") String elementId);

    /// @param pipelineUuid The learner's pipeline.
    /// @param elementId    Its Smart Pipeline element.
    /// @param request      What to tell it.
    /// @return What people have told the learner, oldest first, this among it.
    @POST
    @Path("/pipelines/{pipelineUuid}/elements/{elementId}/guidance")
    @Operation(
            summary = "Tell a learner something, without asking for anything to be run",
            operationId = "addLearningFlowGuidance")
    List<SupervisorGuidance> addGuidance(@PathParam("pipelineUuid") String pipelineUuid,
                                         @PathParam("elementId") String elementId,
                                         GuidanceRequest request);

    /// @param pipelineUuid The learner's pipeline.
    /// @param elementId    Its Smart Pipeline element.
    /// @param id           The guidance to take back; one said to another learner is left alone.
    /// @return What people have told the learner, oldest first, without it.
    @DELETE
    @Path("/pipelines/{pipelineUuid}/elements/{elementId}/guidance/{id}")
    @Operation(
            summary = "Take back something said to a learner, so that it stops being carried",
            operationId = "deleteLearningFlowGuidance")
    List<SupervisorGuidance> deleteGuidance(@PathParam("pipelineUuid") String pipelineUuid,
                                            @PathParam("elementId") String elementId,
                                            @PathParam("id") long id);

    // Attempts

    /// @param criteria Which attempts: by Learning Flow, learner, feed, status or promotion mode.
    /// @return A page of attempts, newest first, without their turns, of the pipelines the person may view.
    @POST
    @Path("/attempts/find")
    @Operation(
            summary = "Find attempts at learning, across every Learning Flow",
            operationId = "findLearningFlowAttempts")
    ResultPage<SupervisorAttempt> findAttempts(AttemptCriteria criteria);

    /// @param id The attempt.
    /// @return The attempt with its turns.
    @GET
    @Path("/attempts/{id}")
    @Operation(
            summary = "Read one attempt with its turns",
            operationId = "fetchLearningFlowAttempt")
    SupervisorAttempt fetchAttempt(@PathParam("id") long id);

    /// Rebuilds the prompt a turn sent to the model, from what the attempt recorded and its stream. Only
    /// someone with permission to view data may see it, since it contains the stream's text.
    ///
    /// @param id     The attempt.
    /// @param number The turn.
    /// @return The prompt, or why it can't be shown.
    @GET
    @Path("/attempts/{id}/turns/{number}/prompt")
    @Operation(
            summary = "Rebuild the prompt a turn was put to the model with",
            operationId = "fetchLearningFlowTurnPrompt")
    RebuiltPrompt fetchPrompt(@PathParam("id") long id, @PathParam("number") int number);

    /// @param id      The attempt.
    /// @param number  The turn.
    /// @param request The answer to give instead.
    /// @return The attempt as it now stands.
    @POST
    @Path("/attempts/{id}/turns/{number}")
    @Operation(
            summary = "Answer a turn instead, or edit one and run the attempt again from there",
            operationId = "amendLearningFlowTurn")
    SupervisorAttempt amendTurn(@PathParam("id") long id,
                                @PathParam("number") int number,
                                AmendTurnRequest request);

    /// @param id The attempt.
    /// @return The attempt as it now stands.
    @POST
    @Path("/attempts/{id}/approve")
    @Operation(
            summary = "Approve the translation an attempt drafted",
            operationId = "approveLearningFlowAttempt")
    SupervisorAttempt approve(@PathParam("id") long id);

    /// @param id      The attempt.
    /// @param request Why, which is required: the learner gives up with it.
    /// @return The attempt as it now stands.
    @POST
    @Path("/attempts/{id}/reject")
    @Operation(
            summary = "Reject the translation an attempt drafted, and give its learner up",
            operationId = "rejectLearningFlowAttempt")
    SupervisorAttempt reject(@PathParam("id") long id, ReasonRequest request);

    /// @param id      The attempt, which is parked.
    /// @param request How many more tokens and how much more time it may spend.
    /// @return The attempt as it now stands.
    @POST
    @Path("/attempts/{id}/grant")
    @Operation(
            summary = "Give a parked attempt more to spend, and carry it on",
            operationId = "grantLearningFlowAttempt")
    SupervisorAttempt grant(@PathParam("id") long id, GrantRequest request);

    /// @param id The attempt, which is parked.
    /// @return The attempt as it now stands.
    @POST
    @Path("/attempts/{id}/exhaust")
    @Operation(
            summary = "Send a parked attempt to the exhaustion step, and carry it on",
            operationId = "exhaustLearningFlowAttempt")
    SupervisorAttempt exhaust(@PathParam("id") long id);

    // Bindings

    /// @param bindingUuid The binding, which is serving.
    /// @param request     What should be better, optionally.
    /// @return What came of it, in words.
    @POST
    @Path("/bindings/{bindingUuid}/improve")
    @Operation(
            summary = "Ask for a translation that is serving to be made better",
            operationId = "improveLearningFlowBinding")
    ImproveOutcome improve(@PathParam("bindingUuid") String bindingUuid, ImproveRequest request);

    /// @param bindingUuid The binding, which is serving.
    /// @param request     Why, which is required: it is recorded against the attempt that wrote the binding.
    /// @return How many streams the binding produced output for, which a person may want to reprocess.
    @POST
    @Path("/bindings/{bindingUuid}/retract")
    @Operation(
            summary = "Take back a translation that is serving, and say how many streams it produced output for",
            operationId = "retractLearningFlowBinding")
    Integer retract(@PathParam("bindingUuid") String bindingUuid, ReasonRequest request);
}
