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
import stroom.meta.api.AttributeMap;
import stroom.meta.shared.Meta;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.ProcessException;
import stroom.pipeline.factory.ElementRegistryFactory;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.shared.stepping.NestedElementData;
import stroom.pipeline.state.FeedHolder;
import stroom.pipeline.state.MetaData;
import stroom.pipeline.state.MetaDataHolder;
import stroom.pipeline.state.MetaHolder;
import stroom.pipeline.state.PipelineContext;
import stroom.pipeline.state.PipelineHolder;
import stroom.processor.api.DeferTaskException;
import stroom.shapeshifter.ai.doc.ShapeshifterAiStore;
import stroom.shapeshifter.ai.fragment.FragmentRunner.FragmentRecord;
import stroom.shapeshifter.ai.fragment.ReplayUnits;
import stroom.shapeshifter.ai.fragment.ServedStream;
import stroom.shapeshifter.ai.stage.Decision;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Deferred;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Provisional;
import stroom.shapeshifter.ai.stage.Decision.Rebound;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.Produced;
import stroom.shapeshifter.ai.stage.RecordSkeleton;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.Stage.Served;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.ReplayUnit;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiStepDetails;
import stroom.util.pipeline.scope.PipelineScoped;
import stroom.util.shared.ElementId;
import stroom.util.shared.NullSafe;
import stroom.util.shared.Severity;

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

/// What a supervised stage does with the text in front of it, whichever element is standing there
/// (design 01 §3, §12 item 4). The stage sits where a parser sits when it is given a stream, and where a
/// filter sits when it is given records, and everything but that difference is here: the document, the
/// position check, the stream as the stage sees it, the decision, the bindings recorded on the output
/// stream, and the fragment's events played on to whatever is downstream.
///
/// Pipeline-scoped, because what it reads the stream's identity from — the meta, feed and pipeline
/// holders — is. One instance serves both supervisors in a pipeline: a [Stage] holds nothing between
/// calls, so one is as good as many.
@PipelineScoped
public class Supervision {

    private final ShapeshifterAiStore store;
    private final ElementRegistryFactory elementRegistryFactory;
    private final FeedHolder feedHolder;
    private final MetaHolder metaHolder;
    private final MetaDataHolder metaDataHolder;
    private final MetaData metaData;
    private final ErrorReceiverProxy errorReceiverProxy;
    private final PipelineHolder pipelineHolder;
    private final PipelineContext pipelineContext;
    private final Stage stage;

    /// What each supervisor in this pipeline has recorded for this stream, by element id. Pipeline-
    /// scoped state, like the attributes it guards.
    private final Map<String, String> recordedBy = new HashMap<>();

    /// What each supervisor last decided, by element id, for the stage pane to show (A30). Replaced
    /// each time the element runs, because the capture asks for the record it has just processed.
    private final Map<String, ShapeshifterAiStepDetails> stepDetails = new HashMap<>();

    /// What the fragment's own elements made of each record of the run this supervisor just made, by
    /// element id (A30). Kept beside the decision rather than folded into it because the decision is the
    /// stream's and this is each record's: a stage fed by the source decides once and then produces a
    /// hundred records, each of which the capture asks about separately.
    private final Map<String, List<FragmentRecord>> fragmentRecords = new HashMap<>();

    /// Whether each supervisor was handed the whole stream rather than one record (A1), which is what
    /// decides whether its chain's one run covered the record at the cursor or all of them.
    /// What separates the stage's name from the fragment element's in a nested element's id. A
    /// character an element id cannot hold, so that a namespaced id can never be mistaken for one a
    /// person gave an element of the pipeline.
    private static final String NESTED_SEPARATOR = "/";

    /// How much of a whole-stream chain's text is carried to each record of the stream it ran over.
    /// Enough to show the shape of what the chain made — a few dozen records of most feeds — and small
    /// enough that carrying it against every record of a long stream stays within what the step store
    /// will hold. See [#carried].
    private static final int WHOLE_STREAM_EXCERPT = 16 * 1024;

    @Inject
    public Supervision(final ShapeshifterAiStore store,
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

    /// The document a supervisor names, or a refusal that says which element has none.
    ///
    /// @param docRef    What the element's property was set to; null where it was not set.
    /// @param elementId The element to name in the refusal.
    public ShapeshifterAiDoc document(final DocRef docRef, final ElementId elementId) {
        if (docRef == null) {
            throw ProcessException.create("No Shapeshifter AI document is set on element " + elementId);
        }
        final ShapeshifterAiDoc doc = store.readDocument(docRef);
        if (doc == null) {
            throw ProcessException.create("Shapeshifter AI document " + docRef + " was not found");
        }
        return doc;
    }

    /// What this stage may learn must match where it stands (A1, design 01 §4): the stage is fed by the
    /// source and given raw data, so the chains its allowed elements can make must parse. Checked as the
    /// pipeline is built rather than when a model is asked: a document that cannot learn anything usable
    /// should say so before it has spent a call finding out. Skipped where the document names no allowed
    /// elements and so constrains nothing.
    ///
    /// @param elementId The supervisor.
    public void checkPosition(final ShapeshifterAiDoc doc, final ElementId elementId) {
        if (NullSafe.isEmptyCollection(doc.getAllowedElements())) {
            return;
        }
        final ReplayUnit allowed = ReplayUnits.ofElements(doc.getAllowedElements(), this::parses);
        if (allowed != ReplayUnit.STREAM) {
            throw ProcessException.create(ReplayUnits.mismatch(allowed,
                    "The allowed elements of Shapeshifter AI document " + doc.getName()));
        }
    }

    /// Learn, judge and serve one stream, and play what the bound fragment made of it on to the
    /// element's own downstream.
    ///
    /// The fragment is run once and once only (§12 item 2): the run the stage judged is the run that is
    /// served, and its events are carried back on the [StageRun]. A decision that binds nothing — a
    /// sentinel, a learner that gave up, a draft awaiting review — emits nothing and says why on the error
    /// stream (A4).
    ///
    /// Stepping is a dry run (A30): the stage serves, and a learner nothing serves is reported
    /// rather than learned. A person opening the stepper on a feed nobody has taught yet must not spend
    /// a model call and write a binding by looking.
    ///
    /// The stage is given the stream itself (design 04 §3, *streams of any size*). A step and a reprocess
    /// read the whole stream as text, as they always have: a step is bounded by the person stepping and a
    /// reprocess asks what was done. A task reads the stream's prefix — the sample the model is shown
    /// needs no more — and the rest is read by the fragment as it serves the
    /// stream.
    ///
    /// @param asProcessed Which question a reprocess is asking (design 01 §7.3).
    /// @param source      The stream.
    /// @param downstream  Where the fragment's events go.
    public void supervise(final ShapeshifterAiDoc doc,
                          final ElementId elementId,
                          final boolean asProcessed,
                          final InputSource source,
                          final ContentHandler downstream) throws SAXException {
        final Input input = pipelineContext.isStepping() || asProcessed
                ? input(read(source))
                : streamed(source);
        supervise(doc, elementId, asProcessed, input, downstream);
    }

    private void supervise(final ShapeshifterAiDoc doc,
                           final ElementId elementId,
                           final boolean asProcessed,
                           final Input input,
                           final ContentHandler downstream) throws SAXException {
        final boolean stepping = pipelineContext.isStepping();
        // A task takes the decision of design 04 §3 — emit, defer or error — and never learns (A60, A99),
        // and serves a bound stream as it is read; a reprocess asks what was done; a step asks what would
        // be.
        if (!stepping && !asProcessed) {
            decideAndStream(doc, elementId, input, downstream);
            return;
        }
        final StageRun run = stepping
                ? stage.dryRun(doc, learner(elementId), input, asProcessed)
                : stage.reprocess(doc, learner(elementId), input);
        // What the stage pane shows for this element, kept for the capture to ask (A30). Per element,
        // because a pipeline may hold two supervised stages and each has its own decision to explain.
        stepDetails.put(elementId.getId(), StepDetails.of(run, input, stepping));
        // And what the fragment's own elements made of it, record by record, so that the chain the
        // stage ran can be stepped beneath it. Only when stepping: nothing else asks, and holding a
        // record's worth of text per record of a production stream would be a leak with no reader.
        // Taken rather than read, so that a decision this run made without running a fragment at all —
        // a draft awaiting review, a learner that gave up — cannot be shown the chain of the
        // run before it. The take happens whether or not this is a step, because a run that is not
        // being stepped must still clear what the last step left.
        final List<FragmentRecord> ran = stage.takeFragment();
        fragmentRecords.put(elementId.getId(), stepping
                ? ran
                : List.of());
        final Produced produced = run.produced();
        // What happened, or — for a step — what would have. A dry run's report is not a fault: a learner
        // nobody has taught yet is the ordinary state of the feed a person has opened the stepper on,
        // and an error marker would say otherwise.
        errorReceiverProxy.log(stepping
                        ? Severity.INFO
                        : produced == null
                                ? Severity.ERROR
                                : Severity.INFO, null, elementId,
                StepDetails.describe(run.decision()), null);

        // Recorded only where a fragment really was bound and really did run: a step leaves no trace of
        // having asked, and the bindings of a run nobody made do not belong on the stream.
        if (produced != null && !stepping) {
            record(produced, elementId);
        }

        // What is served follows the *events*, not the bindings. A stream already bound carries
        // the events of the run it was judged on; a stream the learner learned on carries none,
        // because the chain was judged as it was learned and the fragment it became has not been run as
        // a pipeline yet — so that one fragment is run here, once. A step never goes and makes a run of
        // its own: it shows what the run it already made produced, which for a variant it says it would
        // bind is a real output with no binding behind it.
        final Served served = run.events() != null
                ? new Served(run.events(), run.diagnostics())
                : produced != null && !stepping
                        ? stage.serve(produced, input)
                        : null;
        if (served == null) {
            // Nothing ran, so there is nothing to play on. In a task that is the end of it: the stream
            // is sentinelled and the reason is on the error stream.
            //
            // A step is different, and this is the whole of A30's promise for a feed nobody has taught
            // yet. The stepper stops at records, and a record is an `endDocument` reaching the detector
            // below this element — so an element that emits nothing produces no records, the stepper
            // finds nothing to stop at, and the stage pane that says *would learn*, with the learner and
            // the reason, is never shown. The one case the pane exists for would be the one case it
            // could not be reached in.
            //
            // So one empty document, on a step and only on a step. Nothing is written anywhere by a dry
            // run, and an empty record that a person can select is what carries the explanation; in a
            // task the same document would be an empty stream, which reads as a feed that had nothing
            // in it, and that is why this is not done there.
            if (stepping && downstream != null) {
                downstream.startDocument();
                downstream.endDocument();
            }
            return;
        }
        // What the fragment said, on this pipeline's error stream (A20). The fragment runs under an
        // error receiver of its own, so that the complaints of a candidate nobody keeps go to the model
        // rather than to the operator; the run that is served is the one the operator has to hear, and
        // each of its elements is named as itself. Once per distinct message over the stream, which is
        // the binding a re-ask already follows.
        served.diagnostics().forEach(error -> errorReceiverProxy.log(error.getSeverity(),
                error.getLocation(), error.getElementId(), error.getMessage(), null));
        if (served.events() == null) {
            // Nothing downstream can be served from that, and silently writing an empty stream would
            // look like a feed that had nothing in it.
            errorReceiverProxy.log(Severity.ERROR, null, elementId,
                    "Bound to fragment " + NullSafe.get(produced, Produced::fragment, DocRef::getName)
                    + ", which produced nothing for this stream", null);
            return;
        }
        served.events().fire(downstream);
    }

    /// The task's path (design 04 §3): the decision on the stream's prefix, and where a binding serves the
    /// stream, the stream served as it is read — the fragment running over the whole of it once and each
    /// record of its output judged as it is written. A deferral is thrown out of the pipeline as a
    /// deferral and not a failure, so nothing is logged to the error receiver, the task ends deferred, and
    /// what the downstream wrote before the stop is the task's to discard.
    private void decideAndStream(final ShapeshifterAiDoc doc,
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
        errorReceiverProxy.log(produced == null
                        ? Severity.ERROR
                        : Severity.INFO, null, elementId,
                StepDetails.describe(run.decision()), null);
        if (produced == null) {
            return;
        }
        record(produced, elementId);
        final Binding binding = bindingOf(run.decision());
        final ServedStream served = stage.stream(doc, learner, binding, input, downstream);
        served.diagnostics().forEach(error -> errorReceiverProxy.log(error.getSeverity(),
                error.getLocation(), error.getElementId(), error.getMessage(), null));
        if (served.records() == 0) {
            errorReceiverProxy.log(Severity.ERROR, null, elementId,
                    "Bound to fragment " + produced.fragment().getName()
                    + ", which produced nothing for this stream", null);
        }
    }

    /// The binding a decision that binds a stream names.
    private static Binding bindingOf(final Decision decision) {
        return switch (decision) {
            case Bound bound -> bound.binding();
            case Promoted promoted -> promoted.binding();
            case Provisional provisional -> provisional.binding();
            case Rebound rebound -> rebound.binding();
            default -> throw new IllegalStateException("A decision that binds names a binding: " + decision);
        };
    }

    /// The stream as a task takes it: its prefix as text, and the rest to be read by the fragment. A
    /// stream that is all prefix is read whole; a stream of characters rather than bytes, or in an
    /// encoding other than UTF-8, is read whole as well, since the fragment is given bytes it must be able
    /// to read as the text was.
    private Input streamed(final InputSource source) {
        final InputStream bytes = source.getByteStream();
        final boolean utf8 = source.getEncoding() == null
                             || StandardCharsets.UTF_8.name().equalsIgnoreCase(source.getEncoding());
        if (bytes == null || !utf8) {
            return input(read(source));
        }
        try {
            final byte[] prefix = bytes.readNBytes(Input.PREFIX_BYTES);
            if (prefix.length < Input.PREFIX_BYTES) {
                return input(RecordSkeleton.withoutBom(new String(prefix, StandardCharsets.UTF_8)));
            }
            final Input whole = input(RecordSkeleton.withoutBom(new String(prefix, StandardCharsets.UTF_8)));
            return Input.streamed(whole.id(), whole.feed(), whole.type(), whole.attributes(), whole.data(),
                    whole.pipeline(),
                    () -> new SequenceInputStream(new ByteArrayInputStream(prefix), bytes));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// The stream as text, whole.
    private static String read(final InputSource inputSource) {
        try {
            final Reader reader = inputSource.getCharacterStream() != null
                    ? inputSource.getCharacterStream()
                    : new InputStreamReader(inputSource.getByteStream(), inputSource.getEncoding() == null
                            ? StandardCharsets.UTF_8
                            : Charset.forName(inputSource.getEncoding()));
            final StringBuilder text = new StringBuilder();
            final char[] buffer = new char[8192];
            for (int n = reader.read(buffer); n >= 0; n = reader.read(buffer)) {
                text.append(buffer, 0, n);
            }
            // A byte order mark is not content: read as text it would make XML or JSON look like a line
            // of text to every classification downstream.
            return RecordSkeleton.withoutBom(text.toString());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// The learner this element is (A114): the pipeline the processor is running and this element's id in
    /// it. An element outside a running pipeline has nothing to learn as, so it is refused.
    private Learner learner(final ElementId elementId) {
        if (pipelineHolder.getPipeline() == null) {
            throw ProcessException.create("A Shapeshifter AI element learns as the pipeline it runs in, and "
                                          + "element " + elementId + " is not running in one");
        }
        return new Learner(pipelineHolder.getPipeline().getUuid(), elementId.getId());
    }

    /// Forget what this element last decided, because it is about to decide again (A30). The capture
    /// asks for the record it has just captured, and a record the element never finished — one the
    /// parser above it refused, say — would otherwise be shown the record before it.
    public void startRecord(final ElementId elementId) {
        stepDetails.remove(elementId.getId());
        fragmentRecords.remove(elementId.getId());
    }

    /// What this element last decided, as the stage pane shows it (A30), with the fragment's own
    /// elements for the record being asked about — or null where it has not run.
    ///
    /// The decision is the stream's: the stage decides once and then produces every record of the stream,
    /// so every record shows the same learner, binding and verdicts. What each element of the fragment was
    /// given and wrote is the record's, and that is what the index selects.
    ///
    /// @param recordIndex Which record of this part the capture is asking about, counted from zero.
    public ShapeshifterAiStepDetails stepDetails(final ElementId elementId, final long recordIndex) {
        final ShapeshifterAiStepDetails details = stepDetails.get(elementId.getId());
        if (details == null) {
            return null;
        }
        final List<FragmentRecord> records = fragmentRecords.getOrDefault(elementId.getId(), List.of());
        if (records.isEmpty()) {
            return details;
        }
        // A run that kept fewer records than the stream has — a capped capture, or a fragment whose
        // records are not one-to-one with the stage's — shows its last rather than nothing: a chain
        // that is there is better read late than not at all, and an index past the end is not a fault
        // of the person stepping.
        final int index = (int) Math.max(0, Math.min(recordIndex, records.size() - 1L));
        // One run over the whole stream is what a stage standing where a parser stands makes: the chain
        // cuts the records itself and the stepper walks what came out of the far end, so every record of
        // the stream shows the same one run. Said on the row rather than left to be noticed, because
        // six records' worth of output under a cursor on the first reads as a fault otherwise.
        final boolean wholeStream = records.size() == 1;
        return details.withNested(under(elementId, records.get(index).elements(), wholeStream));
    }

    /// The fragment's elements named by the stage that ran them.
    ///
    /// A fragment may hold an `XSLTFilter` and so may the pipeline it is running inside, and a pipeline
    /// may hold two supervised stages whose fragments were learned from the same chain of element types.
    /// Their own names are what a person reads; the id is what the stepper tells them apart by, so it
    /// carries the stage's.
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

    /// What of an element's text is carried to the step, which for a chain run once over the whole
    /// stream is the beginning of it and no more.
    ///
    /// These details are stored against **every record** of the stream, because that is how the step
    /// store keys an element's capture, and a chain handed the stream has one run to show against all
    /// of them. Carrying the whole of what that run read and wrote would therefore write the stream
    /// into the store once per record of the stream — which for a large stream exhausts the store's own
    /// limits outright and takes stepping with it.
    ///
    /// A chain that ran for this record alone is carried whole: its text is one record's, which is the
    /// same size as any other element's and no more of a burden.
    ///
    /// What is lost is not lost: the fragment is a pipeline document, the stage pane links to it, and
    /// it steps like any other. The excerpt is there to show the shape of what the chain made.
    private static String carried(final String text, final boolean wholeStream) {
        if (!wholeStream || text == null || text.length() <= WHOLE_STREAM_EXCERPT) {
            return text;
        }
        return text.substring(0, WHOLE_STREAM_EXCERPT);
    }

    private static boolean cut(final String was, final String carried) {
        return was != null && carried != null && carried.length() < was.length();
    }

    /// The stream as the [Stage] sees it: its meta id, feed, type, attributes and content.
    private Input input(final String data) {
        final Meta meta = metaHolder.getMeta();
        final AttributeMap attributeMap = metaDataHolder.getMetaData();
        final Map<String, String> attributes = new HashMap<>();
        if (attributeMap != null) {
            attributeMap.forEach(attributes::put);
        }
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
                // Which pipeline is processing it: what an output row records, so that what a binding
                // produced can be counted by where it was produced (§6).
                pipelineHolder.getPipeline() == null
                        ? null
                        : pipelineHolder.getPipeline().getUuid());
    }

    /// The output stream's attributes are one set for the whole stream, and a stream of several parts is
    /// served part by part. The first part's bindings stand for the stream; a later part that bound
    /// differently is reported, since the attributes cannot say so and an as-processed reprocess of it
    /// would be misled (design 01 §7.3).
    ///
    /// A pipeline may also hold two supervised stages, and they share this one set of attributes. Each
    /// stage is remembered by itself, so the part check compares a stage with itself rather than with
    /// the one above it; and the stage that records first — the one nearest the source — takes the plain
    /// attribute names while any behind it are named by their element.
    private void record(final Produced produced, final ElementId elementId) {
        final String recorded = recordedBy.get(elementId.getId());
        if (recorded == null) {
            final boolean first = recordedBy.isEmpty();
            recordedBy.put(elementId.getId(), produced.bindingUuid());
            produced.asAttributes(first
                    ? null
                    : elementId.getId()).forEach(metaData::put);
        } else if (!recorded.equals(produced.bindingUuid())) {
            errorReceiverProxy.log(Severity.WARNING, null, elementId,
                    "Part " + metaHolder.getPartIndex() + " was bound by binding " + produced.bindingUuid()
                    + " but the stream's bindings name binding " + recorded + " from an earlier part", null);
        }
    }

    /// Whether an element type parses, as the node's own element registry has it — the same question
    /// `FragmentCheckImpl` asks of a fragment, asked the same way, because the two must agree.
    private boolean parses(final String elementType) {
        final PipelineElementType type = elementRegistryFactory.get().getElementType(elementType);
        return type != null && type.hasRole(PipelineElementType.ROLE_PARSER);
    }

}
