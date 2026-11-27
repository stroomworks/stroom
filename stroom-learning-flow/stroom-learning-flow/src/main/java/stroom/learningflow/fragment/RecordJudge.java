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

import stroom.learningflow.scoring.Verdict;

import java.util.Optional;
import java.util.function.Supplier;

/// Applies the bar to each record of a fragment's output as a serving run produces it. The stage supplies
/// it, made from the per-record gates, and each record is judged as a document of its own. Unless
/// [#stops] says otherwise, the run stops at the first record that falls short.
public interface RecordJudge {

    /// Judges one record of the output.
    ///
    /// @param index  The record's zero-based index, in output order.
    /// @param record The record as a document of its own: the output's root with this record alone in
    ///               it, so that the scorers read it as they read any output.
    /// @param parsed Supplies the record as the parser produced it, which is what a session is shown for a
    ///               record that fell short; empty where the runner cannot provide it.
    /// @return The verdict. A record clears the bar when every gate passed on it.
    Verdict judge(int index, String record, Supplier<Optional<String>> parsed);

    /// Whether the run stops at the first record that falls short of the bar. A processing task's judge
    /// does, so nothing after that record is read. A learning session's full pass does not, because it
    /// wants every record that falls short.
    default boolean stops() {
        return true;
    }

    /// Whether a verdict clears the bar: every gate passed and no record failed.
    static boolean clears(final Verdict verdict) {
        return verdict.gatesPassed() && verdict.failingRecords().isEmpty();
    }
}
