/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.learningflow.state;

import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerStates;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/// An in-memory [LearnerStates], held as a map by learner, for scenarios. Every method is synchronised,
/// because one node's tasks share it.
///
/// The rolling score is a running mean over a limited number of records, kept as a score and a record
/// count, as the table does. The count carried forward never exceeds the memory, so the latest stream
/// is always weighed against at most that many earlier records.
public final class InMemoryLearnerStates implements LearnerStates {

    /// What a learner with no state reads as. It is never written to, because only [#row] returns a row to
    /// write.
    private static final Row NONE = new Row();

    private final Map<Learner, Row> rows = new HashMap<>();

    @Override
    public synchronized Optional<String> reasonGivenUp(final Learner learner) {
        return Optional.ofNullable(read(learner).givenUp);
    }

    @Override
    public synchronized void giveUp(final Learner learner, final String docUuid, final String reason) {
        row(learner, docUuid).givenUp = reason;
    }

    @Override
    public synchronized OptionalDouble scored(final Learner learner,
                                              final String docUuid,
                                              final double score,
                                              final int records,
                                              final int skipped,
                                              final int memory) {
        final Row row = row(learner, docUuid);
        final int remembered = Math.min(row.records, memory);
        if (remembered + records > 0) {
            row.score = (row.score * remembered + score * records) / (remembered + records);
        }
        // The skip count is scaled down in the same proportion as the older records that the memory has
        // dropped. Otherwise a learner that once skipped many records would go on looking as though it
        // still did.
        row.skips = (row.records == 0
                ? 0
                : (int) Math.round((double) row.skips * remembered / row.records)) + skipped;
        row.records = remembered + records;
        return row.records >= memory
                ? OptionalDouble.of(row.score)
                : OptionalDouble.empty();
    }

    @Override
    public synchronized Optional<String> relearnReason(final Learner learner) {
        return Optional.ofNullable(read(learner).relearn);
    }

    @Override
    public synchronized void markForRelearning(final Learner learner, final String docUuid, final String reason) {
        row(learner, docUuid).relearn = reason;
    }

    @Override
    public synchronized void awaitReview(final Learner learner, final String docUuid, final String bindingUuid) {
        row(learner, docUuid).draft = bindingUuid;
    }

    @Override
    public synchronized Optional<String> draftAwaiting(final Learner learner) {
        return Optional.ofNullable(read(learner).draft);
    }

    /// Clears what was learned, but not who is learning. A promotion resets the state while the attempt
    /// that promoted it is still open; the claim is held by the attempt, not by the state, so a reset
    /// does not affect it.
    @Override
    public synchronized void reset(final Learner learner) {
        final Row row = row(learner);
        row.givenUp = null;
        row.relearn = null;
        row.draft = null;
        row.score = 0.0;
        row.records = 0;
        row.skips = 0;
    }

    /// Returns the rolling score whether or not its memory is full, or empty if no records have been
    /// scored. The Supervisor list shows a learner with three records as well as one with a thousand,
    /// and says how many records each score is based on.
    @Override
    public synchronized Optional<Rolling> rolling(final Learner learner) {
        final Row row = rows.get(learner);
        return row == null || row.records == 0
                ? Optional.empty()
                : Optional.of(new Rolling(row.score, row.records, row.skips));
    }

    /// @param learner The learner.
    /// @return Its rolling score, or zero if it has none.
    public synchronized double rollingScore(final Learner learner) {
        return read(learner).score;
    }

    /// Returns every learner that has a state, for the in-memory learning pipelines list.
    ///
    /// @return The learners.
    public synchronized Set<Learner> learners() {
        return Set.copyOf(rows.keySet());
    }

    /// @param learner The learner.
    /// @return The document its state was last written under, or empty if it has no state.
    public synchronized Optional<String> documentOf(final Learner learner) {
        return Optional.ofNullable(read(learner).docUuid);
    }

    /// Updates the learner's state, if it has one, to the document it runs under (see [InMemoryLearners]).
    ///
    /// @param learner The learner.
    /// @param docUuid The document.
    public synchronized void runsUnder(final Learner learner, final String docUuid) {
        final Row row = rows.get(learner);
        if (row != null) {
            row.docUuid = docUuid;
        }
    }

    /// @param learner The learner.
    /// @return Whether it has a draft waiting for a person to decide.
    public synchronized boolean awaitsReview(final Learner learner) {
        return read(learner).draft != null;
    }

    /// @return Whether no learner has a state. A read creates no row, but a write or a reset does.
    public synchronized boolean isEmpty() {
        return rows.isEmpty();
    }

    private Row read(final Learner learner) {
        return rows.getOrDefault(learner, NONE);
    }

    private Row row(final Learner learner) {
        return rows.computeIfAbsent(learner, k -> new Row());
    }

    /// Returns the learner's row for writing, recording the document it is written under, as the table's
    /// row does.
    private Row row(final Learner learner, final String docUuid) {
        final Row row = row(learner);
        row.docUuid = docUuid;
        return row;
    }

    private static final class Row {

        private String docUuid;
        private String givenUp;
        private String relearn;
        private String draft;
        private double score;
        private int records;
        private int skips;
    }
}
