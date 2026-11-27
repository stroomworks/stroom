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

package stroom.learningflow;

import stroom.docref.DocRef;
import stroom.learningflow.doc.LearningFlowStore;
import stroom.learningflow.doc.SupervisorService;
import stroom.learningflow.element.StageFactory;
import stroom.learningflow.shared.LearnerCriteria;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.test.AbstractCoreIntegrationTest;
import stroom.util.shared.ResultPage;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/// Tests, in a running node, that the Supervisor's service works when called from a REST request.
///
/// A REST request runs outside any pipeline scope, but the service builds a [StageFactory] for several of
/// its operations, and the stage factory's dependencies include pipeline-scoped objects. Those must only be
/// resolved when a pipeline scope is in force, otherwise listing the learners of a new Learning Flow
/// document fails with a Guice `OutOfScopeException`.
class TestSupervisorService extends AbstractCoreIntegrationTest {

    @Inject
    private LearningFlowStore learningFlowStore;
    @Inject
    private SupervisorService supervisorService;
    @Inject
    private Provider<StageFactory> stageFactoryProvider;

    @Test
    void aStageFactoryCanBeBuiltOutsideAPipelineScope() {
        assertThatCode(() -> stageFactoryProvider.get().breaker())
                .describedAs("the stage factory is built without a pipeline scope in force")
                .doesNotThrowAnyException();
    }

    @Test
    void theLearnersOfANewDocumentCanBeListedOutsideAPipelineScope() {
        final DocRef docRef = learningFlowStore.createDocument("new-document");

        final ResultPage<SupervisorLearner> learners = supervisorService.learners(
                new LearnerCriteria(null, null, docRef.getUuid(), null, false));

        assertThat(learners.getValues())
                .describedAs("a new document has no learners yet")
                .isEmpty();
    }
}
