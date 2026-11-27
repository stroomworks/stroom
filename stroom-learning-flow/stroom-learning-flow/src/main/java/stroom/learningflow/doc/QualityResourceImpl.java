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

package stroom.learningflow.doc;

import stroom.event.logging.rs.api.AutoLogged;
import stroom.event.logging.rs.api.AutoLogged.OperationType;
import stroom.learningflow.quality.QualityService;
import stroom.learningflow.shared.QualityReport;
import stroom.learningflow.shared.QualityResource;
import stroom.learningflow.shared.QualitySample;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.List;

/// The REST resource behind a Smart Pipeline's Quality Assurance tab, which shows one pipeline's translated
/// output and how it was judged. It delegates to [QualityService], which checks the pipeline's permissions.
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

}
