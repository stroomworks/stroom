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
import stroom.learningflow.stage.Guidance;
import stroom.learningflow.stage.Learner;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

import static stroom.learningflow.impl.db.jooq.tables.LearningFlowGuidance.LEARNING_FLOW_GUIDANCE;

/// The database implementation of [Guidance], which stores the messages people leave for a learner to
/// steer its learning. There is one row per message, keyed by the learner it was left for.
///
/// Guidance is kept in the database so that every node can read it, since the node that puts a message
/// into a question is rarely the node the person was using when they wrote it. It is keyed by learner
/// rather than by attempt, so that it applies to every later attempt too.
@Singleton
public class GuidanceDao implements Guidance {

    private final LearningFlowDbConnProvider connProvider;

    @Inject
    GuidanceDao(final LearningFlowDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public long given(final Learner learner,
                      final String docUuid,
                      final String message,
                      final String author) {
        return JooqUtil.contextResult(connProvider, context -> context
                .insertInto(LEARNING_FLOW_GUIDANCE)
                .set(LEARNING_FLOW_GUIDANCE.CREATE_TIME_MS, System.currentTimeMillis())
                .set(LEARNING_FLOW_GUIDANCE.PIPELINE_UUID, learner.pipelineUuid())
                .set(LEARNING_FLOW_GUIDANCE.ELEMENT_ID, learner.elementId())
                .set(LEARNING_FLOW_GUIDANCE.DOC_UUID, docUuid)
                .set(LEARNING_FLOW_GUIDANCE.MESSAGE, message)
                .set(LEARNING_FLOW_GUIDANCE.AUTHOR, author)
                .returning(LEARNING_FLOW_GUIDANCE.ID)
                .fetchOne(LEARNING_FLOW_GUIDANCE.ID));
    }

    /// Returns the learner's guidance oldest first. The model should read the messages in the order they
    /// were written, so that a later message that corrects an earlier one reads as a correction.
    @Override
    public List<Given> standing(final Learner learner) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select(LEARNING_FLOW_GUIDANCE.ID,
                        LEARNING_FLOW_GUIDANCE.MESSAGE,
                        LEARNING_FLOW_GUIDANCE.AUTHOR,
                        LEARNING_FLOW_GUIDANCE.CREATE_TIME_MS)
                .from(LEARNING_FLOW_GUIDANCE)
                .where(LEARNING_FLOW_GUIDANCE.PIPELINE_UUID.eq(learner.pipelineUuid()))
                .and(LEARNING_FLOW_GUIDANCE.ELEMENT_ID.eq(learner.elementId()))
                .orderBy(LEARNING_FLOW_GUIDANCE.ID)
                .fetch(row -> new Given(
                        row.get(LEARNING_FLOW_GUIDANCE.ID),
                        row.get(LEARNING_FLOW_GUIDANCE.MESSAGE),
                        row.get(LEARNING_FLOW_GUIDANCE.AUTHOR),
                        row.get(LEARNING_FLOW_GUIDANCE.CREATE_TIME_MS))));
    }

    /// By learner and document as well as id, so that an id naming another pipeline's hint cannot take it
    /// back, even under the same document.
    @Override
    public void withdraw(final Learner learner, final String docUuid, final long id) {
        JooqUtil.context(connProvider, context -> context
                .deleteFrom(LEARNING_FLOW_GUIDANCE)
                .where(LEARNING_FLOW_GUIDANCE.PIPELINE_UUID.eq(learner.pipelineUuid()))
                .and(LEARNING_FLOW_GUIDANCE.ELEMENT_ID.eq(learner.elementId()))
                .and(LEARNING_FLOW_GUIDANCE.DOC_UUID.eq(docUuid))
                .and(LEARNING_FLOW_GUIDANCE.ID.eq(id))
                .execute());
    }
}
