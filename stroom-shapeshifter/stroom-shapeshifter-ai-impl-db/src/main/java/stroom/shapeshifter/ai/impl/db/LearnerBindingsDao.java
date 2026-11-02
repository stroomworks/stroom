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
import stroom.docref.DocRef;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearnerBindings;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.DSLContext;
import org.jooq.Record;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterBinding.SHAPESHIFTER_BINDING;

/// The bindings of A41 and A124 as rows: a learner's binding, and any draft awaiting review to take its
/// place. A promotion is one insert and a rebinding one update, so two nodes promoting for two learners
/// do not contend.
@Singleton
public class LearnerBindingsDao implements LearnerBindings {

    private final ShapeshifterAiDbConnProvider connProvider;

    @Inject
    LearnerBindingsDao(final ShapeshifterAiDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public List<Binding> forLearner(final Learner learner) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select()
                .from(SHAPESHIFTER_BINDING)
                .where(SHAPESHIFTER_BINDING.PIPELINE_UUID.eq(learner.pipelineUuid()))
                .and(SHAPESHIFTER_BINDING.ELEMENT_ID.eq(learner.elementId()))
                .orderBy(SHAPESHIFTER_BINDING.ID)
                .fetch()
                .map(LearnerBindingsDao::binding));
    }

    @Override
    public Optional<Held> byUuid(final String bindingUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select()
                .from(SHAPESHIFTER_BINDING)
                .where(SHAPESHIFTER_BINDING.BINDING_UUID.eq(bindingUuid))
                .fetchOptional()
                .map(record -> new Held(
                        new Learner(record.get(SHAPESHIFTER_BINDING.PIPELINE_UUID),
                                record.get(SHAPESHIFTER_BINDING.ELEMENT_ID)),
                        record.get(SHAPESHIFTER_BINDING.DOC_UUID),
                        binding(record))));
    }

    @Override
    public Binding append(final Learner learner, final String docUuid, final Binding binding) {
        return JooqUtil.contextResult(connProvider, context -> write(context, learner, docUuid, binding));
    }

    /// A binding the learner does not have is appended rather than lost: a promotion must not be dropped
    /// because its row was pruned between the run and the write. Two writers of one binding — a provisional
    /// binding promoted while a person accepts it — are serialised on the row, the second seeing what the first
    /// wrote rather than both succeeding blind.
    @Override
    public void replace(final Learner learner, final String docUuid, final Binding binding) {
        JooqUtil.transaction(connProvider, context -> {
            context.select(SHAPESHIFTER_BINDING.ID)
                    .from(SHAPESHIFTER_BINDING)
                    .where(SHAPESHIFTER_BINDING.BINDING_UUID.eq(binding.getUuid()))
                    .forUpdate()
                    .fetchOptional();
            final int updated = context.update(SHAPESHIFTER_BINDING)
                    .set(SHAPESHIFTER_BINDING.VERSION, SHAPESHIFTER_BINDING.VERSION.plus(1))
                    .set(SHAPESHIFTER_BINDING.UPDATE_TIME_MS, System.currentTimeMillis())
                    .set(SHAPESHIFTER_BINDING.DOC_UUID, docUuid)
                    .set(SHAPESHIFTER_BINDING.FRAGMENT_TYPE, type(binding))
                    .set(SHAPESHIFTER_BINDING.FRAGMENT_UUID, uuid(binding))
                    .set(SHAPESHIFTER_BINDING.FRAGMENT_NAME, name(binding))
                    .set(SHAPESHIFTER_BINDING.DRAFT, binding.isDraft())
                    .set(SHAPESHIFTER_BINDING.PROVISIONAL, binding.isProvisional())
                    .set(SHAPESHIFTER_BINDING.PROMOTED_TIME_MS, binding.getPromotedTimeMs())
                    .set(SHAPESHIFTER_BINDING.SCORE, binding.getScore())
                    .set(SHAPESHIFTER_BINDING.BOUNDARY_ELEMENT, element(binding))
                    .set(SHAPESHIFTER_BINDING.BOUNDARY_ARRAY, array(binding))
                    .set(SHAPESHIFTER_BINDING.BOUNDARY_DEPTH, depth(binding))
                    .set(SHAPESHIFTER_BINDING.SNAPSHOT_ID, binding.getSnapshot())
                    .where(SHAPESHIFTER_BINDING.PIPELINE_UUID.eq(learner.pipelineUuid()))
                    .and(SHAPESHIFTER_BINDING.ELEMENT_ID.eq(learner.elementId()))
                    .and(SHAPESHIFTER_BINDING.BINDING_UUID.eq(binding.getUuid()))
                    .execute();
            if (updated == 0) {
                write(context, learner, docUuid, binding);
            }
        });
    }

    @Override
    public void remove(final Learner learner, final String bindingUuid) {
        JooqUtil.context(connProvider, context -> context
                .deleteFrom(SHAPESHIFTER_BINDING)
                .where(SHAPESHIFTER_BINDING.PIPELINE_UUID.eq(learner.pipelineUuid()))
                .and(SHAPESHIFTER_BINDING.ELEMENT_ID.eq(learner.elementId()))
                .and(SHAPESHIFTER_BINDING.BINDING_UUID.eq(bindingUuid))
                .execute());
    }

    private static Binding write(final DSLContext context,
                                     final Learner learner,
                                     final String docUuid,
                                     final Binding binding) {
        final Binding stored = binding.getUuid() == null
                ? binding.copy().uuid(UUID.randomUUID().toString()).build()
                : binding;
        final long now = System.currentTimeMillis();
        context.insertInto(SHAPESHIFTER_BINDING)
                .set(SHAPESHIFTER_BINDING.VERSION, 1)
                .set(SHAPESHIFTER_BINDING.CREATE_TIME_MS, now)
                .set(SHAPESHIFTER_BINDING.UPDATE_TIME_MS, now)
                .set(SHAPESHIFTER_BINDING.PIPELINE_UUID, learner.pipelineUuid())
                .set(SHAPESHIFTER_BINDING.ELEMENT_ID, learner.elementId())
                .set(SHAPESHIFTER_BINDING.DOC_UUID, docUuid)
                .set(SHAPESHIFTER_BINDING.BINDING_UUID, stored.getUuid())
                .set(SHAPESHIFTER_BINDING.FRAGMENT_TYPE, type(stored))
                .set(SHAPESHIFTER_BINDING.FRAGMENT_UUID, uuid(stored))
                .set(SHAPESHIFTER_BINDING.FRAGMENT_NAME, name(stored))
                .set(SHAPESHIFTER_BINDING.DRAFT, stored.isDraft())
                .set(SHAPESHIFTER_BINDING.PROVISIONAL, stored.isProvisional())
                .set(SHAPESHIFTER_BINDING.PROMOTED_TIME_MS, stored.getPromotedTimeMs())
                .set(SHAPESHIFTER_BINDING.SCORE, stored.getScore())
                .set(SHAPESHIFTER_BINDING.BOUNDARY_ELEMENT, element(stored))
                .set(SHAPESHIFTER_BINDING.BOUNDARY_ARRAY, array(stored))
                .set(SHAPESHIFTER_BINDING.BOUNDARY_DEPTH, depth(stored))
                .set(SHAPESHIFTER_BINDING.SNAPSHOT_ID, stored.getSnapshot())
                .execute();
        return stored;
    }

    private static Binding binding(final Record record) {
        final String fragmentUuid = record.get(SHAPESHIFTER_BINDING.FRAGMENT_UUID);
        final String boundaryElement = record.get(SHAPESHIFTER_BINDING.BOUNDARY_ELEMENT);
        final String boundaryArray = record.get(SHAPESHIFTER_BINDING.BOUNDARY_ARRAY);
        final Integer boundaryDepth = record.get(SHAPESHIFTER_BINDING.BOUNDARY_DEPTH);
        return Binding.builder()
                .uuid(record.get(SHAPESHIFTER_BINDING.BINDING_UUID))
                .pipeline(fragmentUuid == null
                        ? null
                        : new DocRef(record.get(SHAPESHIFTER_BINDING.FRAGMENT_TYPE), fragmentUuid,
                                record.get(SHAPESHIFTER_BINDING.FRAGMENT_NAME)))
                .draft(record.get(SHAPESHIFTER_BINDING.DRAFT))
                .provisional(record.get(SHAPESHIFTER_BINDING.PROVISIONAL))
                .promotedTimeMs(record.get(SHAPESHIFTER_BINDING.PROMOTED_TIME_MS))
                .score(record.get(SHAPESHIFTER_BINDING.SCORE))
                .recordBoundary(boundaryElement != null
                        ? RecordBoundary.ofElement(boundaryElement).atDepth(boundaryDepth)
                        : boundaryArray != null
                                ? RecordBoundary.ofArray(boundaryArray).atDepth(boundaryDepth)
                                : null)
                .snapshot(record.get(SHAPESHIFTER_BINDING.SNAPSHOT_ID))
                .build();
    }

    private static String type(final Binding binding) {
        return binding.getPipeline() == null
                ? null
                : binding.getPipeline().getType();
    }

    private static String uuid(final Binding binding) {
        return binding.getPipeline() == null
                ? null
                : binding.getPipeline().getUuid();
    }

    private static String name(final Binding binding) {
        return binding.getPipeline() == null
                ? null
                : binding.getPipeline().getName();
    }

    private static String element(final Binding binding) {
        return binding.getRecordBoundary() == null
                ? null
                : binding.getRecordBoundary().getElement();
    }

    private static String array(final Binding binding) {
        return binding.getRecordBoundary() == null
                ? null
                : binding.getRecordBoundary().getArray();
    }

    /// How deep the records the boundary names sit, which is part of the boundary and not derivable from
    /// it: a binding read back without it would write a fragment that splits in the wrong place (§12 item
    /// 25).
    private static Integer depth(final Binding binding) {
        return binding.getRecordBoundary() == null
                ? null
                : binding.getRecordBoundary().getDepth();
    }
}
