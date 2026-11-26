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

import stroom.learningflow.transformation.StreamFacts;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

/// One stream as the stage receives it: its metadata and its data.
///
/// @param id         The id that identifies the stream in deferral and output rows; in a node, its meta id.
/// @param feed       The feed name.
/// @param type       The stream type, e.g. `Raw Events`.
/// @param attributes The stream's attribute map, i.e. the receipt headers.
/// @param data       The stream's content, or only its first [#PREFIX_BYTES] if `opener` is set.
/// @param pipeline   The UUID of the pipeline processing it, if any. An output row records it, so that what
///                   a binding produced is counted against the pipeline that produced it. Null if nothing
///                   is processing it, e.g. the worker replaying a session, or a test.
/// @param opener     Opens the whole stream, if `data` is only its prefix; null if `data` is the whole of it.
/// @param receivedMs When the stream was received. A timestamp with no year takes its year from this
///                   (`stroom:format-date`), and the times check measures an event against it. Null if not
///                   known, in which case neither happens, rather than either falling back to the current
///                   time, because judging and serving must agree.
public record Input(long id, String feed, String type, Map<String, String> attributes, String data,
                    String pipeline, Supplier<InputStream> opener, Long receivedMs) {

    /// How much of a stream is read as text before anything is decided about it, so that streams of any
    /// size can be handled. It is enough for the sample the model is shown, which the document limits to
    /// far less than this. The rest is read as the fragment runs over it.
    public static final int PREFIX_BYTES = 64 * 1024;

    /// Measures text in the units the sample size limit is set in.
    ///
    /// The limit is in **bytes, not characters**, as the editor labels it ("Sample size limit (bytes)").
    /// Comparing it with `String.length()` would give a feed whose characters take more than one byte up
    /// to three times the sample it was set to have, and models are billed by what they are sent.
    ///
    /// @param text The text; null counts as zero.
    /// @return Its length in UTF-8 bytes.
    public static int bytes(final String text) {
        return text == null
                ? 0
                : text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /// Gets the longest prefix of some text that fits within a number of UTF-8 bytes, cut at a character
    /// boundary.
    ///
    /// @param text   The text.
    /// @param budget The maximum number of bytes.
    /// @return The prefix, or the whole text if it already fits.
    public static String withinBytes(final String text, final int budget) {
        if (text == null || bytes(text) <= budget) {
            return text;
        }
        int end = Math.min(text.length(), budget);
        while (end > 0 && bytes(text.substring(0, end)) > budget) {
            end--;
        }
        return text.substring(0, end);
    }

    public Input {
        attributes = Map.copyOf(attributes);
    }

    /// Creates a stream whose whole text is in `data`.
    public Input(final long id, final String feed, final String type, final Map<String, String> attributes,
                 final String data, final String pipeline) {
        this(id, feed, type, attributes, data, pipeline, null, null);
    }

    /// Creates a stream that may be read in full through an opener.
    public Input(final long id, final String feed, final String type, final Map<String, String> attributes,
                 final String data, final String pipeline, final Supplier<InputStream> opener) {
        this(id, feed, type, attributes, data, pipeline, opener, null);
    }

    /// @param receivedMs When this stream was received.
    /// @return A copy of this stream with its received time set.
    public Input receivedAt(final Long receivedMs) {
        return new Input(id, feed, type, attributes, data, pipeline, opener, receivedMs);
    }

    /// Gets the facts about this stream that the checks and the stand-in `stroom:` functions may read.
    ///
    /// @return Its facts.
    public StreamFacts facts() {
        return new StreamFacts(id, feed, type, receivedMs, attributes);
    }

    /// Whether `data` is the whole stream. If it is not, `data` is the first [#PREFIX_BYTES] of it as text,
    /// and the whole stream is read through [#open].
    public boolean complete() {
        return opener == null;
    }

    /// Opens the whole stream, as bytes, for a fragment to run over. If the stream was read in full, this is
    /// its text; otherwise it is what the opener supplies, which in a node is the stream itself and may
    /// only be opened once.
    public InputStream open() {
        return opener == null
                ? new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8))
                : opener.get();
    }

    /// Creates a stream that no pipeline is processing, as tests use.
    public Input(final long id, final String feed, final String type, final Map<String, String> attributes,
                 final String data) {
        this(id, feed, type, attributes, data, null);
    }

    /// Creates a stream of which only the prefix has been read; the rest is read as the fragment runs over
    /// it.
    public static Input streamed(final long id, final String feed, final String type,
                                 final Map<String, String> attributes, final String prefix, final String pipeline,
                                 final Supplier<InputStream> opener) {
        return new Input(id, feed, type, attributes, prefix, pipeline, opener);
    }
}
