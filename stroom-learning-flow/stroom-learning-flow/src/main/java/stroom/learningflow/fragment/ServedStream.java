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

/// What a serving run came to (design 04 §3): how many records it judged, how they scored, what the
/// fragment's elements said, and — where a record fell short — which, with its verdict. A run that fell
/// short has handed the downstream every record before that one and nothing after it; what the
/// downstream wrote of them is the task's to discard.
///
/// @param records     How many records were judged, the short one included.
/// @param meanTotal   The mean of the records' weighted totals, over the gates that judge a record: what
///                    the learner's rolling score is fed.
/// @param diagnostics What the fragment's elements said over the run, once each.
/// @param shortfall   The record that fell short, where one did.
/// @param skipped     How many records the translation declared it deliberately skipped
///                    (`DeclaredSkips`). They are not events and are not judged, but they are part of
///                    what completeness counts, and they are shown beside the event count so that a
///                    person can see how much of a stream was skipped rather than translated.
/// @param skipReasons Why it said it skipped them, leaving out the ones that gave no reason.
/// @param input       What the parser made of the input: the records it cut, the errors it logged and
///                    the bytes it read. Without this a run could only say what came out, and a stream
///                    the translation had stopped reading looked exactly like a stream with nothing in
///                    it (Track 1 task 2, fix 1).
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

    /// How many records the parser cut that became neither an event nor a declared skip: the records
    /// this stream lost.
    ///
    /// @return How many, or zero where nothing was lost or nothing counted the input.
    public int lost() {
        return input.counted()
                ? Math.max(0, input.records() - records - skipped)
                : 0;
    }

    /// How much of this stream the parser read, as a share of the records it should have cut: the
    /// records it cut against those plus the ones it reported it could not.
    ///
    /// **Records, not characters** (decision of 20 November 2026, audit finding W4-3). Learning measures
    /// coverage as a share of characters (A36), which cannot be counted over a streamed input without
    /// estimating it. The two are not the same number, but they measure the same thing and in practice
    /// come out close: on the scenario whose feed carries six unmatched alarm lines, characters give
    /// 0.695 and records 0.700.
    ///
    /// @return The share, in [0, 1]; -1 where nothing counted the input, and 0 for a stream with content
    ///         the parser cut nothing from at all.
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
