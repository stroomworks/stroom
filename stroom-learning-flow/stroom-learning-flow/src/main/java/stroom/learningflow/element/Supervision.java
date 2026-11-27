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
import stroom.learningflow.doc.LearningFlowStore;
import stroom.learningflow.fragment.FragmentRunner.FragmentRecord;
import stroom.learningflow.fragment.ReplayUnits;
import stroom.learningflow.fragment.ServedStream;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningFlowStepDetails;
import stroom.learningflow.shared.ReplayUnit;
import stroom.learningflow.stage.Decision;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.Deferred;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Decision.Rebound;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.Produced;
import stroom.learningflow.stage.RecordSkeleton;
import stroom.learningflow.stage.Stage;
import stroom.learningflow.stage.Stage.Served;
import stroom.learningflow.stage.StageRun;
import stroom.meta.api.AttributeMap;
import stroom.meta.shared.Meta;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.ProcessException;
import stroom.pipeline.factory.ElementRegistryFactory;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.stepping.NestedElementData;
import stroom.pipeline.state.FeedHolder;
import stroom.pipeline.state.MetaData;
import stroom.pipeline.state.MetaDataHolder;
import stroom.pipeline.state.MetaHolder;
import stroom.pipeline.state.PipelineContext;
import stroom.pipeline.state.PipelineHolder;
import stroom.processor.api.DeferTaskException;
import stroom.util.pipeline.scope.PipelineScoped;
import stroom.util.shared.ElementId;
import stroom.util.shared.NullSafe;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import jakarta.inject.Inject;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Does the work of the Smart Pipeline element ([LearningFlowParser]) for each stream: reads the Learning
/// Flow document, checks the element's position, builds the [Input] the [Stage] sees, asks the stage for
/// a decision, records the bindings on the output stream's attributes, and passes the fragment's events to
/// the element's downstream. It also keeps what the stepper's Smart Pipeline pane shows.
///
/// It is pipeline-scoped because the holders it reads the stream's identity from (meta, feed and
/// pipeline) are. One instance serves every Smart Pipeline element in a pipeline: a [Stage] holds nothing
/// between calls, so one is as good as many.
@PipelineScoped
public class Supervision {

    private final LearningFlowStore store;
    private final ElementRegistryFactory elementRegistryFactory;
    private final FeedHolder feedHolder;
    private String timeZone;
    private final MetaHolder metaHolder;
    private final MetaDataHolder metaDataHolder;
    private final MetaData metaData;
    private final ErrorReceiverProxy errorReceiverProxy;
    private final PipelineHolder pipelineHolder;
    private final PipelineContext pipelineContext;
    private final Stage stage;

    /// The binding UUID each Smart Pipeline element in this pipeline has recorded on this stream's
    /// attributes, by element id. It is pipeline-scoped, like the attributes it guards.
    private final Map<String, String> recordedBy = new HashMap<>();
    /// The suffix each element's stream attributes were given, so that an attribute written after the
    /// bindings were recorded (e.g. the skipped count) is named the same way.
    private final Map<String, String> attributeSuffix = new HashMap<>();

    /// What each element last decided, by element id, for the stepper's Smart Pipeline pane to show.
    /// Replaced each time the element runs, because the capture asks about the record just processed.
    private final Map<String, LearningFlowStepDetails> stepDetails = new HashMap<>();

    /// What the fragment's own elements made of each record on this element's latest run, by element id.
    /// Kept separately from the decision because the decision applies to the whole stream while these
    /// apply to each record: the element decides once and then produces many records, and the capture
    /// asks about each of them separately.
    private final Map<String, List<FragmentRecord>> fragmentRecords = new HashMap<>();

    /// The separator between the Smart Pipeline element's id and the fragment element's id in a nested
    /// element's id. Element ids cannot contain it, so a prefixed id can never be mistaken for the id of
    /// an element in the outer pipeline.
    private static final String NESTED_SEPARATOR = "/";

    /// The maximum length of a whole-stream chain's text that is stored against each record of the stream
    /// it ran over. It is enough to show the shape of what the chain produced (a few dozen records of most
    /// feeds), and small enough that storing it against every record of a long stream stays within what
    /// the stepping store will hold. See [#carried].
    private static final int WHOLE_STREAM_EXCERPT = 16 * 1024;

    /// The maximum number of distinct reasons for declared skips that the summary line names. Enough to
    /// say what kind of record was skipped without turning the error stream into a list of them.
    private static final int SKIP_REASONS_SHOWN = 3;

    @Inject
    public Supervision(final LearningFlowStore store,
                       final ElementRegistryFactory elementRegistryFactory,
                       final FeedHolder feedHolder,
                       final MetaHolder metaHolder,
                       final MetaDataHolder metaDataHolder,
                       final MetaData metaData,
                       final ErrorReceiverProxy errorReceiverProxy,
                       final PipelineHolder pipelineHolder,
                       final PipelineContext pipelineContext,
                       final StageFactory stageFactory) {
        this.store = store;
        this.elementRegistryFactory = elementRegistryFactory;
        this.feedHolder = feedHolder;
        this.metaHolder = metaHolder;
        this.metaDataHolder = metaDataHolder;
        this.metaData = metaData;
        this.errorReceiverProxy = errorReceiverProxy;
        this.pipelineHolder = pipelineHolder;
        this.pipelineContext = pipelineContext;
        this.stage = stageFactory.create();
    }

    /// Reads the Learning Flow document an element names.
    ///
    /// @param docRef    What the element's property was set to; null where it was not set.
    /// @param elementId The element, named in the error if there is no document.
    /// @return The document.
    /// @throws ProcessException If the property is not set or the document does not exist.
    public LearningFlowDoc document(final DocRef docRef, final ElementId elementId) {
        if (docRef == null) {
            throw ProcessException.create("No Learning Flow is set on element " + elementId);
        }
        final LearningFlowDoc doc = store.readDocument(docRef);
        if (doc == null) {
            throw ProcessException.create("Learning Flow " + docRef + " was not found");
        }
        return doc;
    }

    /// Checks that what the element may learn suits its position. The element is fed by the source and
    /// given raw data, so the chains the document's allowed elements can make must include a parser. This
    /// is checked when the pipeline is built rather than when the model is asked, so that a document that
    /// cannot learn anything usable says so before a model call has been spent finding out. The check is
    /// skipped where the document names no allowed elements and so constrains nothing.
    ///
    /// @param doc       The element's Learning Flow document.
    /// @param elementId The Smart Pipeline element.
    /// @throws ProcessException If none of the allowed elements is a parser.
    public void checkPosition(final LearningFlowDoc doc, final ElementId elementId) {
        if (NullSafe.isEmptyCollection(doc.getAllowedElements())) {
            return;
        }
        final ReplayUnit allowed = ReplayUnits.ofElements(doc.getAllowedElements(), this::parses);
        if (allowed != ReplayUnit.STREAM) {
            throw ProcessException.create(ReplayUnits.mismatch(allowed,
                    "The allowed elements of Learning Flow " + doc.getName()));
        }
    }

    /// Decides what to do with one stream and passes what the bound fragment made of it to the element's
    /// own downstream.
    ///
    /// The fragment is run only once: the run the stage judged is the run that is served. A decision that
    /// binds nothing (e.g. a learner that gave up, or a draft awaiting review) emits nothing and logs the
    /// reason to the error stream.
    ///
    /// Stepping is a dry run: the stage serves, and a learner that nothing serves is reported rather than
    /// learned. A person opening the stepper on a feed nobody has taught yet must not spend a model call
    /// or write a binding just by looking.
    ///
    /// The stage can handle streams of any size. Stepping and as-processed reprocessing read the whole
    /// stream as text, because stepping is limited by the person stepping and a reprocess has to repeat
    /// what was done. A processing task reads only the stream's prefix as text, which is all the sample
    /// shown to the model needs, and the fragment reads the rest as it serves the stream.
    ///
    /// @param doc         The element's Learning Flow document.
    /// @param elementId   The Smart Pipeline element.
    /// @param asProcessed Whether to reprocess with the snapshot that produced the stream's earlier output
    ///                    rather than the current binding.
    /// @param source      The stream.
    /// @param downstream  Where the fragment's events go.
    /// @throws SAXException If the downstream fails.
    public void supervise(final LearningFlowDoc doc,
                          final ElementId elementId,
                          final boolean asProcessed,
                          final InputSource source,
                          final ContentHandler downstream) throws SAXException {
        final Input input = pipelineContext.isStepping() || asProcessed
                ? input(read(source), elementId)
                : streamed(source, elementId);
        supervise(doc, elementId, asProcessed, input, downstream);
    }

    private void supervise(final LearningFlowDoc doc,
                           final ElementId elementId,
                           final boolean asProcessed,
                           final Input input,
                           final ContentHandler downstream) throws SAXException {
        final boolean stepping = pipelineContext.isStepping();
        // A processing task decides to emit, defer or error, never learns, and serves a bound stream as it
        // is read. A reprocess asks what was done, and a step asks what would be done.
        if (!stepping && !asProcessed) {
            decideAndStream(doc, elementId, input, downstream);
            return;
        }
        final StageRun run = stepping
                ? stage.dryRun(doc, learner(elementId), input, asProcessed)
                : stage.reprocess(doc, learner(elementId), input);
        // Keep what the stepper pane shows for this element, for the capture to ask for. It is kept per
        // element, because a pipeline may hold two Smart Pipeline elements and each has its own decision.
        stepDetails.put(elementId.getId(), StepDetails.of(run, input, stepping));
        // Also keep what the fragment's own elements made of it, record by record, so that the chain can
        // be stepped beneath the element. This is only kept when stepping: nothing else asks for it, and
        // holding a record's worth of text per record of a production stream would leak memory for no
        // reader. The records are taken rather than read, so that a decision made without running a
        // fragment at all (a draft awaiting review, a learner that gave up) cannot be shown the previous
        // run's chain. They are taken even when not stepping, to clear what the last step left.
        final List<FragmentRecord> ran = stage.takeFragment();
        fragmentRecords.put(elementId.getId(), stepping
                ? ran
                : List.of());
        final Produced produced = run.produced();
        // Log what happened or, for a step, what would have happened. A dry run's report is logged as INFO
        // rather than an error, because a learner nobody has taught yet is the normal state of a feed a
        // person opens the stepper on.
        final Severity said = stepping
                ? Severity.INFO
                : produced == null
                        ? Severity.ERROR
                        : Severity.INFO;
        StepDetails.lines(run.decision()).forEach(line -> errorReceiverProxy.log(said, null, elementId, line, null));

        // Bindings are only recorded where a fragment really was bound and really did run: a step leaves
        // no trace, and the bindings of a run that did not happen do not belong on the stream.
        if (produced != null && !stepping) {
            record(produced, elementId);
        }

        // What is served is decided by the *events*, not the bindings. A run against an existing binding
        // carries the events of the run it was judged on. A run that learned carries none, because the
        // chain was judged step by step and the resulting fragment has not yet been run as a pipeline, so
        // that fragment is run here, once. A step never makes a separate run of its own: it shows what its
        // run already produced, which for a candidate it says it would bind is real output with no binding
        // behind it.
        final Served served = run.events() != null
                ? new Served(run.events(), run.diagnostics())
                : produced != null && !stepping
                        ? stage.serve(produced, input)
                        : null;
        if (served == null) {
            // Nothing ran, so there is nothing to pass on. For a reprocess that is the end of it: the
            // reason has been logged to the error stream.
            //
            // A step is different. The stepper stops at records, and a record is an `endDocument`
            // reaching the detector below this element. An element that emits nothing produces no
            // records, so the stepper finds nothing to stop at and the pane that says *would learn*, with
            // the learner and the reason, is never shown. That is exactly the case the pane exists for.
            //
            // So an empty document is emitted, but only on a step. A dry run writes nothing anywhere, and
            // an empty record that a person can select is what carries the explanation. Outside stepping
            // the same document would be an empty stream, which looks like a feed that had nothing in it.
            if (stepping && downstream != null) {
                downstream.startDocument();
                downstream.endDocument();
            }
            return;
        }
        // Log what the fragment's elements reported to this pipeline's error stream. The fragment runs
        // under its own error receiver, so that errors from a candidate that is discarded go to the model
        // rather than the operator. The run that is served is the one the operator needs to hear about,
        // with each of its elements named as itself. Each distinct message is logged once per stream.
        logDiagnostics(served.diagnostics());
        if (served.events() == null) {
            // Nothing can be served downstream, and silently writing an empty stream would look like a
            // feed that had nothing in it.
            producedNothing(elementId, NullSafe.get(produced, Produced::fragment, DocRef::getName));
            return;
        }
        served.events().fire(downstream);
    }

    /// The processing task's path. The stage decides from the stream's prefix and, where a binding serves
    /// the stream, the stream is served as it is read: the fragment runs over the whole of it once, and
    /// each record of its output is judged as it is written. A deferral is thrown out of the pipeline as a
    /// [DeferTaskException] rather than a failure, so nothing is logged to the error receiver, the task
    /// ends deferred, and the task discards whatever the downstream wrote before the stop.
    private void decideAndStream(final LearningFlowDoc doc,
                                 final ElementId elementId,
                                 final Input input,
                                 final ContentHandler downstream) {
        final Learner learner = learner(elementId);
        final StageRun run = stage.decide(doc, learner, input);
        if (run.decision() instanceof final Deferred deferred) {
            throw new DeferTaskException(deferred.reason());
        }
        stepDetails.put(elementId.getId(), StepDetails.of(run, input, false));
        fragmentRecords.put(elementId.getId(), List.of());
        stage.takeFragment();
        final Produced produced = run.produced();
        final Severity said = produced == null
                ? Severity.ERROR
                : Severity.INFO;
        StepDetails.lines(run.decision()).forEach(line -> errorReceiverProxy.log(said, null, elementId, line, null));
        if (produced == null) {
            return;
        }
        final Binding binding = bindingOf(run.decision());
        final ServedStream served = stage.stream(doc, learner, binding, input, downstream);
        // Recorded after the stream is served, not before, so that the score on the stream's attributes
        // is this run's, and a run that deferred the stream writes no attributes at all, because `stream`
        // throws before this line is reached.
        record(produced.scoring(served.meanTotal()), elementId);
        logDiagnostics(served.diagnostics());
        if (served.records() == 0) {
            producedNothing(elementId, produced.fragment().getName());
        }
        skipped(elementId, served);
    }

    /// Logs what the fragment's elements reported to this pipeline's error stream, each element named as
    /// itself.
    private void logDiagnostics(final List<StoredError> diagnostics) {
        diagnostics.forEach(error -> errorReceiverProxy.log(error.getSeverity(),
                error.getLocation(), error.getElementId(), error.getMessage(), null));
    }

    /// Logs an error that a bound fragment produced nothing for the stream, since silently writing an
    /// empty stream would look like a feed that had nothing in it.
    private void producedNothing(final ElementId elementId, final String fragmentName) {
        errorReceiverProxy.log(Severity.ERROR, null, elementId,
                "Bound to fragment " + fragmentName + ", which produced nothing for this stream", null);
    }

    /// Reports what the translation declared it deliberately skipped, where a person reading the stream
    /// will find it: in the stream's own attributes, and as a log line beside the event count.
    ///
    /// There is deliberately no limit on how much of a stream may be skipped; the count is shown instead.
    /// So this report is the only thing that stops a translation quietly skipping its awkward records.
    ///
    /// @param elementId The stage's element.
    /// @param served    The result of the serving run.
    private void skipped(final ElementId elementId, final ServedStream served) {
        if (served.skipped() == 0) {
            return;
        }
        metaData.put(Produced.SKIPPED_ATTRIBUTE + attributeSuffix.getOrDefault(elementId.getId(), ""),
                Integer.toString(served.skipped()));
        final List<String> reasons = served.skipReasons().stream().distinct().limit(SKIP_REASONS_SHOWN).toList();
        errorReceiverProxy.log(Severity.INFO, null, elementId,
                served.records() + " event(s), " + served.skipped()
                + " record(s) declared skipped by the translation"
                + (reasons.isEmpty()
                        ? ""
                        : ": " + String.join("; ", reasons)), null);
    }

    /// The binding named by a decision that binds the stream.
    private static Binding bindingOf(final Decision decision) {
        return switch (decision) {
            case Bound bound -> bound.binding();
            case Promoted promoted -> promoted.binding();
            case Rebound rebound -> rebound.binding();
            default -> throw new IllegalStateException("A decision that binds names a binding: " + decision);
        };
    }

    /// Builds the input for a processing task: the stream's prefix as text, with the rest left for the
    /// fragment to read. A stream that fits in the prefix is read whole. A stream of characters rather
    /// than bytes, or in an encoding other than UTF-8, is also read whole, because the fragment is given
    /// UTF-8 bytes and must read them as the same text.
    private Input streamed(final InputSource source, final ElementId elementId) {
        final InputStream bytes = source.getByteStream();
        final boolean utf8 = source.getEncoding() == null
                             || StandardCharsets.UTF_8.name().equalsIgnoreCase(source.getEncoding());
        if (bytes == null || !utf8) {
            return input(read(source), elementId);
        }
        try {
            final byte[] prefix = bytes.readNBytes(Input.PREFIX_BYTES);
            final Input whole = input(
                    RecordSkeleton.withoutBom(new String(prefix, StandardCharsets.UTF_8)), elementId);
            if (prefix.length < Input.PREFIX_BYTES) {
                return whole;
            }
            return Input.streamed(whole.id(), whole.feed(), whole.type(), whole.attributes(), whole.data(),
                    whole.pipeline(),
                    () -> new SequenceInputStream(new ByteArrayInputStream(prefix), bytes))
                    // The input is rebuilt from its parts, so the received time must be copied across.
                    .receivedAt(whole.receivedMs());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Reads the whole stream as text.
    private static String read(final InputSource inputSource) {
        try {
            final Reader reader = inputSource.getCharacterStream() != null
                    ? inputSource.getCharacterStream()
                    : new InputStreamReader(inputSource.getByteStream(), inputSource.getEncoding() == null
                            ? StandardCharsets.UTF_8
                            : Charset.forName(inputSource.getEncoding()));
            // A byte order mark is not content. Left in the text, it would make XML or JSON look like
            // plain text to every classification downstream.
            return RecordSkeleton.withoutBom(reader.readAllAsString());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// The learner for this element: the UUID of the pipeline being run, and this element's id in it. An
    /// element that is not running in a pipeline cannot be a learner, so it is refused.
    private Learner learner(final ElementId elementId) {
        if (pipelineHolder.getPipeline() == null) {
            throw ProcessException.create("A Smart Pipeline element learns as the pipeline it runs in, and "
                                          + "element " + elementId + " is not running in one");
        }
        return new Learner(pipelineHolder.getPipeline().getUuid(), elementId.getId());
    }

    /// Forgets what this element last decided, because it is about to decide again. The capture asks
    /// about the record it has just captured, and for a record the element never finished (e.g. one an
    /// earlier element refused) it would otherwise be shown the previous record's details.
    ///
    /// @param elementId The Smart Pipeline element.
    public void startRecord(final ElementId elementId) {
        stepDetails.remove(elementId.getId());
        fragmentRecords.remove(elementId.getId());
    }

    /// What this element last decided, as the stepper's Smart Pipeline pane shows it, with the fragment's
    /// own elements for the record being asked about.
    ///
    /// The decision applies to the whole stream: the stage decides once and then produces every record,
    /// so every record shows the same learner, binding and verdicts. What each element of the fragment was
    /// given and wrote is per record, and that is what the index selects.
    ///
    /// @param elementId   The Smart Pipeline element.
    /// @param recordIndex The zero-based index of the record in this part that the capture is asking
    ///                    about.
    /// @return The details, or null where the element has not run.
    public LearningFlowStepDetails stepDetails(final ElementId elementId, final long recordIndex) {
        final LearningFlowStepDetails details = stepDetails.get(elementId.getId());
        if (details == null) {
            return null;
        }
        final List<FragmentRecord> records = fragmentRecords.getOrDefault(elementId.getId(), List.of());
        if (records.isEmpty()) {
            return details;
        }
        // A run that kept fewer records than the stream has (a capped capture, or a fragment whose records
        // do not map one-to-one to the element's) shows its last record rather than nothing. Showing a
        // later record's chain is better than showing none, and an index past the end is not the fault of
        // the person stepping.
        final int index = (int) Math.max(0, Math.min(recordIndex, records.size() - 1L));
        // An element in the parser position runs once over the whole stream: the chain divides the records
        // itself and the stepper walks what came out of the end, so every record of the stream shows the
        // same single run. This is flagged on the row, because otherwise six records' worth of output shown
        // against the first record would look like a fault.
        final boolean wholeStream = records.size() == 1;
        return details.withNested(under(elementId, records.get(index).elements(), wholeStream));
    }

    /// Prefixes the fragment's element ids with the id of the Smart Pipeline element that ran them, and
    /// truncates whole-stream text.
    ///
    /// A fragment may hold an `XSLTFilter` and so may the pipeline it is running inside, and a pipeline
    /// may hold two Smart Pipeline elements whose fragments have the same chain of element types. A person
    /// reads the elements' own names, but the stepper tells elements apart by id, so the id carries the
    /// Smart Pipeline element's id.
    private static List<NestedElementData> under(final ElementId elementId,
                                                 final List<NestedElementData> elements,
                                                 final boolean wholeStream) {
        return elements.stream()
                .map(element -> {
                    final String input = carried(element.getInput(), wholeStream);
                    final String output = carried(element.getOutput(), wholeStream);
                    return new NestedElementData(
                            elementId.getId() + NESTED_SEPARATOR + element.getId(),
                            element.getName(),
                            element.getType(),
                            input,
                            output,
                            element.isFormatInput(),
                            element.isFormatOutput(),
                            wholeStream,
                            cut(element.getInput(), input) || cut(element.getOutput(), output));
                })
                .toList();
    }

    /// How much of an element's text is passed to the stepper. For a chain run once over the whole stream,
    /// only the beginning of it is kept.
    ///
    /// These details are stored against **every record** of the stream, because that is how the stepping
    /// store keys an element's capture, and a chain given the whole stream has one run to show against all
    /// of them. Storing the whole of what that run read and wrote would therefore write the stream into
    /// the store once per record, which for a large stream exceeds the store's limits and breaks stepping.
    ///
    /// A chain that ran for this record alone is kept whole, because its text is only one record's, the
    /// same size as any other element's.
    ///
    /// Nothing is really lost: the fragment is a pipeline document, the pane links to it, and it can be
    /// stepped like any other. The excerpt is there to show the shape of what the chain produced.
    private static String carried(final String text, final boolean wholeStream) {
        if (!wholeStream || text == null || text.length() <= WHOLE_STREAM_EXCERPT) {
            return text;
        }
        return text.substring(0, WHOLE_STREAM_EXCERPT);
    }

    private static boolean cut(final String was, final String carried) {
        return was != null && carried != null && carried.length() < was.length();
    }

    /// Sets the time zone of timestamps that carry no offset, from the element's property. A stream's own
    /// `TimeZone` header takes precedence.
    ///
    /// @param timeZone A zone id, or null where the pipeline sets none.
    public void setTimeZone(final String timeZone) {
        this.timeZone = timeZone;
    }

    /// Builds the stream as the [Stage] sees it: its meta id, feed, type, attributes and content.
    ///
    /// The stream's time zone is resolved here and put into the metadata as `ResolvedTimeZone`, so that
    /// the learned stylesheet serving this stream reads the same zone it read while it was judged.
    private Input input(final String data, final ElementId elementId) {
        final Meta meta = metaHolder.getMeta();
        final AttributeMap attributeMap = metaDataHolder.getMetaData();
        final Map<String, String> attributes = new HashMap<>();
        if (attributeMap != null) {
            attributeMap.forEach(attributes::put);
        }
        final ResolvedZone zone = ResolvedZone.of(attributes.get(ResolvedZone.HEADER), timeZone);
        if (zone.problem() != null) {
            // A zone that cannot be read is reported rather than quietly treated as UTC, because otherwise
            // a feed whose zone is misspelt would be hours out for as long as nobody noticed.
            errorReceiverProxy.log(Severity.WARNING, null, elementId, zone.problem(), null);
        }
        attributes.put(ResolvedZone.KEY, zone.id());
        metaDataHolder.setMetaDataProvider(
                ZonedMetaData.wrapping(metaDataHolder.getMetaDataProvider(), zone));
        return new Input(
                meta == null
                        ? -1L
                        : meta.getId(),
                feedHolder.getFeedName(),
                meta == null
                        ? null
                        : meta.getTypeName(),
                attributes,
                data,
                // The pipeline processing the stream, which an output row records so that what a binding
                // produced can be counted by where it was produced.
                pipelineHolder.getPipeline() == null
                        ? null
                        : pipelineHolder.getPipeline().getUuid())
                // When the stream was received, which a timestamp with no year takes its year from, and
                // which the times check measures an event against.
                .receivedAt(meta == null
                        ? null
                        : meta.getCreateMs());
    }

    /// Records the binding that produced the output in the output stream's attributes.
    ///
    /// The output stream has one set of attributes for the whole stream, but a stream of several parts is
    /// served part by part. The first part's bindings are recorded for the stream. A later part that was
    /// bound differently is reported as a warning, because the attributes cannot record it and an
    /// as-processed reprocess of that part would use the wrong snapshot.
    ///
    /// A pipeline may also hold two Smart Pipeline elements, which share this one set of attributes. Each
    /// element is tracked separately, so the part check compares an element with itself rather than with
    /// the one above it. The element that records first (the one nearest the source) uses the plain
    /// attribute names, while any after it have their element id appended.
    private void record(final Produced produced, final ElementId elementId) {
        final String recorded = recordedBy.get(elementId.getId());
        if (recorded == null) {
            final boolean first = recordedBy.isEmpty();
            recordedBy.put(elementId.getId(), produced.bindingUuid());
            final String named = first
                    ? null
                    : elementId.getId();
            attributeSuffix.put(elementId.getId(), named == null
                    ? ""
                    : "." + named);
            produced.asAttributes(named).forEach(metaData::put);
        } else if (!recorded.equals(produced.bindingUuid())) {
            errorReceiverProxy.log(Severity.WARNING, null, elementId,
                    "Part " + metaHolder.getPartIndex() + " was bound by binding " + produced.bindingUuid()
                    + " but the stream's bindings name binding " + recorded + " from an earlier part", null);
        }
    }

    /// Whether an element type is a parser, according to the node's element registry. This is the same
    /// test `PipelineFragmentRunner` applies to a fragment's elements, because the two must agree.
    private boolean parses(final String elementType) {
        return ReplayUnits.parses(elementRegistryFactory.get(), elementType);
    }

}
