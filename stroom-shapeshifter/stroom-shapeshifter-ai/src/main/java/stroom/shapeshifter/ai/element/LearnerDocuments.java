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


package stroom.shapeshifter.ai.element;

import stroom.docref.DocRef;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelinePropertyValue;
import stroom.security.api.SecurityContext;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.shared.ShapeshifterAiElements;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/// The document a learner runs under, as its pipeline says now (design 06 phase J slice 2b): the
/// Shapeshifter AI document named on the element, read from the pipeline as it is merged, so that one
/// inherited from a parent counts.
///
/// What a person may do about a learner is decided by the document, and a request names the learner and
/// the document separately. Asking the pipeline is what joins them: rows record the document too, but
/// only as of when they were written.
public class LearnerDocuments {

    /// The element's property naming its document: the name Stroom gives
    /// [ShapeshifterAiParser#setShapeshifterAi].
    static final String DOCUMENT_PROPERTY = "shapeshifterAi";

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

    /// The document the learner's element names.
    ///
    /// Read as the processing user: the question is whether a learner belongs to a document the person
    /// has already been permitted on, and the answer shows them nothing of the pipeline. A person may
    /// supervise a document without being able to open every pipeline that uses it.
    ///
    /// @param learner The learner.
    /// @return The document, if the pipeline exists and its element of that id is a Shapeshifter AI stage
    /// naming one.
    public Optional<String> documentOf(final Learner learner) {
        return elementOf(learner)
                .map(element -> element.properties().get(DOCUMENT_PROPERTY))
                .map(PipelinePropertyValue::getEntity)
                .map(DocRef::getUuid);
    }

    /// The learner's element as its pipeline has it now, merged, so that a property inherited from a parent
    /// counts: the pipeline, and the element's properties by name. Read as the processing user, as
    /// [#documentOf] is.
    ///
    /// @param learner The learner.
    /// @return The element, if the pipeline exists and its element of that id is a Shapeshifter AI stage.
    Optional<Element> elementOf(final Learner learner) {
        return securityContextProvider.get().asProcessingUserResult(() -> read(learner));
    }

    private Optional<Element> read(final Learner learner) {
        final PipelineDoc pipeline;
        try {
            pipeline = pipelineStoreProvider.get().readDocument(
                    new DocRef(PipelineDoc.TYPE, learner.pipelineUuid()));
        } catch (final RuntimeException e) {
            // A pipeline that cannot be read has no element.
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
                                     && ShapeshifterAiElements.PARSER.equals(element.getType()));
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
    /// @param pipeline   The pipeline, as stored: its name and uuid.
    /// @param properties The element's properties as merged, by name.
    record Element(PipelineDoc pipeline, Map<String, PipelinePropertyValue> properties) {

    }
}
