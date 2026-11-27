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

package stroom.learningflow.impl.db;

import stroom.db.util.JooqUtil;
import stroom.docref.DocRef;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerBindings;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.DSLContext;
import org.jooq.Record;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static stroom.learningflow.impl.db.jooq.tables.LearningFlowBinding.LEARNING_FLOW_BINDING;

/// The database implementation of [LearnerBindings], which stores what serves each learner: its live
/// binding and any draft awaiting review to replace it.
///
/// Bindings are kept in their own table rather than in the Learning Flow document, which holds only what a
/// person authors. A promotion is one insert and a rebinding one update, so two nodes promoting for two
/// different learners do not contend with each other.
@Singleton
public class LearnerBindingsDao implements LearnerBindings {

    private final LearningFlowDbConnProvider connProvider;

    @Inject
    LearnerBindingsDao(final LearningFlowDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public List<Binding> forLearner(final Learner learner) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select()
                .from(LEARNING_FLOW_BINDING)
                .where(LEARNING_FLOW_BINDING.PIPELINE_UUID.eq(learner.pipelineUuid()))
                .and(LEARNING_FLOW_BINDING.ELEMENT_ID.eq(learner.elementId()))
                .orderBy(LEARNING_FLOW_BINDING.ID)
                .fetch()
                .map(LearnerBindingsDao::binding));
    }

    @Override
    public Optional<Held> byUuid(final String bindingUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select()
                .from(LEARNING_FLOW_BINDING)
                .where(LEARNING_FLOW_BINDING.BINDING_UUID.eq(bindingUuid))
                .fetchOptional()
                .map(record -> new Held(
                        new Learner(record.get(LEARNING_FLOW_BINDING.PIPELINE_UUID),
                                record.get(LEARNING_FLOW_BINDING.ELEMENT_ID)),
                        record.get(LEARNING_FLOW_BINDING.DOC_UUID),
                        binding(record))));
    }

    /// Adds a binding for a learner. A learner has at most one live (non-draft) binding, so if it already
    /// has a different one, that existing binding is returned and nothing is written. This stops a node
    /// whose cache has not yet seen another node's promotion from adding a second live binding.
    ///
    /// The table enforces the same rule: the `learning_flow_binding_live` unique key covers the learner
    /// while a binding is not a draft, so if two nodes race here the database rejects the second insert.
    /// The check in this method means the losing node gets back the binding it lost to rather than an
    /// error.
    @Override
    public Binding append(final Learner learner, final String docUuid, final Binding binding) {
        return JooqUtil.transactionResult(connProvider, context -> {
            if (!binding.isDraft()) {
                final Optional<Binding> live = context.selectFrom(LEARNING_FLOW_BINDING)
                        .where(LEARNING_FLOW_BINDING.PIPELINE_UUID.eq(learner.pipelineUuid()))
                        .and(LEARNING_FLOW_BINDING.ELEMENT_ID.eq(learner.elementId()))
                        .and(LEARNING_FLOW_BINDING.DRAFT.isFalse())
                        .forUpdate()
                        .fetchOptional()
                        .map(LearnerBindingsDao::binding);
                if (live.isPresent() && !live.get().getUuid().equals(binding.getUuid())) {
                    return live.get();
                }
            }
            return write(context, learner, docUuid, binding);
        });
    }

    /// Updates a learner's binding. If the learner does not have it, it is inserted instead, so that a
    /// promotion is not lost because its row was removed between the run and the write.
    ///
    /// **The update is checked against the version the binding was read at.** Two writers of one binding
    /// are serialised by the row lock, but the lock alone would let the second writer overwrite every field
    /// with a copy taken before the first writer's change. For example, a node writing a binding from its
    /// cache could revert another node's change to the binding's pipeline and snapshot. If the version has
    /// moved on, nothing is written and false is returned.
    @Override
    public boolean replace(final Learner learner, final String docUuid, final Binding binding) {
        return JooqUtil.transactionResult(connProvider, context -> {
            final Optional<Integer> current = context.select(LEARNING_FLOW_BINDING.VERSION)
                    .from(LEARNING_FLOW_BINDING)
                    .where(LEARNING_FLOW_BINDING.BINDING_UUID.eq(binding.getUuid()))
                    .forUpdate()
                    .fetchOptional(LEARNING_FLOW_BINDING.VERSION);
            if (current.isPresent() && binding.getVersion() != null
                && !binding.getVersion().equals(current.get())) {
                // The row has changed since this copy was read. The stored row is more recent, so the
                // stale copy must not overwrite it.
                return false;
            }
            final int updated = context.update(LEARNING_FLOW_BINDING)
                    .set(LEARNING_FLOW_BINDING.VERSION, LEARNING_FLOW_BINDING.VERSION.plus(1))
                    .set(LEARNING_FLOW_BINDING.UPDATE_TIME_MS, System.currentTimeMillis())
                    .set(LEARNING_FLOW_BINDING.DOC_UUID, docUuid)
                    .set(LEARNING_FLOW_BINDING.FRAGMENT_TYPE, type(binding))
                    .set(LEARNING_FLOW_BINDING.FRAGMENT_UUID, uuid(binding))
                    .set(LEARNING_FLOW_BINDING.FRAGMENT_NAME, name(binding))
                    .set(LEARNING_FLOW_BINDING.DRAFT, binding.isDraft())
                    .set(LEARNING_FLOW_BINDING.PROMOTED_TIME_MS, binding.getPromotedTimeMs())
                    .set(LEARNING_FLOW_BINDING.SCORE, binding.getScore())
                    .set(LEARNING_FLOW_BINDING.BOUNDARY_ELEMENT, element(binding))
                    .set(LEARNING_FLOW_BINDING.BOUNDARY_CHILDREN, children(binding))
                    .set(LEARNING_FLOW_BINDING.BOUNDARY_ARRAY, array(binding))
                    .set(LEARNING_FLOW_BINDING.BOUNDARY_DEPTH, depth(binding))
                    .set(LEARNING_FLOW_BINDING.SNAPSHOT_ID, binding.getSnapshot())
                    .where(LEARNING_FLOW_BINDING.PIPELINE_UUID.eq(learner.pipelineUuid()))
                    .and(LEARNING_FLOW_BINDING.ELEMENT_ID.eq(learner.elementId()))
                    .and(LEARNING_FLOW_BINDING.BINDING_UUID.eq(binding.getUuid()))
                    .execute();
            if (updated == 0) {
                write(context, learner, docUuid, binding);
            }
            return true;
        });
    }

    @Override
    public void remove(final Learner learner, final String bindingUuid) {
        JooqUtil.context(connProvider, context -> context
                .deleteFrom(LEARNING_FLOW_BINDING)
                .where(LEARNING_FLOW_BINDING.PIPELINE_UUID.eq(learner.pipelineUuid()))
                .and(LEARNING_FLOW_BINDING.ELEMENT_ID.eq(learner.elementId()))
                .and(LEARNING_FLOW_BINDING.BINDING_UUID.eq(bindingUuid))
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
        context.insertInto(LEARNING_FLOW_BINDING)
                .set(LEARNING_FLOW_BINDING.VERSION, 1)
                .set(LEARNING_FLOW_BINDING.CREATE_TIME_MS, now)
                .set(LEARNING_FLOW_BINDING.UPDATE_TIME_MS, now)
                .set(LEARNING_FLOW_BINDING.PIPELINE_UUID, learner.pipelineUuid())
                .set(LEARNING_FLOW_BINDING.ELEMENT_ID, learner.elementId())
                .set(LEARNING_FLOW_BINDING.DOC_UUID, docUuid)
                .set(LEARNING_FLOW_BINDING.BINDING_UUID, stored.getUuid())
                .set(LEARNING_FLOW_BINDING.FRAGMENT_TYPE, type(stored))
                .set(LEARNING_FLOW_BINDING.FRAGMENT_UUID, uuid(stored))
                .set(LEARNING_FLOW_BINDING.FRAGMENT_NAME, name(stored))
                .set(LEARNING_FLOW_BINDING.DRAFT, stored.isDraft())
                .set(LEARNING_FLOW_BINDING.PROMOTED_TIME_MS, stored.getPromotedTimeMs())
                .set(LEARNING_FLOW_BINDING.SCORE, stored.getScore())
                .set(LEARNING_FLOW_BINDING.BOUNDARY_ELEMENT, element(stored))
                .set(LEARNING_FLOW_BINDING.BOUNDARY_CHILDREN, children(stored))
                .set(LEARNING_FLOW_BINDING.BOUNDARY_ARRAY, array(stored))
                .set(LEARNING_FLOW_BINDING.BOUNDARY_DEPTH, depth(stored))
                .set(LEARNING_FLOW_BINDING.SNAPSHOT_ID, stored.getSnapshot())
                .execute();
        return stored;
    }

    private static Binding binding(final Record record) {
        final String fragmentUuid = record.get(LEARNING_FLOW_BINDING.FRAGMENT_UUID);
        final String boundaryElement = record.get(LEARNING_FLOW_BINDING.BOUNDARY_ELEMENT);
        final String boundaryChildren = record.get(LEARNING_FLOW_BINDING.BOUNDARY_CHILDREN);
        final String boundaryArray = record.get(LEARNING_FLOW_BINDING.BOUNDARY_ARRAY);
        final Integer boundaryDepth = record.get(LEARNING_FLOW_BINDING.BOUNDARY_DEPTH);
        return Binding.builder()
                .uuid(record.get(LEARNING_FLOW_BINDING.BINDING_UUID))
                .pipeline(fragmentUuid == null
                        ? null
                        : new DocRef(record.get(LEARNING_FLOW_BINDING.FRAGMENT_TYPE), fragmentUuid,
                                record.get(LEARNING_FLOW_BINDING.FRAGMENT_NAME)))
                .draft(record.get(LEARNING_FLOW_BINDING.DRAFT))
                .promotedTimeMs(record.get(LEARNING_FLOW_BINDING.PROMOTED_TIME_MS))
                .score(record.get(LEARNING_FLOW_BINDING.SCORE))
                .recordBoundary(recordBoundary(boundaryElement, boundaryChildren, boundaryArray,
                        boundaryDepth))
                .snapshot(record.get(LEARNING_FLOW_BINDING.SNAPSHOT_ID))
                // The version that a later replace checks against.
                .version(record.get(LEARNING_FLOW_BINDING.VERSION))
                .build();
    }

    private static String type(final Binding binding) {
        return NullSafe.get(binding, Binding::getPipeline, DocRef::getType);
    }

    private static String uuid(final Binding binding) {
        return NullSafe.get(binding, Binding::getPipeline, DocRef::getUuid);
    }

    private static String name(final Binding binding) {
        return NullSafe.get(binding, Binding::getPipeline, DocRef::getName);
    }

    /// Rebuilds a record boundary from its four columns, whichever of its three forms (element, children
    /// or array) it was written in. It is also used by [OutputsDao], which stores a boundary the same way.
    ///
    /// @param element  The element that is one record, or null.
    /// @param children The element whose children are records, or null.
    /// @param array    The array whose items are records, or null.
    /// @param depth    How deep a record sits, or null.
    /// @return The boundary, or null where the row holds none.
    static RecordBoundary recordBoundary(final String element,
                                         final String children,
                                         final String array,
                                         final Integer depth) {
        if (element == null && children == null && array == null) {
            return null;
        }
        return new RecordBoundary(element, children, array, depth);
    }

    /// @param binding A binding.
    /// @return The element whose children are its records, or null.
    private static String children(final Binding binding) {
        return NullSafe.get(binding, Binding::getRecordBoundary, RecordBoundary::getChildren);
    }

    private static String element(final Binding binding) {
        return NullSafe.get(binding, Binding::getRecordBoundary, RecordBoundary::getElement);
    }

    private static String array(final Binding binding) {
        return NullSafe.get(binding, Binding::getRecordBoundary, RecordBoundary::getArray);
    }

    /// How deep the records named by the boundary sit. This cannot be derived from the rest of the boundary,
    /// so it must be stored: a binding read back without it would produce a fragment that splits the input
    /// in the wrong place.
    private static Integer depth(final Binding binding) {
        return NullSafe.get(binding, Binding::getRecordBoundary, RecordBoundary::getDepth);
    }
}
