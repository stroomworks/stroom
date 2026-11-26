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

package stroom.learningflow.extraction;

import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.StepRunner;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Runs a step one record at a time, the way the learned fragment will run it, so that what is scored is what
/// will actually be served.
///
/// The fragment's `SplitFilter` gives the transform one record at a time. A stylesheet that reads the whole
/// document (counting its siblings, asking for its position, or taking a value from the previous record)
/// behaves differently when it is given one record, and the promotion gate has to see that difference before
/// it promotes rather than after.
public final class PerRecord {

    /// The maximum number of distinct diagnostics one per-record run reports. Twenty is already enough to
    /// show the model that the step has gone wrong.
    private static final int MOST = 20;

    private PerRecord() {
    }

    /// The depth of the records in the output of a per-record step. Each output document is the transform's
    /// output for one record, so its records are the children of its root. An element after the first
    /// per-record one is given those one at a time, as the pipeline gives them, because a fragment has only
    /// one `SplitFilter` and everything after it already works one record at a time.
    public static final int WRITTEN = 1;

    /// Runs a step over each record of the input in turn and joins the outputs into one document.
    ///
    /// @param runner        The step to run.
    /// @param configuration The candidate configuration, or null for a run-only step.
    /// @param input         The whole input document.
    /// @param depth         How deep the records sit, as recorded in the binding's record boundary.
    /// @return What the step wrote for each record, joined. If there are no records at that depth, the step
    /// is run once over the whole input instead. A stream with a single record is run as that one record.
    public static StepResult run(final StepRunner runner,
                                 final String configuration,
                                 final String input,
                                 final int depth) {
        return run(runner, configuration, input, RecordSplit.split(input, depth));
    }

    /// The same, for a caller that has already split the input into records. The conversation splits it once
    /// to show the model a record, and uses this to avoid splitting it again for every candidate.
    ///
    /// @param runner        The step to run.
    /// @param configuration The candidate configuration, or null for a run-only step.
    /// @param input         The whole input, run as one if `records` is empty.
    /// @param records       The records of the input, each one an XML document.
    /// @return What the step wrote for each record, joined, with each distinct diagnostic once.
    public static StepResult run(final StepRunner runner,
                                 final String configuration,
                                 final String input,
                                 final List<String> records) {
        if (records.isEmpty()) {
            return runner.run(configuration, input);
        }
        // Compile once for the candidate and run once per record; compiling per record would compile a
        // stylesheet ten thousand times for a stream of ten thousand records.
        final List<String> written = new ArrayList<>();
        final List<StoredError> diagnostics = new ArrayList<>();
        try (StepRunner.Prepared prepared = runner.prepare(configuration)) {
            for (final String record : records) {
                final StepResult result = prepared.run(record);
                diagnostics.addAll(result.diagnostics());
                if (result.output() != null) {
                    written.add(result.output());
                }
            }
        }
        final String joined = RecordJoin.join(written);
        return new StepResult(joined, joined == null
                ? unjoined(runner, records.size(), written, told(diagnostics))
                : told(diagnostics));
    }

    // Explains why a per-record run produced nothing, when no diagnostic already says why. The outputs for
    // each record are joined into one document, and one piece that will not parse spoils the whole document.
    // A stylesheet can write something that is not XML without Saxon raising anything, so the step would fail
    // silently. Asking the model again without saying why would waste the attempt, so a reason is given.
    private static List<StoredError> unjoined(final StepRunner runner,
                                              final int records,
                                              final List<String> written,
                                              final List<StoredError> diagnostics) {
        if (!diagnostics.isEmpty()) {
            return diagnostics;
        }
        final ElementId element = new ElementId(runner.elementId());
        return List.of(new StoredError(Severity.ERROR, null, element, written.isEmpty()
                ? "Nothing was written for any of the " + records + " records of the stream"
                : "What was written for a record is not an XML document: the stream's " + records
                  + " records produced " + written.size() + " pieces and they could not be read back. A "
                  + "stylesheet whose templates match nothing writes the input's text and no elements."));
    }

    /// Removes repeated diagnostics, keeping the first of each and at most twenty in all.
    ///
    /// A stylesheet that raises the same error for every record of a stream raises it once per record. Ten
    /// thousand copies of one message tell the model no more than one copy, but cost far more tokens to send.
    /// This is public because a run through the real pipeline also gathers its diagnostics record by record,
    /// and the two must report alike.
    ///
    /// @param diagnostics The diagnostics gathered over every record.
    /// @return Each distinct diagnostic (by severity, element and message) once, in first-seen order.
    public static List<StoredError> told(final List<StoredError> diagnostics) {
        final Map<String, StoredError> once = new LinkedHashMap<>();
        for (final StoredError error : diagnostics) {
            once.putIfAbsent(error.getSeverity() + "\u0000" + error.getElementId() + "\u0000"
                             + error.getMessage(), error);
            if (once.size() >= MOST) {
                break;
            }
        }
        return List.copyOf(once.values());
    }
}
