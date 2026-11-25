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

/**
 * Turns a learned chain into content: one configuration document per document-bearing step, and a
 * pipeline fragment (proposed ruling A20) whose elements reference them. The fragment starts at the
 * explicit {@code Source} element every base pipeline carries and ends at the last step, with no writer
 * or destination; the supervisor attaches its own capture when it runs one.
 * <p>
 * Every document is new (design §7.3 rule 1): a learned chain never overwrites what an earlier one wrote.
 * Where the documents go is the {@link ContentCreator}'s business; what they hold is this class's.
 */
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

    /// Make the folder learned content goes in, or find it, before anything is learned for it: a folder
    /// that cannot be made is found out while nothing has been spent on what would go in it.
    ///
    /// @param folder The folder.
    public void ensure(final DocPath folder) {
        creator.ensure(folder);
    }

    /// Copy a draft fragment into the fragment that serves, and delete the draft's documents (A58,
    /// simplification option A).
    ///
    /// Every result is written as a draft fragment of its own, whatever the Learning Flow's mode, so that
    /// what serves goes on serving until the draft is approved. Approving it must not leave the learner
    /// with two fragments: the serving pipeline keeps its own `DocRef` — so every output ever recorded
    /// against the binding still names the pipeline that serves it — and takes the draft's shape, with
    /// the draft's text copied into its own configuration documents. A document for an element the
    /// serving fragment did not have is written in the folder; one it had and the draft does not is left,
    /// since the pipeline no longer references it and a person may want to read it.
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
        // What the serving fragment already has for each element, so that the copy overwrites a document
        // rather than making another beside it.
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
        // The draft is spent: its documents go, so that the learner is left with one fragment (A58).
        copied.forEach(creator::delete);
        creator.delete(draft);
        return serving;
    }

    /// What a fragment's elements reference, by element id and property name.
    private static Map<String, DocRef> referenced(final PipelineData pipelineData) {
        final Map<String, DocRef> referenced = new HashMap<>();
        for (final PipelineProperty property : pipelineData.getAddedProperties()) {
            if (property.getValue() != null && property.getValue().getEntity() != null) {
                referenced.put(property.getElement() + "/" + property.getName(), property.getValue().getEntity());
            }
        }
        return referenced;
    }

    /// A configuration document copied into the folder under its own name, for an element the serving
    /// fragment did not have.
    private DocRef copy(final DocPath folder, final String name, final DocRef source) {
        return overwrite(creator.create(folder, source.getType(), creator.unused(folder, name)), read(source));
    }

    /// What a configuration document holds.
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

    /// A configuration document's text replaced with what another holds.
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

    /**
     * @param folder Where the fragment and its configuration documents are created.
     * @param name   The name for the fragment, or the first free number after it where the folder has one
     *               by that name; configuration documents take it with the element id appended.
     * @param chain  The learned steps in chain order.
     * @return The fragment's DocRef, for the binding.
     */
    public DocRef write(final DocPath folder,
                        final String name,
                        final List<LearnedStep> chain,
                        final RecordBoundary boundary) {
        // Checked for the whole chain before anything is created: a step whose document this writer
        // cannot write must not leave the earlier steps' documents behind.
        chain.stream()
                .map(step -> step.runner().configured())
                .flatMap(Optional::stream)
                .map(Configured::documentType)
                .filter(type -> !WRITABLE.contains(type))
                .findFirst()
                .ifPresent(type -> {
                    throw new IllegalArgumentException("No store to write a " + type + " document");
                });
        // One name for the fragment and every document it references, found once: a second fragment of a
        // learner's takes the first free number (A132), and its documents go with it.
        final String named = creator.unused(folder, name);
        final PipelineDataBuilder builder = new PipelineDataBuilder()
                .addElement(new PipelineElement(PipelineRuns.SOURCE, PipelineRuns.SOURCE));
        String previous = PipelineRuns.SOURCE;
        // No depth, no filter: a boundary from a binding learned before the depth was recorded cannot say
        // where to split, and a guessed depth splits nothing (see RecordBoundary#splitDepth).
        final OptionalInt depth = boundary == null
                ? OptionalInt.empty()
                : boundary.splitDepth();
        boolean split = false;
        final Map<String, Integer> idsUsed = new HashMap<>();
        for (final LearnedStep step : chain) {
            // Stroom's own shape for markup is parser, then split, then transform (§12 item 25): the
            // filter cuts the parsed stream into one document per record, so the stylesheet sees one
            // record as a person writing one is shown one, memory is bounded by the record rather than
            // the stream, and an error is isolated to the record that raised it. It goes in front of the
            // first element that is not a parser — after the parser where there is one, and straight
            // after the source where the input is already markup. Raw text needs none: the Data Splitter
            // *is* the splitter, and a chain that cuts with a configuration has no boundary of this kind.
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

    /**
     * A chain may hold the same element type twice — two transforms in a row, say — and element ids are
     * unique within a pipeline, so the second and later take a suffix.
     */
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
                // The kind comes from the step, because the element that will read it refuses one of
                // the wrong kind: XMLFragmentParser throws "The assigned text converter is not an XML
                // fragment". Written as Data Splitter for everything, a learned XMLFragmentParser chain
                // passes every scorer — the stand-in reads only the text — and then fails on every
                // stream a node gives it.
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


    /// What a configuration document holds: its text, and for a text converter the kind it is, since the
    /// element that reads one refuses a converter of the wrong kind.
    ///
    /// @param data          The document's text.
    /// @param converterType The kind of text converter, or null for a document that is not one.
    private record Content(String data, TextConverterType converterType) {

    }
}
