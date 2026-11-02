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
import stroom.pipeline.factory.PipelineStackLoader;
import stroom.pipeline.shared.PipelineDataMerger;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.PipelineModelException;
import stroom.pipeline.shared.TextConverterDoc;
import stroom.pipeline.shared.XsltDoc;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineLayer;
import stroom.pipeline.shared.data.PipelineLink;
import stroom.pipeline.shared.data.PipelineProperty;
import stroom.pipeline.textconverter.TextConverterStore;
import stroom.pipeline.xslt.XsltStore;
import stroom.shapeshifter.ai.extraction.PerRecord;
import stroom.shapeshifter.ai.extraction.RecordSplit;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.ai.scoring.ConfinedXml;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.util.shared.StoredError;

import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs a written fragment over an input with the step runners, the way the conversation ran it before it
 * was written: the chain is walked from {@code Source} along the links, each element's configuration
 * document read back from its store, each step's output the next step's input. The Tier 1 stand-in for
 * merging the fragment into a pipeline (design 02 §2). It runs the fragment as a pipeline would — the
 * whole inheritance stack merged, as {@link FragmentCheckImpl} judges it — so a fragment that inherits
 * its structure from a template (§3, {@code parentPipeline}) runs here as it will there.
 */
public final class StandInFragmentRunner implements FragmentRunner {

    private static final String SOURCE = "Source";

    /// Elements that shape the stream into records without changing what any record says: run by the
    /// pipeline, passed over here (§12 item 25).
    private static final Set<String> SHAPING = Set.of("SplitFilter");

    private final PipelineStore pipelineStore;
    private final PipelineStackLoader pipelineStackLoader;
    private final TextConverterStore textConverterStore;
    private final XsltStore xsltStore;
    private final Map<String, StepRunner> runners;

    public StandInFragmentRunner(final PipelineStore pipelineStore,
                                 final PipelineStackLoader pipelineStackLoader,
                                 final TextConverterStore textConverterStore,
                                 final XsltStore xsltStore,
                                 final List<StepRunner> runners) {
        this.pipelineStore = pipelineStore;
        this.pipelineStackLoader = pipelineStackLoader;
        this.textConverterStore = textConverterStore;
        this.xsltStore = xsltStore;
        this.runners = runners.stream()
                .collect(Collectors.toUnmodifiableMap(StepRunner::elementType, Function.identity()));
    }

    /**
     * Walks the chain with the module's step runners. A step that produced output and raised errors on
     * the way is followed, as a pipeline would follow it: the errors are records that failed, dropped to
     * the error stream, and the scorers count what got through (design 01 §5).
     *
     * @param boundary What one record is in the stream (A35), as the binding carries it, for the scorers that
     *                 count records; null where none was settled.
     */
    @Override
    public List<Attempted> run(final DocRef fragment, final String input, final RecordBoundary boundary) {
        return run(fragment, input, boundary, Map.of());
    }

    @Override
    public List<Attempted> run(final DocRef fragment,
                               final String input,
                               final RecordBoundary boundary,
                               final Map<String, String> code) {
        final PipelineDataMerger merged = merge(fragment);
        final Map<String, PipelineElement> elements = merged.getElements();
        final Map<String, String> next = merged.getLinks().values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toMap(PipelineLink::getFrom, PipelineLink::getTo, (a, b) -> {
                    throw new IllegalStateException("Fragment " + fragment.getName()
                                                    + " forks: a fragment is a single chain");
                }));

        final List<Attempted> steps = new ArrayList<>();
        final Set<String> visited = new HashSet<>();
        // From the filter on, the chain runs one record at a time, because that is how the pipeline will
        // run it (§12 item 25): a stylesheet that reads the whole document behaves differently when it is
        // given one record, and what is scored has to be what will run.
        final OptionalInt depth = boundary == null
                ? OptionalInt.empty()
                : boundary.splitDepth();
        boolean split = false;
        boolean ran = false;
        String current = input;
        for (String id = next.get(SOURCE); id != null; id = next.get(id)) {
            final String elementId = id;
            if (SOURCE.equals(elementId) || !visited.add(elementId)) {
                throw new IllegalStateException("Fragment " + fragment.getName()
                                                + " links back to '" + elementId + "': a fragment is a chain");
            }
            final PipelineElement element = elements.get(elementId);
            if (element == null) {
                throw new IllegalStateException("Fragment " + fragment.getName() + " links to element '"
                                                + elementId + "', which it does not define");
            }
            final StepRunner runner = runners.get(element.getType());
            if (runner == null) {
                if (SHAPING.contains(element.getType())) {
                    // The `SplitFilter` the fragment carries so that the pipeline gives its transform one
                    // record at a time (§12 item 25). There is no runner for it because it changes
                    // nothing about any record: what it changes is how many documents the elements after
                    // it are given, and that is what happens from here.
                    split = true;
                    continue;
                }
                throw new IllegalStateException("No runner for element type " + element.getType()
                                                + " in fragment " + fragment.getName());
            }
            // A snapshot's code for the element where one was given (A70), else what the fragment's
            // documents say.
            final String configuration = code.containsKey(elementId)
                    ? code.get(elementId)
                    : runner.configured()
                            .map(configured -> configuration(merged, fragment, elementId, configured.propertyName()))
                            .orElse(null);
            final boolean first = split && !ran && depth.isPresent();
            ran = ran || first;
            // As above: one SplitFilter in a fragment, so what follows the first per-record element is
            // given what that element wrote for each record.
            final StepResult result = split && depth.isPresent()
                    ? PerRecord.run(runner, configuration, current, first
                            ? depth.getAsInt()
                            : PerRecord.WRITTEN)
                    : runner.run(configuration, current);
            final Attempted step = Attempted.of(runner, current, result, boundary);
            steps.add(step);
            if (step.result().output() == null) {
                break;
            }
            current = step.result().output();
        }
        return steps;
    }

    /// The stand-in serves as it runs: the chain over the whole text, step by step, and the tail's output
    /// then read through the same gate the pipeline's tail feeds, so that what Tier 1 asserts about a
    /// record's verdict is what a node decides. Nothing here is streaming — the text is in hand — and
    /// nothing here needs to be.
    @Override
    public ServedStream serve(final DocRef fragment,
                              final InputStream input,
                              final RecordBoundary boundary,
                              final RecordJudge judge,
                              final ContentHandler downstream) {
        final String text;
        try {
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        final List<Attempted> steps = run(fragment, text, boundary);
        final List<StoredError> diagnostics = steps.stream()
                .flatMap(step -> step.result().diagnostics().stream())
                .toList();
        final String output = steps.isEmpty()
                ? null
                : steps.get(steps.size() - 1).result().output();
        if (output == null || output.isBlank()) {
            return new ServedStream(0, 0.0, diagnostics, Optional.empty());
        }
        // The parser's records, by index, for a judge that wants the record whose output fell short: the
        // one-to-one premise of design 04 §3, which is what the pipeline's tap gives too.
        final List<String> parsedRecords = steps.get(0).parser()
                ? RecordSplit.split(steps.get(0).result().output(), boundary == null
                        ? 1
                        : boundary.splitDepth().orElse(1))
                : List.of();
        final RecordGate gate = new RecordGate(judge, downstream, index -> index < parsedRecords.size()
                ? Optional.of(parsedRecords.get(index))
                : Optional.empty());
        try {
            final XMLReader reader = ConfinedXml.reader();
            reader.setContentHandler(gate);
            reader.parse(new InputSource(new StringReader(output)));
            gate.finish();
        } catch (final RecordShortfall shortfall) {
            return new ServedStream(gate.records(), gate.meanTotal(), diagnostics, Optional.of(shortfall));
        } catch (final SAXException | IOException e) {
            throw new IllegalStateException("The chain's output could not be read: " + e.getMessage(), e);
        }
        return new ServedStream(gate.records(), gate.meanTotal(), diagnostics, Optional.empty());
    }

    private PipelineDataMerger merge(final DocRef fragment) {
        final PipelineDoc doc = pipelineStore.readDocument(fragment);
        final List<PipelineLayer> layers = pipelineStackLoader.loadPipelineStack(doc).stream()
                .map(layer -> new PipelineLayer(DocRefUtil.create(layer), layer.getPipelineData()))
                .toList();
        try {
            return new PipelineDataMerger().merge(layers);
        } catch (final PipelineModelException e) {
            throw new IllegalStateException("Fragment " + fragment.getName() + " cannot be merged: "
                                            + e.getMessage(), e);
        }
    }

    private String configuration(final PipelineDataMerger merged,
                                 final DocRef fragment,
                                 final String elementId,
                                 final String property) {
        final PipelineProperty found = merged.getProperties()
                .getOrDefault(elementId, Map.of())
                .get(property);
        final DocRef docRef = found == null || found.getValue() == null
                ? null
                : found.getValue().getEntity();
        if (docRef == null) {
            throw new IllegalStateException("Element " + elementId + " of fragment " + fragment.getName()
                                            + " has no " + property + " document");
        }
        return switch (docRef.getType()) {
            case TextConverterDoc.TYPE -> textConverterStore.readDocument(docRef).getData();
            case XsltDoc.TYPE -> xsltStore.readDocument(docRef).getData();
            default -> throw new IllegalStateException("No store for " + docRef.getType());
        };
    }
}
