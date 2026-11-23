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
import stroom.learningflow.impl.db.jooq.tables.LearningFlowOutput;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.stage.Outputs;
import stroom.learningflow.stage.Produced;
import stroom.learningflow.stage.Replayable;
import stroom.pipeline.shared.PipelineDoc;
import stroom.util.shared.DefaultLocation;
import stroom.util.shared.NullSafe;
import stroom.util.shared.TextRange;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.impl.DSL;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/// What each binding produced, as rows (design 01 §7.3 rule 3, A26): retracting a binding means finding the
/// inputs whose outputs it produced, and the node that retracts is rarely the node that produced them.
///
/// The bindings are on the output stream's attributes too, where a person reads them; a custom stream
/// attribute is not a field stroom can query, so what a retraction must find is kept here as well.
@Singleton
public class OutputsDao implements Outputs {

    /// How many rows one pass removes: a delete of every old row at once would hold locks across the
    /// table, and the job runs again.
    private static final int PRUNE_BATCH = 1000;

    private final LearningFlowDbConnProvider connProvider;

    @Inject
    OutputsDao(final LearningFlowDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public void emitted(final long inputId,
                        final String pipeline,
                        final Produced produced) {
        emitted(inputId, pipeline, produced, null, null);
    }

    @Override
    public void emitted(final long inputId,
                        final String pipeline,
                        final Produced produced,
                        final Integer records,
                        final Integer skipped) {
        final long now = System.currentTimeMillis();
        final RecordBoundary boundary = produced.boundary();
        JooqUtil.context(connProvider, context -> context
                .insertInto(LearningFlowOutput.LEARNING_FLOW_OUTPUT)
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.CREATE_TIME_MS, now)
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.PRODUCE_TIME_MS, now)
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.DOC_UUID, produced.docUuid())
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BINDING_UUID, produced.bindingUuid())
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.INPUT_META_ID, inputId)
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.PIPELINE_UUID, ofPipeline(pipeline))
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.SNAPSHOT_ID, produced.snapshotId())
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.FRAGMENT_UUID,
                        produced.fragment().getUuid())
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_ELEMENT, NullSafe.get(boundary,
                        RecordBoundary::getElement))
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_CHILDREN, NullSafe.get(boundary,
                        RecordBoundary::getChildren))
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_ARRAY, NullSafe.get(boundary,
                        RecordBoundary::getArray))
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_DEPTH, NullSafe.get(boundary,
                        RecordBoundary::getDepth))
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.SCORE, produced.score())
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.RECORD_COUNT, records)
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.SKIP_COUNT, skipped)
                // A stream processed twice by one pipeline under one binding is one thing to replay; the
                // later run is what its output is, so the row says the later one — including when it
                // was produced, since the row keeps the id it was inserted with and the id cannot then
                // say which row was written last.
                .onDuplicateKeyUpdate()
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.PRODUCE_TIME_MS, now)
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.SNAPSHOT_ID, produced.snapshotId())
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.FRAGMENT_UUID,
                        produced.fragment().getUuid())
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_ELEMENT, NullSafe.get(boundary,
                        RecordBoundary::getElement))
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_CHILDREN, NullSafe.get(boundary,
                        RecordBoundary::getChildren))
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_ARRAY, NullSafe.get(boundary,
                        RecordBoundary::getArray))
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_DEPTH, NullSafe.get(boundary,
                        RecordBoundary::getDepth))
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.SCORE, produced.score())
                // A replay that can't count its records keeps what was counted
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.RECORD_COUNT, records == null
                        ? LearningFlowOutput.LEARNING_FLOW_OUTPUT.RECORD_COUNT
                        : DSL.val(records))
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.SKIP_COUNT, skipped == null
                        ? LearningFlowOutput.LEARNING_FLOW_OUTPUT.SKIP_COUNT
                        : DSL.val(skipped))
                .execute());
    }

    /// An output of no pipeline is of no pipeline rather than of any: the empty string says so, and a
    /// unique key can hold it where it cannot hold a NULL.
    private static String ofPipeline(final String pipeline) {
        return pipeline == null
                ? ""
                : pipeline;
    }

    @Override
    public List<Replayable> boundBy(final String bindingUuid, final String fragmentUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select(LearningFlowOutput.LEARNING_FLOW_OUTPUT.INPUT_META_ID,
                        LearningFlowOutput.LEARNING_FLOW_OUTPUT.PIPELINE_UUID)
                .from(LearningFlowOutput.LEARNING_FLOW_OUTPUT)
                .where(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BINDING_UUID.eq(bindingUuid))
                .and(fragmentUuid == null
                        ? DSL.noCondition()
                        : LearningFlowOutput.LEARNING_FLOW_OUTPUT.FRAGMENT_UUID.eq(fragmentUuid))
                .orderBy(LearningFlowOutput.LEARNING_FLOW_OUTPUT.ID)
                .fetch(row -> new Replayable(
                        row.get(LearningFlowOutput.LEARNING_FLOW_OUTPUT.INPUT_META_ID),
                        // Back to null: the seam says null where no pipeline is known, and the empty
                        // string is only how a unique key holds that.
                        namedPipeline(row.get(LearningFlowOutput.LEARNING_FLOW_OUTPUT.PIPELINE_UUID)))));
    }

    @Override
    public Optional<Produced> asProcessed(final String docUuid, final long inputId, final String pipeline) {
        return JooqUtil.contextResult(connProvider, context -> context
                        .select(LearningFlowOutput.LEARNING_FLOW_OUTPUT.DOC_UUID,
                                LearningFlowOutput.LEARNING_FLOW_OUTPUT.BINDING_UUID,
                                LearningFlowOutput.LEARNING_FLOW_OUTPUT.FRAGMENT_UUID,
                                LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_ELEMENT,
                                LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_CHILDREN,
                                LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_ARRAY,
                                LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_DEPTH,
                                LearningFlowOutput.LEARNING_FLOW_OUTPUT.SCORE,
                                LearningFlowOutput.LEARNING_FLOW_OUTPUT.SNAPSHOT_ID)
                        .from(LearningFlowOutput.LEARNING_FLOW_OUTPUT)
                        .where(LearningFlowOutput.LEARNING_FLOW_OUTPUT.INPUT_META_ID.eq(inputId))
                        // This document's stage, since a pipeline may hold two of them (design 01 §3)
                        // and each records what it made of the same input.
                        .and(LearningFlowOutput.LEARNING_FLOW_OUTPUT.DOC_UUID.eq(docUuid))
                        .and(LearningFlowOutput.LEARNING_FLOW_OUTPUT.PIPELINE_UUID.eq(ofPipeline(pipeline)))
                        // What was produced last is what its output is: a stream served again under the
                        // same binding updates its row in place and keeps the id it was inserted with, so
                        // the id is the order rows were first written and not the order they were run.
                        .orderBy(LearningFlowOutput.LEARNING_FLOW_OUTPUT.PRODUCE_TIME_MS.desc(),
                                LearningFlowOutput.LEARNING_FLOW_OUTPUT.ID.desc())
                        .limit(1)
                        .fetchOptional())
                .map(row -> new Produced(
                        row.get(LearningFlowOutput.LEARNING_FLOW_OUTPUT.DOC_UUID),
                        row.get(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BINDING_UUID),
                        PipelineDoc.buildDocRef()
                                .uuid(row.get(LearningFlowOutput.LEARNING_FLOW_OUTPUT.FRAGMENT_UUID))
                                .build(),
                        boundary(row.get(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_ELEMENT),
                                row.get(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_CHILDREN),
                                row.get(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_ARRAY),
                                row.get(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_DEPTH)),
                        // A row written before the score was recorded scores nothing rather than
                        // failing the reprocess it was read for.
                        Objects.requireNonNullElse(
                                row.get(LearningFlowOutput.LEARNING_FLOW_OUTPUT.SCORE), 0.0),
                        row.get(LearningFlowOutput.LEARNING_FLOW_OUTPUT.SNAPSHOT_ID)));
    }

    /// The pipeline a row names, or null where it names none: the inverse of [#ofPipeline(String)].
    private static String namedPipeline(final String pipeline) {
        return pipeline == null || pipeline.isEmpty()
                ? null
                : pipeline;
    }

    /// The boundary a row remembers, or null where the binding had none.
    private static RecordBoundary boundary(final String element,
                                           final String children,
                                           final String array,
                                           final Integer depth) {
        if (element == null && children == null && array == null) {
            return null;
        }
        return new RecordBoundary(element, children, array, depth);
    }

    /// In batches, like the attempts': a delete of every old row at once would hold locks across the
    /// table, and the job runs again until there is nothing left to forget.
    @Override
    public int prune(final long producedBeforeMs) {
        return JooqUtil.contextResult(connProvider, context -> {
            final List<Long> old = context
                    .select(LearningFlowOutput.LEARNING_FLOW_OUTPUT.ID)
                    .from(LearningFlowOutput.LEARNING_FLOW_OUTPUT)
                    // By when the row was last *produced*, not when it was first inserted. A stream
                    // served again updates the row in place, which is why migration 009 added the
                    // column: pruning on the insert time would delete a stream processed sixty days ago
                    // and reprocessed this morning, and with it the bindings that make
                    // it reprocessable at all.
                    .where(LearningFlowOutput.LEARNING_FLOW_OUTPUT.PRODUCE_TIME_MS.lt(producedBeforeMs))
                    .limit(PRUNE_BATCH)
                    .fetch(LearningFlowOutput.LEARNING_FLOW_OUTPUT.ID);
            if (old.isEmpty()) {
                return 0;
            }
            return context.deleteFrom(LearningFlowOutput.LEARNING_FLOW_OUTPUT)
                    .where(LearningFlowOutput.LEARNING_FLOW_OUTPUT.ID.in(old))
                    .execute();
        });
    }
}
