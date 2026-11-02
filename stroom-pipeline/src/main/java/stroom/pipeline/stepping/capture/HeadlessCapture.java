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

package stroom.pipeline.stepping.capture;

import stroom.pipeline.errorhandler.ErrorReceiver;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.LoggingErrorReceiver;
import stroom.pipeline.shared.SharedElementData;
import stroom.pipeline.shared.SourceLocation;
import stroom.pipeline.state.LocationHolder;
import stroom.pipeline.stepping.store.CapturedElementData;
import stroom.pipeline.stepping.store.CapturedElementDataMapper;
import stroom.util.shared.DataRange;
import stroom.util.shared.DefaultLocation;
import stroom.util.shared.Indicators;
import stroom.util.shared.NullSafe;
import stroom.util.shared.TextRange;

import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// Capture without stepping: run a pipeline over an input and keep what each element made of it, in
/// memory, for whoever asked (§12 item 2 of the Shapeshifter AI design).
///
/// This is [SteppingController]'s other half. Stepping needs a session, a step request, a durable store
/// and a person waiting; judging a configuration needs none of those — it needs the pipeline run and
/// each element's input and output handed back. Both are captures, which is why they now share
/// [PipelineCapture] and the recorders the build wraps elements with.
///
/// What it keeps is one entry per record, holding every monitored element's input and output as text —
/// SAX output rendered as XML exactly as the stepper renders it for a person, so that what a supervisor
/// judges is what a person would have read — and whatever that element logged while producing it.
///
/// Two things to know before using it:
///
/// - **It holds everything it captures.** There is no store behind it, so a capture is for a *sample*,
///   not for a production stream of a million records. [#setMaxRecords] caps it; past the cap the parse
///   runs on but nothing more is kept, and [#isTruncated] says so.
/// - **One capture is one run.** It is handed to the build rather than looked up, so a caller that runs
///   two pipelines takes two of these; nothing about it is scoped to the pipeline it captured.
/// - **A reader's input needs a highlight.** An element whose recorder reports what it read by source
///   span — the readers above the parser, and so a parser's own input — gives back nothing unless
///   something is tracking the record's position in the source. Parsers, filters and writers capture
///   their output regardless, and that is what a configuration is judged on.
public class HeadlessCapture implements PipelineCapture {

    /// Where a record is said to be when nothing is tracking the source position. Recorders that report
    /// by span are given this rather than null, as stepping gives them, because null is a value several
    /// of them dereference.
    private static final TextRange NOWHERE = new TextRange(
            new DefaultLocation(1, 1),
            new DefaultLocation(1, 1));

    private final Set<ElementMonitor> monitors = new HashSet<>();
    private final List<Record> records = new ArrayList<>();
    private final LocationHolder locationHolder;
    private final ErrorReceiverProxy errorReceiverProxy;

    private RecordDetector recordDetector;
    private int maxRecords = Integer.MAX_VALUE;
    private boolean truncated;

    @Inject
    public HeadlessCapture(final LocationHolder locationHolder,
                           final ErrorReceiverProxy errorReceiverProxy) {
        this.locationHolder = locationHolder;
        this.errorReceiverProxy = errorReceiverProxy;
    }

    /// How many records to keep. The parse still runs to the end of the stream — stopping it would
    /// change what the configuration being judged is judged on — but nothing past the cap is held.
    public void setMaxRecords(final int maxRecords) {
        this.maxRecords = maxRecords;
    }

    /// Whether more records went through than are held.
    public boolean isTruncated() {
        return truncated;
    }

    /// Forget what was captured, for a caller that runs a pipeline more than once through one capture
    /// and wants each run's records to be that run's.
    ///
    /// A caller that did not clear would have to remember how many records there were before and skip
    /// them, which works until the cap is reached: past it nothing more is kept, the count stops
    /// advancing, and every later run reads back as having produced nothing at all.
    public void clear() {
        records.clear();
        truncated = false;
    }

    /// What every element made of every record, in order.
    public List<Record> getRecords() {
        return List.copyOf(records);
    }

    /// How many records are held, which is how many went through unless [#isTruncated].
    public int getRecordCount() {
        return records.size();
    }

    /// What one element wrote, one entry per record held, in record order.
    ///
    /// @return An entry per record, null where that record produced nothing from this element, so that
    /// the list lines up with [#getRecords] and with [#inputOf] — a scorer pairing an input with an
    /// output must be able to trust the position.
    public List<String> outputOf(final String elementId) {
        return records.stream()
                .map(record -> NullSafe.get(record.byElement().get(elementId), ElementIo::output))
                .toList();
    }

    /// What one element was given, one entry per record held, in record order and null where nothing was
    /// captured — see [#outputOf].
    public List<String> inputOf(final String elementId) {
        return records.stream()
                .map(record -> NullSafe.get(record.byElement().get(elementId), ElementIo::input))
                .toList();
    }

    /// The elements this capture watched, whether or not they produced anything.
    public Set<String> getElementIds() {
        return monitors.stream()
                .map(monitor -> monitor.getElementId().getId())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /// The pipeline's own shape and no more: where it splits, records are its records; where it does
    /// not, the stream is one record. A configuration is judged on what the pipeline it will run in
    /// gives it, so the capture must not insert a split that pipeline does not have.
    @Override
    public int captureSplitDepth(final int pipelineSplitDepth) {
        return pipelineSplitDepth;
    }

    @Override
    public void registerMonitor(final ElementMonitor monitor) {
        monitors.add(monitor);
    }

    @Override
    public Set<ElementMonitor> getMonitors() {
        return monitors;
    }

    @Override
    public RecordDetector getRecordDetector() {
        return recordDetector;
    }

    @Override
    public void setRecordDetector(final RecordDetector recordDetector) {
        this.recordDetector = recordDetector;
    }

    /// Take every element's IO for the record that has just finished, and clear the recorders and the
    /// indicators for the next one. Never terminates: a capture runs to the end of the stream, because
    /// what is being judged is the whole of what a configuration did with it.
    ///
    /// @param recordIndex Where the detector says this record sits, which restarts at zero for each part
    ///                    of a segmented source. [Record#sequence()] is what counts records of the
    ///                    capture as a whole.
    @Override
    public boolean endRecord(final long recordIndex) {
        final TextRange highlight = highlight();
        final LoggingErrorReceiver logging = loggingErrorReceiver();
        if (records.size() < maxRecords) {
            final Map<String, ElementIo> byElement = new LinkedHashMap<>();
            for (final ElementMonitor monitor : monitors) {
                final CapturedElementData captured =
                        monitor.getCapturedElementData(logging, highlight, recordIndex);
                final SharedElementData shared = CapturedElementDataMapper.toShared(captured);
                // An entry for every element watched, whether or not it produced anything: a caller
                // reading record by record must find the same elements in each.
                byElement.put(monitor.getElementId().getId(), shared == null
                        ? new ElementIo(null, null, null)
                        : new ElementIo(shared.getInput(), shared.getOutput(), shared.getIndicators()));
            }
            records.add(new Record(records.size(), recordIndex, Map.copyOf(byElement)));
        } else {
            truncated = true;
        }

        monitors.forEach(monitor -> monitor.clear(highlight));
        // What an element logged belongs to the record it logged it for. Left uncleared, one record's
        // fatal error is reported against every record after it.
        if (logging != null) {
            logging.clearIndicators();
        }
        return false;
    }

    @Override
    public void resetSourceLocation() {
        if (locationHolder != null) {
            locationHolder.reset();
        }
    }

    /// The error receiver to read each element's indicators from, where the one in use records them.
    private LoggingErrorReceiver loggingErrorReceiver() {
        final ErrorReceiver errorReceiver = errorReceiverProxy == null
                ? null
                : errorReceiverProxy.getErrorReceiver();
        return errorReceiver instanceof final LoggingErrorReceiver logging
                ? logging
                : null;
    }

    /// Where in the source the finished record sat, where anything is tracking it.
    private TextRange highlight() {
        final SourceLocation location = locationHolder == null
                ? null
                : locationHolder.getCurrentLocation();
        final TextRange range = location == null
                ? null
                : NullSafe.get(location.getFirstHighlight(),
                        DataRange::getAsTextRange,
                        (final Optional<TextRange> found) -> found.orElse(null));
        return range == null
                ? NOWHERE
                : range;
    }


    // --------------------------------------------------------------------------------


    /// One record of the stream, and what each element made of it.
    ///
    /// @param sequence    Where this record sits among the records of the whole capture, counted from
    ///                    zero and never repeated.
    /// @param recordIndex What the record detector called it, which restarts at zero for each part of a
    ///                    segmented source, so several records of one capture may share it.
    public record Record(long sequence, long recordIndex, Map<String, ElementIo> byElement) {

    }


    // --------------------------------------------------------------------------------


    /// What one element was given, what it wrote, and what it said while doing so. Any of the three may
    /// be null: an element that produced nothing for a record still has an entry, so that reading the
    /// same element across records is reading the same position.
    public record ElementIo(String input, String output, Indicators indicators) {

    }
}
