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

package stroom.shapeshifter.ai.impl.db;

import stroom.shapeshifter.ai.quality.Assessments.Held;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.shared.AssessmentVerdict;
import stroom.shapeshifter.shared.QualityAssessment;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// What QA people say of a learner's translated output: newest first per learner, an issue resolved once, and
/// an assessment found by id with whose it is.
class TestAssessmentsDao {

    private static final String DOC = "doc-" + System.nanoTime();
    private static final Learner LEARNER = new Learner("pipeline-" + System.nanoTime(), "shapeshifterAi");
    private static final Learner ANOTHER = new Learner(LEARNER.pipelineUuid(), "audit");

    @Inject
    private AssessmentsDao assessments;

    @BeforeEach
    void setUp() {
        Guice.createInjector(new TestModule()).injectMembers(this);
    }

    @Test
    void aLearnersAssessmentsComeNewestFirstAndNoOneElses() {
        assessments.add(LEARNER, DOC, "b-1", AssessmentVerdict.LOOKS_RIGHT, null, "qa", 1000L);
        assessments.add(LEARNER, DOC, "b-1", AssessmentVerdict.ISSUE, "Ports are swapped", "qa", 2000L);
        assessments.add(ANOTHER, DOC, null, AssessmentVerdict.ISSUE, "Not mine", "qa", 3000L);

        final List<QualityAssessment> said = assessments.forLearner(LEARNER);
        assertThat(said).extracting(QualityAssessment::getVerdict)
                .containsExactly(AssessmentVerdict.ISSUE, AssessmentVerdict.LOOKS_RIGHT);
        assertThat(said.get(0).getComment()).isEqualTo("Ports are swapped");
        assertThat(said.get(0).getBindingUuid()).isEqualTo("b-1");
        assertThat(said.get(0).getCreateTimeMs()).isEqualTo(2000L);
        assertThat(said.get(1).getComment()).isNull();
    }

    @Test
    void anOpenIssueIsResolvedOnceAndALooksRightIsNot() {
        assessments.add(LEARNER, DOC, "b-1", AssessmentVerdict.ISSUE, "Times are local", "qa", 1000L);
        assessments.add(LEARNER, DOC, "b-1", AssessmentVerdict.LOOKS_RIGHT, null, "qa", 2000L);
        final List<QualityAssessment> said = assessments.forLearner(LEARNER);
        final long issue = said.stream().filter(one -> one.getVerdict() == AssessmentVerdict.ISSUE)
                .findFirst().orElseThrow().getId();
        final long looksRight = said.get(0).getId();

        assertThat(assessments.resolve(issue, "provider", 5000L)).isTrue();
        assertThat(assessments.resolve(issue, "someone else", 6000L)).describedAs("already resolved").isFalse();
        assertThat(assessments.resolve(looksRight, "provider", 5000L)).isFalse();

        final Held held = assessments.byId(issue).orElseThrow();
        assertThat(held.learner()).isEqualTo(LEARNER);
        assertThat(held.docUuid()).isEqualTo(DOC);
        assertThat(held.assessment().getResolvedBy()).isEqualTo("provider");
        assertThat(held.assessment().getResolveTimeMs()).isEqualTo(5000L);
        assertThat(assessments.byId(-1L)).isEmpty();
    }
}
