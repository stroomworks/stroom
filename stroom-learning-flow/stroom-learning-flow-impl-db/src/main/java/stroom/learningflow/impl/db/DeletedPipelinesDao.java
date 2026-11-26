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
import stroom.learningflow.element.KeptLearning;
import stroom.learningflow.impl.db.jooq.tables.LearningFlowCorpus;
import stroom.learningflow.impl.db.jooq.tables.LearningFlowDeferral;
import stroom.learningflow.impl.db.jooq.tables.LearningFlowDeletedPipeline;
import stroom.learningflow.impl.db.jooq.tables.LearningFlowOutput;
import stroom.learningflow.impl.db.jooq.tables.LearningFlowSnapshot;
import stroom.learningflow.stage.DeletedPipelines;
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

import static stroom.learningflow.impl.db.jooq.tables.LearningFlowAttempt.LEARNING_FLOW_ATTEMPT;
import static stroom.learningflow.impl.db.jooq.tables.LearningFlowBinding.LEARNING_FLOW_BINDING;
import static stroom.learningflow.impl.db.jooq.tables.LearningFlowGuidance.LEARNING_FLOW_GUIDANCE;
import static stroom.learningflow.impl.db.jooq.tables.LearningFlowLearnerState.LEARNING_FLOW_LEARNER_STATE;
import static stroom.learningflow.impl.db.jooq.tables.LearningFlowTurn.LEARNING_FLOW_TURN;

/// The database implementation of [DeletedPipelines] and [KeptLearning]. It marks pipelines that have been
/// deleted, so that their learning stops at once and is shown only on request, and it lets the retention job
/// find and remove the learning of pipelines deleted long enough ago.
///
/// A learner's rows are keyed by its pipeline's UUID in the learner state, binding, guidance, deferral and
/// attempt tables. Its bindings' outputs, corpus entries and snapshots are keyed by the binding UUIDs, which
/// are found through its bindings and attempts.
@Singleton
public class DeletedPipelinesDao implements DeletedPipelines, KeptLearning {

    private final LearningFlowDbConnProvider connProvider;

    @Inject
    DeletedPipelinesDao(final LearningFlowDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    /// Marks a pipeline as deleted, but only if it has learning rows, since every pipeline deleted in
    /// Stroom is reported here and most never learned anything. An existing mark is left as it is.
    @Override
    public boolean deleted(final String pipelineUuid, final long timeMs) {
        return JooqUtil.transactionResult(connProvider, context -> {
            if (!keeps(context, pipelineUuid)) {
                return false;
            }
            return context.insertInto(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE)
                           .set(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE.PIPELINE_UUID, pipelineUuid)
                           .set(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE.DELETED_TIME_MS, timeMs)
                           .onDuplicateKeyIgnore()
                           .execute() > 0;
        });
    }

    @Override
    public boolean restored(final String pipelineUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                .deleteFrom(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE)
                .where(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE.PIPELINE_UUID.eq(pipelineUuid))
                .execute() > 0);
    }

    @Override
    public boolean isDeleted(final String pipelineUuid) {
        return JooqUtil.contextResult(connProvider, context -> context.fetchExists(
                LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE,
                LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE.PIPELINE_UUID.eq(pipelineUuid)));
    }

    @Override
    public Set<String> deletedOf(final Collection<String> pipelineUuids) {
        if (NullSafe.isEmptyCollection(pipelineUuids)) {
            return Set.of();
        }
        return JooqUtil.contextResult(connProvider, context -> context
                .select(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE.PIPELINE_UUID)
                .from(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE)
                .where(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE.PIPELINE_UUID.in(pipelineUuids))
                .fetchSet(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE.PIPELINE_UUID));
    }

    @Override
    public Set<String> pipelines() {
        return JooqUtil.contextResult(connProvider, context -> {
            final Set<String> kept = new HashSet<>(context
                    .select(LEARNING_FLOW_LEARNER_STATE.PIPELINE_UUID).from(LEARNING_FLOW_LEARNER_STATE)
                    .union(DSL.select(LEARNING_FLOW_BINDING.PIPELINE_UUID).from(LEARNING_FLOW_BINDING))
                    .union(DSL.select(LEARNING_FLOW_GUIDANCE.PIPELINE_UUID).from(LEARNING_FLOW_GUIDANCE))
                    .union(DSL.select(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.PIPELINE_UUID)
                            .from(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL))
                    .union(DSL.select(LEARNING_FLOW_ATTEMPT.PIPELINE_UUID).from(LEARNING_FLOW_ATTEMPT))
                    .fetch(0, String.class));
            kept.removeAll(context
                    .select(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE.PIPELINE_UUID)
                    .from(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE)
                    .fetchSet(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE.PIPELINE_UUID));
            return Set.copyOf(kept);
        });
    }

    @Override
    public List<String> deletedBefore(final long timeMs) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE.PIPELINE_UUID)
                .from(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE)
                .where(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE.DELETED_TIME_MS.lt(timeMs))
                .orderBy(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE.DELETED_TIME_MS.asc(),
                        LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE.PIPELINE_UUID.asc())
                .fetch(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE.PIPELINE_UUID));
    }

    /// Deletes all of a pipeline's learning, in one transaction so that it is never left half deleted.
    /// The rows keyed by binding UUID are deleted first, while the bindings and attempts that identify
    /// them still exist, and an attempt's turns are deleted before the attempt that they reference.
    @Override
    public void forget(final String pipelineUuid) {
        JooqUtil.transaction(connProvider, context -> {
            final Select<Record1<String>> bindings = DSL
                    .select(LEARNING_FLOW_BINDING.BINDING_UUID)
                    .from(LEARNING_FLOW_BINDING)
                    .where(LEARNING_FLOW_BINDING.PIPELINE_UUID.eq(pipelineUuid))
                    .union(DSL.select(LEARNING_FLOW_ATTEMPT.BINDING_UUID)
                            .from(LEARNING_FLOW_ATTEMPT)
                            .where(LEARNING_FLOW_ATTEMPT.PIPELINE_UUID.eq(pipelineUuid))
                            .and(LEARNING_FLOW_ATTEMPT.BINDING_UUID.isNotNull()));
            // The binding UUIDs are read into a list first, because a delete may not select from the
            // table it deletes from. There are only a few of them.
            final List<String> bindingUuids = context.fetch(bindings).getValues(0, String.class);
            if (!bindingUuids.isEmpty()) {
                context.deleteFrom(LearningFlowOutput.LEARNING_FLOW_OUTPUT)
                        .where(LearningFlowOutput.LEARNING_FLOW_OUTPUT.BINDING_UUID.in(bindingUuids))
                        .execute();
                context.deleteFrom(LearningFlowCorpus.LEARNING_FLOW_CORPUS)
                        .where(LearningFlowCorpus.LEARNING_FLOW_CORPUS.BINDING_UUID.in(bindingUuids))
                        .execute();
                context.deleteFrom(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT)
                        .where(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.BINDING_UUID.in(bindingUuids))
                        .execute();
            }
            context.deleteFrom(LEARNING_FLOW_TURN)
                    .where(LEARNING_FLOW_TURN.FK_ATTEMPT_ID.in(DSL
                            .select(LEARNING_FLOW_ATTEMPT.ID)
                            .from(LEARNING_FLOW_ATTEMPT)
                            .where(LEARNING_FLOW_ATTEMPT.PIPELINE_UUID.eq(pipelineUuid))))
                    .execute();
            context.deleteFrom(LEARNING_FLOW_ATTEMPT)
                    .where(LEARNING_FLOW_ATTEMPT.PIPELINE_UUID.eq(pipelineUuid))
                    .execute();
            context.deleteFrom(LEARNING_FLOW_BINDING)
                    .where(LEARNING_FLOW_BINDING.PIPELINE_UUID.eq(pipelineUuid))
                    .execute();
            context.deleteFrom(LEARNING_FLOW_LEARNER_STATE)
                    .where(LEARNING_FLOW_LEARNER_STATE.PIPELINE_UUID.eq(pipelineUuid))
                    .execute();
            context.deleteFrom(LEARNING_FLOW_GUIDANCE)
                    .where(LEARNING_FLOW_GUIDANCE.PIPELINE_UUID.eq(pipelineUuid))
                    .execute();
            context.deleteFrom(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL)
                    .where(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.PIPELINE_UUID.eq(pipelineUuid))
                    .execute();
            context.deleteFrom(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE)
                    .where(LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE.PIPELINE_UUID.eq(pipelineUuid))
                    .execute();
        });
    }

    /// Whether any learning table holds a row keyed by this pipeline's UUID.
    private static boolean keeps(final DSLContext context, final String pipelineUuid) {
        return context.fetchExists(LEARNING_FLOW_LEARNER_STATE,
                       LEARNING_FLOW_LEARNER_STATE.PIPELINE_UUID.eq(pipelineUuid))
               || context.fetchExists(LEARNING_FLOW_BINDING, LEARNING_FLOW_BINDING.PIPELINE_UUID.eq(pipelineUuid))
               || context.fetchExists(LEARNING_FLOW_GUIDANCE,
                       LEARNING_FLOW_GUIDANCE.PIPELINE_UUID.eq(pipelineUuid))
               || context.fetchExists(LearningFlowDeferral.LEARNING_FLOW_DEFERRAL,
                       LearningFlowDeferral.LEARNING_FLOW_DEFERRAL.PIPELINE_UUID.eq(pipelineUuid))
               || context.fetchExists(LEARNING_FLOW_ATTEMPT,
                       LEARNING_FLOW_ATTEMPT.PIPELINE_UUID.eq(pipelineUuid));
    }
}
