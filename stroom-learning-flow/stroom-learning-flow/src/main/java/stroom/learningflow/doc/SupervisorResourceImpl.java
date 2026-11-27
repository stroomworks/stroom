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
import stroom.learningflow.shared.AmendTurnRequest;
import stroom.learningflow.shared.AttemptCriteria;
import stroom.learningflow.shared.BacklogStream;
import stroom.learningflow.shared.GrantRequest;
import stroom.learningflow.shared.GuidanceRequest;
import stroom.learningflow.shared.ImproveOutcome;
import stroom.learningflow.shared.ImproveRequest;
import stroom.learningflow.shared.LearnerCriteria;
import stroom.learningflow.shared.LearnerRequest;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.RebuiltPrompt;
import stroom.learningflow.shared.RejectRequest;
import stroom.learningflow.shared.SupervisorAttempt;
import stroom.learningflow.shared.SupervisorGuidance;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.shared.SupervisorResource;
import stroom.util.shared.PageRequest;
import stroom.util.shared.ResultPage;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.List;

/// The REST resource behind the Supervisor screen. Requests are audited by Stroom's REST logging; everything
/// else, including what each request does and who may make it, is in [SupervisorService].
@AutoLogged
public class SupervisorResourceImpl implements SupervisorResource {

    private final Provider<SupervisorService> serviceProvider;

    @Inject
    SupervisorResourceImpl(final Provider<SupervisorService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @Override
    public ResultPage<SupervisorLearner> learners(final LearnerCriteria criteria) {
        return serviceProvider.get().learners(criteria);
    }

    @Override
    public List<SupervisorLearner> pipelineLearners(final String pipelineUuid) {
        return serviceProvider.get().pipelineLearners(pipelineUuid);
    }

    @Override
    public LearningFlowDoc settings(final String docUuid, final String pipelineUuid, final String elementId) {
        return serviceProvider.get().settings(docUuid, pipelineUuid, elementId);
    }

    @Override
    public ResultPage<BacklogStream> waiting(final String docUuid,
                                             final String pipelineUuid,
                                             final String elementId,
                                             final PageRequest pageRequest) {
        return serviceProvider.get().waiting(docUuid, pipelineUuid, elementId, pageRequest);
    }

    @Override
    public Boolean relearnLearner(final String docUuid, final LearnerRequest request) {
        return serviceProvider.get().relearnLearner(docUuid, request);
    }

    @Override
    public Boolean giveUp(final String docUuid, final LearnerRequest request) {
        return serviceProvider.get().giveUp(docUuid, request);
    }

    @Override
    public SupervisorAttempt grant(final long id, final GrantRequest request) {
        return serviceProvider.get().grant(id, request);
    }

    @Override
    public SupervisorAttempt exhaust(final long id) {
        return serviceProvider.get().exhaust(id);
    }

    @Override
    public ResultPage<SupervisorAttempt> find(final AttemptCriteria criteria) {
        return serviceProvider.get().find(criteria);
    }

    @Override
    public ImproveOutcome improve(final String docUuid, final String bindingUuid, final ImproveRequest request) {
        return serviceProvider.get().improve(docUuid, bindingUuid, request);
    }

    @Override
    public Integer retract(final String docUuid, final String bindingUuid, final RejectRequest request) {
        return serviceProvider.get().retract(docUuid, bindingUuid, request);
    }

    @Override
    public List<SupervisorGuidance> guidance(final String docUuid,
                                             final String pipelineUuid,
                                             final String elementId) {
        return serviceProvider.get().guidance(docUuid, pipelineUuid, elementId);
    }

    @Override
    public List<SupervisorGuidance> hint(final String docUuid, final GuidanceRequest request) {
        return serviceProvider.get().hint(docUuid, request);
    }

    @Override
    public List<SupervisorGuidance> withdraw(final String docUuid,
                                             final long id,
                                             final String pipelineUuid,
                                             final String elementId) {
        return serviceProvider.get().withdraw(docUuid, id, pipelineUuid, elementId);
    }

    @Override
    public SupervisorAttempt fetch(final long id) {
        return serviceProvider.get().fetch(id);
    }

    @Override
    public SupervisorAttempt amend(final long id, final int number, final AmendTurnRequest request) {
        return serviceProvider.get().amend(id, number, request);
    }

    @Override
    public RebuiltPrompt prompt(final long id, final int number) {
        return serviceProvider.get().prompt(id, number);
    }

    @Override
    public SupervisorAttempt approve(final long id) {
        return serviceProvider.get().approve(id);
    }

    @Override
    public SupervisorAttempt reject(final long id, final RejectRequest request) {
        return serviceProvider.get().reject(id, request);
    }
}
