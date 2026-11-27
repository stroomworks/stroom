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

package stroom.learningflow.fragment;

import stroom.docref.DocRef;
import stroom.learningflow.extraction.PerRecord;
import stroom.learningflow.extraction.RecordJoin;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.StepRunner;
import stroom.learningflow.scoring.Attempted;
import stroom.learningflow.shared.RecordBoundary;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiver;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.LoggedException;
import stroom.pipeline.errorhandler.LoggingErrorReceiver;
import stroom.pipeline.factory.ElementRegistryFactory;
import stroom.pipeline.factory.InjectedCode;
import stroom.pipeline.factory.Pipeline;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.factory.PipelineFactory;
import stroom.pipeline.reader.BOMRemovalInputStream;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineLink;
import stroom.pipeline.shared.stepping.NestedElementData;
import stroom.pipeline.stepping.capture.HeadlessCapture;
import stroom.pipeline.xml.event.EventList;
import stroom.pipeline.xml.event.simple.SimpleEventList;
import stroom.pipeline.xml.event.simple.SimpleEventListBuilder;
import stroom.processor.api.DeferTaskException;
import stroom.task.api.TaskContextFactory;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.ElementId;
import stroom.util.shared.Indicators;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// The [FragmentRunner] used on a real node. It runs a fragment as a real Stroom pipeline, with the real
/// elements, pools and filters, and reads what each element made of the stream from a capture.
///
/// A node uses this both to judge a candidate fragment and to serve streams with a binding. Running the
/// real pipeline matters because a stand-in is only as accurate as whoever wrote it: the pipeline really
/// runs a `SplitFilter`, compiles a stylesheet through the pool, and applies whatever the fragment
/// inherits from its parent. The module's step runners do none of these, and all of them decide whether
/// a promoted binding works on the next stream.
///
/// When judging, each element's *input* is taken to be what the element before it wrote, rather than
/// read from the capture, because the recorders above the parser report what they read by source
/// location and a headless run has no source locations to ask for (see [HeadlessCapture]). The first
/// element's input is the stream itself, which is what the input coverage scorer needs, and every later
/// element is given its predecessor's output, which is the same chain the scorers read.
public final class PipelineFragmentRunner implements FragmentRunner {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(PipelineFragmentRunner.class);

    /// The id of the XML parser added in front of a fragment that has no parser of its own. A fragment
    /// learned over XML input holds no parser, because its input is already records, but this runner
    /// still hands it a stream of bytes, so an XML parser is put in front to turn the text into events.
    /// It is not part of the fragment's chain as far as scoring and stepping are concerned: those read
    /// the fragment's own elements only.
    private static final String XML_PARSER_ID = "learningFlowFragmentParser";

    /// The id of the [FragmentOutputFilter] added to the end of the chain, which captures the events the
    /// chain produces so that they can be replayed to the Smart Pipeline element's own downstream rather
    /// than produced a second time (see [FragmentRunner#lastOutput]).
    private static final String OUTPUT_ID = "learningFlowOutput";
    /// The id of the [FragmentTapFilter] that a serving run adds below the parser to see each record.
    private static final String TAP_ID = "learningFlowTap";

    /// Element types that divide the stream into records without changing what any record says. They
    /// are run by the pipeline but are not treated as steps that are scored.
    private static final Set<String> SHAPING = Set.of(PipelineRuns.SPLIT_FILTER);

    private final PipelineStore pipelineStore;
    private final PipelineDataCache pipelineDataCache;
    private final ElementRegistryFactory elementRegistryFactory;
    private final Provider<PipelineFactory> pipelineFactoryProvider;
    private final Provider<HeadlessCapture> captureProvider;
    private final Provider<ErrorReceiverProxy> errorReceiverProvider;
    private final Provider<FragmentOutput> fragmentOutputProvider;
    private final Provider<FragmentTap> fragmentTapProvider;
    private final Provider<InjectedCode> injectedCodeProvider;
    private final TaskContextFactory taskContextFactory;

    private SimpleEventList lastOutput;
    /// What the fragment's own elements were given and wrote on the latest run, for a caller that shows
    /// the chain in the stepper. Replaced by the next run, as [#lastOutput] is.
    private List<FragmentRecord> lastRecords = List.of();

    @Inject
    public PipelineFragmentRunner(final PipelineStore pipelineStore,
                                  final PipelineDataCache pipelineDataCache,
                                  final ElementRegistryFactory elementRegistryFactory,
                                  final Provider<PipelineFactory> pipelineFactoryProvider,
                                  final Provider<HeadlessCapture> captureProvider,
                                  final Provider<ErrorReceiverProxy> errorReceiverProvider,
                                  final Provider<FragmentOutput> fragmentOutputProvider,
                                  final Provider<FragmentTap> fragmentTapProvider,
                                  final Provider<InjectedCode> injectedCodeProvider,
                                  final TaskContextFactory taskContextFactory) {
        this.pipelineStore = pipelineStore;
        this.pipelineDataCache = pipelineDataCache;
        this.elementRegistryFactory = elementRegistryFactory;
        this.pipelineFactoryProvider = pipelineFactoryProvider;
        this.captureProvider = captureProvider;
        this.errorReceiverProvider = errorReceiverProvider;
        this.fragmentOutputProvider = fragmentOutputProvider;
        this.fragmentTapProvider = fragmentTapProvider;
        this.injectedCodeProvider = injectedCodeProvider;
        this.taskContextFactory = taskContextFactory;
    }

    @Override
    public List<Attempted> run(final DocRef fragment, final String input, final RecordBoundary boundary) {
        return run(fragment, input, boundary, Map.of());
    }

    @Override
    public List<Attempted> run(final DocRef fragment,
                               final String input,
                               final RecordBoundary boundary,
                               final Map<String, String> code) {
        final PipelineDoc fragmentDoc = pipelineStore.readDocument(fragment);
        final PipelineData merged = pipelineDataCache.get(fragmentDoc);

        final Map<String, PipelineElement> elements = elements(merged);
        final Map<String, String> next = next(fragment, merged);

        // A new capture for each run, so it holds only what this fragment did with this stream.
        final HeadlessCapture capture = captureProvider.get();
        capture.setMaxRecords(PipelineRuns.MOST_RECORDS);
        // Cleared before the run rather than after it, so that if this run throws, the previous run's
        // records are not shown as though they were this run's.
        lastRecords = List.of();

        // A new event recorder for each run, for a caller that wants to replay the chain's output rather
        // than run the fragment again. It is kept however the run ends, since a run that stopped leaves an
        // empty list rather than a stale one.
        final SimpleEventListBuilder emitted = new SimpleEventListBuilder();
        lastOutput = (SimpleEventList) emitted.getEventList();
        final FragmentOutput fragmentOutput = fragmentOutputProvider.get();
        final ContentHandler borrowed = fragmentOutput.getHandler();
        fragmentOutput.setHandler(emitted);

        // Give the elements an error receiver to log to. A candidate that will not compile reports that
        // through the receiver, and without one in place the report becomes a NullPointerException that
        // fails the whole attempt. The previous receiver is restored afterwards, because this run happens
        // inside another pipeline and its errors are not that pipeline's errors.
        final ErrorReceiverProxy errorReceiverProxy = errorReceiverProvider.get();
        final ErrorReceiver previous = errorReceiverProxy.getErrorReceiver();
        final LoggingErrorReceiver logging = new LoggingErrorReceiver();
        errorReceiverProxy.setErrorReceiver(logging);
        // Likewise, clear any code injected by a person stepping the outer pipeline. Injected code is
        // keyed by element id and scoped to the pipeline, so a person stepping a pipeline whose
        // XSLTFilter happens to be called `xsltFilter` (which is the id this feature gives the transform
        // in every fragment it writes) would have their edit compiled into the fragment in place of what
        // was learned. The fragment must run what its own documents say. The injected code is restored
        // afterwards so that the person's edits are kept.
        final InjectedCode injectedCode = injectedCodeProvider.get();
        final Map<String, String> borrowedCode = injectedCode.asMap();
        // Replace it with no code, or, for an as-processed reprocess, with the code from the snapshot
        // that originally produced the output, in place of what the fragment's documents say today.
        injectedCode.set(code);
        String failure = null;
        try {
            final Pipeline pipeline = pipelineFactoryProvider.get()
                    .create(runnable(fragment, merged, elements, next), taskContextFactory.current(), capture);
            pipeline.process(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8.name());
        } catch (final LoggedException e) {
            // The error has already been reported through the receiver, which is where the diagnostics
            // are read from. A candidate that will not compile stops the pipeline in exactly this way,
            // and that is a valid result of the judgement, not a failure to make it.
            LOGGER.debug("Fragment {} stopped: {}", fragment.getName(), e.getMessage());
        } catch (final RuntimeException e) {
            // An error that no element reported. It is kept so that a step that produced nothing still
            // has a reason to show.
            LOGGER.debug(() -> LogUtil.message("Fragment {} failed: {}", fragment.getName(), e.getMessage()), e);
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            errorReceiverProxy.setErrorReceiver(previous);
            fragmentOutput.setHandler(borrowed);
            injectedCode.set(borrowedCode);
        }

        final List<Attempted> steps = new ArrayList<>();
        final Set<String> visited = new HashSet<>();
        String current = input;
        for (String id = next.get(PipelineRuns.SOURCE); id != null; id = next.get(id)) {
            if (!visited.add(id)) {
                throw new IllegalStateException("Fragment " + fragment.getName() + " loops at " + id);
            }
            final PipelineElement element = elements.get(id);
            if (element == null) {
                throw new IllegalStateException("Fragment " + fragment.getName() + " links to " + id
                                                + ", which it does not define");
            }
            if (SHAPING.contains(element.getType())) {
                continue;
            }
            final String output = written(capture, id);
            // A step that produced nothing ends the chain, as it does in the stand-in runner, and must
            // carry the reason. The element that stopped the pipeline is often not the one that produced
            // nothing: a stylesheet that will not compile fails before the parser above it has written a
            // single record. So the stopping step carries the diagnostics of every element from here on,
            // otherwise the run would report a chain that stopped for no stated reason.
            final List<String> speaking = output == null
                    ? remaining(id, next, elements)
                    : List.of(id);
            final StepResult result = new StepResult(output,
                    PipelineRuns.diagnostics(capture, speaking, logging, output == null
                            ? failure
                            : null));
            // Whether the element is a parser is asked of the node's element registry rather than the
            // step runners (see `parses`). A fragment may hold a parser that no step runner covers
            // (Stroom's XML parser, the combined parser, anything a person added by hand), and a parser
            // mistaken for a filter would have its output judged against the target schema, which would
            // fail every stream of a working chain.
            steps.add(new Attempted(element.getType(), parses(element), current, result, boundary, null));
            if (output == null) {
                break;
            }
            current = output;
        }
        lastRecords = recorded(capture, chain(fragment, elements, next));
        return steps;
    }

    /// The fragment's own elements, by id.
    private static Map<String, PipelineElement> elements(final PipelineData merged) {
        final Map<String, PipelineElement> elements = new HashMap<>();
        merged.getAddedElements().forEach(element -> elements.put(element.getId(), element));
        return elements;
    }

    /// The fragment's links, as a map from each element's id to the id of the element it feeds.
    ///
    /// @throws IllegalStateException If an element feeds more than one element, since a fragment must be a
    /// single chain.
    private static Map<String, String> next(final DocRef fragment, final PipelineData merged) {
        final Map<String, String> next = new HashMap<>();
        for (final PipelineLink link : merged.getAddedLinks()) {
            if (next.put(link.getFrom(), link.getTo()) != null) {
                throw new IllegalStateException("Fragment " + fragment.getName()
                                                + " forks: a fragment is a single chain");
            }
        }
        return next;
    }

    @Override
    public ServedStream serve(final DocRef fragment,
                              final InputStream input,
                              final RecordBoundary boundary,
                              final RecordJudge judge,
                              final ContentHandler downstream) {
        final PipelineDoc fragmentDoc = pipelineStore.readDocument(fragment);
        final PipelineData merged = pipelineDataCache.get(fragmentDoc);
        final Map<String, PipelineElement> elements = elements(merged);
        final Map<String, String> next = next(fragment, merged);
        // No capture is used, because a serving run must not hold the stream in memory. The chain's
        // output goes to the record gate, which judges each record and passes on those that clear the bar.
        lastRecords = List.of();
        lastOutput = null;
        // Tap the parser's records as they are produced, so that a record whose output falls short can be
        // shown to a learning session as the parser produced it. This is only done where the fragment has
        // its own parser; a fragment that is given records has nothing of its own to tap.
        final boolean parsed = parses(elements.get(next.get(PipelineRuns.SOURCE)));
        // Divide records at the same depth as the split does, so that the records the tap counts are the
        // ones the transform was asked to turn into events. The two counts are compared when checking
        // completeness.
        final RecordTap tap = new RecordTap(boundary == null
                ? 1
                : boundary.splitDepth().orElse(1));
        final RecordGate gate = new RecordGate(judge, downstream, parsed
                ? index -> tap.current()
                : index -> Optional.empty());
        final FragmentOutput fragmentOutput = fragmentOutputProvider.get();
        final ContentHandler borrowed = fragmentOutput.getHandler();
        fragmentOutput.setHandler(gate);
        final FragmentTap fragmentTap = fragmentTapProvider.get();
        final ContentHandler borrowedTap = fragmentTap.getHandler();
        fragmentTap.setHandler(parsed
                ? tap
                : null);
        final ErrorReceiverProxy errorReceiverProxy = errorReceiverProvider.get();
        final ErrorReceiver previous = errorReceiverProxy.getErrorReceiver();
        final LoggingErrorReceiver logging = new LoggingErrorReceiver();
        errorReceiverProxy.setErrorReceiver(logging);
        final InjectedCode injectedCode = injectedCodeProvider.get();
        final Map<String, String> borrowedCode = injectedCode.asMap();
        injectedCode.set(Map.of());
        RecordShortfall shortfall = null;
        String failure = null;
        // Count the content the run reads, so that a stream the parser made nothing of can be told apart
        // from an empty one.
        final ContentCountInputStream counted = new ContentCountInputStream(input);
        try {
            final Pipeline pipeline = pipelineFactoryProvider.get()
                    .create(tapped(runnable(fragment, merged, elements, next), next.get(PipelineRuns.SOURCE), parsed),
                            taskContextFactory.current());
            // A byte order mark is not content. The path that reads the whole stream as text removes it
            // before the fragment sees it, so it is removed here too.
            pipeline.process(new BOMRemovalInputStream(counted, StandardCharsets.UTF_8.name()),
                    StandardCharsets.UTF_8.name());
            gate.finish();
        } catch (final SAXException e) {
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (final RuntimeException e) {
            // The gate stops the run by throwing a RecordShortfall out of the pipeline, which may wrap it.
            // It is a DeferTaskException, so the layers that let deferrals through also let it through.
            final DeferTaskException deferral = DeferTaskException.unwrap(e);
            if (deferral instanceof final RecordShortfall stopped) {
                shortfall = stopped;
            } else if (deferral != null) {
                throw deferral;
            } else if (e instanceof LoggedException) {
                LOGGER.debug("Fragment {} stopped: {}", fragment.getName(), e.getMessage());
            } else {
                LOGGER.debug(() -> LogUtil.message("Fragment {} failed: {}", fragment.getName(), e.getMessage()), e);
                failure = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        } finally {
            errorReceiverProxy.setErrorReceiver(previous);
            fragmentOutput.setHandler(borrowed);
            fragmentTap.setHandler(borrowedTap);
            injectedCode.set(borrowedCode);
        }
        final List<StoredError> diagnostics = new ArrayList<>();
        for (final PipelineElement element : chain(fragment, elements, next)) {
            final Indicators said = logging.getIndicators(new ElementId(element.getId()));
            if (said != null) {
                diagnostics.addAll(said.getErrorList());
            }
        }
        if (diagnostics.isEmpty() && failure != null) {
            diagnostics.add(new StoredError(Severity.FATAL_ERROR, null, new ElementId(next.get(PipelineRuns.SOURCE)),
                    failure));
        }
        return new ServedStream(gate.records(), gate.meanTotal(), PerRecord.told(diagnostics),
                Optional.ofNullable(shortfall), gate.skips(), gate.skipReasons(),
                read(parsed, tap, logging, next.get(PipelineRuns.SOURCE), counted.content()));
    }

    /// Summarises what the parser made of the input.
    ///
    /// The parser's errors are counted from its own indicators rather than from the diagnostics, because
    /// the diagnostics are de-duplicated and capped at twenty: a stream with a hundred unmatched lines
    /// would otherwise report twenty errors.
    ///
    /// @param parsed  Whether the fragment has a parser of its own.
    /// @param tap     The tap below the parser, which counted the records it produced.
    /// @param logging The error receiver the run's elements logged to.
    /// @param parser  The parser's element id.
    /// @param bytes   How many bytes of non-whitespace content the run read.
    /// @return What the parser read, or [InputRead#UNKNOWN] for a fragment with no parser of its own.
    private static InputRead read(final boolean parsed,
                                  final RecordTap tap,
                                  final LoggingErrorReceiver logging,
                                  final String parser,
                                  final long bytes) {
        if (!parsed) {
            return InputRead.UNKNOWN;
        }
        final Indicators said = logging.getIndicators(new ElementId(parser));
        final int errors = said == null
                ? 0
                : said.getCount(Severity.ERROR) + said.getCount(Severity.FATAL_ERROR);
        return new InputRead(tap.records(), errors, bytes);
    }


    // --------------------------------------------------------------------------------


    /// Returns the latest run's records and clears them (see [FragmentRunner#takeRecords]), so that a
    /// decision that runs no fragment is not given the chain of the decision before it.
    @Override
    public List<FragmentRecord> takeRecords() {
        final List<FragmentRecord> taken = lastRecords;
        lastRecords = List.of();
        return taken;
    }

    /// The fragment's own elements in the order they run, as a person would recognise the chain. The
    /// parser this runner adds in front of a chain without one, and the output filter it adds at the end,
    /// are scaffolding and not part of what was learned, so they are not included. The scored walk in
    /// [#run] leaves them out in the same way.
    ///
    /// Unlike in the scored walk, elements such as `SplitFilter` that only divide the stream are kept: a
    /// `SplitFilter` is not judged, but it did run, and a person stepping the chain should see where the
    /// records were divided.
    private static List<PipelineElement> chain(final DocRef fragment,
                                               final Map<String, PipelineElement> elements,
                                               final Map<String, String> next) {
        final List<PipelineElement> chain = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (String id = next.get(PipelineRuns.SOURCE); id != null && seen.add(id); id = next.get(id)) {
            final PipelineElement element = elements.get(id);
            if (element != null) {
                chain.add(element);
            }
        }
        return chain;
    }

    /// One entry per record the capture kept, holding what each element of the chain was given and
    /// wrote for that record.
    ///
    /// An element that produced nothing for a record still has an entry, because a person stepping the
    /// chain needs to see *which* element stopped and not merely that the chain did.
    private static List<FragmentRecord> recorded(final HeadlessCapture capture,
                                                 final List<PipelineElement> chain) {
        final List<FragmentRecord> records = new ArrayList<>();
        for (final HeadlessCapture.Record record : capture.getRecords()) {
            final List<NestedElementData> elements = new ArrayList<>();
            for (final PipelineElement element : chain) {
                final HeadlessCapture.ElementIo io = record.byElement().get(element.getId());
                elements.add(new NestedElementData(
                        element.getId(),
                        element.getId(),
                        element.getType(),
                        io == null
                                ? null
                                : io.input(),
                        io == null
                                ? null
                                : io.output(),
                        // formatInput: every element after the first is given XML, which the stepper
                        // pretty-prints as it does anywhere else. The first element is given the text the
                        // stage was given, and that is shown as it arrived.
                        !chain.isEmpty() && !element.equals(chain.get(0)),
                        // formatOutput: every element, including the first, writes XML, so the stepper
                        // always formats the output.
                        true,
                        // wholeStream and truncated: whether one run covered the whole stream, and so
                        // whether its input and output must be truncated before they are sent, depends on
                        // what the calling stage was given, not on the runner. They are set later, when
                        // the records are handed out.
                        false,
                        false));
            }
            records.add(new FragmentRecord(List.copyOf(elements)));
        }
        return List.copyOf(records);
    }

    @Override
    public Optional<EventList> lastOutput() {
        // A run that emitted nothing has nothing to serve. Sending an empty event list downstream would
        // write an empty stream, which looks like a feed that had nothing in it, so the caller is told
        // there are no events and reports why.
        return Optional.ofNullable(lastOutput)
                .filter(events -> !events.getEvents().isEmpty())
                .map(EventList.class::cast);
    }

    /// Builds the fragment as a pipeline that can be given a stream. Two elements are added to what the
    /// fragment itself holds: an XML parser in front where the chain has no parser, because a fragment
    /// learned over XML input is given text here; and a [FragmentOutputFilter] at the end, so that the
    /// events the last element produces are kept.
    ///
    /// The walk that reports what each element made of the input reads only the fragment's own elements,
    /// so nothing added here is scored, sent back to the model or shown to anyone.
    private PipelineData runnable(final DocRef fragment,
                                  final PipelineData merged,
                                  final Map<String, PipelineElement> elements,
                                  final Map<String, String> next) {
        final PipelineDataBuilder builder = new PipelineDataBuilder(merged);
        final String first = next.get(PipelineRuns.SOURCE);
        if (first == null) {
            throw new IllegalStateException("Fragment " + fragment.getName() + " links nothing to its source");
        }
        if (!parses(elements.get(first))) {
            // The link is taken out of the add list rather than put on the remove list, because
            // `removeLink` records a link removed from an *inherited* pipeline, and this one is not
            // inherited. If it were left, the source would still be linked straight to an element that
            // cannot take a stream.
            builder.getLinks().getAddList()
                    .removeIf(link -> PipelineRuns.SOURCE.equals(link.getFrom()) && first.equals(link.getTo()));
            builder.addElement(new PipelineElement(XML_PARSER_ID, PipelineRuns.XML_PARSER));
            builder.addLink(PipelineRuns.SOURCE, XML_PARSER_ID);
            builder.addLink(XML_PARSER_ID, first);
        }
        String tail = first;
        final Set<String> seen = new HashSet<>();
        seen.add(first);
        for (String id = next.get(tail); id != null && seen.add(id); id = next.get(id)) {
            tail = id;
        }
        builder.addElement(new PipelineElement(OUTPUT_ID, FragmentOutputFilter.TYPE));
        builder.addLink(tail, OUTPUT_ID);
        return builder.build();
    }

    /// Adds a [FragmentTapFilter] to the runnable fragment as a second child of its parser, beside the
    /// split and the transform, so that the tap receives the parser's events as they are produced.
    private static PipelineData tapped(final PipelineData runnable, final String parser, final boolean parsed) {
        if (!parsed) {
            return runnable;
        }
        final PipelineDataBuilder builder = new PipelineDataBuilder(runnable);
        builder.addElement(new PipelineElement(TAP_ID, FragmentTapFilter.TYPE));
        builder.addLink(parser, TAP_ID);
        return builder.build();
    }

    /// Whether an element of a fragment turns raw input into records, according to the node's element
    /// registry. This is the same test [ReplayUnits] uses to derive a fragment's replay unit.
    ///
    /// The step runners are not asked, because a fragment may hold a parser that no step runner covers
    /// (Stroom's XML parser, the combined parser, anything a person added by hand). A parser mistaken for
    /// a filter would get another parser added in front of it, giving a pipeline that cannot be linked.
    private boolean parses(final PipelineElement element) {
        return element != null && ReplayUnits.parses(elementRegistryFactory.get(), element.getType());
    }

    /// The ids of this element and every scored element after it in the chain: the elements that might
    /// have reported why a run stopped here.
    private static List<String> remaining(final String from,
                                          final Map<String, String> next,
                                          final Map<String, PipelineElement> elements) {
        final List<String> ids = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (String id = from; id != null && seen.add(id); id = next.get(id)) {
            if (elements.containsKey(id) && !SHAPING.contains(elements.get(id).getType())) {
                ids.add(id);
            }
        }
        return ids;
    }

    /// What one element wrote for the whole stream, with each record's output joined back into one
    /// document, since that is what the scorers and the golden test files read (see [RecordJoin]).
    private static String written(final HeadlessCapture capture, final String elementId) {
        return RecordJoin.join(capture.outputOf(elementId));
    }
}
