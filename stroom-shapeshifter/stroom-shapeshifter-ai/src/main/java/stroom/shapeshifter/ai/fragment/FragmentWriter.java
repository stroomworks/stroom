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
import stroom.pipeline.PipelineStore;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.TextConverterDoc;
import stroom.pipeline.shared.XsltDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineProperty;
import stroom.pipeline.shared.data.PipelinePropertyValue;
import stroom.pipeline.textconverter.TextConverterStore;
import stroom.pipeline.xslt.XsltStore;
import stroom.shapeshifter.ai.learning.LearnedStep;
import stroom.shapeshifter.ai.learning.StepRunner.Configured;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.util.shared.DocPath;

import jakarta.inject.Inject;

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

    private static final String SOURCE = "Source";
    private static final String SPLIT = "splitFilter";
    private static final String SPLIT_FILTER = "SplitFilter";
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

    /// Rewrite a fragment in place (A58): the same pipeline document, and the same configuration
    /// documents where the chain still has an element of the same id and type to run them — their text
    /// overwritten — with a new document only for an element the fragment did not have. One fragment and
    /// one stylesheet per learner, growing as the session learns; what ran before is a snapshot's to keep.
    ///
    /// @param fragment The fragment to rewrite.
    /// @param folder   Where a new configuration document goes, where one is needed.
    /// @param name     The name a new configuration document is prefixed with.
    /// @param chain    The chain as learned.
    /// @param boundary What one record is, where settled.
    /// @return The same fragment.
    public DocRef rewrite(final DocRef fragment,
                          final DocPath folder,
                          final String name,
                          final List<LearnedStep> chain,
                          final RecordBoundary boundary) {
        chain.stream()
                .map(step -> step.runner().configured())
                .flatMap(Optional::stream)
                .map(Configured::documentType)
                .filter(type -> !WRITABLE.contains(type))
                .findFirst()
                .ifPresent(type -> {
                    throw new IllegalArgumentException("No store to write a " + type + " document");
                });
        final PipelineDoc existing = pipelineStore.readDocument(fragment);
        final Map<String, DocRef> referenced = new HashMap<>();
        for (final PipelineProperty property : existing.getPipelineData().getAddedProperties()) {
            if (property.getValue() != null && property.getValue().getEntity() != null) {
                referenced.put(property.getElement() + "/" + property.getName(), property.getValue().getEntity());
            }
        }
        final PipelineDataBuilder builder = new PipelineDataBuilder()
                .addElement(new PipelineElement(SOURCE, SOURCE));
        String previous = SOURCE;
        final OptionalInt depth = boundary == null
                ? OptionalInt.empty()
                : boundary.splitDepth();
        boolean split = false;
        final Map<String, Integer> idsUsed = new HashMap<>();
        for (final LearnedStep step : chain) {
            if (!split && !step.runner().parser() && depth.isPresent()) {
                builder.addElement(new PipelineElement(SPLIT, SPLIT_FILTER));
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
                final DocRef was = referenced.get(elementId + "/" + configured.propertyName());
                final DocRef configuration = was != null && configured.documentType().equals(was.getType())
                        ? overwrite(was, configured, step.configuration())
                        : writeConfiguration(folder, name + "-" + elementId, configured, step.configuration());
                builder.addProperty(new PipelineProperty(
                        elementId, configured.propertyName(), new PipelinePropertyValue(configuration)));
            });
            previous = elementId;
        }
        pipelineStore.writeDocument(existing.copy().pipelineData(builder.build()).build());
        return fragment;
    }

    /// A configuration document's text replaced with what was learned, in place.
    private DocRef overwrite(final DocRef docRef, final Configured configured, final String data) {
        switch (docRef.getType()) {
            case TextConverterDoc.TYPE -> textConverterStore.writeDocument(textConverterStore.readDocument(docRef)
                    .copy()
                    .converterType(configured.converterType() == null
                            ? textConverterStore.readDocument(docRef).getConverterType()
                            : configured.converterType())
                    .data(data)
                    .build());
            case XsltDoc.TYPE -> xsltStore.writeDocument(xsltStore.readDocument(docRef)
                    .copy()
                    .data(data)
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
                .addElement(new PipelineElement(SOURCE, SOURCE));
        String previous = SOURCE;
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
                builder.addElement(new PipelineElement(SPLIT, SPLIT_FILTER));
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
}
