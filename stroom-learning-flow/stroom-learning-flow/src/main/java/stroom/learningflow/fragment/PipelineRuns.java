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

import stroom.learningflow.extraction.PerRecord;
import stroom.pipeline.errorhandler.LoggingErrorReceiver;
import stroom.pipeline.stepping.capture.HeadlessCapture;
import stroom.util.shared.ElementId;
import stroom.util.shared.Indicators;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import java.util.ArrayList;
import java.util.List;

/// Constants and helpers shared by the module's pipeline runners: [PipelineFragmentRunner], which runs a
/// whole fragment, and `stroom.learningflow.element.PipelineStepRunner`, which runs one element of a
/// candidate chain. It holds the element types they build pipelines from, how many records a capture
/// keeps, and how the diagnostics the elements logged during a run are gathered.
public final class PipelineRuns {

    /// The element type, and the id, of the element a pipeline's stream enters at.
    public static final String SOURCE = "Source";
    /// The element type of Stroom's XML parser, which turns text into the events a filter expects.
    public static final String XML_PARSER = "XMLParser";
    /// The element type of Stroom's split filter, which shapes a stream into records.
    public static final String SPLIT_FILTER = "SplitFilter";

    /// The maximum number of records of a run that a capture keeps. A judgement is made on a sample, not on
    /// a whole production stream, and the capture holds what it keeps in memory.
    public static final int MOST_RECORDS = 10_000;

    private PipelineRuns() {
    }

    /// Gathers what some elements logged during a run, with duplicates removed. This includes what each
    /// element logged against every record the capture kept, and anything it logged outside a record (a
    /// parser that failed before it produced one, an element that failed before anything reached it, or
    /// an error at the end of processing). The capture takes what it finds at each record boundary and
    /// clears it, so what the receiver still holds is exactly what no record carried.
    ///
    /// @param capture    What the run kept, record by record.
    /// @param elementIds The elements whose diagnostics are wanted; the first is the one an unreported
    ///                   failure is attributed to.
    /// @param logging    The receiver holding what the elements logged outside a record.
    /// @param failure    The error the run failed with that no element reported, or null.
    /// @return The diagnostics, de-duplicated and capped as [PerRecord#told] does; the failure is added as
    /// a fatal error only where nothing else was reported.
    public static List<StoredError> diagnostics(final HeadlessCapture capture,
                                                final List<String> elementIds,
                                                final LoggingErrorReceiver logging,
                                                final String failure) {
        final List<StoredError> errors = new ArrayList<>();
        for (final String elementId : elementIds) {
            capture.getRecords().forEach(record -> {
                final HeadlessCapture.ElementIo io = record.byElement().get(elementId);
                final Indicators indicators = io == null
                        ? null
                        : io.indicators();
                if (indicators != null) {
                    errors.addAll(indicators.getErrorList());
                }
            });
            final Indicators outside = logging.getIndicators(new ElementId(elementId));
            if (outside != null) {
                errors.addAll(outside.getErrorList());
            }
        }
        if (errors.isEmpty() && failure != null) {
            errors.add(new StoredError(Severity.FATAL_ERROR, null, new ElementId(elementIds.getFirst()),
                    failure));
        }
        return PerRecord.told(errors);
    }
}
