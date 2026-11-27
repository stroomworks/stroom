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
import stroom.docref.DocRef;
import stroom.learningflow.impl.db.jooq.tables.LearningFlowDeferral;
import stroom.learningflow.impl.db.jooq.tables.LearningFlowDeletedPipeline;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.LearnerRef;
import stroom.learningflow.shared.LearnerState;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearningPipelines;
import stroom.pipeline.shared.PipelineDoc;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static stroom.learningflow.impl.db.jooq.tables.LearningFlowAttempt.LEARNING_FLOW_ATTEMPT;
import static stroom.learningflow.impl.db.jooq.tables.LearningFlowBinding.LEARNING_FLOW_BINDING;
import static stroom.learningflow.impl.db.jooq.tables.LearningFlowGuidance.LEARNING_FLOW_GUIDANCE;
import static stroom.learningflow.impl.db.jooq.tables.LearningFlowLearnerState.LEARNING_FLOW_LEARNER_STATE;

/// The database implementation of [LearningPipelines], which produces the Supervisor's list of learning
/// pipelines with one query over the tables that hold learners' rows.
///
/// A learner is any pipeline and element that has a state, a binding, a session or a waiting stream. Its
/// document is taken from its state row, failing that from its bindings, then from its waiting streams (all
/// of which are kept up to date with the document it runs under), and failing all of those from its latest
/// session. Its [LearnerState] is worked out in the query, so that a page filtered by state contains the
/// right rows and its total counts them. Ordering and paging are done in the database because there is one
/// pipeline, and so one learner, per feed, which can be a great many.
@Singleton
public class LearningPipelinesDao implements LearningPipelines {

    private static final LearningFlowDeferral DEFERRAL = LearningFlowDeferral.LEARNING_FLOW_DEFERRAL;
    private static final LearningFlowDeletedPipeline DELETED =
            LearningFlowDeletedPipeline.LEARNING_FLOW_DELETED_PIPELINE;
    private static final List<String> OPEN = List.of(AttemptStatus.IN_PROGRESS.name(),
            AttemptStatus.AWAITING_MODEL.name());
    private static final List<String> FOR_A_PERSON = List.of(AttemptStatus.PARKED.name(),
            AttemptStatus.AWAITING_REVIEW.name());

    private final LearningFlowDbConnProvider connProvider;

    @Inject
    LearningPipelinesDao(final LearningFlowDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public Page learners(final Collection<String> docUuids,
                         final Set<String> learningOff,
                         final Collection<LearnerState> states,
                         final boolean showDeleted,
                         final Collection<String> pipelineUuids,
                         final long offset,
                         final int limit) {
        if (NullSafe.isEmptyCollection(docUuids) || (pipelineUuids != null && pipelineUuids.isEmpty())) {
            return new Page(List.of(), 0L);
        }
        // Every learner that any of the tables has a row for.
        final Table<?> keys = DSL.select(LEARNING_FLOW_LEARNER_STATE.PIPELINE_UUID.as("p"),
                        LEARNING_FLOW_LEARNER_STATE.ELEMENT_ID.as("e"))
                .from(LEARNING_FLOW_LEARNER_STATE)
                .union(DSL.select(LEARNING_FLOW_BINDING.PIPELINE_UUID, LEARNING_FLOW_BINDING.ELEMENT_ID)
                        .from(LEARNING_FLOW_BINDING))
                .union(DSL.select(LEARNING_FLOW_ATTEMPT.PIPELINE_UUID, LEARNING_FLOW_ATTEMPT.ELEMENT_ID)
                        .from(LEARNING_FLOW_ATTEMPT))
                .union(DSL.select(DEFERRAL.PIPELINE_UUID, DEFERRAL.ELEMENT_ID).from(DEFERRAL))
                .asTable("k");
        final Field<String> pipeline = keys.field("p", String.class);
        final Field<String> element = keys.field("e", String.class);

        final Table<?> serving = LEARNING_FLOW_BINDING.as("serving");
        final Field<String> servingUuid = serving.field(LEARNING_FLOW_BINDING.BINDING_UUID);
        final Table<?> bound = DSL.select(LEARNING_FLOW_BINDING.PIPELINE_UUID.as("p"),
                        LEARNING_FLOW_BINDING.ELEMENT_ID.as("e"),
                        DSL.max(LEARNING_FLOW_BINDING.DOC_UUID).as("doc"))
                .from(LEARNING_FLOW_BINDING)
                .groupBy(LEARNING_FLOW_BINDING.PIPELINE_UUID, LEARNING_FLOW_BINDING.ELEMENT_ID)
                .asTable("bound");
        final Table<?> waits = DSL.select(DEFERRAL.PIPELINE_UUID.as("p"),
                        DEFERRAL.ELEMENT_ID.as("e"),
                        DSL.count().as("n"),
                        DSL.min(DEFERRAL.FIRST_DEFERRED_MS).as("oldest"),
                        DSL.max(DEFERRAL.DOC_UUID).as("doc"))
                .from(DEFERRAL)
                .groupBy(DEFERRAL.PIPELINE_UUID, DEFERRAL.ELEMENT_ID)
                .asTable("waits");
        final Table<?> sessions = DSL.select(LEARNING_FLOW_ATTEMPT.PIPELINE_UUID.as("p"),
                        LEARNING_FLOW_ATTEMPT.ELEMENT_ID.as("e"),
                        DSL.max(LEARNING_FLOW_ATTEMPT.CREATE_TIME_MS).as("last"),
                        DSL.sum(DSL.when(LEARNING_FLOW_ATTEMPT.STATUS.in(OPEN), 1).otherwise(0)).as("open"),
                        DSL.sum(DSL.when(LEARNING_FLOW_ATTEMPT.STATUS.in(FOR_A_PERSON), 1).otherwise(0))
                                .as("person"))
                .from(LEARNING_FLOW_ATTEMPT)
                .groupBy(LEARNING_FLOW_ATTEMPT.PIPELINE_UUID, LEARNING_FLOW_ATTEMPT.ELEMENT_ID)
                .asTable("sessions");
        final Table<?> said = DSL.select(LEARNING_FLOW_GUIDANCE.PIPELINE_UUID.as("p"),
                        LEARNING_FLOW_GUIDANCE.ELEMENT_ID.as("e"),
                        DSL.count().as("said"))
                .from(LEARNING_FLOW_GUIDANCE)
                .groupBy(LEARNING_FLOW_GUIDANCE.PIPELINE_UUID, LEARNING_FLOW_GUIDANCE.ELEMENT_ID)
                .asTable("said");
        // The latest session's document, for a learner that has no other row recording one.
        final Field<String> latestDoc = DSL.select(LEARNING_FLOW_ATTEMPT.DOC_UUID)
                .from(LEARNING_FLOW_ATTEMPT)
                .where(LEARNING_FLOW_ATTEMPT.PIPELINE_UUID.eq(pipeline))
                .and(LEARNING_FLOW_ATTEMPT.ELEMENT_ID.eq(element))
                .orderBy(LEARNING_FLOW_ATTEMPT.CREATE_TIME_MS.desc(), LEARNING_FLOW_ATTEMPT.ID.desc())
                .limit(1)
                .asField();
        final Field<String> doc = DSL.coalesce(LEARNING_FLOW_LEARNER_STATE.DOC_UUID,
                bound.field("doc", String.class),
                waits.field("doc", String.class),
                latestDoc);
        final Field<Integer> open = DSL.coalesce(sessions.field("open", Integer.class), 0);
        final Field<Integer> person = DSL.coalesce(sessions.field("person", Integer.class), 0);
        final Field<String> state = DSL
                .when(LEARNING_FLOW_LEARNER_STATE.GIVEN_UP_REASON.isNotNull(), LearnerState.GIVEN_UP.name())
                .when(doc.in(learningOff), LearnerState.LEARNING_OFF.name())
                .when(LEARNING_FLOW_LEARNER_STATE.AWAITING_BINDING_UUID.isNotNull().or(person.gt(0)),
                        LearnerState.AWAITING_PERSON.name())
                .when(servingUuid.isNull().or(open.gt(0)), LearnerState.LEARNING.name())
                .otherwise(LearnerState.SERVING.name());

        final Table<?> view = DSL.select(
                        pipeline.as("p"),
                        element.as("e"),
                        doc.as("doc"),
                        state.as("state"),
                        LEARNING_FLOW_LEARNER_STATE.GIVEN_UP_REASON.as("given_up"),
                        servingUuid.as("binding"),
                        serving.field(LEARNING_FLOW_BINDING.FRAGMENT_TYPE).as("fragment_type"),
                        serving.field(LEARNING_FLOW_BINDING.FRAGMENT_UUID).as("fragment_uuid"),
                        serving.field(LEARNING_FLOW_BINDING.FRAGMENT_NAME).as("fragment_name"),
                        serving.field(LEARNING_FLOW_BINDING.SCORE).as("promoted_score"),
                        LEARNING_FLOW_LEARNER_STATE.ROLLING_SCORE.as("rolling_score"),
                        DSL.coalesce(LEARNING_FLOW_LEARNER_STATE.ROLLING_RECORDS, 0).as("records"),
                        DSL.coalesce(LEARNING_FLOW_LEARNER_STATE.ROLLING_SKIPS, 0).as("skips"),
                        DSL.coalesce(waits.field("n", Integer.class), 0).as("waiting"),
                        waits.field("oldest", Long.class).as("oldest"),
                        sessions.field("last", Long.class).as("last"),
                        DSL.coalesce(said.field("said", Integer.class), 0).as("said"),
                        DELETED.PIPELINE_UUID.isNotNull().as("deleted"))
                .from(keys)
                .leftJoin(LEARNING_FLOW_LEARNER_STATE)
                .on(LEARNING_FLOW_LEARNER_STATE.PIPELINE_UUID.eq(pipeline))
                .and(LEARNING_FLOW_LEARNER_STATE.ELEMENT_ID.eq(element))
                .leftJoin(serving)
                .on(serving.field(LEARNING_FLOW_BINDING.PIPELINE_UUID).eq(pipeline))
                .and(serving.field(LEARNING_FLOW_BINDING.ELEMENT_ID).eq(element))
                .and(serving.field(LEARNING_FLOW_BINDING.DRAFT).isFalse())
                .leftJoin(bound)
                .on(bound.field("p", String.class).eq(pipeline))
                .and(bound.field("e", String.class).eq(element))
                .leftJoin(waits)
                .on(waits.field("p", String.class).eq(pipeline))
                .and(waits.field("e", String.class).eq(element))
                .leftJoin(sessions)
                .on(sessions.field("p", String.class).eq(pipeline))
                .and(sessions.field("e", String.class).eq(element))
                .leftJoin(said)
                .on(said.field("p", String.class).eq(pipeline))
                .and(said.field("e", String.class).eq(element))
                .leftJoin(DELETED)
                .on(DELETED.PIPELINE_UUID.eq(pipeline))
                .asTable("v");

        Condition matching = view.field("doc", String.class).in(docUuids);
        if (!showDeleted) {
            matching = matching.and(view.field("deleted", Boolean.class).isFalse());
        }
        if (pipelineUuids != null) {
            matching = matching.and(view.field("p", String.class).in(pipelineUuids));
        }
        if (!NullSafe.isEmptyCollection(states)) {
            matching = matching.and(view.field("state", String.class)
                    .in(states.stream().map(LearnerState::name).toList()));
        }
        final Condition where = matching;
        return JooqUtil.contextResult(connProvider, context -> {
            final List<SupervisorLearner> rows = context
                    .select()
                    .from(view)
                    .where(where)
                    // Most waiting streams first, then the most recent session (learners with none come
                    // last), then the learner's key, so that the order is stable from page to page.
                    .orderBy(view.field("waiting").desc(),
                            view.field("last").desc(),
                            view.field("p").asc(),
                            view.field("e").asc())
                    .limit(offset, limit)
                    .fetch(LearningPipelinesDao::learner);
            final long total = context.fetchCount(DSL.selectFrom(view).where(where));
            return new Page(rows, total);
        });
    }

    @Override
    public Set<String> pipelines() {
        return JooqUtil.contextResult(connProvider, context -> new HashSet<>(context
                .select(LEARNING_FLOW_LEARNER_STATE.PIPELINE_UUID)
                .from(LEARNING_FLOW_LEARNER_STATE)
                .union(DSL.select(LEARNING_FLOW_BINDING.PIPELINE_UUID).from(LEARNING_FLOW_BINDING))
                .union(DSL.select(LEARNING_FLOW_ATTEMPT.PIPELINE_UUID).from(LEARNING_FLOW_ATTEMPT))
                .union(DSL.select(DEFERRAL.PIPELINE_UUID).from(DEFERRAL))
                .fetch(0, String.class)));
    }

    /// Returns the document a learner is listed under, looking in the tables in the same order as
    /// [#learners]. It uses its own query for the one learner, because narrowing the list's query would
    /// still group every table.
    @Override
    public Optional<String> documentOf(final Learner learner) {
        final String pipelineUuid = learner.pipelineUuid();
        final String elementId = learner.elementId();
        final Field<String> stated = DSL.select(LEARNING_FLOW_LEARNER_STATE.DOC_UUID)
                .from(LEARNING_FLOW_LEARNER_STATE)
                .where(LEARNING_FLOW_LEARNER_STATE.PIPELINE_UUID.eq(pipelineUuid))
                .and(LEARNING_FLOW_LEARNER_STATE.ELEMENT_ID.eq(elementId))
                .asField();
        final Field<String> bound = DSL.select(DSL.max(LEARNING_FLOW_BINDING.DOC_UUID))
                .from(LEARNING_FLOW_BINDING)
                .where(LEARNING_FLOW_BINDING.PIPELINE_UUID.eq(pipelineUuid))
                .and(LEARNING_FLOW_BINDING.ELEMENT_ID.eq(elementId))
                .asField();
        final Field<String> waiting = DSL.select(DSL.max(DEFERRAL.DOC_UUID))
                .from(DEFERRAL)
                .where(DEFERRAL.PIPELINE_UUID.eq(pipelineUuid))
                .and(DEFERRAL.ELEMENT_ID.eq(elementId))
                .asField();
        final Field<String> latest = DSL.select(LEARNING_FLOW_ATTEMPT.DOC_UUID)
                .from(LEARNING_FLOW_ATTEMPT)
                .where(LEARNING_FLOW_ATTEMPT.PIPELINE_UUID.eq(pipelineUuid))
                .and(LEARNING_FLOW_ATTEMPT.ELEMENT_ID.eq(elementId))
                .orderBy(LEARNING_FLOW_ATTEMPT.CREATE_TIME_MS.desc(), LEARNING_FLOW_ATTEMPT.ID.desc())
                .limit(1)
                .asField();
        return Optional.ofNullable(JooqUtil.contextResult(connProvider, context -> context
                .select(DSL.coalesce(stated, bound, waiting, latest))
                .fetchOne(0, String.class)));
    }

    private static SupervisorLearner learner(final Record row) {
        final String fragmentUuid = row.get("fragment_uuid", String.class);
        final int records = row.get("records", Integer.class);
        return new SupervisorLearner(
                new LearnerRef(row.get("p", String.class), row.get("e", String.class)),
                new DocRef(PipelineDoc.TYPE, row.get("p", String.class)),
                LearningFlowDoc.buildDocRef().uuid(row.get("doc", String.class)).build(),
                LearnerState.valueOf(row.get("state", String.class)),
                row.get("given_up", String.class),
                row.get("binding", String.class),
                fragmentUuid == null
                        ? null
                        : new DocRef(row.get("fragment_type", String.class), fragmentUuid,
                                row.get("fragment_name", String.class)),
                row.get("promoted_score", Double.class),
                // A learner that has not served anything has no rolling score; it is shown as blank rather
                // than as zero.
                records == 0
                        ? null
                        : row.get("rolling_score", Double.class),
                records,
                row.get("skips", Integer.class),
                row.get("waiting", Integer.class),
                row.get("oldest", Long.class),
                false,
                row.get("last", Long.class),
                row.get("said", Integer.class),
                Boolean.TRUE.equals(row.get("deleted", Boolean.class)),
                false);
    }
}
