/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.learningflow.stage;

import stroom.learningflow.fragment.ServedStream;
import stroom.learningflow.fragment.Shortfalls;

/// The result of running a candidate over the whole held stream: how each record fared against the bar,
/// and how much of the input the parser accounted for.
///
/// Both matter. A pass where every record cleared the bar is not a clear pass if the parser read only half
/// the stream (the fault described in `docs/learning-flow-parser-drift-not-detected.md`). If only the
/// records were checked, the worker could promote and wake a stream that the task would then defer again.
///
/// @param shortfalls How each record fared against the bar.
/// @param served     What the run made of the input.
/// @param lostInput  A description of the input the parser did not account for, as the document's checks
///                   judge it; null if it accounted for all of it.
record HeldPass(Shortfalls shortfalls, ServedStream served, String lostInput) {

    /// Whether the stream came through whole: every record cleared the bar, **and** all of the input was
    /// accounted for.
    boolean clear() {
        return shortfalls.clear() && lostInput == null;
    }

    /// The pass as the session judges it: the records that fell short if any did, otherwise the input
    /// that was lost.
    Judged judged() {
        return shortfalls.clear()
                ? Judged.lostFrom(served, lostInput)
                : Judged.shortOf(shortfalls);
    }

    /// Describes why the stream did not come through, for the next question to the model and for the
    /// session's row.
    ///
    /// @param round Which round of the session this was.
    /// @return The reason.
    String reason(final int round) {
        return (shortfalls.clear()
                ? lostInput
                : shortfalls.shortCount() + " of " + shortfalls.judged()
                  + " record(s) fell short of the bar")
               + " on the held stream, round " + round;
    }
}
