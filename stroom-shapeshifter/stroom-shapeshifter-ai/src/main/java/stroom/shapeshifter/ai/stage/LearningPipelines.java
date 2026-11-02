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

package stroom.shapeshifter.ai.stage;

import stroom.shapeshifter.shared.LearnerState;
import stroom.shapeshifter.shared.SupervisorLearner;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// The Supervisor's learning pipelines list (design 06 A115, A142): every learner of the documents a
/// person may read that has learned or is learning, where it stands, what serves it and what waits on it,
/// narrowed and paged where the rows are, since an installation with a pipeline per feed has as many
/// learners as it has feeds. Read-only, and off the hot path.
public interface LearningPipelines {

    /// A page of learners, most waiting streams first, then the most recent session.
    ///
    /// @param docUuids    The documents the person may read. Empty answers empty.
    /// @param learningOff Those of them with learning turned off, whose learners are said to be so.
    /// @param states      The states asked for; empty for every state.
    /// @param showDeleted Whether the learning of pipelines since deleted is shown too (A137).
    /// @param offset      The first row of the page.
    /// @param limit       How many rows.
    /// @return The page, its rows naming their pipeline and document by uuid alone, and none raised: the
    /// resource names them and judges their waits against each document's maximum age.
    Page learners(Collection<String> docUuids,
                  Set<String> learningOff,
                  Collection<LearnerState> states,
                  boolean showDeleted,
                  long offset,
                  int limit);

    /// The document a learner is listed under, worked out as the list works it out. For a learner whose
    /// pipeline has been deleted, and so names no document of its own, this is the document its learning
    /// is read from (A137).
    ///
    /// @param learner The learner.
    /// @return Its document, or empty where no table knows the learner.
    Optional<String> documentOf(Learner learner);


    // --------------------------------------------------------------------------------


    /// One page of learners, and how many there are in all.
    ///
    /// @param learners The page's rows.
    /// @param total    How many match, across every page.
    record Page(List<SupervisorLearner> learners, long total) {

    }
}
