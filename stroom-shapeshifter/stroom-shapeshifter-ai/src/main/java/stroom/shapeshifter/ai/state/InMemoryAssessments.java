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

package stroom.shapeshifter.ai.state;

import stroom.shapeshifter.ai.quality.Assessments;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.shared.AssessmentVerdict;
import stroom.shapeshifter.shared.QualityAssessment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// What QA people have said of each learner, in memory, for a harness without a database: as the node keeps it,
/// newest first per learner, an issue resolved once.
public class InMemoryAssessments implements Assessments {

    private final List<Held> held = new ArrayList<>();
    private long nextId = 1;

    @Override
    public synchronized List<QualityAssessment> forLearner(final Learner learner) {
        final List<QualityAssessment> found = new ArrayList<>();
        for (int i = held.size() - 1; i >= 0; i--) {
            if (held.get(i).learner().equals(learner)) {
                found.add(held.get(i).assessment());
            }
        }
        return found;
    }

    @Override
    public synchronized void add(final Learner learner,
                                 final String docUuid,
                                 final String bindingUuid,
                                 final AssessmentVerdict verdict,
                                 final String comment,
                                 final String author,
                                 final long nowMs) {
        held.add(new Held(learner, docUuid, new QualityAssessment(nextId++, nowMs, bindingUuid, verdict, comment,
                author, null, null)));
    }

    @Override
    public synchronized Optional<Held> byId(final long id) {
        return held.stream().filter(one -> one.assessment().getId() == id).findFirst();
    }

    @Override
    public synchronized boolean resolve(final long id, final String by, final long nowMs) {
        for (int i = 0; i < held.size(); i++) {
            final Held one = held.get(i);
            final QualityAssessment assessment = one.assessment();
            if (assessment.getId() == id
                && assessment.getVerdict() == AssessmentVerdict.ISSUE
                && assessment.getResolvedBy() == null) {
                held.set(i, new Held(one.learner(), one.docUuid(), new QualityAssessment(assessment.getId(),
                        assessment.getCreateTimeMs(), assessment.getBindingUuid(), assessment.getVerdict(),
                        assessment.getComment(), assessment.getAuthor(), by, nowMs)));
                return true;
            }
        }
        return false;
    }
}
