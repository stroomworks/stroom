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

package stroom.learningflow.impl.db;

import stroom.db.util.JooqUtil;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerStates;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.Condition;
import org.jooq.DSLContext;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static stroom.learningflow.impl.db.jooq.tables.LearningFlowLearnerState.LEARNING_FLOW_LEARNER_STATE;

/// The database implementation of [LearnerStates], which stores the state of each learner.
///
/// There is one row per learner. It records the document the learner runs under, whether it has given up,
/// whether it is marked to learn again, any draft binding awaiting review, and its rolling score. The row is
/// created the first time anything is written for the learner.
@Singleton
public class LearnerStatesDao implements LearnerStates {

    private final LearningFlowDbConnProvider connProvider;

    @Inject
    LearnerStatesDao(final LearningFlowDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public Optional<String> reasonGivenUp(final Learner learner) {
        return text(learner, LEARNING_FLOW_LEARNER_STATE.GIVEN_UP_REASON);
    }

    @Override
    public void giveUp(final Learner learner, final String docUuid, final String reason) {
        set(learner, docUuid, LEARNING_FLOW_LEARNER_STATE.GIVEN_UP_REASON, reason);
    }

    /// Adds a stream's score to the learner's rolling score, a mean over at most `memory` records. This
    /// is done in one transaction with the row locked, so that two nodes serving the same learner at once
    /// do not both read the old mean and overwrite each other's update.
    @Override
    public OptionalDouble scored(final Learner learner,
                                 final String docUuid,
                                 final double score,
                                 final int records,
                                 final int skipped,
                                 final int memory) {
        return JooqUtil.transactionResult(connProvider, context -> {
            row(context, learner, docUuid);
            final var current = context
                    .select(LEARNING_FLOW_LEARNER_STATE.ROLLING_SCORE, LEARNING_FLOW_LEARNER_STATE.ROLLING_RECORDS,
                            LEARNING_FLOW_LEARNER_STATE.ROLLING_SKIPS)
                    .from(LEARNING_FLOW_LEARNER_STATE)
                    .where(key(learner))
                    .forUpdate()
                    .fetchOne();
            final double was = current.get(LEARNING_FLOW_LEARNER_STATE.ROLLING_SCORE) == null
                    ? 0.0
                    : current.get(LEARNING_FLOW_LEARNER_STATE.ROLLING_SCORE);
            final int remembered = Math.min(current.get(LEARNING_FLOW_LEARNER_STATE.ROLLING_RECORDS), memory);
            final double now = remembered + records > 0
                    ? (was * remembered + score * records) / (remembered + records)
                    : was;
            final int held = remembered + records;
            // The skip count is scaled down in the same proportion as the older records that the memory
            // has dropped. Otherwise a learner that once skipped many records would go on looking as
            // though it still did.
            final int skipsWas = current.get(LEARNING_FLOW_LEARNER_STATE.ROLLING_SKIPS);
            final int kept = current.get(LEARNING_FLOW_LEARNER_STATE.ROLLING_RECORDS) == 0
                    ? 0
                    : (int) Math.round((double) skipsWas * remembered
                                       / current.get(LEARNING_FLOW_LEARNER_STATE.ROLLING_RECORDS));
            context.update(LEARNING_FLOW_LEARNER_STATE)
                    .set(LEARNING_FLOW_LEARNER_STATE.ROLLING_SCORE, now)
                    .set(LEARNING_FLOW_LEARNER_STATE.ROLLING_RECORDS, held)
                    .set(LEARNING_FLOW_LEARNER_STATE.ROLLING_SKIPS, kept + skipped)
                    .set(LEARNING_FLOW_LEARNER_STATE.VERSION, LEARNING_FLOW_LEARNER_STATE.VERSION.plus(1))
                    .set(LEARNING_FLOW_LEARNER_STATE.UPDATE_TIME_MS, System.currentTimeMillis())
                    .set(LEARNING_FLOW_LEARNER_STATE.DOC_UUID, docUuid)
                    .where(key(learner))
                    .execute();
            return held >= memory
                    ? OptionalDouble.of(now)
                    : OptionalDouble.empty();
        });
    }

    @Override
    public Optional<String> relearnReason(final Learner learner) {
        return text(learner, LEARNING_FLOW_LEARNER_STATE.RELEARN_REASON);
    }

    @Override
    public void markForRelearning(final Learner learner,
                                  final String docUuid,
                                  final String reason) {
        set(learner, docUuid, LEARNING_FLOW_LEARNER_STATE.RELEARN_REASON, reason);
    }

    @Override
    public void awaitReview(final Learner learner,
                            final String docUuid,
                            final String bindingUuid) {
        set(learner, docUuid, LEARNING_FLOW_LEARNER_STATE.AWAITING_BINDING_UUID, bindingUuid);
    }

    @Override
    public Optional<String> draftAwaiting(final Learner learner) {
        return text(learner, LEARNING_FLOW_LEARNER_STATE.AWAITING_BINDING_UUID);
    }

    /// Clears the learner's state and rolling score. No row is created for a learner that has none, since
    /// there is nothing to clear.
    @Override
    public void reset(final Learner learner) {
        JooqUtil.context(connProvider, context -> {
            context.update(LEARNING_FLOW_LEARNER_STATE)
                    .setNull(LEARNING_FLOW_LEARNER_STATE.GIVEN_UP_REASON)
                    .setNull(LEARNING_FLOW_LEARNER_STATE.RELEARN_REASON)
                    .setNull(LEARNING_FLOW_LEARNER_STATE.AWAITING_BINDING_UUID)
                    .setNull(LEARNING_FLOW_LEARNER_STATE.ROLLING_SCORE)
                    .set(LEARNING_FLOW_LEARNER_STATE.ROLLING_RECORDS, 0)
                    .set(LEARNING_FLOW_LEARNER_STATE.ROLLING_SKIPS, 0)
                    .set(LEARNING_FLOW_LEARNER_STATE.VERSION, LEARNING_FLOW_LEARNER_STATE.VERSION.plus(1))
                    .set(LEARNING_FLOW_LEARNER_STATE.UPDATE_TIME_MS, System.currentTimeMillis())
                    .where(key(learner))
                    .execute();
        });
    }

    /// Returns the learner's rolling score, or empty if it has never been scored. Returning empty lets a
    /// list show no score rather than zero, which would look like a learner scoring badly.
    @Override
    public Optional<Rolling> rolling(final Learner learner) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select(LEARNING_FLOW_LEARNER_STATE.ROLLING_SCORE, LEARNING_FLOW_LEARNER_STATE.ROLLING_RECORDS,
                        LEARNING_FLOW_LEARNER_STATE.ROLLING_SKIPS)
                .from(LEARNING_FLOW_LEARNER_STATE)
                .where(key(learner))
                .fetchOptional()
                .filter(row -> row.get(LEARNING_FLOW_LEARNER_STATE.ROLLING_RECORDS) > 0)
                .map(row -> new Rolling(
                        row.get(LEARNING_FLOW_LEARNER_STATE.ROLLING_SCORE) == null
                                ? 0.0
                                : row.get(LEARNING_FLOW_LEARNER_STATE.ROLLING_SCORE),
                        row.get(LEARNING_FLOW_LEARNER_STATE.ROLLING_RECORDS),
                        row.get(LEARNING_FLOW_LEARNER_STATE.ROLLING_SKIPS))));
    }

    private Optional<String> text(final Learner learner,
                                  final org.jooq.TableField<?, String> column) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select(column)
                .from(LEARNING_FLOW_LEARNER_STATE)
                .where(key(learner))
                .fetchOptional(column)
                .filter(value -> value != null));
    }

    private void set(final Learner learner,
                     final String docUuid,
                     final org.jooq.TableField<?, String> column,
                     final String value) {
        JooqUtil.context(connProvider, context -> {
            row(context, learner, docUuid);
            context.update(LEARNING_FLOW_LEARNER_STATE)
                    .set((org.jooq.Field<String>) column, value)
                    .set(LEARNING_FLOW_LEARNER_STATE.VERSION, LEARNING_FLOW_LEARNER_STATE.VERSION.plus(1))
                    .set(LEARNING_FLOW_LEARNER_STATE.UPDATE_TIME_MS, System.currentTimeMillis())
                    .set(LEARNING_FLOW_LEARNER_STATE.DOC_UUID, docUuid)
                    .where(key(learner))
                    .execute();
        });
    }

    /// The row of one learner.
    private static Condition key(final Learner learner) {
        return LEARNING_FLOW_LEARNER_STATE.PIPELINE_UUID.eq(learner.pipelineUuid())
                .and(LEARNING_FLOW_LEARNER_STATE.ELEMENT_ID.eq(learner.elementId()));
    }

    /// Creates the learner's row if it does not exist yet. See [RowMaker#makeIfMissing] for why it is
    /// created that way.
    private static void row(final DSLContext context,
                            final Learner learner,
                            final String docUuid) {
        final long now = System.currentTimeMillis();
        RowMaker.makeIfMissing(context,
                LEARNING_FLOW_LEARNER_STATE,
                key(learner),
                LEARNING_FLOW_LEARNER_STATE.VERSION,
                Map.of(LEARNING_FLOW_LEARNER_STATE.CREATE_TIME_MS, now,
                        LEARNING_FLOW_LEARNER_STATE.UPDATE_TIME_MS, now,
                        LEARNING_FLOW_LEARNER_STATE.PIPELINE_UUID, learner.pipelineUuid(),
                        LEARNING_FLOW_LEARNER_STATE.ELEMENT_ID, learner.elementId(),
                        LEARNING_FLOW_LEARNER_STATE.DOC_UUID, docUuid));
    }
}
