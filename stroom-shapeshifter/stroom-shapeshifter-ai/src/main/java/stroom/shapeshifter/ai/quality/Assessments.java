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

package stroom.shapeshifter.ai.quality;

import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.shared.AssessmentVerdict;
import stroom.shapeshifter.shared.QualityAssessment;

import java.util.List;
import java.util.Optional;

/// What QA people have said of each learner's translated output (a Smart Pipeline's Quality Assurance tab):
/// assessments, and the issues among them, open until resolved.
public interface Assessments {

    /// @param learner The learner.
    /// @return What has been said of it, newest first.
    List<QualityAssessment> forLearner(Learner learner);

    /// Records an assessment.
    ///
    /// @param learner     The learner it is of.
    /// @param docUuid     The Learning Flow it runs under, for who may read it once its pipeline is deleted.
    /// @param bindingUuid The translation in use, or null for none.
    /// @param verdict     What is said.
    /// @param comment     What the person wrote, or null.
    /// @param author      Who said it.
    /// @param nowMs       When.
    void add(Learner learner,
             String docUuid,
             String bindingUuid,
             AssessmentVerdict verdict,
             String comment,
             String author,
             long nowMs);

    /// @param id An assessment.
    /// @return It with its learner and Learning Flow, or empty where there is none.
    Optional<Held> byId(long id);

    /// Resolves an open issue.
    ///
    /// @param id    The assessment.
    /// @param by    Who resolved it.
    /// @param nowMs When.
    /// @return Whether it was an open issue, now resolved.
    boolean resolve(long id, String by, long nowMs);

    /// An assessment with whose it is.
    ///
    /// @param learner    The learner it is of.
    /// @param docUuid    The Learning Flow it ran under.
    /// @param assessment The assessment.
    record Held(Learner learner, String docUuid, QualityAssessment assessment) {

    }
}
