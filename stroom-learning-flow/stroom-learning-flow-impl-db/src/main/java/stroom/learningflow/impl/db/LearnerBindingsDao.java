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

/// The bindings of A41 and A124 as rows: a learner's binding, and any draft awaiting review to take its
/// place. A promotion is one insert and a rebinding one update, so two nodes promoting for two learners
/// do not contend.
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

    /// A learner has at most one binding that serves it (audit finding W7-2). Where one already does,
    /// appending another that would serve it as well returns the one that is there instead of writing a
    /// second: a node whose cache has not yet seen another node's promotion must not add a rival to it.
    ///
    /// The table says the same thing — `learning_flow_binding_live` is unique over the learner while the
    /// binding is not a draft — so two nodes racing here lose the race in the database rather than
    /// between these lines. This is what keeps the loser's work out, with the row it lost to in hand.
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

    /// A binding the learner does not have is appended rather than lost: a promotion must not be dropped
    /// because its row was pruned between the run and the write.
    ///
    /// **Checked against the version the binding was read at** (audit finding W7-3). Two writers of one
    /// binding are serialised on the row, but the lock alone did not stop the second writing every field
    /// back from a copy taken before the first: a node writing a binding from its cache
    /// reverted a rebind another node had made to the pipeline and the snapshot. The version in the
    /// `WHERE` is what makes the second writer see what the first wrote instead of writing over it.
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
                // The row has moved since this copy was read: what is there now is somebody else's more
                // recent work, and a stale copy must not be forced over it.
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
                // What a later replace checks it against (W7-3)
                .version(record.get(LEARNING_FLOW_BINDING.VERSION))
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

    /// The boundary a row holds, in whichever of its three shapes it was written
    /// (`docs/learning-flow-mixed-record-elements.md`).
    ///
    /// @param element  The element that is one record, or null.
    /// @param children The element whose children are records, or null.
    /// @param array    The array whose items are records, or null.
    /// @param depth    How deep a record sits, or null.
    /// @return The boundary, or null where the row holds none.
    private static RecordBoundary recordBoundary(final String element,
                                                 final String children,
                                                 final String array,
                                                 final Integer depth) {
        if (element != null) {
            return RecordBoundary.ofElement(element).atDepth(depth);
        }
        if (children != null) {
            return RecordBoundary.ofChildrenOf(children).atDepth(depth);
        }
        return array == null
                ? null
                : RecordBoundary.ofArray(array).atDepth(depth);
    }

    /// @param binding A binding.
    /// @return The element whose children are its records, or null.
    private static String children(final Binding binding) {
        return binding.getRecordBoundary() == null
                ? null
                : binding.getRecordBoundary().getChildren();
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
