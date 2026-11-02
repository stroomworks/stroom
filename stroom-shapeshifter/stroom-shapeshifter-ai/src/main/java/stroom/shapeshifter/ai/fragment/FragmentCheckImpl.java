/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.shapeshifter.ai.fragment;

import stroom.docref.DocRef;
import stroom.docstore.shared.DocRefUtil;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.factory.ElementRegistry;
import stroom.pipeline.factory.ElementRegistryFactory;
import stroom.pipeline.factory.PipelineStackLoader;
import stroom.pipeline.shared.PipelineDataMerger;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.PipelineModelException;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.shared.data.PipelineLayer;
import stroom.security.api.SecurityContext;
import stroom.shapeshifter.shared.ReplayUnit;
import stroom.util.shared.EntityServiceException;

import jakarta.inject.Inject;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Judges the pipeline as it would run: the whole inheritance stack merged, every element's type looked
 * up in the element registry for its roles. Reading the stack needs only USE on the pipelines, as
 * running one does.
 */
public class FragmentCheckImpl implements FragmentCheck {

    private final PipelineStore pipelineStore;
    private final PipelineStackLoader pipelineStackLoader;
    private final ElementRegistryFactory elementRegistryFactory;
    private final SecurityContext securityContext;

    @Inject
    FragmentCheckImpl(final PipelineStore pipelineStore,
                      final PipelineStackLoader pipelineStackLoader,
                      final ElementRegistryFactory elementRegistryFactory,
                      final SecurityContext securityContext) {
        this.pipelineStore = pipelineStore;
        this.pipelineStackLoader = pipelineStackLoader;
        this.elementRegistryFactory = elementRegistryFactory;
        this.securityContext = securityContext;
    }

    @Override
    public ReplayUnit check(final DocRef pipeline) {
        if (!PipelineDoc.TYPE.equals(pipeline.getType())) {
            throw new EntityServiceException("A binding must bind a pipeline, not '"
                                             + pipeline.getType() + "' " + pipeline.getName());
        }
        final ElementRegistry registry = elementRegistryFactory.get();
        final Collection<PipelineElement> elements = securityContext.useAsReadResult(() -> {
            final PipelineDoc doc = pipelineStore.readDocument(pipeline);
            final List<PipelineLayer> layers = pipelineStackLoader.loadPipelineStack(doc).stream()
                    .map(layer -> new PipelineLayer(DocRefUtil.create(layer), layer.getPipelineData()))
                    .toList();
            try {
                return new PipelineDataMerger().merge(layers).getElements().values();
            } catch (final PipelineModelException e) {
                throw new EntityServiceException("Pipeline " + pipeline.getName() + " cannot be merged: "
                                                 + e.getMessage());
            }
        });
        offendingElement(elements, registry::getElementType).ifPresent(element -> {
            throw new EntityServiceException("Pipeline " + pipeline.getName()
                                             + " is not a fragment: element '" + element.getId() + "' ("
                                             + element.getType() + ") is a writer or destination");
        });
        // What it can be replayed over follows from the chain itself (A1): the caller holds it against
        // what the stage binding it may host.
        return ReplayUnits.ofElements(elements.stream().map(PipelineElement::getType).toList(),
                type -> {
                    final PipelineElementType elementType = registry.getElementType(type);
                    return elementType != null && elementType.hasRole(PipelineElementType.ROLE_PARSER);
                });
    }

    /**
     * The first element that makes the pipeline not a fragment, if any. A fragment ends at a filter: a
     * writer would turn the events the supervisor captures into bytes, and a destination would send them
     * somewhere. An element the registry does not know is left to the pipeline validator to reject.
     */
    static Optional<PipelineElement> offendingElement(final Collection<PipelineElement> elements,
                                                      final Function<String, PipelineElementType> types) {
        return elements.stream()
                .filter(element -> {
                    final PipelineElementType type = types.apply(element.getType());
                    return type != null
                           && (type.hasRole(PipelineElementType.ROLE_WRITER)
                               || type.hasRole(PipelineElementType.ROLE_DESTINATION));
                })
                .findFirst();
    }
}
