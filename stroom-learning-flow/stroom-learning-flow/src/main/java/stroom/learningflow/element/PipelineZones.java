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

package stroom.learningflow.element;

import stroom.learningflow.stage.Learner;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Optional;

/// The `timeZone` property of a learner's Smart Pipeline element (decision N6), read from the pipeline
/// rather than from the element.
///
/// It is read here, as the learned folder and name are ([PipelinePlaces]), because the worker that
/// carries an attempt on runs no pipeline and so has no element to be told the property by — and the
/// zone is needed while learning as much as while serving, or a date judged in one zone would be served
/// in another.
@Singleton
public class PipelineZones {

    /// The element property, as the pipeline holds it.
    static final String PROPERTY = "timeZone";

    private final LearnerDocuments learnerDocuments;

    @Inject
    public PipelineZones(final LearnerDocuments learnerDocuments) {
        this.learnerDocuments = learnerDocuments;
    }

    /// Resolves the zone for a stream of this learner.
    ///
    /// @param learner The learner.
    /// @param header  The stream's `TimeZone` header, or null where it carries none.
    /// @return The zone, where it came from, and what was wrong with what was asked for.
    public ResolvedZone of(final Learner learner, final String header) {
        return ResolvedZone.of(header, property(learner).orElse(null));
    }

    /// @param learner The learner.
    /// @return The pipeline's `timeZone` property, where it sets one; a blank value is an unset one.
    private Optional<String> property(final Learner learner) {
        try {
            return learnerDocuments.elementOf(learner)
                    .flatMap(element -> element.text(PROPERTY));
        } catch (final RuntimeException e) {
            // A pipeline that cannot be read is not a reason to stop translating: the header, or UTC,
            // still gives a zone, and what is wrong with the pipeline is reported where it is found
            return Optional.empty();
        }
    }
}
