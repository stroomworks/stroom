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

package stroom.shapeshifter.ai.impl.db;

import stroom.db.util.JooqUtil;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearnerStates;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.Condition;
import org.jooq.DSLContext;

import java.util.Optional;
import java.util.OptionalDouble;

import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterLearnerState.SHAPESHIFTER_LEARNER_STATE;

/// The learner state of A117 and A124 as rows: one per learner, recording the document it runs under,
/// holding what the stage knows about it — given up, marked to learn again, awaiting review — and its
/// rolling score. The row is made on first mention.
@Singleton
public class LearnerStatesDao implements LearnerStates {

    private final ShapeshifterAiDbConnProvider connProvider;

    @Inject
    LearnerStatesDao(final ShapeshifterAiDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public Optional<String> reasonGivenUp(final Learner learner) {
        return text(learner, SHAPESHIFTER_LEARNER_STATE.GIVEN_UP_REASON);
    }

    @Override
    public void giveUp(final Learner learner, final String docUuid, final String reason) {
        JooqUtil.context(connProvider, context -> {
            row(context, learner, docUuid);
            context.update(SHAPESHIFTER_LEARNER_STATE)
                    .set(SHAPESHIFTER_LEARNER_STATE.GIVEN_UP_REASON, reason)
                    .set(SHAPESHIFTER_LEARNER_STATE.VERSION, SHAPESHIFTER_LEARNER_STATE.VERSION.plus(1))
                    .set(SHAPESHIFTER_LEARNER_STATE.UPDATE_TIME_MS, System.currentTimeMillis())
                    .set(SHAPESHIFTER_LEARNER_STATE.DOC_UUID, docUuid)
                    .where(key(learner))
                    .execute();
        });
    }

    /// The rolling score of §5, folded in one transaction so that two nodes serving the same learner at once
    /// do not each read the old mean and write over the other's.
    @Override
    public OptionalDouble scored(final Learner learner,
                                 final String docUuid,
                                 final double score,
                                 final int records,
                                 final int memory) {
        return JooqUtil.transactionResult(connProvider, context -> {
            row(context, learner, docUuid);
            final var current = context
                    .select(SHAPESHIFTER_LEARNER_STATE.ROLLING_SCORE, SHAPESHIFTER_LEARNER_STATE.ROLLING_RECORDS)
                    .from(SHAPESHIFTER_LEARNER_STATE)
                    .where(key(learner))
                    .forUpdate()
                    .fetchOne();
            final double was = current.get(SHAPESHIFTER_LEARNER_STATE.ROLLING_SCORE) == null
                    ? 0.0
                    : current.get(SHAPESHIFTER_LEARNER_STATE.ROLLING_SCORE);
            final int remembered = Math.min(current.get(SHAPESHIFTER_LEARNER_STATE.ROLLING_RECORDS), memory);
            final double now = remembered + records > 0
                    ? (was * remembered + score * records) / (remembered + records)
                    : was;
            final int held = remembered + records;
            context.update(SHAPESHIFTER_LEARNER_STATE)
                    .set(SHAPESHIFTER_LEARNER_STATE.ROLLING_SCORE, now)
                    .set(SHAPESHIFTER_LEARNER_STATE.ROLLING_RECORDS, held)
                    .set(SHAPESHIFTER_LEARNER_STATE.VERSION, SHAPESHIFTER_LEARNER_STATE.VERSION.plus(1))
                    .set(SHAPESHIFTER_LEARNER_STATE.UPDATE_TIME_MS, System.currentTimeMillis())
                    .set(SHAPESHIFTER_LEARNER_STATE.DOC_UUID, docUuid)
                    .where(key(learner))
                    .execute();
            return held >= memory
                    ? OptionalDouble.of(now)
                    : OptionalDouble.empty();
        });
    }

    @Override
    public Optional<String> relearnReason(final Learner learner) {
        return text(learner, SHAPESHIFTER_LEARNER_STATE.RELEARN_REASON);
    }

    @Override
    public void markForRelearning(final Learner learner,
                                  final String docUuid,
                                  final String reason) {
        set(learner, docUuid, SHAPESHIFTER_LEARNER_STATE.RELEARN_REASON, reason);
    }

    @Override
    public void awaitReview(final Learner learner,
                            final String docUuid,
                            final String bindingUuid) {
        set(learner, docUuid, SHAPESHIFTER_LEARNER_STATE.AWAITING_BINDING_UUID, bindingUuid);
    }

    @Override
    public Optional<String> draftAwaiting(final Learner learner) {
        return text(learner, SHAPESHIFTER_LEARNER_STATE.AWAITING_BINDING_UUID);
    }

    /// Nothing is made for a learner with no row: it has nothing to forget.
    @Override
    public void reset(final Learner learner) {
        JooqUtil.context(connProvider, context -> {
            context.update(SHAPESHIFTER_LEARNER_STATE)
                    .setNull(SHAPESHIFTER_LEARNER_STATE.GIVEN_UP_REASON)
                    .setNull(SHAPESHIFTER_LEARNER_STATE.RELEARN_REASON)
                    .setNull(SHAPESHIFTER_LEARNER_STATE.AWAITING_BINDING_UUID)
                    .setNull(SHAPESHIFTER_LEARNER_STATE.ROLLING_SCORE)
                    .set(SHAPESHIFTER_LEARNER_STATE.ROLLING_RECORDS, 0)
                    .set(SHAPESHIFTER_LEARNER_STATE.VERSION, SHAPESHIFTER_LEARNER_STATE.VERSION.plus(1))
                    .set(SHAPESHIFTER_LEARNER_STATE.UPDATE_TIME_MS, System.currentTimeMillis())
                    .where(key(learner))
                    .execute();
        });
    }

    /// Whatever the row holds, scored or not; a row that has never been scored reads as nothing, so that
    /// a list shows no score rather than showing zero, which would read as a learner scoring badly.
    @Override
    public Optional<Rolling> rolling(final Learner learner) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select(SHAPESHIFTER_LEARNER_STATE.ROLLING_SCORE, SHAPESHIFTER_LEARNER_STATE.ROLLING_RECORDS)
                .from(SHAPESHIFTER_LEARNER_STATE)
                .where(key(learner))
                .fetchOptional()
                .filter(row -> row.get(SHAPESHIFTER_LEARNER_STATE.ROLLING_RECORDS) > 0)
                .map(row -> new Rolling(
                        row.get(SHAPESHIFTER_LEARNER_STATE.ROLLING_SCORE) == null
                                ? 0.0
                                : row.get(SHAPESHIFTER_LEARNER_STATE.ROLLING_SCORE),
                        row.get(SHAPESHIFTER_LEARNER_STATE.ROLLING_RECORDS))));
    }

    private Optional<String> text(final Learner learner,
                                  final org.jooq.TableField<?, String> column) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select(column)
                .from(SHAPESHIFTER_LEARNER_STATE)
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
            context.update(SHAPESHIFTER_LEARNER_STATE)
                    .set((org.jooq.Field<String>) column, value)
                    .set(SHAPESHIFTER_LEARNER_STATE.VERSION, SHAPESHIFTER_LEARNER_STATE.VERSION.plus(1))
                    .set(SHAPESHIFTER_LEARNER_STATE.UPDATE_TIME_MS, System.currentTimeMillis())
                    .set(SHAPESHIFTER_LEARNER_STATE.DOC_UUID, docUuid)
                    .where(key(learner))
                    .execute();
        });
    }

    /// The row of one learner.
    private static Condition key(final Learner learner) {
        return SHAPESHIFTER_LEARNER_STATE.PIPELINE_UUID.eq(learner.pipelineUuid())
                .and(SHAPESHIFTER_LEARNER_STATE.ELEMENT_ID.eq(learner.elementId()));
    }

    /// The row for a learner, made where this node is the first to mention it. Two nodes meeting a new learner
    /// at once both insert; the unique key decides, and the loser reads what the winner wrote. The row is
    /// looked for first, so that the steady path — the row exists — takes no lock here. Where it is
    /// missing, the insert takes the row's exclusive lock even on a duplicate, so that a following
    /// `FOR UPDATE` has nothing to upgrade: a shared lock held by two transactions that each then want it
    /// exclusively is a deadlock.
    private static void row(final DSLContext context,
                            final Learner learner,
                            final String docUuid) {
        final boolean exists = context.fetchExists(context.selectFrom(SHAPESHIFTER_LEARNER_STATE)
                .where(key(learner)));
        if (exists) {
            return;
        }
        final long now = System.currentTimeMillis();
        context.insertInto(SHAPESHIFTER_LEARNER_STATE)
                .set(SHAPESHIFTER_LEARNER_STATE.VERSION, 1)
                .set(SHAPESHIFTER_LEARNER_STATE.CREATE_TIME_MS, now)
                .set(SHAPESHIFTER_LEARNER_STATE.UPDATE_TIME_MS, now)
                .set(SHAPESHIFTER_LEARNER_STATE.PIPELINE_UUID, learner.pipelineUuid())
                .set(SHAPESHIFTER_LEARNER_STATE.ELEMENT_ID, learner.elementId())
                .set(SHAPESHIFTER_LEARNER_STATE.DOC_UUID, docUuid)
                // Not IGNORE: on a duplicate that takes a shared lock, and the transactions that met the
                // row missing together — the first streams of a new learner, scored at once — each then
                // ask for the exclusive lock the others' shared locks refuse, and the database kills one.
                // An update takes the exclusive lock at once, so they queue instead.
                .onDuplicateKeyUpdate()
                .set(SHAPESHIFTER_LEARNER_STATE.VERSION, SHAPESHIFTER_LEARNER_STATE.VERSION)
                .execute();
    }
}
