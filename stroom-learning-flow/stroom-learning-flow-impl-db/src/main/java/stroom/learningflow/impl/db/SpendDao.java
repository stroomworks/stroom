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
import stroom.learningflow.stage.Spend;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.Record3;
import org.jooq.impl.DSL;

import java.math.BigDecimal;
import java.util.Map;

import static stroom.learningflow.impl.db.jooq.tables.LearningFlowSpend.LEARNING_FLOW_SPEND;

/// The database implementation of [Spend], which counts the tokens and model calls each document has used
/// in the current time window, so that spending limits apply across the whole cluster rather than per node.
///
/// There is one row per document, which every node adds to. The window restarts once it is older than the
/// window length. Each addition is made in a transaction with the row locked, so that two nodes adding at
/// once do not both add to the same old total.
@Singleton
public class SpendDao implements Spend {

    private final LearningFlowDbConnProvider connProvider;

    @Inject
    SpendDao(final LearningFlowDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public Spent record(final String docUuid, final long tokens, final int calls, final long windowMs) {
        return JooqUtil.transactionResult(connProvider, context -> {
            final long now = System.currentTimeMillis();
            RowMaker.makeIfMissing(context,
                    LEARNING_FLOW_SPEND,
                    LEARNING_FLOW_SPEND.DOC_UUID.eq(docUuid),
                    LEARNING_FLOW_SPEND.VERSION,
                    Map.of(LEARNING_FLOW_SPEND.UPDATE_TIME_MS, now,
                            LEARNING_FLOW_SPEND.DOC_UUID, docUuid,
                            LEARNING_FLOW_SPEND.WINDOW_START_MS, now));
            final Record3<Long, Long, Integer> current = context
                    .select(LEARNING_FLOW_SPEND.WINDOW_START_MS,
                            LEARNING_FLOW_SPEND.TOKENS_SPENT,
                            LEARNING_FLOW_SPEND.CALLS_MADE)
                    .from(LEARNING_FLOW_SPEND)
                    .where(LEARNING_FLOW_SPEND.DOC_UUID.eq(docUuid))
                    .forUpdate()
                    .fetchOne();
            final boolean fresh = now - current.value1() >= windowMs;
            final Spent spent = new Spent(fresh
                    ? now
                    : current.value1(),
                    (fresh
                            ? 0L
                            : current.value2()) + tokens,
                    (fresh
                            ? 0
                            : current.value3()) + calls);
            context.update(LEARNING_FLOW_SPEND)
                    .set(LEARNING_FLOW_SPEND.WINDOW_START_MS, spent.windowStartMs())
                    .set(LEARNING_FLOW_SPEND.TOKENS_SPENT, spent.tokens())
                    .set(LEARNING_FLOW_SPEND.CALLS_MADE, spent.calls())
                    .set(LEARNING_FLOW_SPEND.VERSION, LEARNING_FLOW_SPEND.VERSION.plus(1))
                    .set(LEARNING_FLOW_SPEND.UPDATE_TIME_MS, now)
                    .where(LEARNING_FLOW_SPEND.DOC_UUID.eq(docUuid))
                    .execute();
            return spent;
        });
    }

    @Override
    public Spent spent(final String docUuid, final long windowMs) {
        final long now = System.currentTimeMillis();
        return JooqUtil.contextResult(connProvider, context -> context
                        .select(LEARNING_FLOW_SPEND.WINDOW_START_MS,
                                LEARNING_FLOW_SPEND.TOKENS_SPENT,
                                LEARNING_FLOW_SPEND.CALLS_MADE)
                        .from(LEARNING_FLOW_SPEND)
                        .where(LEARNING_FLOW_SPEND.DOC_UUID.eq(docUuid))
                        .fetchOptional())
                .map(row -> now - row.value1() >= windowMs
                        ? new Spent(now, 0L, 0)
                        : new Spent(row.value1(), row.value2(), row.value3()))
                .orElseGet(() -> new Spent(now, 0L, 0));
    }

    @Override
    public Spent spentAll(final long windowMs) {
        final long now = System.currentTimeMillis();
        // Rows whose window has expired are left out rather than reset. Resetting is done by `record`,
        // under its lock, and a read must not write.
        return JooqUtil.contextResult(connProvider, context -> {
            final Record3<Long, BigDecimal, BigDecimal> row = context
                    .select(DSL.min(LEARNING_FLOW_SPEND.WINDOW_START_MS),
                            DSL.sum(LEARNING_FLOW_SPEND.TOKENS_SPENT),
                            DSL.sum(LEARNING_FLOW_SPEND.CALLS_MADE))
                    .from(LEARNING_FLOW_SPEND)
                    .where(LEARNING_FLOW_SPEND.WINDOW_START_MS.gt(now - windowMs))
                    .fetchOne();
            if (row == null || row.value1() == null) {
                return new Spent(now, 0L, 0);
            }
            return new Spent(row.value1(),
                    row.value2() == null
                            ? 0L
                            : row.value2().longValue(),
                    row.value3() == null
                            ? 0
                            : row.value3().intValue());
        });
    }
}
