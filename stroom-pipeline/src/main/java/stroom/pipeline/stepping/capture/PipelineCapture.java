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

import stroom.pipeline.shared.stepping.SteppingFilterSettings;

import java.util.Set;

/// What a pipeline build needs in order to capture each element's input and output: somewhere to
/// register a monitor per element, a record detector to drive, and something to tell at the end of each
/// record. [PipelineFactory][stroom.pipeline.factory.PipelineFactory] wraps every steppable element with
/// recorders when it is given one of these, and links them to the monitors held here.
///
/// [SteppingController] is the implementation stepping uses: it persists each record's IO to the step
/// data store and answers a person's step by reading it back. [HeadlessCapture] is the implementation
/// for everything else — a supervisor judging a candidate, a harness scoring a chain — which keeps what
/// it captured in memory and never involves a session, a store or a step request.
///
/// The seam exists because capture and stepping are not the same thing (§12 item 2 of the Shapeshifter
/// AI design): running a pipeline and reading what each element made of its input is useful wherever
/// something needs to judge a configuration, and until this was extracted the only way to do it was to
/// be a stepping session.
public interface PipelineCapture {

    /// Register an element's monitor, which holds its recorders: the build calls this once per element
    /// it wraps, and what is captured is read back through the monitors.
    void registerMonitor(ElementMonitor monitor);

    /// The monitors registered by the build, one per element wrapped.
    Set<ElementMonitor> getMonitors();

    /// The detector that says where one record ends, set by the build.
    RecordDetector getRecordDetector();

    void setRecordDetector(RecordDetector recordDetector);

    /// Called by the record detector when a record has finished passing through the pipeline: every
    /// monitored element's IO for that record is complete and can be taken.
    ///
    /// @return Whether to stop processing. A capture normally runs to the end of the stream and returns
    /// false; stepping returns true only when the task has been terminated.
    boolean endRecord(long recordIndex);

    /// Called when a record begins, to forget where in the source the last one was.
    void resetSourceLocation();

    /// What depth the build should insert a split filter at, so that a record is captured on its own.
    ///
    /// Stepping steps record by record whatever the pipeline does, so a pipeline with no split of its
    /// own is still given one at the parser's records. A capture that is judging a configuration must
    /// not: a split the pipeline does not have would give the transform one record where the pipeline
    /// gives it the whole stream, and the configuration would then be judged on the difference.
    ///
    /// @param pipelineSplitDepth The depth the pipeline's own `SplitFilter` splits at, or -1 where it
    ///                           has none.
    /// @return The depth to insert a split at; zero or less to insert none.
    default int captureSplitDepth(final int pipelineSplitDepth) {
        return Math.max(pipelineSplitDepth, 1);
    }

    /// What the person stepping asked this element's output to be filtered by, where there is a person.
    ///
    /// @return Null where nothing filters this element, which is always so for a capture that is not a
    /// stepping session.
    default SteppingFilterSettings getStepFilterSettings(final String elementId) {
        return null;
    }
}
