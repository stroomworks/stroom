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
import stroom.shapeshifter.ai.stage.Spend;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.Record3;

import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterSpend.SHAPESHIFTER_SPEND;

/// The spend of A44 as a row per document: a token bucket every node adds to, so that a budget is the
/// cluster's and not each node's share of a guess. The count is folded inside a transaction on the row,
/// as the rolling score is, or two nodes finishing at once would each add to the same old total.
@Singleton
public class SpendDao implements Spend {

    private final ShapeshifterAiDbConnProvider connProvider;

    @Inject
    SpendDao(final ShapeshifterAiDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public Spent record(final String docUuid, final long tokens, final int calls, final long windowMs) {
        return JooqUtil.transactionResult(connProvider, context -> {
            final long now = System.currentTimeMillis();
            // The row is looked for before it is made: an `INSERT ... ON DUPLICATE KEY IGNORE` that finds a
            // duplicate leaves a shared lock the `FOR UPDATE` below would have to upgrade, and two nodes
            // recording spend for one document at once would deadlock on that upgrade — which is the very
            // thing this table is for.
            final boolean exists = context.fetchExists(context.selectFrom(SHAPESHIFTER_SPEND)
                    .where(SHAPESHIFTER_SPEND.DOC_UUID.eq(docUuid)));
            if (!exists) {
                context.insertInto(SHAPESHIFTER_SPEND)
                        .set(SHAPESHIFTER_SPEND.VERSION, 1)
                        .set(SHAPESHIFTER_SPEND.UPDATE_TIME_MS, now)
                        .set(SHAPESHIFTER_SPEND.DOC_UUID, docUuid)
                        .set(SHAPESHIFTER_SPEND.WINDOW_START_MS, now)
                        .onDuplicateKeyIgnore()
                        .execute();
            }
            final Record3<Long, Long, Integer> current = context
                    .select(SHAPESHIFTER_SPEND.WINDOW_START_MS,
                            SHAPESHIFTER_SPEND.TOKENS_SPENT,
                            SHAPESHIFTER_SPEND.CALLS_MADE)
                    .from(SHAPESHIFTER_SPEND)
                    .where(SHAPESHIFTER_SPEND.DOC_UUID.eq(docUuid))
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
            context.update(SHAPESHIFTER_SPEND)
                    .set(SHAPESHIFTER_SPEND.WINDOW_START_MS, spent.windowStartMs())
                    .set(SHAPESHIFTER_SPEND.TOKENS_SPENT, spent.tokens())
                    .set(SHAPESHIFTER_SPEND.CALLS_MADE, spent.calls())
                    .set(SHAPESHIFTER_SPEND.VERSION, SHAPESHIFTER_SPEND.VERSION.plus(1))
                    .set(SHAPESHIFTER_SPEND.UPDATE_TIME_MS, now)
                    .where(SHAPESHIFTER_SPEND.DOC_UUID.eq(docUuid))
                    .execute();
            return spent;
        });
    }

    @Override
    public Spent spent(final String docUuid, final long windowMs) {
        final long now = System.currentTimeMillis();
        return JooqUtil.contextResult(connProvider, context -> context
                        .select(SHAPESHIFTER_SPEND.WINDOW_START_MS,
                                SHAPESHIFTER_SPEND.TOKENS_SPENT,
                                SHAPESHIFTER_SPEND.CALLS_MADE)
                        .from(SHAPESHIFTER_SPEND)
                        .where(SHAPESHIFTER_SPEND.DOC_UUID.eq(docUuid))
                        .fetchOptional())
                .map(row -> now - row.value1() >= windowMs
                        ? new Spent(now, 0L, 0)
                        : new Spent(row.value1(), row.value2(), row.value3()))
                .orElseGet(() -> new Spent(now, 0L, 0));
    }
}
