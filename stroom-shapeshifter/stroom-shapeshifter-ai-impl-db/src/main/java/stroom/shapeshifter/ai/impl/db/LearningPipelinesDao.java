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
import stroom.docref.DocRef;
import stroom.pipeline.shared.PipelineDoc;
import stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterDeferral;
import stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterDeletedPipeline;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearningPipelines;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.LearnerRef;
import stroom.shapeshifter.shared.LearnerState;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.SupervisorLearner;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterAttempt.SHAPESHIFTER_ATTEMPT;
import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterBinding.SHAPESHIFTER_BINDING;
import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterGuidance.SHAPESHIFTER_GUIDANCE;
import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterLearnerState.SHAPESHIFTER_LEARNER_STATE;

/// The learning pipelines list (design 06 A115, A142) as one statement over the tables a learner keeps rows
/// in. A learner is any pipeline and element with a state, a binding, a session or a waiting stream; its
/// document is the one its state, then its bindings, then its waiting streams record, which are brought
/// to the document it runs under now, and failing all of those its latest session's. Its state is worked
/// out in the query, so that a page filtered by state is a page of the right rows and its total counts
/// them, and the ordering and the paging are here for the same reason the serving view's are: a feed per
/// pipeline is a learner per feed.
@Singleton
public class LearningPipelinesDao implements LearningPipelines {

    private static final ShapeshifterDeferral DEFERRAL = ShapeshifterDeferral.SHAPESHIFTER_DEFERRAL;
    private static final ShapeshifterDeletedPipeline DELETED =
            ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE;
    private static final List<String> OPEN = List.of(AttemptStatus.IN_PROGRESS.name(),
            AttemptStatus.AWAITING_MODEL.name());
    private static final List<String> FOR_A_PERSON = List.of(AttemptStatus.PARKED.name(),
            AttemptStatus.AWAITING_REVIEW.name());

    private final ShapeshifterAiDbConnProvider connProvider;

    @Inject
    LearningPipelinesDao(final ShapeshifterAiDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public Page learners(final Collection<String> docUuids,
                         final Set<String> learningOff,
                         final Collection<LearnerState> states,
                         final boolean showDeleted,
                         final long offset,
                         final int limit) {
        if (NullSafe.isEmptyCollection(docUuids)) {
            return new Page(List.of(), 0L);
        }
        // Every learner any table knows.
        final Table<?> keys = DSL.select(SHAPESHIFTER_LEARNER_STATE.PIPELINE_UUID.as("p"),
                        SHAPESHIFTER_LEARNER_STATE.ELEMENT_ID.as("e"))
                .from(SHAPESHIFTER_LEARNER_STATE)
                .union(DSL.select(SHAPESHIFTER_BINDING.PIPELINE_UUID, SHAPESHIFTER_BINDING.ELEMENT_ID)
                        .from(SHAPESHIFTER_BINDING))
                .union(DSL.select(SHAPESHIFTER_ATTEMPT.PIPELINE_UUID, SHAPESHIFTER_ATTEMPT.ELEMENT_ID)
                        .from(SHAPESHIFTER_ATTEMPT))
                .union(DSL.select(DEFERRAL.PIPELINE_UUID, DEFERRAL.ELEMENT_ID).from(DEFERRAL))
                .asTable("k");
        final Field<String> pipeline = keys.field("p", String.class);
        final Field<String> element = keys.field("e", String.class);

        final Table<?> serving = SHAPESHIFTER_BINDING.as("serving");
        final Field<String> servingUuid = serving.field(SHAPESHIFTER_BINDING.BINDING_UUID);
        final Table<?> bound = DSL.select(SHAPESHIFTER_BINDING.PIPELINE_UUID.as("p"),
                        SHAPESHIFTER_BINDING.ELEMENT_ID.as("e"),
                        DSL.max(SHAPESHIFTER_BINDING.DOC_UUID).as("doc"))
                .from(SHAPESHIFTER_BINDING)
                .groupBy(SHAPESHIFTER_BINDING.PIPELINE_UUID, SHAPESHIFTER_BINDING.ELEMENT_ID)
                .asTable("bound");
        final Table<?> waits = DSL.select(DEFERRAL.PIPELINE_UUID.as("p"),
                        DEFERRAL.ELEMENT_ID.as("e"),
                        DSL.count().as("n"),
                        DSL.min(DEFERRAL.FIRST_DEFERRED_MS).as("oldest"),
                        DSL.max(DEFERRAL.DOC_UUID).as("doc"))
                .from(DEFERRAL)
                .groupBy(DEFERRAL.PIPELINE_UUID, DEFERRAL.ELEMENT_ID)
                .asTable("waits");
        final Table<?> sessions = DSL.select(SHAPESHIFTER_ATTEMPT.PIPELINE_UUID.as("p"),
                        SHAPESHIFTER_ATTEMPT.ELEMENT_ID.as("e"),
                        DSL.max(SHAPESHIFTER_ATTEMPT.CREATE_TIME_MS).as("last"),
                        DSL.sum(DSL.when(SHAPESHIFTER_ATTEMPT.STATUS.in(OPEN), 1).otherwise(0)).as("open"),
                        DSL.sum(DSL.when(SHAPESHIFTER_ATTEMPT.STATUS.in(FOR_A_PERSON), 1).otherwise(0))
                                .as("person"))
                .from(SHAPESHIFTER_ATTEMPT)
                .groupBy(SHAPESHIFTER_ATTEMPT.PIPELINE_UUID, SHAPESHIFTER_ATTEMPT.ELEMENT_ID)
                .asTable("sessions");
        final Table<?> said = DSL.select(SHAPESHIFTER_GUIDANCE.PIPELINE_UUID.as("p"),
                        SHAPESHIFTER_GUIDANCE.ELEMENT_ID.as("e"),
                        DSL.count().as("said"))
                .from(SHAPESHIFTER_GUIDANCE)
                .groupBy(SHAPESHIFTER_GUIDANCE.PIPELINE_UUID, SHAPESHIFTER_GUIDANCE.ELEMENT_ID)
                .asTable("said");
        // The latest session's document, for a learner nothing else records one for.
        final Field<String> latestDoc = DSL.select(SHAPESHIFTER_ATTEMPT.DOC_UUID)
                .from(SHAPESHIFTER_ATTEMPT)
                .where(SHAPESHIFTER_ATTEMPT.PIPELINE_UUID.eq(pipeline))
                .and(SHAPESHIFTER_ATTEMPT.ELEMENT_ID.eq(element))
                .orderBy(SHAPESHIFTER_ATTEMPT.CREATE_TIME_MS.desc(), SHAPESHIFTER_ATTEMPT.ID.desc())
                .limit(1)
                .asField();
        final Field<String> doc = DSL.coalesce(SHAPESHIFTER_LEARNER_STATE.DOC_UUID,
                bound.field("doc", String.class),
                waits.field("doc", String.class),
                latestDoc);
        final Field<Integer> open = DSL.coalesce(sessions.field("open", Integer.class), 0);
        final Field<Integer> person = DSL.coalesce(sessions.field("person", Integer.class), 0);
        final Field<String> state = DSL
                .when(SHAPESHIFTER_LEARNER_STATE.GIVEN_UP_REASON.isNotNull(), LearnerState.GIVEN_UP.name())
                .when(doc.in(learningOff), LearnerState.LEARNING_OFF.name())
                .when(SHAPESHIFTER_LEARNER_STATE.AWAITING_BINDING_UUID.isNotNull().or(person.gt(0)),
                        LearnerState.AWAITING_PERSON.name())
                .when(servingUuid.isNull().or(open.gt(0)), LearnerState.LEARNING.name())
                .otherwise(LearnerState.SERVING.name());

        final Table<?> view = DSL.select(
                        pipeline.as("p"),
                        element.as("e"),
                        doc.as("doc"),
                        state.as("state"),
                        SHAPESHIFTER_LEARNER_STATE.GIVEN_UP_REASON.as("given_up"),
                        servingUuid.as("binding"),
                        serving.field(SHAPESHIFTER_BINDING.FRAGMENT_TYPE).as("fragment_type"),
                        serving.field(SHAPESHIFTER_BINDING.FRAGMENT_UUID).as("fragment_uuid"),
                        serving.field(SHAPESHIFTER_BINDING.FRAGMENT_NAME).as("fragment_name"),
                        serving.field(SHAPESHIFTER_BINDING.PROVISIONAL).as("provisional"),
                        serving.field(SHAPESHIFTER_BINDING.SCORE).as("promoted_score"),
                        SHAPESHIFTER_LEARNER_STATE.ROLLING_SCORE.as("rolling_score"),
                        DSL.coalesce(SHAPESHIFTER_LEARNER_STATE.ROLLING_RECORDS, 0).as("records"),
                        DSL.coalesce(waits.field("n", Integer.class), 0).as("waiting"),
                        waits.field("oldest", Long.class).as("oldest"),
                        sessions.field("last", Long.class).as("last"),
                        DSL.coalesce(said.field("said", Integer.class), 0).as("said"),
                        DELETED.PIPELINE_UUID.isNotNull().as("deleted"))
                .from(keys)
                .leftJoin(SHAPESHIFTER_LEARNER_STATE)
                .on(SHAPESHIFTER_LEARNER_STATE.PIPELINE_UUID.eq(pipeline))
                .and(SHAPESHIFTER_LEARNER_STATE.ELEMENT_ID.eq(element))
                .leftJoin(serving)
                .on(serving.field(SHAPESHIFTER_BINDING.PIPELINE_UUID).eq(pipeline))
                .and(serving.field(SHAPESHIFTER_BINDING.ELEMENT_ID).eq(element))
                .and(serving.field(SHAPESHIFTER_BINDING.DRAFT).isFalse())
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
                    // Most waiting first, then the latest session — a learner that has never had one last —
                    // and the learner itself, so that a page turned twice reads the same twice.
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

    /// The list's document for one learner: the same order of tables as the list's, asked of that learner
    /// alone, rather than the list's statement narrowed, which would group every table to answer it.
    @Override
    public Optional<String> documentOf(final Learner learner) {
        final String pipelineUuid = learner.pipelineUuid();
        final String elementId = learner.elementId();
        final Field<String> stated = DSL.select(SHAPESHIFTER_LEARNER_STATE.DOC_UUID)
                .from(SHAPESHIFTER_LEARNER_STATE)
                .where(SHAPESHIFTER_LEARNER_STATE.PIPELINE_UUID.eq(pipelineUuid))
                .and(SHAPESHIFTER_LEARNER_STATE.ELEMENT_ID.eq(elementId))
                .asField();
        final Field<String> bound = DSL.select(DSL.max(SHAPESHIFTER_BINDING.DOC_UUID))
                .from(SHAPESHIFTER_BINDING)
                .where(SHAPESHIFTER_BINDING.PIPELINE_UUID.eq(pipelineUuid))
                .and(SHAPESHIFTER_BINDING.ELEMENT_ID.eq(elementId))
                .asField();
        final Field<String> waiting = DSL.select(DSL.max(DEFERRAL.DOC_UUID))
                .from(DEFERRAL)
                .where(DEFERRAL.PIPELINE_UUID.eq(pipelineUuid))
                .and(DEFERRAL.ELEMENT_ID.eq(elementId))
                .asField();
        final Field<String> latest = DSL.select(SHAPESHIFTER_ATTEMPT.DOC_UUID)
                .from(SHAPESHIFTER_ATTEMPT)
                .where(SHAPESHIFTER_ATTEMPT.PIPELINE_UUID.eq(pipelineUuid))
                .and(SHAPESHIFTER_ATTEMPT.ELEMENT_ID.eq(elementId))
                .orderBy(SHAPESHIFTER_ATTEMPT.CREATE_TIME_MS.desc(), SHAPESHIFTER_ATTEMPT.ID.desc())
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
                ShapeshifterAiDoc.buildDocRef().uuid(row.get("doc", String.class)).build(),
                LearnerState.valueOf(row.get("state", String.class)),
                row.get("given_up", String.class),
                row.get("binding", String.class),
                fragmentUuid == null
                        ? null
                        : new DocRef(row.get("fragment_type", String.class), fragmentUuid,
                                row.get("fragment_name", String.class)),
                Boolean.TRUE.equals(row.get("provisional", Boolean.class)),
                row.get("promoted_score", Double.class),
                // No rolling score until it has served something: shown as nothing rather than as zero.
                records == 0
                        ? null
                        : row.get("rolling_score", Double.class),
                records,
                row.get("waiting", Integer.class),
                row.get("oldest", Long.class),
                false,
                row.get("last", Long.class),
                row.get("said", Integer.class),
                Boolean.TRUE.equals(row.get("deleted", Boolean.class)),
                false);
    }
}
