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

import stroom.db.util.JooqUtil;
import stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterDeferral;
import stroom.shapeshifter.ai.stage.Deferrals;
import stroom.shapeshifter.ai.stage.Learner;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.Condition;
import org.jooq.Record;

import java.util.List;
import java.util.Optional;

/// The deferral rows of design 04 §6 as a table, one row per stream and document. A stream put back a
/// second time is the same row brought up to date — the unique key on document and meta id makes the
/// insert an update — and the time it first waited is what the row was inserted with, so that a new
/// reason does not make it newly late.
@Singleton
public class DeferralsDao implements Deferrals {

    private final ShapeshifterAiDbConnProvider connProvider;

    @Inject
    DeferralsDao(final ShapeshifterAiDbConnProvider connProvider) {
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
                .insertInto(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL)
                .set(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.PIPELINE_UUID, learner.pipelineUuid())
                .set(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.ELEMENT_ID, learner.elementId())
                .set(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.DOC_UUID, docUuid)
                .set(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.META_ID, inputId)
                .set(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.FEED_NAME, feed)
                .set(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.TYPE_NAME, type)
                .set(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.FAILED_AGAINST, failedAgainst)
                .set(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.REASON, reason)
                .set(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.FAILING_RECORDS, failingRecords)
                .set(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.FIRST_DEFERRED_MS, now)
                .set(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.UPDATE_TIME_MS, now)
                .set(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.ATTEMPTS, 1)
                // Tried again and put back again: everything but when it first waited moves, the document
                // too, since the learner may have been pointed at another.
                .onDuplicateKeyUpdate()
                .set(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.DOC_UUID, docUuid)
                .set(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.FAILED_AGAINST, failedAgainst)
                .set(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.REASON, reason)
                .set(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.FAILING_RECORDS, failingRecords)
                .set(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.UPDATE_TIME_MS, now)
                .set(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.ATTEMPTS,
                        ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.ATTEMPTS.plus(1))
                .execute());
    }

    @Override
    public void released(final Learner learner, final long inputId) {
        JooqUtil.context(connProvider, context -> context
                .deleteFrom(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL)
                .where(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.PIPELINE_UUID.eq(learner.pipelineUuid()))
                .and(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.ELEMENT_ID.eq(learner.elementId()))
                .and(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.META_ID.eq(inputId))
                .execute());
    }

    @Override
    public int releasedAll(final String pipelineUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                .deleteFrom(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL)
                .where(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.PIPELINE_UUID.eq(pipelineUuid))
                .execute());
    }

    @Override
    public Waiting waitingOn(final Learner learner, final long offset, final int limit) {
        final Condition mine = ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.PIPELINE_UUID.eq(learner.pipelineUuid())
                .and(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.ELEMENT_ID.eq(learner.elementId()));
        return JooqUtil.contextResult(connProvider, context -> new Waiting(
                context.selectFrom(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL)
                        .where(mine)
                        .orderBy(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.FIRST_DEFERRED_MS.asc(),
                                ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.META_ID.asc())
                        .limit(offset, limit)
                        .fetch(DeferralsDao::deferral),
                context.fetchCount(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL, mine)));
    }

    @Override
    public List<Deferral> forDocument(final String docUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                .selectFrom(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL)
                .where(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.DOC_UUID.eq(docUuid))
                .orderBy(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.FIRST_DEFERRED_MS.asc(),
                        ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.META_ID.asc())
                .fetch(DeferralsDao::deferral));
    }

    @Override
    public List<Stamp> stampsWaiting() {
        return JooqUtil.contextResult(connProvider, context -> context
                .selectDistinct(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.PIPELINE_UUID,
                        ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.ELEMENT_ID,
                        ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.FAILED_AGAINST)
                .from(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL)
                .where(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.FAILED_AGAINST.isNotNull())
                .fetch(row -> new Stamp(
                        new Learner(row.get(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.PIPELINE_UUID),
                                row.get(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.ELEMENT_ID)),
                        row.get(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.FAILED_AGAINST))));
    }

    @Override
    public Optional<Deferral> of(final Learner learner, final long inputId) {
        return JooqUtil.contextResult(connProvider, context -> context
                .selectFrom(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL)
                .where(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.PIPELINE_UUID.eq(learner.pipelineUuid()))
                .and(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.ELEMENT_ID.eq(learner.elementId()))
                .and(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.META_ID.eq(inputId))
                .fetchOptional(DeferralsDao::deferral));
    }

    private static Deferral deferral(final Record row) {
        return new Deferral(
                new Learner(row.get(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.PIPELINE_UUID),
                        row.get(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.ELEMENT_ID)),
                row.get(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.DOC_UUID),
                row.get(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.META_ID),
                row.get(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.FEED_NAME),
                row.get(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.TYPE_NAME),
                row.get(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.FAILED_AGAINST),
                row.get(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.REASON),
                row.get(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.FAILING_RECORDS),
                row.get(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.FIRST_DEFERRED_MS),
                row.get(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.ATTEMPTS));
    }
}
