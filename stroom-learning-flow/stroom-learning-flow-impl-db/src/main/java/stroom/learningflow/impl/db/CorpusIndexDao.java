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
import stroom.learningflow.impl.db.jooq.tables.LearningFlowCorpus;
import stroom.learningflow.stage.CorpusIndex;
import stroom.learningflow.stage.RegressionSet.Kind;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.DSLContext;
import org.jooq.Record;

import java.util.List;

/// The database implementation of [CorpusIndex], which records which records make up each binding's
/// regression corpus.
///
/// There is one row per corpus entry, keyed by binding. Rows are ordered by id, which is the order in which
/// the entries were accepted, so that trimming the corpus to its cap drops the oldest entries first.
@Singleton
public class CorpusIndexDao implements CorpusIndex {

    private final LearningFlowDbConnProvider connProvider;

    @Inject
    CorpusIndexDao(final LearningFlowDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public List<Row> rows(final String bindingUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                .selectFrom(LearningFlowCorpus.LEARNING_FLOW_CORPUS)
                .where(LearningFlowCorpus.LEARNING_FLOW_CORPUS.BINDING_UUID.eq(bindingUuid))
                .orderBy(LearningFlowCorpus.LEARNING_FLOW_CORPUS.ID.asc())
                .fetch(CorpusIndexDao::row));
    }

    @Override
    public void add(final List<Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        JooqUtil.context(connProvider, context -> {
            // One statement per row, in order, so that the generated ids follow the order of acceptance.
            for (final Row row : rows) {
                context.insertInto(LearningFlowCorpus.LEARNING_FLOW_CORPUS)
                        .set(LearningFlowCorpus.LEARNING_FLOW_CORPUS.DOC_UUID, row.docUuid())
                        .set(LearningFlowCorpus.LEARNING_FLOW_CORPUS.BINDING_UUID, row.bindingUuid())
                        .set(LearningFlowCorpus.LEARNING_FLOW_CORPUS.META_ID, row.metaId())
                        .set(LearningFlowCorpus.LEARNING_FLOW_CORPUS.SEGMENT, row.segment())
                        .set(LearningFlowCorpus.LEARNING_FLOW_CORPUS.KIND, row.kind().name())
                        .set(LearningFlowCorpus.LEARNING_FLOW_CORPUS.SCORE, row.score())
                        .set(LearningFlowCorpus.LEARNING_FLOW_CORPUS.ATTEMPT_ID, row.attemptId())
                        .set(LearningFlowCorpus.LEARNING_FLOW_CORPUS.ACCEPT_TIME_MS, row.acceptTimeMs())
                        .execute();
            }
        });
    }

    @Override
    public List<Long> trim(final String bindingUuid, final int cap) {
        return JooqUtil.transactionResult(connProvider, context -> {
            final List<Long> ids = context
                    .select(LearningFlowCorpus.LEARNING_FLOW_CORPUS.ID)
                    .from(LearningFlowCorpus.LEARNING_FLOW_CORPUS)
                    .where(LearningFlowCorpus.LEARNING_FLOW_CORPUS.BINDING_UUID.eq(bindingUuid))
                    .orderBy(LearningFlowCorpus.LEARNING_FLOW_CORPUS.ID.asc())
                    .fetch(LearningFlowCorpus.LEARNING_FLOW_CORPUS.ID);
            if (ids.size() <= cap) {
                return List.of();
            }
            final List<Long> dropping = ids.subList(0, ids.size() - cap);
            final List<Long> streams = context
                    .selectDistinct(LearningFlowCorpus.LEARNING_FLOW_CORPUS.META_ID)
                    .from(LearningFlowCorpus.LEARNING_FLOW_CORPUS)
                    .where(LearningFlowCorpus.LEARNING_FLOW_CORPUS.ID.in(dropping))
                    .fetch(LearningFlowCorpus.LEARNING_FLOW_CORPUS.META_ID);
            context.deleteFrom(LearningFlowCorpus.LEARNING_FLOW_CORPUS)
                    .where(LearningFlowCorpus.LEARNING_FLOW_CORPUS.ID.in(dropping))
                    .execute();
            return orphaned(context, streams);
        });
    }

    @Override
    public List<Long> discard(final String bindingUuid) {
        return JooqUtil.transactionResult(connProvider, context -> {
            final List<Long> streams = context
                    .selectDistinct(LearningFlowCorpus.LEARNING_FLOW_CORPUS.META_ID)
                    .from(LearningFlowCorpus.LEARNING_FLOW_CORPUS)
                    .where(LearningFlowCorpus.LEARNING_FLOW_CORPUS.BINDING_UUID.eq(bindingUuid))
                    .fetch(LearningFlowCorpus.LEARNING_FLOW_CORPUS.META_ID);
            context.deleteFrom(LearningFlowCorpus.LEARNING_FLOW_CORPUS)
                    .where(LearningFlowCorpus.LEARNING_FLOW_CORPUS.BINDING_UUID.eq(bindingUuid))
                    .execute();
            return orphaned(context, streams);
        });
    }

    @Override
    public void move(final String fromBindingUuid, final String toBindingUuid) {
        JooqUtil.context(connProvider, context -> context
                .update(LearningFlowCorpus.LEARNING_FLOW_CORPUS)
                .set(LearningFlowCorpus.LEARNING_FLOW_CORPUS.BINDING_UUID, toBindingUuid)
                .where(LearningFlowCorpus.LEARNING_FLOW_CORPUS.BINDING_UUID.eq(fromBindingUuid))
                .execute());
    }

    /// Returns those of the given streams that no corpus row refers to any more, so that the caller can
    /// remove them.
    private static List<Long> orphaned(final DSLContext context, final List<Long> streams) {
        if (streams.isEmpty()) {
            return List.of();
        }
        final List<Long> named = context
                .selectDistinct(LearningFlowCorpus.LEARNING_FLOW_CORPUS.META_ID)
                .from(LearningFlowCorpus.LEARNING_FLOW_CORPUS)
                .where(LearningFlowCorpus.LEARNING_FLOW_CORPUS.META_ID.in(streams))
                .fetch(LearningFlowCorpus.LEARNING_FLOW_CORPUS.META_ID);
        return streams.stream().filter(id -> !named.contains(id)).toList();
    }

    private static Row row(final Record row) {
        return new Row(
                row.get(LearningFlowCorpus.LEARNING_FLOW_CORPUS.DOC_UUID),
                row.get(LearningFlowCorpus.LEARNING_FLOW_CORPUS.BINDING_UUID),
                row.get(LearningFlowCorpus.LEARNING_FLOW_CORPUS.META_ID),
                row.get(LearningFlowCorpus.LEARNING_FLOW_CORPUS.SEGMENT),
                Kind.valueOf(row.get(LearningFlowCorpus.LEARNING_FLOW_CORPUS.KIND)),
                row.get(LearningFlowCorpus.LEARNING_FLOW_CORPUS.SCORE),
                row.get(LearningFlowCorpus.LEARNING_FLOW_CORPUS.ATTEMPT_ID),
                row.get(LearningFlowCorpus.LEARNING_FLOW_CORPUS.ACCEPT_TIME_MS));
    }
}
