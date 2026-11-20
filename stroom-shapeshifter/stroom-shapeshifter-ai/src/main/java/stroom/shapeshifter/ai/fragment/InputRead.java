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

package stroom.shapeshifter.ai.fragment;

/// What the parser made of a served stream's input (Track 1 task 2, fix 1): the one thing a serving run
/// could not say before, and the reason a stream whose translation had stopped reading it was recorded as
/// clean (`docs/shapeshifter-ai-parser-drift-not-detected.md`).
///
/// **Records, not characters** (decided 20 November 2026, audit finding W4-3). Learning measures coverage
/// as a share of characters (A36), which cannot be counted over a streamed input without estimating; the
/// records the parser cut can be counted exactly, and comparing them with the events that came out is the
/// same arithmetic as the completeness check. A line is not a record either — a Data Splitter record may
/// span several (W4-1) — so the count is taken where the records are cut.
///
/// @param records How many records the parser cut, or -1 where nothing could count them: a fragment with
///                no parser of its own was handed records somebody else cut.
/// @param errors  How many errors the parser logged, counted **before** the diagnostics are de-duplicated
///                and capped (W4-2), so that a stream with a hundred unmatched lines does not report
///                twenty.
/// @param content How many bytes of **content** the run read — everything but whitespace — which is what
///                tells an empty stream from one the parser could make nothing of. A stream holding one
///                newline is not a translation that has stopped working.
public record InputRead(int records, int errors, long content) {

    /// A run that could not say what went in.
    public static final InputRead UNKNOWN = new InputRead(-1, 0, 0L);

    /// Whether the records the parser cut are known.
    public boolean counted() {
        return records >= 0;
    }

    /// Whether the stream held anything at all. A stream of nothing is not a translation's fault.
    public boolean empty() {
        return content == 0 && records <= 0;
    }
}
