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
import stroom.learningflow.impl.db.jooq.tables.LearningFlowBinding;
import stroom.learningflow.impl.db.jooq.tables.LearningFlowDeferral;
import stroom.learningflow.impl.db.jooq.tables.LearningFlowGuidance;
import stroom.learningflow.impl.db.jooq.tables.LearningFlowLearnerState;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.Learners;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// A learner's rows brought to the document it runs under, in every table that keeps them by learner and
/// records a document beside it. Each update is on the learner's prefix of a key, and touches only the
/// rows that record another document, so a learner that has not moved costs nothing but the lookup.
///
/// The node remembers the document it last brought each learner to, and does nothing while that is still
/// the one: the task decision calls this for every stream, and a write per stream would be the first
/// thing to give at volume. So a learner is brought once per node it runs on after it moves — and a
/// node that met it under the old document and meets it under the new brings it again, which finds
/// nothing left to move.
@Singleton
public class LearnersDao implements Learners {

    private final LearningFlowDbConnProvider connProvider;
    private final Map<Learner, String> brought = new ConcurrentHashMap<>();

    @Inject
    LearnersDao(final LearningFlowDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public void runsUnder(final Learner learner, final String docUuid) {
        if (docUuid.equals(brought.get(learner))) {
            return;
        }
        final LearningFlowBinding binding = LearningFlowBinding.LEARNING_FLOW_BINDING;
        final LearningFlowLearnerState state = LearningFlowLearnerState.LEARNING_FLOW_LEARNER_STATE;
        final LearningFlowGuidance guidance = LearningFlowGuidance.LEARNING_FLOW_GUIDANCE;
        final LearningFlowDeferral deferral = LearningFlowDeferral.LEARNING_FLOW_DEFERRAL;
        JooqUtil.transaction(connProvider, context -> {
            bring(context, binding, binding.PIPELINE_UUID, binding.ELEMENT_ID, binding.DOC_UUID, learner, docUuid);
            bring(context, state, state.PIPELINE_UUID, state.ELEMENT_ID, state.DOC_UUID, learner, docUuid);
            bring(context, guidance, guidance.PIPELINE_UUID, guidance.ELEMENT_ID, guidance.DOC_UUID, learner,
                    docUuid);
            bring(context, deferral, deferral.PIPELINE_UUID, deferral.ELEMENT_ID, deferral.DOC_UUID, learner,
                    docUuid);
        });
        brought.put(learner, docUuid);
    }

    private static void bring(final DSLContext context,
                              final Table<?> table,
                              final Field<String> pipelineUuid,
                              final Field<String> elementId,
                              final Field<String> docUuidField,
                              final Learner learner,
                              final String docUuid) {
        context.update(table)
                .set(docUuidField, docUuid)
                .where(pipelineUuid.eq(learner.pipelineUuid()))
                .and(elementId.eq(learner.elementId()))
                .and(docUuidField.ne(docUuid))
                .execute();
    }
}
