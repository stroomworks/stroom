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

package stroom.learningflow.doc;

import stroom.event.logging.rs.api.AutoLogged;
import stroom.event.logging.rs.api.AutoLogged.OperationType;
import stroom.learningflow.shared.AmendTurnRequest;
import stroom.learningflow.shared.AttemptCriteria;
import stroom.learningflow.shared.BacklogStream;
import stroom.learningflow.shared.GrantRequest;
import stroom.learningflow.shared.GuidanceRequest;
import stroom.learningflow.shared.ImproveOutcome;
import stroom.learningflow.shared.ImproveRequest;
import stroom.learningflow.shared.LearnerCriteria;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.ReasonRequest;
import stroom.learningflow.shared.RebuiltPrompt;
import stroom.learningflow.shared.SupervisorAttempt;
import stroom.learningflow.shared.SupervisorGuidance;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.shared.SupervisorResource;
import stroom.util.shared.PageRequest;
import stroom.util.shared.ResultPage;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.List;

/// The REST resource behind the Smart Pipelines screen and a pipeline's Learning Status tab. Each request is
/// audited by Stroom's REST logging as the operation its annotation names; everything else, including what
/// each request does and who may make it, is in [SupervisorService].
@AutoLogged
public class SupervisorResourceImpl implements SupervisorResource {

    private final Provider<SupervisorService> serviceProvider;

    @Inject
    SupervisorResourceImpl(final Provider<SupervisorService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    // --------------------------------------------------------------------------------
    // Learners

    @Override
    @AutoLogged(OperationType.SEARCH)
    public ResultPage<SupervisorLearner> findLearners(final LearnerCriteria criteria) {
        return serviceProvider.get().findLearners(criteria);
    }

    @Override
    @AutoLogged(OperationType.VIEW)
    public List<SupervisorLearner> fetchPipelineLearners(final String pipelineUuid) {
        return serviceProvider.get().fetchPipelineLearners(pipelineUuid);
    }

    @Override
    @AutoLogged(OperationType.VIEW)
    public LearningFlowDoc fetchSettings(final String pipelineUuid, final String elementId) {
        return serviceProvider.get().fetchSettings(pipelineUuid, elementId);
    }

    @Override
    @AutoLogged(OperationType.SEARCH)
    public ResultPage<BacklogStream> findWaiting(final String pipelineUuid,
                                                 final String elementId,
                                                 final PageRequest pageRequest) {
        return serviceProvider.get().findWaiting(pipelineUuid, elementId, pageRequest);
    }

    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Sending a Smart Pipeline learner back to learn afresh")
    public SupervisorLearner relearn(final String pipelineUuid,
                                     final String elementId,
                                     final ReasonRequest request) {
        return serviceProvider.get().relearn(pipelineUuid, elementId, request);
    }

    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Giving up a Smart Pipeline learner")
    public SupervisorLearner giveUp(final String pipelineUuid,
                                    final String elementId,
                                    final ReasonRequest request) {
        return serviceProvider.get().giveUp(pipelineUuid, elementId, request);
    }

    // --------------------------------------------------------------------------------
    // Guidance

    @Override
    @AutoLogged(OperationType.VIEW)
    public List<SupervisorGuidance> fetchGuidance(final String pipelineUuid, final String elementId) {
        return serviceProvider.get().fetchGuidance(pipelineUuid, elementId);
    }

    @Override
    @AutoLogged(OperationType.CREATE)
    public List<SupervisorGuidance> addGuidance(final String pipelineUuid,
                                                final String elementId,
                                                final GuidanceRequest request) {
        return serviceProvider.get().addGuidance(pipelineUuid, elementId, request);
    }

    @Override
    @AutoLogged(OperationType.DELETE)
    public List<SupervisorGuidance> deleteGuidance(final String pipelineUuid,
                                                   final String elementId,
                                                   final long id) {
        return serviceProvider.get().deleteGuidance(pipelineUuid, elementId, id);
    }

    // --------------------------------------------------------------------------------
    // Attempts

    @Override
    @AutoLogged(OperationType.SEARCH)
    public ResultPage<SupervisorAttempt> findAttempts(final AttemptCriteria criteria) {
        return serviceProvider.get().findAttempts(criteria);
    }

    @Override
    @AutoLogged(OperationType.VIEW)
    public SupervisorAttempt fetchAttempt(final long id) {
        return serviceProvider.get().fetchAttempt(id);
    }

    @Override
    @AutoLogged(OperationType.VIEW)
    public RebuiltPrompt fetchPrompt(final long id, final int number) {
        return serviceProvider.get().fetchPrompt(id, number);
    }

    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Answering or editing a learning attempt turn")
    public SupervisorAttempt amendTurn(final long id, final int number, final AmendTurnRequest request) {
        return serviceProvider.get().amendTurn(id, number, request);
    }

    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Approving a drafted translation")
    public SupervisorAttempt approve(final long id) {
        return serviceProvider.get().approve(id);
    }

    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Rejecting a drafted translation")
    public SupervisorAttempt reject(final long id, final ReasonRequest request) {
        return serviceProvider.get().reject(id, request);
    }

    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Granting a parked learning attempt more to spend")
    public SupervisorAttempt grant(final long id, final GrantRequest request) {
        return serviceProvider.get().grant(id, request);
    }

    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Sending a parked learning attempt to its exhaustion step")
    public SupervisorAttempt exhaust(final long id) {
        return serviceProvider.get().exhaust(id);
    }

    // --------------------------------------------------------------------------------
    // Bindings

    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Asking for a serving translation to be improved")
    public ImproveOutcome improve(final String bindingUuid, final ImproveRequest request) {
        return serviceProvider.get().improve(bindingUuid, request);
    }

    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Retracting a serving translation")
    public Integer retract(final String bindingUuid, final ReasonRequest request) {
        return serviceProvider.get().retract(bindingUuid, request);
    }
}
