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
import stroom.pipeline.shared.PipelineDoc;
import stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterOutput;
import stroom.shapeshifter.ai.stage.Outputs;
import stroom.shapeshifter.ai.stage.Produced;
import stroom.shapeshifter.ai.stage.Replayable;
import stroom.shapeshifter.shared.RecordBoundary;
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

    /// How many records' spans one output keeps. A judgement is made on a sample and a fault is found
    /// in a record, but a stream is a stream: past this, a record has no span rather than a wrong one.
    private static final int MOST_SPANS = 100_000;

    private static final Pattern SPAN = Pattern.compile("(\\d+):(\\d+)-(\\d+):(\\d+)");

    private final ShapeshifterAiDbConnProvider connProvider;

    @Inject
    OutputsDao(final ShapeshifterAiDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public Optional<TextRange> span(final String docUuid,
                                    final long inputId,
                                    final String pipeline,
                                    final int recordIndex) {
        if (recordIndex < 0) {
            return Optional.empty();
        }
        return JooqUtil.contextResult(connProvider, context -> context
                        .select(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.RECORD_SPANS)
                        .from(ShapeshifterOutput.SHAPESHIFTER_OUTPUT)
                        .where(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.INPUT_META_ID.eq(inputId))
                        .and(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.DOC_UUID.eq(docUuid))
                        .and(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PIPELINE_UUID.eq(ofPipeline(pipeline)))
                        .orderBy(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PRODUCE_TIME_MS.desc(),
                                ShapeshifterOutput.SHAPESHIFTER_OUTPUT.ID.desc())
                        .limit(1)
                        .fetchOptional(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.RECORD_SPANS))
                .flatMap(spans -> spanAt(spans, recordIndex));
    }

    @Override
    public void emitted(final long inputId,
                        final String pipeline,
                        final Produced produced,
                        final List<TextRange> spans) {
        final long now = System.currentTimeMillis();
        final RecordBoundary boundary = produced.boundary();
        JooqUtil.context(connProvider, context -> context
                .insertInto(ShapeshifterOutput.SHAPESHIFTER_OUTPUT)
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.CREATE_TIME_MS, now)
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PRODUCE_TIME_MS, now)
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.DOC_UUID, produced.docUuid())
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.BINDING_UUID, produced.bindingUuid())
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.INPUT_META_ID, inputId)
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PIPELINE_UUID, ofPipeline(pipeline))
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.SNAPSHOT_ID, produced.snapshotId())
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.FRAGMENT_UUID,
                        produced.fragment().getUuid())
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.BOUNDARY_ELEMENT, NullSafe.get(boundary,
                        RecordBoundary::getElement))
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.BOUNDARY_ARRAY, NullSafe.get(boundary,
                        RecordBoundary::getArray))
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.BOUNDARY_DEPTH, NullSafe.get(boundary,
                        RecordBoundary::getDepth))
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PROVISIONAL, produced.provisional())
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.SCORE, produced.score())
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.RECORD_SPANS, written(spans))
                // A stream processed twice by one pipeline under one binding is one thing to replay; the
                // later run is what its output is, so the row says the later one — including when it
                // was produced, since the row keeps the id it was inserted with and the id cannot then
                // say which row was written last.
                .onDuplicateKeyUpdate()
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PRODUCE_TIME_MS, now)
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.SNAPSHOT_ID, produced.snapshotId())
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.FRAGMENT_UUID,
                        produced.fragment().getUuid())
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.BOUNDARY_ELEMENT, NullSafe.get(boundary,
                        RecordBoundary::getElement))
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.BOUNDARY_ARRAY, NullSafe.get(boundary,
                        RecordBoundary::getArray))
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.BOUNDARY_DEPTH, NullSafe.get(boundary,
                        RecordBoundary::getDepth))
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PROVISIONAL, produced.provisional())
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.SCORE, produced.score())
                // The spans are left as they are where this run has none to offer: a run with no parser
                // to ask — an as-processed reprocess, a chain that was given records — knows nothing
                // about where the records began, and knowing nothing must not erase what was known. The
                // stream being reprocessed is the one somebody is investigating.
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.RECORD_SPANS, written(spans) == null
                        ? ShapeshifterOutput.SHAPESHIFTER_OUTPUT.RECORD_SPANS
                        : DSL.val(written(spans)))
                .execute());
    }

    /// The spans as one column holds them: `line:column-line:column`, a record apiece, in the order the
    /// parser cut them. Capped, because a stream of a million records is a million spans and what a node
    /// keeps has to be bounded by something — past the cap a record has no span, which reads as "not
    /// recorded" and never as a wrong one.
    private static String written(final List<TextRange> spans) {
        if (NullSafe.isEmptyCollection(spans)) {
            return null;
        }
        return spans.stream()
                .limit(MOST_SPANS)
                .map(span -> span.getFrom().getLineNo() + ":" + span.getFrom().getColNo() + "-"
                             + span.getTo().getLineNo() + ":" + span.getTo().getColNo())
                .collect(Collectors.joining(","));
    }

    /// One record's span, read back out of the column.
    private static Optional<TextRange> spanAt(final String spans, final int recordIndex) {
        if (spans == null || spans.isEmpty()) {
            return Optional.empty();
        }
        final String[] written = spans.split(",");
        if (recordIndex >= written.length) {
            return Optional.empty();
        }
        final Matcher matcher = SPAN.matcher(written[recordIndex]);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new TextRange(
                DefaultLocation.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))),
                DefaultLocation.of(Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)))));
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
                .select(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.INPUT_META_ID,
                        ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PIPELINE_UUID)
                .from(ShapeshifterOutput.SHAPESHIFTER_OUTPUT)
                .where(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.BINDING_UUID.eq(bindingUuid))
                .and(fragmentUuid == null
                        ? DSL.noCondition()
                        : ShapeshifterOutput.SHAPESHIFTER_OUTPUT.FRAGMENT_UUID.eq(fragmentUuid))
                .orderBy(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.ID)
                .fetch(row -> new Replayable(
                        row.get(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.INPUT_META_ID),
                        // Back to null: the seam says null where no pipeline is known, and the empty
                        // string is only how a unique key holds that.
                        namedPipeline(row.get(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PIPELINE_UUID)))));
    }

    @Override
    public Optional<Produced> asProcessed(final String docUuid, final long inputId, final String pipeline) {
        return JooqUtil.contextResult(connProvider, context -> context
                        .select(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.DOC_UUID,
                                ShapeshifterOutput.SHAPESHIFTER_OUTPUT.BINDING_UUID,
                                ShapeshifterOutput.SHAPESHIFTER_OUTPUT.FRAGMENT_UUID,
                                ShapeshifterOutput.SHAPESHIFTER_OUTPUT.BOUNDARY_ELEMENT,
                                ShapeshifterOutput.SHAPESHIFTER_OUTPUT.BOUNDARY_ARRAY,
                                ShapeshifterOutput.SHAPESHIFTER_OUTPUT.BOUNDARY_DEPTH,
                                ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PROVISIONAL,
                                ShapeshifterOutput.SHAPESHIFTER_OUTPUT.SCORE,
                                ShapeshifterOutput.SHAPESHIFTER_OUTPUT.SNAPSHOT_ID)
                        .from(ShapeshifterOutput.SHAPESHIFTER_OUTPUT)
                        .where(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.INPUT_META_ID.eq(inputId))
                        // This document's stage, since a pipeline may hold two of them (design 01 §3)
                        // and each records what it made of the same input.
                        .and(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.DOC_UUID.eq(docUuid))
                        .and(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PIPELINE_UUID.eq(ofPipeline(pipeline)))
                        // What was produced last is what its output is: a stream served again under the
                        // same binding updates its row in place and keeps the id it was inserted with, so
                        // the id is the order rows were first written and not the order they were run.
                        .orderBy(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PRODUCE_TIME_MS.desc(),
                                ShapeshifterOutput.SHAPESHIFTER_OUTPUT.ID.desc())
                        .limit(1)
                        .fetchOptional())
                .map(row -> new Produced(
                        row.get(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.DOC_UUID),
                        row.get(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.BINDING_UUID),
                        PipelineDoc.buildDocRef()
                                .uuid(row.get(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.FRAGMENT_UUID))
                                .build(),
                        boundary(row.get(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.BOUNDARY_ELEMENT),
                                row.get(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.BOUNDARY_ARRAY),
                                row.get(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.BOUNDARY_DEPTH)),
                        row.get(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PROVISIONAL),
                        // A row written before the score was recorded scores nothing rather than
                        // failing the reprocess it was read for.
                        Objects.requireNonNullElse(
                                row.get(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.SCORE), 0.0),
                        row.get(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.SNAPSHOT_ID)));
    }

    /// The pipeline a row names, or null where it names none: the inverse of [#ofPipeline(String)].
    private static String namedPipeline(final String pipeline) {
        return pipeline == null || pipeline.isEmpty()
                ? null
                : pipeline;
    }

    /// The boundary a row remembers, or null where the binding had none.
    private static RecordBoundary boundary(final String element, final String array, final Integer depth) {
        if (element == null && array == null) {
            return null;
        }
        return new RecordBoundary(element, array, depth);
    }

    /// In batches, like the attempts': a delete of every old row at once would hold locks across the
    /// table, and the job runs again until there is nothing left to forget.
    @Override
    public int prune(final long producedBeforeMs) {
        return JooqUtil.contextResult(connProvider, context -> {
            final List<Long> old = context
                    .select(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.ID)
                    .from(ShapeshifterOutput.SHAPESHIFTER_OUTPUT)
                    // By when the row was last *produced*, not when it was first inserted. A stream
                    // served again updates the row in place, which is why migration 009 added the
                    // column: pruning on the insert time would delete a stream processed sixty days ago
                    // and reprocessed this morning, and with it the record spans and bindings that make
                    // it reprocessable at all.
                    .where(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PRODUCE_TIME_MS.lt(producedBeforeMs))
                    .limit(PRUNE_BATCH)
                    .fetch(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.ID);
            if (old.isEmpty()) {
                return 0;
            }
            return context.deleteFrom(ShapeshifterOutput.SHAPESHIFTER_OUTPUT)
                    .where(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.ID.in(old))
                    .execute();
        });
    }
}
