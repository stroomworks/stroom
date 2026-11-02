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

package stroom.shapeshifter.ai.state;

import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearnerStates;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/// The learners' states as a map, by learner: what scenarios run over. The rolling score is a running mean
/// whose memory is capped in records: the two columns a row can hold, `score` and `records`, with
/// `records` never counted above the memory so that the latest stream always weighs against at most that
/// many earlier records. Every method is synchronised: one node's tasks share it.
public final class InMemoryLearnerStates implements LearnerStates {

    /// What a learner nothing was written of reads as. Never written to: only [#row] hands out a row to write.
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
                                              final int memory) {
        final Row row = row(learner, docUuid);
        final int remembered = Math.min(row.records, memory);
        if (remembered + records > 0) {
            row.score = (row.score * remembered + score * records) / (remembered + records);
        }
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

    /// What was learned is forgotten; who is learning is not. A promotion resets the state while the
    /// attempt that promoted it is still open, and the attempt's row is what holds the claim (A45), so a
    /// reset here takes nothing from it.
    @Override
    public synchronized void reset(final Learner learner) {
        final Row row = row(learner);
        row.givenUp = null;
        row.relearn = null;
        row.draft = null;
        row.score = 0.0;
        row.records = 0;
    }

    /// Whatever the row holds, scored or not: the list of A46 shows a learner that has brought three
    /// records as well as one that has brought a thousand, and says how many each rests on.
    @Override
    public synchronized Optional<Rolling> rolling(final Learner learner) {
        final Row row = rows.get(learner);
        return row == null || row.records == 0
                ? Optional.empty()
                : Optional.of(new Rolling(row.score, row.records));
    }

    /// @param learner The learner.
    /// @return Its rolling score, nought where it has none.
    public synchronized double rollingScore(final Learner learner) {
        return read(learner).score;
    }

    /// @return Every learner that has a state, for the learning pipelines list.
    public synchronized Set<Learner> learners() {
        return Set.copyOf(rows.keySet());
    }

    /// @param learner The learner.
    /// @return The document its state was last written under, where it has a state.
    public synchronized Optional<String> documentOf(final Learner learner) {
        return Optional.ofNullable(read(learner).docUuid);
    }

    /// The learner's state, brought to the document it runs under (see [InMemoryLearners]).
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
    /// @return Whether a person is to decide its draft.
    public synchronized boolean awaitsReview(final Learner learner) {
        return read(learner).draft != null;
    }

    /// @return Whether no learner has a state: nothing was written of any, since a read makes no row.
    public synchronized boolean isEmpty() {
        return rows.isEmpty();
    }

    private Row read(final Learner learner) {
        return rows.getOrDefault(learner, NONE);
    }

    private Row row(final Learner learner) {
        return rows.computeIfAbsent(learner, k -> new Row());
    }

    /// The row, recording the document it was last written under, as the table's row does.
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
    }
}
