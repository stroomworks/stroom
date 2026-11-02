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

package stroom.shapeshifter.ai.extraction;

import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Running a step the way the fragment will run it (§12 item 25): the `SplitFilter` the fragment carries
/// gives the transform one record at a time, so what is scored must be what a transform run one record at
/// a time produces. A stylesheet that reads the whole document — counting its siblings, asking for its
/// position, pulling a value from the record before it — behaves differently when it is given one record,
/// and the promotion gate has to see that difference before it promotes rather than after.
public final class PerRecord {

    /// The most distinct shortfalls one per-record run reports. A step that has gone wrong in twenty
    /// different ways has gone wrong.
    private static final int MOST = 20;

    private PerRecord() {
    }

    /// The depth at which the documents a per-record step *writes* carry their records: each is the
    /// transform's output for one record, so what it wrote is the children of its root. An element after
    /// the first per-record one is given those, one at a time, exactly as the pipeline gives them — there
    /// is only ever one `SplitFilter` in a fragment, and what follows it is already one record deep.
    public static final int WRITTEN = 1;

    /// @param depth How deep the records sit, as the binding's boundary carries it.
    /// @return What the step wrote for each record, joined; or the step run over the whole document where
    /// the depth names no records at all, which is the only case in which there is nothing to run per
    /// record. A stream carrying a single record is run as the one record it is.
    public static StepResult run(final StepRunner runner,
                                 final String configuration,
                                 final String input,
                                 final int depth) {
        return run(runner, configuration, input, RecordSplit.split(input, depth));
    }

    /// The same, for a caller that has already cut the input — the conversation cuts it to show the model one
    /// record and would otherwise cut it again for every candidate it asks about.
    public static StepResult run(final StepRunner runner,
                                 final String configuration,
                                 final String input,
                                 final List<String> records) {
        if (records.isEmpty()) {
            return runner.run(configuration, input);
        }
        // Compiled once for the candidate, run once per record: a stylesheet compiled per record is a
        // stylesheet compiled ten thousand times for a stream of ten thousand records.
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

    /// Why a per-record run produced nothing, where nothing else said why. What the transform wrote for
    /// each record is read back to make one document, and a piece that will not parse takes the whole
    /// document with it — but a stylesheet can write something that is not a document without Saxon
    /// raising anything, and the step then failed in silence. A re-ask that says nothing is an attempt
    /// spent for nothing, so the silence is filled here.
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

    /// What fell short, once each: a stylesheet that raises the same error for every record of a stream
    /// raises it ten thousand times, and ten thousand copies of one sentence is a re-ask that says no
    /// more than one copy and costs a budget (A44) to send. Public because a run through the real
    /// pipeline gathers its diagnostics record by record too, and the two must report alike.
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
