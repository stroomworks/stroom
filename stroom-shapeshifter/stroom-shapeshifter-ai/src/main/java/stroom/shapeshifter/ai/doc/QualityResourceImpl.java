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

package stroom.shapeshifter.ai.doc;

import stroom.event.logging.rs.api.AutoLogged;
import stroom.event.logging.rs.api.AutoLogged.OperationType;
import stroom.shapeshifter.ai.quality.QualityService;
import stroom.shapeshifter.shared.AssessmentRequest;
import stroom.shapeshifter.shared.QualityAssessment;
import stroom.shapeshifter.shared.QualityReport;
import stroom.shapeshifter.shared.QualityResource;
import stroom.shapeshifter.shared.QualitySample;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.List;

/// A Smart Pipeline's Quality Assurance tab's resource ([QualityResource]): what a QA person reads and records
/// of one pipeline's translated output, through [QualityService], which holds it to the pipeline's permissions.
@AutoLogged
public class QualityResourceImpl implements QualityResource {

    private final Provider<QualityService> qualityServiceProvider;

    @Inject
    QualityResourceImpl(final Provider<QualityService> qualityServiceProvider) {
        this.qualityServiceProvider = qualityServiceProvider;
    }

    @Override
    @AutoLogged(OperationType.VIEW)
    public QualityReport fetchReport(final String pipelineUuid, final String elementId) {
        return qualityServiceProvider.get().report(pipelineUuid, elementId);
    }

    @Override
    @AutoLogged(OperationType.VIEW)
    public List<QualitySample> fetchSamples(final String pipelineUuid, final String elementId) {
        return qualityServiceProvider.get().samples(pipelineUuid, elementId);
    }

    @Override
    @AutoLogged(OperationType.VIEW)
    public List<QualityAssessment> fetchAssessments(final String pipelineUuid, final String elementId) {
        return qualityServiceProvider.get().assessments(pipelineUuid, elementId);
    }

    @Override
    @AutoLogged(OperationType.CREATE)
    public List<QualityAssessment> addAssessment(final AssessmentRequest request) {
        return qualityServiceProvider.get().assess(request);
    }

    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Resolving a Smart Pipeline quality issue")
    public List<QualityAssessment> resolveAssessment(final long id) {
        return qualityServiceProvider.get().resolve(id);
    }
}
