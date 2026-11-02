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

package stroom.pipelinefactory.impl;

import stroom.docref.DocRef;
import stroom.docstore.api.DocumentNotFoundException;
import stroom.docstore.shared.DocRefUtil;
import stroom.explorer.api.ExplorerService;
import stroom.explorer.shared.ExplorerNode;
import stroom.explorer.shared.PermissionInheritance;
import stroom.meta.shared.MetaFields;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.factory.ElementRegistry;
import stroom.pipeline.factory.ElementRegistryFactory;
import stroom.pipeline.factory.PipelineStackLoader;
import stroom.pipeline.shared.PipelineDataMerger;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.PipelineModelException;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.shared.data.PipelineLayer;
import stroom.pipeline.shared.data.PipelinePropertyType;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.pipelinefactory.shared.PipelineFactoryFilterSettings;
import stroom.pipelinefactory.shared.PipelineFactoryKey;
import stroom.pipelinefactory.shared.PipelineFactoryProperty;
import stroom.processor.api.ProcessorFilterService;
import stroom.processor.shared.CreateProcessFilterRequest;
import stroom.processor.shared.ProcessorFilter;
import stroom.processor.shared.QueryData;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.security.api.SecurityContext;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.DocPath;

import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Makes the pipeline and processor filter for one combination of a factory's key (design 06 §3.3):
/// the folder its pattern names, a pipeline there inheriting the template with the factory's property
/// values written onto it, and a filter on that pipeline for the factory's stream filter and the
/// combination's values, with the factory's filter settings.
///
/// Everything is made as the factory's run-as user (A123), so a factory makes only what that user may
/// make. A failure leaves nothing behind but the folder: a pipeline whose filter could not be made is
/// deleted again, so that trying the combination again does not make a second pipeline.
public class PipelineMaker {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(PipelineMaker.class);

    /// What a string-valued element property is typed as.
    private static final String STRING = "String";

    private final ExplorerService explorerService;
    private final PipelineStore pipelineStore;
    private final PipelineStackLoader pipelineStackLoader;
    private final ElementRegistryFactory elementRegistryFactory;
    private final ProcessorFilterService processorFilterService;
    private final SecurityContext securityContext;

    @Inject
    PipelineMaker(final ExplorerService explorerService,
                  final PipelineStore pipelineStore,
                  final PipelineStackLoader pipelineStackLoader,
                  final ElementRegistryFactory elementRegistryFactory,
                  final ProcessorFilterService processorFilterService,
                  final SecurityContext securityContext) {
        this.explorerService = explorerService;
        this.pipelineStore = pipelineStore;
        this.pipelineStackLoader = pipelineStackLoader;
        this.elementRegistryFactory = elementRegistryFactory;
        this.processorFilterService = processorFilterService;
        this.securityContext = securityContext;
    }

    /// Make the pipeline and filter for one combination.
    ///
    /// @param factory     A factory without problems, with a template and a run-as user.
    /// @param combination Values for exactly the factory's key.
    /// @return What was made.
    /// @throws PipelineMakerException  If the factory cannot make it: it has problems, the template is
    ///                                 missing or cannot be merged, it lacks an element or property the
    ///                                 factory writes to, or the run-as user may not make it. Nothing but
    ///                                 the folder is left behind.
    /// @throws IllegalArgumentException If the combination does not fit the factory's key.
    public Made make(final PipelineFactoryDoc factory, final Combination combination) {
        refuseUnready(factory, combination);
        try {
            return securityContext.asUserResult(factory.getRunAsUser(), () -> makeAsRunAsUser(factory, combination));
        } catch (final PipelineMakerException e) {
            throw e;
        } catch (final RuntimeException e) {
            // A permission the run-as user lacks, a filter the processor refuses: said in the factory's terms.
            throw new PipelineMakerException("Pipeline Factory '" + factory.getName()
                                             + "' could not make a pipeline for " + combination + ": "
                                             + e.getMessage(), e);
        }
    }

    private Made makeAsRunAsUser(final PipelineFactoryDoc factory, final Combination combination) {
        checkTemplate(factory);
        final Map<String, String> names = namesOf(combination);
        final ExplorerNode folder = explorerService.ensureFolderPath(
                folderOf(factory, names), PermissionInheritance.DESTINATION);
        final String name = PipelineFactoryKey.substitute(factory.getNamePattern(), names);
        final DocRef pipeline = explorerService.create(
                PipelineDoc.TYPE, name, folder, PermissionInheritance.DESTINATION).getDocRef();
        try {
            writePipeline(factory, pipeline, names);
            final ProcessorFilter filter = processorFilterService.create(filterRequest(
                    factory, pipeline, combination, names));
            LOGGER.info(() -> LogUtil.message("Pipeline Factory {} made pipeline {} for {}",
                    factory.getName(), pipeline.getName(), combination));
            return new Made(pipeline, filter);
        } catch (final RuntimeException e) {
            unmake(pipeline);
            throw e;
        }
    }

    private static void refuseUnready(final PipelineFactoryDoc factory, final Combination combination) {
        final List<String> problems = factory.problems();
        if (!problems.isEmpty()) {
            throw new PipelineMakerException("Pipeline Factory '" + factory.getName() + "' has problems: "
                                             + String.join("; ", problems));
        }
        if (factory.getTemplate() == null || factory.getRunAsUser() == null) {
            throw new PipelineMakerException("Pipeline Factory '" + factory.getName()
                                             + "' needs a template and a run-as user to make pipelines");
        }
        if (!combination.fits(factory.getKey())) {
            throw new IllegalArgumentException(combination + " does not fit the key " + factory.getKey());
        }
    }

    /// The template must be there to inherit, or every pipeline made from it would error on every stream.
    /// And every property the factory writes must be one a string can be written to, on an element the
    /// template's chain holds; otherwise the value would be written where nothing reads it.
    private void checkTemplate(final PipelineFactoryDoc factory) {
        final Map<String, PipelineElement> elements = templateElements(factory.getTemplate());
        final ElementRegistry registry = elementRegistryFactory.get();
        for (final PipelineFactoryProperty property : factory.getProperties()) {
            final PipelineElement element = elements.get(property.getElementId());
            if (element == null) {
                throw new PipelineMakerException("The template " + factory.getTemplate().getName()
                                                 + " has no element '" + property.getElementId() + "'");
            }
            final PipelineElementType elementType = registry.getElementType(element.getType());
            final PipelinePropertyType propertyType = elementType == null
                    ? null
                    : registry.getPropertyType(elementType, property.getName());
            if (propertyType == null) {
                throw new PipelineMakerException("Element '" + element.getId() + "' (" + element.getType()
                                                 + ") has no property '" + property.getName() + "'");
            }
            if (!STRING.equals(propertyType.getType())) {
                throw new PipelineMakerException("Property '" + property.getName() + "' of element '"
                                                 + element.getId() + "' takes a " + propertyType.getType()
                                                 + ", and a factory writes only text");
            }
        }
    }

    private Map<String, PipelineElement> templateElements(final DocRef template) {
        return securityContext.useAsReadResult(() -> {
            final PipelineDoc doc = readTemplate(template);
            final List<PipelineLayer> layers = pipelineStackLoader.loadPipelineStack(doc).stream()
                    .map(layer -> new PipelineLayer(DocRefUtil.create(layer), layer.getPipelineData()))
                    .toList();
            try {
                return new PipelineDataMerger().merge(layers).getElements();
            } catch (final PipelineModelException e) {
                throw new PipelineMakerException("The template " + template.getName() + " cannot be merged: "
                                                 + e.getMessage(), e);
            }
        });
    }

    private PipelineDoc readTemplate(final DocRef template) {
        final PipelineDoc doc;
        try {
            doc = pipelineStore.readDocument(template);
        } catch (final DocumentNotFoundException e) {
            throw new PipelineMakerException("The template " + template.getName() + " does not exist", e);
        }
        if (doc == null) {
            throw new PipelineMakerException("The template " + template.getName() + " does not exist");
        }
        return doc;
    }

    /// The values a pattern is written with. A pipeline is matched by its UUID but named by its name.
    private Map<String, String> namesOf(final Combination combination) {
        final Map<String, String> names = new LinkedHashMap<>(combination.values());
        final String pipelineField = MetaFields.PIPELINE.getFldName();
        final String uuid = names.get(pipelineField);
        if (uuid != null) {
            names.put(pipelineField, pipelineName(uuid));
        }
        return names;
    }

    /// A pipeline the run-as user cannot read, or that has gone, is named by its UUID: the name is only
    /// what a person reads, and the filter matches the UUID either way.
    private String pipelineName(final String uuid) {
        try {
            final PipelineDoc pipeline = securityContext.useAsReadResult(() ->
                    pipelineStore.readDocument(PipelineDoc.buildDocRef().uuid(uuid).build()));
            return pipeline == null
                    ? uuid
                    : pipeline.getName();
        } catch (final RuntimeException e) {
            LOGGER.debug(() -> LogUtil.message("Pipeline {} named by its UUID: {}", uuid, e.getMessage()));
            return uuid;
        }
    }

    private static DocPath folderOf(final PipelineFactoryDoc factory, final Map<String, String> names) {
        final String path = PipelineFactoryKey.substitute(factory.getFolderPattern(), names);
        return DocPath.fromParts(Arrays.stream(path.split("/"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList());
    }

    private void writePipeline(final PipelineFactoryDoc factory,
                               final DocRef pipeline,
                               final Map<String, String> names) {
        final PipelineDataBuilder data = new PipelineDataBuilder();
        for (final PipelineFactoryProperty property : factory.getProperties()) {
            data.addProperty(PipelineDataUtil.createProperty(property.getElementId(),
                    property.getName(),
                    PipelineFactoryKey.substitute(property.getValue(), names)));
        }
        final PipelineDoc made = pipelineStore.readDocument(pipeline);
        pipelineStore.writeDocument(made.copy()
                .description("Made by Pipeline Factory " + factory.getName())
                .parentPipeline(factory.getTemplate())
                .pipelineData(data.build())
                .build());
    }

    private static CreateProcessFilterRequest filterRequest(final PipelineFactoryDoc factory,
                                                            final DocRef pipeline,
                                                            final Combination combination,
                                                            final Map<String, String> names) {
        final PipelineFactoryFilterSettings settings = factory.getFilterSettings();
        return CreateProcessFilterRequest.builder()
                .pipeline(pipeline)
                .queryData(QueryData.builder()
                        .dataSource(MetaFields.STREAM_STORE_DOC_REF)
                        .expression(expressionOf(factory, combination, names))
                        .build())
                .priority(settings.getPriority())
                .autoPriority(settings.isAutoPriority())
                .maxProcessingTasks(settings.getMaxProcessingTasks())
                .profileName(settings.getProfileName())
                .enabled(settings.isEnabled())
                .export(settings.isExport())
                .minMetaCreateTimeMs(settings.getMinMetaCreateTimeMs())
                .maxMetaCreateTimeMs(settings.getMaxMetaCreateTimeMs())
                .maxTaskCreationDelay(settings.getMaxTaskCreationDelay())
                .reprocess(false)
                .runAsUser(factory.getRunAsUser())
                .build();
    }

    /// The factory's stream filter, and each key field equal to the combination's value.
    static ExpressionOperator expressionOf(final PipelineFactoryDoc factory,
                                           final Combination combination,
                                           final Map<String, String> names) {
        final ExpressionOperator.Builder builder = ExpressionOperator.builder();
        if (factory.getStreamFilter() != null) {
            builder.addOperator(factory.getStreamFilter());
        }
        combination.values().forEach((field, value) -> {
            if (MetaFields.FEED.getFldName().equals(field)) {
                builder.addTextTerm(MetaFields.FEED, Condition.EQUALS, value);
            } else if (MetaFields.TYPE.getFldName().equals(field)) {
                builder.addTextTerm(MetaFields.TYPE, Condition.EQUALS, value);
            } else if (MetaFields.PIPELINE.getFldName().equals(field)) {
                builder.addDocRefTerm(MetaFields.PIPELINE, Condition.IS_DOC_REF,
                        PipelineDoc.buildDocRef().uuid(value).name(names.get(field)).build());
            } else {
                throw new IllegalArgumentException("'" + field + "' cannot be a key field");
            }
        });
        return builder.build();
    }

    private void unmake(final DocRef pipeline) {
        try {
            explorerService.getFromDocRef(pipeline).ifPresent(node -> explorerService.delete(List.of(node)));
        } catch (final RuntimeException e) {
            LOGGER.error(() -> LogUtil.message("Pipeline {} was left behind by a factory that could not finish "
                                               + "making it: {}", pipeline, e.getMessage()), e);
        }
    }
}
