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
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.impl.DSL;

import java.util.List;
import java.util.Objects;
import java.util.Optional;


/// The database implementation of [Outputs], which records which binding and snapshot produced the output
/// for each input stream.
///
/// When a binding is retracted, the streams it produced output for must be found so that they can be
/// reprocessed, and the node that retracts is rarely the node that produced them. The binding is also
/// written to the output stream's attributes for a person to read, but Stroom cannot query a custom stream
/// attribute, so it is kept here as well. The snapshot recorded here also lets a stream be reprocessed
/// exactly as it was first processed.
@Singleton
public class OutputsDao implements Outputs {

    /// The most rows one call to [#prune] removes. Deleting every old row at once would hold locks across
    /// the table; the job that prunes runs again, so the rest are removed later.
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
                // A stream processed twice by one pipeline under one binding has one row, which records
                // the later run, since that produced its current output. This includes the produce time:
                // the row keeps the id it was inserted with, so the id cannot show which run was last.
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
                // A replay that cannot count its records keeps the counts already recorded.
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.RECORD_COUNT, records == null
                        ? LearningFlowOutput.LEARNING_FLOW_OUTPUT.RECORD_COUNT
                        : DSL.val(records))
                .set(LearningFlowOutput.LEARNING_FLOW_OUTPUT.SKIP_COUNT, skipped == null
                        ? LearningFlowOutput.LEARNING_FLOW_OUTPUT.SKIP_COUNT
                        : DSL.val(skipped))
                .execute());
    }

    /// Stores "no pipeline" as an empty string rather than null, because the unique key treats nulls as
    /// distinct and so would not stop duplicate rows.
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
                        // Converted back to null, which is how the interface represents "no pipeline".
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
                        // Only this document's row, since a pipeline may contain two Smart Pipeline
                        // elements, each recording its own output for the same input.
                        .and(LearningFlowOutput.LEARNING_FLOW_OUTPUT.DOC_UUID.eq(docUuid))
                        .and(LearningFlowOutput.LEARNING_FLOW_OUTPUT.PIPELINE_UUID.eq(ofPipeline(pipeline)))
                        // The most recently produced row is the stream's current output. A stream served
                        // again under the same binding updates its row in place and keeps its original id,
                        // so the id gives the order rows were first written, not the order they were run.
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
                        LearnerBindingsDao.recordBoundary(
                                row.get(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_ELEMENT),
                                row.get(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_CHILDREN),
                                row.get(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_ARRAY),
                                row.get(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BOUNDARY_DEPTH)),
                        // A row written before scores were recorded reads as a score of zero, rather
                        // than failing the reprocessing it was read for.
                        Objects.requireNonNullElse(
                                row.get(LearningFlowOutput.LEARNING_FLOW_OUTPUT.SCORE), 0.0),
                        row.get(LearningFlowOutput.LEARNING_FLOW_OUTPUT.SNAPSHOT_ID)));
    }

    /// Returns the pipeline a row records, or null if it records none: the inverse of [#ofPipeline(String)].
    private static String namedPipeline(final String pipeline) {
        return pipeline == null || pipeline.isEmpty()
                ? null
                : pipeline;
    }

    /// Deletes up to [#PRUNE_BATCH] rows last produced before the given time. Deleting every old row at
    /// once would hold locks across the table; the job runs again until nothing is left to delete.
    @Override
    public int prune(final long producedBeforeMs) {
        return JooqUtil.contextResult(connProvider, context -> {
            final List<Long> old = context
                    .select(LearningFlowOutput.LEARNING_FLOW_OUTPUT.ID)
                    .from(LearningFlowOutput.LEARNING_FLOW_OUTPUT)
                    // Pruned by when the row was last produced, not when it was first inserted. A stream
                    // served again updates its row in place. Pruning by insert time would delete the row
                    // of a stream first processed sixty days ago and reprocessed this morning, and with
                    // it the record that lets the stream be reprocessed exactly.
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
