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

import stroom.docref.DocRef;
import stroom.learningflow.shared.LearningFlowElements;
import stroom.learningflow.stage.Learner;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelinePropertyValue;
import stroom.security.api.SecurityContext;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/// Finds the Learning Flow document a learner currently runs under, by reading the document property of
/// the learner's Smart Pipeline element from its pipeline. The pipeline is read merged with its parents, so
/// a document set on an inherited element counts.
///
/// A request about a learner names the learner and the document separately, and the permission checks
/// are done against the document. Reading the pipeline is how the two are reliably linked: stored rows
/// also record the document, but only as it was when they were written.
public class LearnerDocuments {

    private final Provider<PipelineStore> pipelineStoreProvider;
    private final Provider<PipelineDataCache> pipelineDataCacheProvider;
    private final Provider<SecurityContext> securityContextProvider;

    @Inject
    public LearnerDocuments(final Provider<PipelineStore> pipelineStoreProvider,
                            final Provider<PipelineDataCache> pipelineDataCacheProvider,
                            final Provider<SecurityContext> securityContextProvider) {
        this.pipelineStoreProvider = pipelineStoreProvider;
        this.pipelineDataCacheProvider = pipelineDataCacheProvider;
        this.securityContextProvider = securityContextProvider;
    }

    /// Gets the UUID of the Learning Flow document that the learner's element names.
    ///
    /// The pipeline is read as the processing user. The caller only needs to know whether the learner
    /// belongs to a document the person already has permission on, and the answer reveals nothing else
    /// about the pipeline. A person may supervise a document without being able to open every pipeline
    /// that uses it.
    ///
    /// @param learner The learner.
    /// @return The document's UUID, if the pipeline exists and its element with that id is a Smart
    /// Pipeline element naming a document.
    public Optional<String> documentOf(final Learner learner) {
        return elementOf(learner)
                .map(element -> element.properties().get(LearningFlowElements.DOCUMENT_PROPERTY))
                .map(PipelinePropertyValue::getEntity)
                .map(DocRef::getUuid);
    }

    /// Reads the learner's element from its pipeline as it is now, merged with its parents so that
    /// inherited properties count. Like [#documentOf], it reads as the processing user.
    ///
    /// @param learner The learner.
    /// @return The pipeline and the element's properties, if the pipeline exists and its element with
    /// that id is a Smart Pipeline element.
    Optional<Element> elementOf(final Learner learner) {
        return securityContextProvider.get().asProcessingUserResult(() -> read(learner));
    }

    private Optional<Element> read(final Learner learner) {
        final PipelineDoc pipeline;
        try {
            pipeline = pipelineStoreProvider.get().readDocument(
                    new DocRef(PipelineDoc.TYPE, learner.pipelineUuid()));
        } catch (final RuntimeException e) {
            // A pipeline that cannot be read is treated as having no such element.
            return Optional.empty();
        }
        if (pipeline == null) {
            return Optional.empty();
        }
        final PipelineData merged = pipelineDataCacheProvider.get().get(pipeline);
        if (merged == null) {
            return Optional.empty();
        }
        final boolean stage = merged.getAddedElements().stream()
                .anyMatch(element -> learner.elementId().equals(element.getId())
                                     && LearningFlowElements.PARSER.equals(element.getType()));
        if (!stage) {
            return Optional.empty();
        }
        final Map<String, PipelinePropertyValue> properties = new HashMap<>();
        merged.getAddedProperties().stream()
                .filter(property -> learner.elementId().equals(property.getElement())
                                    && property.getValue() != null)
                .forEach(property -> properties.put(property.getName(), property.getValue()));
        return Optional.of(new Element(pipeline, Map.copyOf(properties)));
    }


    // --------------------------------------------------------------------------------


    /// A learner's element, read from its pipeline.
    ///
    /// @param pipeline   The pipeline as stored, which gives its name and UUID.
    /// @param properties The element's properties after merging with parent pipelines, by name.
    record Element(PipelineDoc pipeline, Map<String, PipelinePropertyValue> properties) {

        /// Gets a property's string value, treating a blank value as unset.
        ///
        /// @param property The property's name.
        /// @return Its value with surrounding whitespace removed, or empty if it is unset or blank.
        Optional<String> text(final String property) {
            return Optional.ofNullable(properties.get(property))
                    .map(PipelinePropertyValue::getString)
                    .map(String::strip)
                    .filter(value -> !NullSafe.isBlankString(value));
        }
    }
}
