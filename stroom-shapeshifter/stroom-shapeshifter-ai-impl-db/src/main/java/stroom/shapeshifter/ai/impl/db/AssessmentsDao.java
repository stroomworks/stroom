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
import stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterAssessment;
import stroom.shapeshifter.ai.impl.db.jooq.tables.records.ShapeshifterAssessmentRecord;
import stroom.shapeshifter.ai.quality.Assessments;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.shared.AssessmentVerdict;
import stroom.shapeshifter.shared.QualityAssessment;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Optional;

/// What QA people have said of each learner's translated output, as rows: one per assessment, keyed by the
/// learner, with the Learning Flow it ran under for who may read it once its pipeline is deleted. An issue is
/// resolved in place, keeping who said it and who resolved it.
@Singleton
public class AssessmentsDao implements Assessments {

    private static final ShapeshifterAssessment ASSESSMENT = ShapeshifterAssessment.SHAPESHIFTER_ASSESSMENT;

    private final ShapeshifterAiDbConnProvider connProvider;

    @Inject
    AssessmentsDao(final ShapeshifterAiDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public List<QualityAssessment> forLearner(final Learner learner) {
        return JooqUtil.contextResult(connProvider, context -> context
                .selectFrom(ASSESSMENT)
                .where(ASSESSMENT.PIPELINE_UUID.eq(learner.pipelineUuid()))
                .and(ASSESSMENT.ELEMENT_ID.eq(learner.elementId()))
                .orderBy(ASSESSMENT.ID.desc())
                .fetch(AssessmentsDao::assessment));
    }

    @Override
    public void add(final Learner learner,
                    final String docUuid,
                    final String bindingUuid,
                    final AssessmentVerdict verdict,
                    final String comment,
                    final String author,
                    final long nowMs) {
        JooqUtil.context(connProvider, context -> context
                .insertInto(ASSESSMENT)
                .set(ASSESSMENT.CREATE_TIME_MS, nowMs)
                .set(ASSESSMENT.PIPELINE_UUID, learner.pipelineUuid())
                .set(ASSESSMENT.ELEMENT_ID, learner.elementId())
                .set(ASSESSMENT.DOC_UUID, docUuid)
                .set(ASSESSMENT.BINDING_UUID, bindingUuid)
                .set(ASSESSMENT.VERDICT, verdict.name())
                .set(ASSESSMENT.COMMENT, comment)
                .set(ASSESSMENT.AUTHOR, author)
                .execute());
    }

    @Override
    public Optional<Held> byId(final long id) {
        return JooqUtil.contextResult(connProvider, context -> context
                .selectFrom(ASSESSMENT)
                .where(ASSESSMENT.ID.eq(id))
                .fetchOptional(row -> new Held(new Learner(row.getPipelineUuid(), row.getElementId()),
                        row.getDocUuid(), assessment(row))));
    }

    /// Only an open issue is resolved, so that resolving twice keeps who resolved it first.
    @Override
    public boolean resolve(final long id, final String by, final long nowMs) {
        return JooqUtil.contextResult(connProvider, context -> context
                .update(ASSESSMENT)
                .set(ASSESSMENT.RESOLVED_BY, by)
                .set(ASSESSMENT.RESOLVE_TIME_MS, nowMs)
                .where(ASSESSMENT.ID.eq(id))
                .and(ASSESSMENT.VERDICT.eq(AssessmentVerdict.ISSUE.name()))
                .and(ASSESSMENT.RESOLVED_BY.isNull())
                .execute()) == 1;
    }

    private static QualityAssessment assessment(final ShapeshifterAssessmentRecord row) {
        return new QualityAssessment(row.getId(), row.getCreateTimeMs(), row.getBindingUuid(),
                AssessmentVerdict.valueOf(row.getVerdict()), row.getComment(), row.getAuthor(),
                row.getResolvedBy(), row.getResolveTimeMs());
    }
}
