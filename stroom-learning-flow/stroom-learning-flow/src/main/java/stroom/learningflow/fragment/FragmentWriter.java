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

package stroom.learningflow.fragment;

import stroom.docref.DocRef;
import stroom.learningflow.learning.LearnedStep;
import stroom.learningflow.learning.StepRunner.Configured;
import stroom.learningflow.shared.RecordBoundary;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.TextConverterDoc;
import stroom.pipeline.shared.TextConverterDoc.TextConverterType;
import stroom.pipeline.shared.XsltDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineProperty;
import stroom.pipeline.shared.data.PipelinePropertyValue;
import stroom.pipeline.textconverter.TextConverterStore;
import stroom.pipeline.xslt.XsltStore;
import stroom.util.shared.DocPath;

import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/// Turns a learned chain into Stroom content: one configuration document (text converter or XSLT) for
/// each step that needs one, and a pipeline fragment whose elements reference them. The fragment starts
/// at the explicit `Source` element every base pipeline has and ends at the last step, with no writer or
/// destination; [PipelineFragmentRunner] adds its own output element when it runs one.
///
/// [#write] always creates new documents, so writing a learned chain never overwrites what an earlier one
/// wrote. [#copyInto] is the one operation that overwrites, when a draft is copied into the fragment that
/// serves. Where the documents are created is the [ContentCreator]'s responsibility; what they hold is
/// this class's.
public final class FragmentWriter {

    private static final String SPLIT = "splitFilter";
    private static final String SPLIT_DEPTH = "splitDepth";
    private static final String SPLIT_COUNT = "splitCount";
    private static final Set<String> WRITABLE = Set.of(TextConverterDoc.TYPE, XsltDoc.TYPE);

    private final ContentCreator creator;
    private final PipelineStore pipelineStore;
    private final TextConverterStore textConverterStore;
    private final XsltStore xsltStore;

    @Inject
    public FragmentWriter(final ContentCreator creator,
                          final PipelineStore pipelineStore,
                          final TextConverterStore textConverterStore,
                          final XsltStore xsltStore) {
        this.creator = creator;
        this.pipelineStore = pipelineStore;
        this.textConverterStore = textConverterStore;
        this.xsltStore = xsltStore;
    }

    /// Creates the folder learned content goes in, if it does not exist, before anything is learned for
    /// it. This way a folder that cannot be created is discovered before anything has been spent on the
    /// model.
    ///
    /// @param folder The folder.
    public void ensure(final DocPath folder) {
        creator.ensure(folder);
    }

    /// Copies a draft fragment into the fragment that serves, and deletes the draft's documents.
    ///
    /// Every learning result is written as a separate draft fragment, whatever the Learning Flow's
    /// promotion mode, so that the serving fragment carries on serving until the draft is approved.
    /// Approving it must not leave the learner with two fragments. So the serving pipeline keeps its own
    /// `DocRef`, which means every output ever recorded against the binding still names the pipeline that
    /// serves it, and takes the draft's structure, with the draft's text copied into its own configuration
    /// documents. A document for an element the serving fragment did not have is created in the folder. A
    /// document the serving fragment had but the draft does not is left in place, since the pipeline no
    /// longer references it and a person may want to read it.
    ///
    /// @param serving The fragment that serves, which keeps its identity.
    /// @param draft   The draft fragment, whose documents are deleted once copied.
    /// @param folder  Where a new configuration document goes, where one is needed.
    /// @param name    The name a new configuration document is prefixed with.
    /// @return The serving fragment, unchanged as a `DocRef`.
    public DocRef copyInto(final DocRef serving,
                           final DocRef draft,
                           final DocPath folder,
                           final String name) {
        final PipelineDoc from = pipelineStore.readDocument(draft);
        final PipelineDoc into = pipelineStore.readDocument(serving);
        // The documents the serving fragment already references for each element, so that the copy
        // overwrites a document rather than creating another beside it.
        final Map<String, DocRef> existing = referenced(into.getPipelineData());
        final PipelineDataBuilder builder = new PipelineDataBuilder();
        builder.addElements(from.getPipelineData().getAddedElements());
        builder.addLinks(from.getPipelineData().getAddedLinks());
        final List<DocRef> copied = new ArrayList<>();
        for (final PipelineProperty property : from.getPipelineData().getAddedProperties()) {
            final DocRef source = property.getValue() == null
                    ? null
                    : property.getValue().getEntity();
            if (source == null || !WRITABLE.contains(source.getType())) {
                builder.addProperty(property);
                continue;
            }
            copied.add(source);
            final DocRef was = existing.get(property.getElement() + "/" + property.getName());
            final DocRef target = was != null && source.getType().equals(was.getType())
                    ? overwrite(was, read(source))
                    : copy(folder, name + "-" + property.getElement(), source);
            builder.addProperty(new PipelineProperty(property.getElement(), property.getName(),
                    new PipelinePropertyValue(target)));
        }
        pipelineStore.writeDocument(into.copy().pipelineData(builder.build()).build());
        // The draft has been used, so its documents are deleted, leaving the learner with one fragment.
        copied.forEach(creator::delete);
        creator.delete(draft);
        return serving;
    }

    /// The documents a fragment's elements reference, keyed by `elementId/propertyName`.
    private static Map<String, DocRef> referenced(final PipelineData pipelineData) {
        final Map<String, DocRef> referenced = new HashMap<>();
        for (final PipelineProperty property : pipelineData.getAddedProperties()) {
            if (property.getValue() != null && property.getValue().getEntity() != null) {
                referenced.put(property.getElement() + "/" + property.getName(), property.getValue().getEntity());
            }
        }
        return referenced;
    }

    /// Creates a copy of a configuration document in the folder under a new name, for an element the
    /// serving fragment did not have.
    private DocRef copy(final DocPath folder, final String name, final DocRef source) {
        return overwrite(creator.create(folder, source.getType(), creator.unused(folder, name)), read(source));
    }

    /// Reads what a configuration document holds.
    private Content read(final DocRef docRef) {
        return switch (docRef.getType()) {
            case TextConverterDoc.TYPE -> {
                final TextConverterDoc doc = textConverterStore.readDocument(docRef);
                yield new Content(doc.getData(), doc.getConverterType());
            }
            case XsltDoc.TYPE -> new Content(xsltStore.readDocument(docRef).getData(), null);
            default -> throw new IllegalArgumentException(
                    "No store for configuration documents of type " + docRef.getType());
        };
    }

    /// Replaces a configuration document's content with the given content.
    private DocRef overwrite(final DocRef docRef, final Content content) {
        switch (docRef.getType()) {
            case TextConverterDoc.TYPE -> textConverterStore.writeDocument(textConverterStore.readDocument(docRef)
                    .copy()
                    .converterType(content.converterType() == null
                            ? textConverterStore.readDocument(docRef).getConverterType()
                            : content.converterType())
                    .data(content.data())
                    .build());
            case XsltDoc.TYPE -> xsltStore.writeDocument(xsltStore.readDocument(docRef)
                    .copy()
                    .data(content.data())
                    .build());
            default -> throw new IllegalArgumentException("No store for configuration documents of type "
                                                          + docRef.getType());
        }
        return docRef;
    }

    /// Writes a learned chain as a new fragment and new configuration documents.
    ///
    /// @param folder   Where the fragment and its configuration documents are created.
    /// @param name     The name for the fragment, followed by the first free number where the folder already
    ///                 has something by that name; configuration documents use it with the element id
    ///                 appended.
    /// @param chain    The learned steps in chain order.
    /// @param boundary What one record is in the stream, which sets the depth of any split filter; null
    ///                 where none was settled.
    /// @return The fragment's DocRef, for the binding.
    public DocRef write(final DocPath folder,
                        final String name,
                        final List<LearnedStep> chain,
                        final RecordBoundary boundary) {
        // Check the whole chain before anything is created, so that a step whose document this writer
        // cannot write does not leave the earlier steps' documents behind.
        chain.stream()
                .map(step -> step.runner().configured())
                .flatMap(Optional::stream)
                .map(Configured::documentType)
                .filter(type -> !WRITABLE.contains(type))
                .findFirst()
                .ifPresent(type -> {
                    throw new IllegalArgumentException("No store to write a " + type + " document");
                });
        // Find one name for the fragment and every document it references. A learner's second fragment
        // takes the first free number, and its documents use the same numbered name.
        final String named = creator.unused(folder, name);
        final PipelineDataBuilder builder = new PipelineDataBuilder()
                .addElement(new PipelineElement(PipelineRuns.SOURCE, PipelineRuns.SOURCE));
        String previous = PipelineRuns.SOURCE;
        // Without a depth, no split filter is added: a boundary from a binding learned before the depth was
        // recorded cannot say where to split, and a guessed depth would split nothing
        // (see RecordBoundary#splitDepth).
        final OptionalInt depth = boundary == null
                ? OptionalInt.empty()
                : boundary.splitDepth();
        boolean split = false;
        final Map<String, Integer> idsUsed = new HashMap<>();
        for (final LearnedStep step : chain) {
            // Stroom's usual arrangement for XML is parser, then split filter, then transform. The split
            // filter divides the parsed stream into one document per record, so the stylesheet sees one
            // record at a time, just as the model was shown one when writing it; memory is bounded by the
            // record rather than the stream; and an error is isolated to the record that raised it. The
            // filter goes in front of the first element that is not a parser: after the parser where there
            // is one, and straight after the source where the input is already XML. Raw text needs no split
            // filter, because the Data Splitter divides the records itself, and a chain that divides
            // records with a configuration has no split depth.
            if (!split && !step.runner().parser() && depth.isPresent()) {
                builder.addElement(new PipelineElement(SPLIT, PipelineRuns.SPLIT_FILTER));
                builder.addLink(previous, SPLIT);
                builder.addProperty(new PipelineProperty(SPLIT, SPLIT_DEPTH,
                        new PipelinePropertyValue(depth.getAsInt())));
                builder.addProperty(new PipelineProperty(SPLIT, SPLIT_COUNT, new PipelinePropertyValue(1)));
                previous = SPLIT;
                split = true;
            }
            final String elementId = uniqueId(step.runner().elementId(), idsUsed);
            builder.addElement(new PipelineElement(elementId, step.elementType()));
            builder.addLink(previous, elementId);
            step.runner().configured().ifPresent(configured -> {
                final DocRef configuration = writeConfiguration(
                        folder, named + "-" + elementId, configured, step.configuration());
                builder.addProperty(new PipelineProperty(
                        elementId, configured.propertyName(), new PipelinePropertyValue(configuration)));
            });
            previous = elementId;
        }
        final PipelineData pipelineData = builder.build();

        final DocRef fragment = creator.create(folder, PipelineDoc.TYPE, named);
        pipelineStore.writeDocument(pipelineStore.readDocument(fragment)
                .copy()
                .pipelineData(pipelineData)
                .build());
        return fragment;
    }

    /// Makes an element id unique within the fragment. A chain may hold the same element type twice (e.g.
    /// two transforms in a row), and element ids must be unique within a pipeline, so the second and later
    /// ones get a numeric suffix.
    private static String uniqueId(final String elementId, final Map<String, Integer> idsUsed) {
        final int count = idsUsed.merge(elementId, 1, Integer::sum);
        return count == 1
                ? elementId
                : elementId + count;
    }

    private DocRef writeConfiguration(final DocPath folder,
                                      final String name,
                                      final Configured configured,
                                      final String data) {
        // The type is checked before anything is created, so an unsupported one leaves no orphan behind.
        return switch (configured.documentType()) {
            case TextConverterDoc.TYPE -> {
                // The converter type comes from the step, because the element that reads the converter
                // refuses one of the wrong type: XMLFragmentParser throws "The assigned text converter is
                // not an XML fragment". If every converter were written as Data Splitter, a learned
                // XMLFragmentParser chain would pass every scorer (the stand-in runner reads only the
                // text) and then fail on every stream a node gives it.
                if (configured.converterType() == null) {
                    throw new IllegalArgumentException("Step configuration of type "
                                                       + configured.documentType() + " does not say which "
                                                       + "kind of text converter it is");
                }
                final DocRef docRef = creator.create(folder, TextConverterDoc.TYPE, name);
                textConverterStore.writeDocument(textConverterStore.readDocument(docRef)
                        .copy()
                        .converterType(configured.converterType())
                        .data(data)
                        .build());
                yield docRef;
            }
            case XsltDoc.TYPE -> {
                final DocRef docRef = creator.create(folder, XsltDoc.TYPE, name);
                xsltStore.writeDocument(xsltStore.readDocument(docRef)
                        .copy()
                        .data(data)
                        .build());
                yield docRef;
            }
            default -> throw new IllegalArgumentException(
                    "No store for configuration documents of type " + configured.documentType());
        };
    }


    // --------------------------------------------------------------------------------


    /// What a configuration document holds: its text and, for a text converter, its converter type, since
    /// the element that reads a converter refuses one of the wrong type.
    ///
    /// @param data          The document's text.
    /// @param converterType The text converter type, or null for a document that is not a text converter.
    private record Content(String data, TextConverterType converterType) {

    }
}
