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

package stroom.learningflow.impl.db;

import stroom.db.util.JooqUtil;
import stroom.learningflow.impl.db.jooq.tables.LearningFlowDeferral;
import stroom.learningflow.stage.Deferrals;
import stroom.learningflow.stage.Learner;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.Condition;
import org.jooq.Record;

import java.util.List;
import java.util.Optional;

/// The database implementation of [Deferrals], which records the streams that are waiting (deferred)
/// because nothing can translate them yet, and why.
///
/// There is one row per learner and stream. When a stream is deferred again, the unique key on the learner
/// and meta id turns the insert into an update of the same row. The time it was first deferred is kept from
/// the original insert, so that deferring it again for a new reason does not reset how long it has waited.
@Singleton
public class DeferralsDao implements Deferrals {

    private final LearningFlowDbConnProvider connProvider;

    @Inject
    DeferralsDao(final LearningFlowDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public void deferred(final Learner learner,
                         final String docUuid,
                         final long inputId,
                         final String feed,
                         final String type,
                         final String failedAgainst,
                         final String reason,
                         final int failingRecords) {
        final long now = System.currentTimeMillis();
        JooqUtil.context(connProvider, context -> context
                .insertInto(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL)
                .set(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.PIPELINE_UUID, learner.pipelineUuid())
                .set(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.ELEMENT_ID, learner.elementId())
                .set(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.DOC_UUID, docUuid)
                .set(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.META_ID, inputId)
                .set(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.FEED_NAME, feed)
                .set(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.TYPE_NAME, type)
                .set(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.FAILED_AGAINST, failedAgainst)
                .set(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.REASON, reason)
                .set(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.FAILING_RECORDS, failingRecords)
                .set(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.FIRST_DEFERRED_MS, now)
                .set(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.UPDATE_TIME_MS, now)
                .set(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.ATTEMPTS, 1)
                // The stream was retried and deferred again. Everything except the time it was first
                // deferred is updated, including the document, since the learner may now use a different
                // one.
                .onDuplicateKeyUpdate()
                .set(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.DOC_UUID, docUuid)
                .set(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.FAILED_AGAINST, failedAgainst)
                .set(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.REASON, reason)
                .set(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.FAILING_RECORDS, failingRecords)
                .set(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.UPDATE_TIME_MS, now)
                .set(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.ATTEMPTS,
                        LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.ATTEMPTS.plus(1))
                .execute());
    }

    @Override
    public void released(final Learner learner, final long inputId) {
        JooqUtil.context(connProvider, context -> context
                .deleteFrom(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL)
                .where(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.PIPELINE_UUID.eq(learner.pipelineUuid()))
                .and(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.ELEMENT_ID.eq(learner.elementId()))
                .and(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.META_ID.eq(inputId))
                .execute());
    }

    @Override
    public int releasedAll(final String pipelineUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                .deleteFrom(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL)
                .where(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.PIPELINE_UUID.eq(pipelineUuid))
                .execute());
    }

    @Override
    public Waiting waitingOn(final Learner learner, final long offset, final int limit) {
        final Condition mine = LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.PIPELINE_UUID.eq(learner.pipelineUuid())
                .and(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.ELEMENT_ID.eq(learner.elementId()));
        return JooqUtil.contextResult(connProvider, context -> new Waiting(
                context.selectFrom(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL)
                        .where(mine)
                        .orderBy(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.FIRST_DEFERRED_MS.asc(),
                                LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.META_ID.asc())
                        .limit(offset, limit)
                        .fetch(DeferralsDao::deferral),
                context.fetchCount(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL, mine)));
    }

    @Override
    public List<Deferral> forDocument(final String docUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                .selectFrom(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL)
                .where(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.DOC_UUID.eq(docUuid))
                .orderBy(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.FIRST_DEFERRED_MS.asc(),
                        LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.META_ID.asc())
                .fetch(DeferralsDao::deferral));
    }

    @Override
    public List<Stamp> stampsWaiting() {
        return JooqUtil.contextResult(connProvider, context -> context
                .selectDistinct(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.PIPELINE_UUID,
                        LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.ELEMENT_ID,
                        LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.FAILED_AGAINST)
                .from(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL)
                // Rows with no stamp (no binding they failed against) are included. A stream deferred while
                // its learner had nothing serving it has no stamp. If such rows were left out of this
                // sweep, a stream deferred just after a promotion had woken the others would wait the full
                // retry delay for a translation that already exists.
                .fetch(row -> new Stamp(
                        new Learner(row.get(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.PIPELINE_UUID),
                                row.get(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.ELEMENT_ID)),
                        row.get(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.FAILED_AGAINST))));
    }

    @Override
    public Optional<Deferral> of(final Learner learner, final long inputId) {
        return JooqUtil.contextResult(connProvider, context -> context
                .selectFrom(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL)
                .where(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.PIPELINE_UUID.eq(learner.pipelineUuid()))
                .and(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.ELEMENT_ID.eq(learner.elementId()))
                .and(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.META_ID.eq(inputId))
                .fetchOptional(DeferralsDao::deferral));
    }

    private static Deferral deferral(final Record row) {
        return new Deferral(
                new Learner(row.get(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.PIPELINE_UUID),
                        row.get(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.ELEMENT_ID)),
                row.get(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.DOC_UUID),
                row.get(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.META_ID),
                row.get(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.FEED_NAME),
                row.get(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.TYPE_NAME),
                row.get(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.FAILED_AGAINST),
                row.get(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.REASON),
                row.get(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.FAILING_RECORDS),
                row.get(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.FIRST_DEFERRED_MS),
                row.get(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.ATTEMPTS));
    }
}
