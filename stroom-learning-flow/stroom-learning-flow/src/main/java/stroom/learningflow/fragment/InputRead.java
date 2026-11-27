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

/// What the parser made of a served stream's input. Without this, a serving run could only report what
/// came out, so a stream that the translation had stopped being able to read looked the same as a clean
/// stream with nothing in it.
///
/// It counts **records, not characters**. Learning measures input coverage as a share of characters,
/// which cannot be counted over a streamed input without estimating. The records the parser produces can
/// be counted exactly, and comparing them with the events that came out is the same arithmetic as the
/// completeness check. Lines are not counted either, because a Data Splitter record may span several
/// lines, so the count is taken where the records are divided.
///
/// @param records How many records the parser produced, or -1 where they could not be counted because the
///                fragment has no parser of its own and was given records produced elsewhere.
/// @param errors  How many errors the parser logged, counted **before** the diagnostics are de-duplicated
///                and capped, so that a stream with a hundred unmatched lines does not report twenty.
/// @param content How many bytes of **content** (everything but whitespace) the run read, which tells an
///                empty stream apart from one the parser could make nothing of. A stream holding one
///                newline is not a translation that has stopped working.
public record InputRead(int records, int errors, long content) {

    /// The value for a run that could not count what the parser read.
    public static final InputRead UNKNOWN = new InputRead(-1, 0, 0L);

    /// Whether the number of records the parser produced is known.
    public boolean counted() {
        return records >= 0;
    }

    /// Whether the stream held no content and no records. An empty stream is not the translation's fault.
    public boolean empty() {
        return content == 0 && records <= 0;
    }
}
