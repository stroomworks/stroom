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
import stroom.docstore.shared.DocRefUtil;
import stroom.learningflow.extraction.PerRecord;
import stroom.learningflow.extraction.RecordSplit;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.StepRunner;
import stroom.learningflow.scoring.Attempted;
import stroom.learningflow.scoring.ConfinedXml;
import stroom.learningflow.shared.RecordBoundary;
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
import stroom.util.shared.Severity;
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

/// A [FragmentRunner] for the in-memory scenario tests, which runs a written fragment with the step
/// runners instead of as a real nested pipeline.
///
/// It runs the fragment the same way a learning session runs its candidates before they are written: the
/// chain is followed from `Source` along the links, each element's configuration document is read back
/// from its store, and each step's output is the next step's input. Like a real pipeline, it first merges
/// the whole inheritance stack, so a fragment that inherits its structure from a template (through
/// `parentPipeline`) runs here as it would in a pipeline.
public final class StandInFragmentRunner implements FragmentRunner {

    /// Elements that divide the stream into records without changing what any record says. A real pipeline
    /// runs them; here they are skipped, and their effect is reproduced by running the following steps one
    /// record at a time.
    private static final Set<String> SHAPING = Set.of(PipelineRuns.SPLIT_FILTER);

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

    /// Runs the chain with the module's step runners. A step that produced output but also raised errors
    /// is followed by the next step, as in a pipeline: the errors are records that failed and went to the
    /// error stream, and the scorers count what got through.
    ///
    /// @param boundary What one record is in the stream, as the binding records it, for the scorers that
    ///                 count records; null if none has been settled.
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
        // After the split filter, the chain runs one record at a time, because that is how the pipeline
        // will run it. A stylesheet that reads the whole document behaves differently when it is given one
        // record, and what is scored has to be what will run.
        final OptionalInt depth = boundary == null
                ? OptionalInt.empty()
                : boundary.splitDepth();
        boolean split = false;
        boolean ran = false;
        String current = input;
        for (String id = next.get(PipelineRuns.SOURCE); id != null; id = next.get(id)) {
            final String elementId = id;
            if (PipelineRuns.SOURCE.equals(elementId) || !visited.add(elementId)) {
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
                    // The `SplitFilter` that the fragment includes so that the pipeline gives its
                    // transform one record at a time. There is no runner for it because it changes
                    // nothing in any record; it only changes how many documents the elements after it are
                    // given, which is reproduced from here on.
                    split = true;
                    continue;
                }
                throw new IllegalStateException("No runner for element type " + element.getType()
                                                + " in fragment " + fragment.getName());
            }
            // The snapshot's code for the element if one was given, otherwise the configuration in the
            // fragment's documents.
            final String configuration = code.containsKey(elementId)
                    ? code.get(elementId)
                    : runner.configured()
                            .map(configured -> configuration(merged, fragment, elementId, configured.propertyName()))
                            .orElse(null);
            final boolean first = split && !ran && depth.isPresent();
            ran = ran || first;
            // A fragment has only one SplitFilter. The first element after it is given each record split
            // from the input at the boundary's depth; the elements after that are given what the previous
            // element wrote for each record.
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

    /// Serves a stream by running the chain over the whole text, step by step, and then reading the last
    /// step's output through the same [RecordGate] that a real pipeline's output passes through, so that
    /// what the in-memory tests assert about each record's verdict is what a node would decide. Nothing
    /// here streams, because the whole text is already in memory and tests do not need streaming.
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
        // The parser's records, by index, for a judge that needs the input record whose output fell short.
        // This assumes each input record produces one output record, as the real runner also does. They
        // are counted the same way the real runner counts them, so that the two runners agree on how many
        // records went in.
        final List<String> parsedRecords = !steps.isEmpty() && steps.get(0).parser()
                ? RecordSplit.split(steps.get(0).result().output(), boundary == null
                        ? 1
                        : boundary.splitDepth().orElse(1))
                : List.of();
        final InputRead read = !steps.isEmpty() && steps.get(0).parser()
                ? new InputRead(parsedRecords.size(), errors(steps.get(0)),
                        text.replaceAll("\\s", "").length())
                : InputRead.UNKNOWN;
        if (output == null || output.isBlank()) {
            return new ServedStream(0, 0.0, diagnostics, Optional.empty(), 0, List.of(), read);
        }
        final RecordGate gate = new RecordGate(judge, downstream, index -> index < parsedRecords.size()
                ? Optional.of(parsedRecords.get(index))
                : Optional.empty());
        try {
            final XMLReader reader = ConfinedXml.reader();
            reader.setContentHandler(gate);
            reader.parse(new InputSource(new StringReader(output)));
            gate.finish();
        } catch (final RecordShortfall shortfall) {
            return new ServedStream(gate.records(), gate.meanTotal(), diagnostics, Optional.of(shortfall),
                    gate.skips(), gate.skipReasons(), read);
        } catch (final SAXException | IOException e) {
            throw new IllegalStateException("The chain's output could not be read: " + e.getMessage(), e);
        }
        return new ServedStream(gate.records(), gate.meanTotal(), diagnostics, Optional.empty(),
                gate.skips(), gate.skipReasons(), read);
    }

    /// Counts the errors the parser logged, as the real runner counts them: every error, not the
    /// de-duplicated few that a caller is shown.
    ///
    /// @param parser The parser's step.
    /// @return The number of errors and fatal errors.
    private static int errors(final Attempted parser) {
        return (int) parser.result().diagnostics().stream()
                .filter(error -> Severity.ERROR.equals(error.getSeverity())
                                 || Severity.FATAL_ERROR.equals(error.getSeverity()))
                .count();
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
