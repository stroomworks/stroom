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
import stroom.shapeshifter.ai.element.KeptLearning;
import stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterCorpus;
import stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterDeferral;
import stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterDeletedPipeline;
import stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterOutput;
import stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterSnapshot;
import stroom.shapeshifter.ai.stage.DeletedPipelines;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Select;
import org.jooq.impl.DSL;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterAttempt.SHAPESHIFTER_ATTEMPT;
import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterBinding.SHAPESHIFTER_BINDING;
import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterGuidance.SHAPESHIFTER_GUIDANCE;
import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterLearnerState.SHAPESHIFTER_LEARNER_STATE;
import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterTurn.SHAPESHIFTER_TURN;

/// Deleted pipelines' learning as a table of marks (design 06 A120, A134–A136), and the learning a node
/// keeps as the retention job sees it. A learner keeps rows under its pipeline's uuid in its state, its
/// bindings, its guidance, its waiting streams and its attempts; its bindings' outputs, corpus and
/// snapshots are kept under the bindings' uuids, which its bindings and attempts name.
@Singleton
public class DeletedPipelinesDao implements DeletedPipelines, KeptLearning {

    private final ShapeshifterAiDbConnProvider connProvider;

    @Inject
    DeletedPipelinesDao(final ShapeshifterAiDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    /// Marked only where the pipeline keeps a row, since every pipeline deleted in Stroom is heard of here
    /// and most never learned; and where a mark is already made, it stands.
    @Override
    public boolean deleted(final String pipelineUuid, final long timeMs) {
        return JooqUtil.transactionResult(connProvider, context -> {
            if (!keeps(context, pipelineUuid)) {
                return false;
            }
            return context.insertInto(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE)
                           .set(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.PIPELINE_UUID, pipelineUuid)
                           .set(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.DELETED_TIME_MS, timeMs)
                           .onDuplicateKeyIgnore()
                           .execute() > 0;
        });
    }

    @Override
    public boolean restored(final String pipelineUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                .deleteFrom(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE)
                .where(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.PIPELINE_UUID.eq(pipelineUuid))
                .execute() > 0);
    }

    @Override
    public boolean isDeleted(final String pipelineUuid) {
        return JooqUtil.contextResult(connProvider, context -> context.fetchExists(
                ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE,
                ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.PIPELINE_UUID.eq(pipelineUuid)));
    }

    @Override
    public Set<String> deletedOf(final Collection<String> pipelineUuids) {
        if (NullSafe.isEmptyCollection(pipelineUuids)) {
            return Set.of();
        }
        return JooqUtil.contextResult(connProvider, context -> context
                .select(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.PIPELINE_UUID)
                .from(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE)
                .where(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.PIPELINE_UUID.in(pipelineUuids))
                .fetchSet(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.PIPELINE_UUID));
    }

    @Override
    public Set<String> pipelines() {
        return JooqUtil.contextResult(connProvider, context -> {
            final Set<String> kept = new HashSet<>(context
                    .select(SHAPESHIFTER_LEARNER_STATE.PIPELINE_UUID).from(SHAPESHIFTER_LEARNER_STATE)
                    .union(DSL.select(SHAPESHIFTER_BINDING.PIPELINE_UUID).from(SHAPESHIFTER_BINDING))
                    .union(DSL.select(SHAPESHIFTER_GUIDANCE.PIPELINE_UUID).from(SHAPESHIFTER_GUIDANCE))
                    .union(DSL.select(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.PIPELINE_UUID)
                            .from(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL))
                    .union(DSL.select(SHAPESHIFTER_ATTEMPT.PIPELINE_UUID).from(SHAPESHIFTER_ATTEMPT))
                    .fetch(0, String.class));
            kept.removeAll(context
                    .select(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.PIPELINE_UUID)
                    .from(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE)
                    .fetchSet(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.PIPELINE_UUID));
            return Set.copyOf(kept);
        });
    }

    @Override
    public List<String> deletedBefore(final long timeMs) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.PIPELINE_UUID)
                .from(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE)
                .where(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.DELETED_TIME_MS.lt(timeMs))
                .orderBy(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.DELETED_TIME_MS.asc(),
                        ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.PIPELINE_UUID.asc())
                .fetch(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.PIPELINE_UUID));
    }

    /// In one transaction, so that a pipeline's learning is never half forgotten; and in the order the
    /// keys need: what is kept under a binding's uuid while the bindings and attempts that name it are
    /// still there to say which, and an attempt's turns before the attempt.
    @Override
    public void forget(final String pipelineUuid) {
        JooqUtil.transaction(connProvider, context -> {
            final Select<Record1<String>> bindings = DSL
                    .select(SHAPESHIFTER_BINDING.BINDING_UUID)
                    .from(SHAPESHIFTER_BINDING)
                    .where(SHAPESHIFTER_BINDING.PIPELINE_UUID.eq(pipelineUuid))
                    .union(DSL.select(SHAPESHIFTER_ATTEMPT.BINDING_UUID)
                            .from(SHAPESHIFTER_ATTEMPT)
                            .where(SHAPESHIFTER_ATTEMPT.PIPELINE_UUID.eq(pipelineUuid))
                            .and(SHAPESHIFTER_ATTEMPT.BINDING_UUID.isNotNull()));
            // Read once: a delete may not select from the table it deletes from, and these are a few.
            final List<String> bindingUuids = context.fetch(bindings).getValues(0, String.class);
            if (!bindingUuids.isEmpty()) {
                context.deleteFrom(ShapeshifterOutput.SHAPESHIFTER_OUTPUT)
                        .where(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.BINDING_UUID.in(bindingUuids))
                        .execute();
                context.deleteFrom(ShapeshifterCorpus.SHAPESHIFTER_CORPUS)
                        .where(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.BINDING_UUID.in(bindingUuids))
                        .execute();
                context.deleteFrom(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT)
                        .where(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.BINDING_UUID.in(bindingUuids))
                        .execute();
            }
            context.deleteFrom(SHAPESHIFTER_TURN)
                    .where(SHAPESHIFTER_TURN.FK_ATTEMPT_ID.in(DSL
                            .select(SHAPESHIFTER_ATTEMPT.ID)
                            .from(SHAPESHIFTER_ATTEMPT)
                            .where(SHAPESHIFTER_ATTEMPT.PIPELINE_UUID.eq(pipelineUuid))))
                    .execute();
            context.deleteFrom(SHAPESHIFTER_ATTEMPT)
                    .where(SHAPESHIFTER_ATTEMPT.PIPELINE_UUID.eq(pipelineUuid))
                    .execute();
            context.deleteFrom(SHAPESHIFTER_BINDING)
                    .where(SHAPESHIFTER_BINDING.PIPELINE_UUID.eq(pipelineUuid))
                    .execute();
            context.deleteFrom(SHAPESHIFTER_LEARNER_STATE)
                    .where(SHAPESHIFTER_LEARNER_STATE.PIPELINE_UUID.eq(pipelineUuid))
                    .execute();
            context.deleteFrom(SHAPESHIFTER_GUIDANCE)
                    .where(SHAPESHIFTER_GUIDANCE.PIPELINE_UUID.eq(pipelineUuid))
                    .execute();
            context.deleteFrom(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL)
                    .where(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.PIPELINE_UUID.eq(pipelineUuid))
                    .execute();
            context.deleteFrom(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE)
                    .where(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.PIPELINE_UUID.eq(pipelineUuid))
                    .execute();
        });
    }

    /// Whether the pipeline keeps any row a learner keeps under its uuid.
    private static boolean keeps(final DSLContext context, final String pipelineUuid) {
        return context.fetchExists(SHAPESHIFTER_LEARNER_STATE,
                       SHAPESHIFTER_LEARNER_STATE.PIPELINE_UUID.eq(pipelineUuid))
               || context.fetchExists(SHAPESHIFTER_BINDING, SHAPESHIFTER_BINDING.PIPELINE_UUID.eq(pipelineUuid))
               || context.fetchExists(SHAPESHIFTER_GUIDANCE,
                       SHAPESHIFTER_GUIDANCE.PIPELINE_UUID.eq(pipelineUuid))
               || context.fetchExists(ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL,
                       ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL.PIPELINE_UUID.eq(pipelineUuid))
               || context.fetchExists(SHAPESHIFTER_ATTEMPT,
                       SHAPESHIFTER_ATTEMPT.PIPELINE_UUID.eq(pipelineUuid));
    }
}
