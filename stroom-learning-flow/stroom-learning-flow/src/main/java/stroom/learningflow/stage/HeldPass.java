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

/// The held stream run in full (design 04 §4), as both ends of it: what the bar made of each record,
/// and what the parser made of the input.
///
/// Both matter. A pass where every record cleared the bar is not a clear pass if the parser read
/// only half the stream — that is the fault in
/// `docs/learning-flow-parser-drift-not-detected.md` wearing the pass's own badge, and reading only
/// the first half of this is what would have left the worker waking a stream the task then defers
/// again (W4-4).
///
/// @param shortfalls What the bar made of each record.
/// @param served     What the run made of the input.
/// @param lostInput What the stream's input did not account for, as the document's checks judge it;
///                   null where it accounted for itself.
record HeldPass(Shortfalls shortfalls, ServedStream served, String lostInput) {

    /// Whether the stream came through whole: every record cleared the bar, **and** the input
    /// accounted for itself.
    boolean clear() {
        return shortfalls.clear() && lostInput == null;
    }

    /// The pass as the session judges it: the records that fell short where any did, else what the
    /// input lost.
    Judged judged() {
        return shortfalls.clear()
                ? Judged.lostFrom(served, lostInput)
                : Judged.shortOf(shortfalls);
    }

    /// Why the stream did not come through, for the next question and for the row.
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
