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
import stroom.shapeshifter.ai.stage.Guidance;
import stroom.shapeshifter.ai.stage.Learner;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterGuidance.SHAPESHIFTER_GUIDANCE;

/// A supervisor's messages as rows (A46): one per thing said, keyed by the learner it was said to.
///
/// Kept where every node can read it, because the node that carries a hint into a question is rarely
/// the node the person who gave it was talking to — and because a hint outlives the attempt that first
/// used it, which is the point of attaching it to the learner.
@Singleton
public class GuidanceDao implements Guidance {

    private final ShapeshifterAiDbConnProvider connProvider;

    @Inject
    GuidanceDao(final ShapeshifterAiDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public long given(final Learner learner,
                      final String docUuid,
                      final String message,
                      final String author) {
        return JooqUtil.contextResult(connProvider, context -> context
                .insertInto(SHAPESHIFTER_GUIDANCE)
                .set(SHAPESHIFTER_GUIDANCE.CREATE_TIME_MS, System.currentTimeMillis())
                .set(SHAPESHIFTER_GUIDANCE.PIPELINE_UUID, learner.pipelineUuid())
                .set(SHAPESHIFTER_GUIDANCE.ELEMENT_ID, learner.elementId())
                .set(SHAPESHIFTER_GUIDANCE.DOC_UUID, docUuid)
                .set(SHAPESHIFTER_GUIDANCE.MESSAGE, message)
                .set(SHAPESHIFTER_GUIDANCE.AUTHOR, author)
                .returning(SHAPESHIFTER_GUIDANCE.ID)
                .fetchOne(SHAPESHIFTER_GUIDANCE.ID));
    }

    /// Oldest first: the order a person wrote them in is the order they should be read in, and a later
    /// hint that corrects an earlier one only reads as a correction if it comes after it.
    @Override
    public List<Given> standing(final Learner learner) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select(SHAPESHIFTER_GUIDANCE.ID,
                        SHAPESHIFTER_GUIDANCE.MESSAGE,
                        SHAPESHIFTER_GUIDANCE.AUTHOR,
                        SHAPESHIFTER_GUIDANCE.CREATE_TIME_MS)
                .from(SHAPESHIFTER_GUIDANCE)
                .where(SHAPESHIFTER_GUIDANCE.PIPELINE_UUID.eq(learner.pipelineUuid()))
                .and(SHAPESHIFTER_GUIDANCE.ELEMENT_ID.eq(learner.elementId()))
                .orderBy(SHAPESHIFTER_GUIDANCE.ID)
                .fetch(row -> new Given(
                        row.get(SHAPESHIFTER_GUIDANCE.ID),
                        row.get(SHAPESHIFTER_GUIDANCE.MESSAGE),
                        row.get(SHAPESHIFTER_GUIDANCE.AUTHOR),
                        row.get(SHAPESHIFTER_GUIDANCE.CREATE_TIME_MS))));
    }

    /// By document as well as id, so that a mistyped id cannot take back somebody else's hint.
    @Override
    public void withdraw(final String docUuid, final long id) {
        JooqUtil.context(connProvider, context -> context
                .deleteFrom(SHAPESHIFTER_GUIDANCE)
                .where(SHAPESHIFTER_GUIDANCE.DOC_UUID.eq(docUuid))
                .and(SHAPESHIFTER_GUIDANCE.ID.eq(id))
                .execute());
    }
}
