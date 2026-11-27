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

import stroom.learningflow.stage.RegressionSet.Kind;

import java.util.List;

/// The index of a node's regression corpus: one row per entry, naming the stream and segment that hold the
/// record and its accepted output, so that the corpus can be read without scanning the feed. The text
/// itself is in the stream store. A test harness without a database keeps the index in memory; a node uses a
/// table.
public interface CorpusIndex {

    /// Gets a binding's rows, oldest first.
    ///
    /// @param bindingUuid The binding's UUID.
    /// @return Its rows.
    List<Row> rows(String bindingUuid);

    /// Adds rows for the entries a promotion wrote, once the stream holding them has been closed.
    ///
    /// @param rows The rows, in the order the entries were written.
    void add(List<Row> rows);

    /// Drops a binding's oldest rows beyond the cap set on the document.
    ///
    /// @param bindingUuid The binding's UUID.
    /// @param cap         How many rows it may keep.
    /// @return The streams no row names any more, for the caller to delete.
    List<Long> trim(String bindingUuid, int cap);

    /// Drops every row of a binding.
    ///
    /// @param bindingUuid The binding's UUID.
    /// @return The streams no row names any more, for the caller to delete.
    List<Long> discard(String bindingUuid);

    /// Moves a binding's rows to another binding. When a draft is approved, its rows are added to the
    /// serving binding's, after those it already holds.
    ///
    /// @param fromBindingUuid The UUID of the approved draft.
    /// @param toBindingUuid   The UUID of the serving binding it replaces.
    void move(String fromBindingUuid, String toBindingUuid);

    // --------------------------------------------------------------------------------

    /// One index row, locating one corpus entry.
    ///
    /// @param docUuid      The UUID of the document the learner ran under.
    /// @param bindingUuid  The UUID of the binding the entry belongs to.
    /// @param metaId       The id of the stream holding the entry.
    /// @param segment      Which segment of the stream holds it, counted from zero.
    /// @param kind         What the record became.
    /// @param score        The score it was accepted with.
    /// @param attemptId    The session that accepted it, if one was open.
    /// @param acceptTimeMs When it was accepted.
    record Row(String docUuid,
               String bindingUuid,
               long metaId,
               int segment,
               Kind kind,
               double score,
               Long attemptId,
               long acceptTimeMs) {

    }
}
