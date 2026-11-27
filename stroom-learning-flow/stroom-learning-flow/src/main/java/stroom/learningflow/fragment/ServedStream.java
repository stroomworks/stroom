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

import stroom.util.shared.StoredError;

import java.util.List;
import java.util.Optional;

/// The result of a serving run ([FragmentRunner#serve]): how many records it judged, how they scored,
/// what the fragment's elements logged, and, where a record fell short of the bar, which one and its
/// verdict. A run that fell short has passed every record before that one downstream and nothing after
/// it; the processing task is responsible for discarding what the downstream wrote.
///
/// @param records     How many records were judged, including the one that fell short.
/// @param meanTotal   The mean of the records' weighted totals over the per-record gates, which is what
///                    feeds the learner's rolling score.
/// @param diagnostics What the fragment's elements logged over the run, with duplicates removed.
/// @param shortfall   The record that fell short, where one did.
/// @param skipped     How many records the translation declared it deliberately skipped
///                    (`DeclaredSkips`). They are not events and are not judged, but they are part of
///                    what completeness counts, and they are shown beside the event count so that a
///                    person can see how much of a stream was skipped rather than translated.
/// @param skipReasons The reasons the translation gave for the skips, leaving out skips with no reason.
/// @param input       What the parser made of the input: the records it produced, the errors it logged
///                    and the bytes it read. Without this a run could only report what came out, and a
///                    stream the translation had stopped being able to read would look exactly like a
///                    stream with nothing in it.
public record ServedStream(int records,
                           double meanTotal,
                           List<StoredError> diagnostics,
                           Optional<RecordShortfall> shortfall,
                           int skipped,
                           List<String> skipReasons,
                           InputRead input) {

    public ServedStream {
        diagnostics = List.copyOf(diagnostics);
        skipReasons = List.copyOf(skipReasons);
        input = input == null
                ? InputRead.UNKNOWN
                : input;
    }

    /// Whether every record judged cleared the bar.
    public boolean clear() {
        return shortfall.isEmpty();
    }

    /// How many records the parser produced that became neither an event nor a declared skip, i.e. the
    /// records this stream lost.
    ///
    /// @return How many, or zero where nothing was lost or nothing counted the input.
    public int lost() {
        return input.counted()
                ? Math.max(0, input.records() - records - skipped)
                : 0;
    }

    /// How much of this stream the parser read, as the records it produced divided by those records plus
    /// the errors it logged for input it could not read.
    ///
    /// This counts **records, not characters**. Learning measures input coverage as a share of
    /// characters, which cannot be counted over a streamed input without estimating it. The two are not
    /// the same number, but they measure the same thing and in practice come out close: on a test feed
    /// with six unmatched alarm lines, characters give 0.695 and records 0.700.
    ///
    /// @return The share, in [0, 1]; -1 where the input could not be counted or the stream was empty; 0
    ///         for a stream with content from which the parser produced no records.
    public double coverage() {
        if (!input.counted() || input.empty()) {
            return -1.0;
        }
        final int attempted = input.records() + input.errors();
        return attempted == 0
                ? 0.0
                : (double) input.records() / attempted;
    }
}
