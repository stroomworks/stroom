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

package stroom.learningflow.stage;

import stroom.learningflow.shared.LearnerState;
import stroom.learningflow.shared.SupervisorLearner;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// Reads the Supervisor's list of learning pipelines: every learner under the documents a user may view
/// that has learned or is learning, with its state, what serves it and what waits on it.
///
/// Filtering and paging are done by the database, because an installation with a pipeline per feed has
/// as many learners as it has feeds. This is read-only, and not used by stream processing.
public interface LearningPipelines {

    /// Gets a page of learners of every pipeline, ordered by most waiting streams, then by most recent
    /// session.
    ///
    /// @param docUuids    The UUIDs of the documents the user may view. If empty, the result is empty.
    /// @param learningOff Those of them with learning disabled, whose learners are shown as such.
    /// @param states      The states to include; empty for every state.
    /// @param showDeleted Whether to include the learning of pipelines that have been deleted.
    /// @param offset      The first row of the page.
    /// @param limit       How many rows.
    /// @return The page, as [#learners(Collection, Set, Collection, boolean, Collection, long, int)] answers.
    default Page learners(final Collection<String> docUuids,
                          final Set<String> learningOff,
                          final Collection<LearnerState> states,
                          final boolean showDeleted,
                          final long offset,
                          final int limit) {
        return learners(docUuids, learningOff, states, showDeleted, null, offset, limit);
    }

    /// Gets a page of learners, ordered by most waiting streams, then by most recent session.
    ///
    /// @param docUuids     The documents the learners may be listed under. Empty answers empty.
    /// @param learningOff  Those of them with learning turned off, whose learners are said to be so.
    /// @param states       The states asked for; empty for every state.
    /// @param showDeleted  Whether the learning of pipelines since deleted is shown too (A137).
    /// @param pipelineUuids The pipelines whose learners may be listed: e.g. those the person may view, or
    ///                     one pipeline from its own editor. Null for every pipeline; empty answers empty.
    /// @param offset       The first row of the page.
    /// @param limit        The maximum number of rows.
    /// @return The page. Its rows name their pipeline and document by UUID only, and none is flagged as
    ///         raised: the caller adds the names and compares the waits with each document's maximum
    ///         deferral age.
    Page learners(Collection<String> docUuids,
                  Set<String> learningOff,
                  Collection<LearnerState> states,
                  boolean showDeleted,
                  Collection<String> pipelineUuids,
                  long offset,
                  int limit);

    /// Returns every pipeline any learning table knows a learner of, deleted or not: the few pipelines whose
    /// permissions decide which rows of the lists a person sees.
    ///
    /// @return The pipelines' UUIDs.
    Set<String> pipelines();

    /// Gets the document a learner is listed under, worked out in the same way as the list. For a learner
    /// whose pipeline has been deleted, and so names no document, this is the document its learning is
    /// viewed under.
    ///
    /// @param learner The learner.
    /// @return The document's UUID, or empty if no table knows the learner.
    Optional<String> documentOf(Learner learner);


    // --------------------------------------------------------------------------------


    /// One page of learners, and how many there are in total.
    ///
    /// @param learners The page's rows.
    /// @param total    How many learners match, across every page.
    record Page(List<SupervisorLearner> learners, long total) {

    }
}
