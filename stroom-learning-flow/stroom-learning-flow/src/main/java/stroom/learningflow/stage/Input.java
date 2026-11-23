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

/**
 * One stream as the stage receives it: its metadata and its data.
 *
 * @param id         What names the stream in a deferral and an output row: its meta id in a node.
 * @param feed       The feed name.
 * @param type       The stream type, e.g. {@code Raw Events}.
 * @param attributes The stream's attribute map — the receipt headers.
 * @param data       The stream's content.
 * @param pipeline   The uuid of the pipeline processing it, where one is: what an output row records, so
 *                   that what a binding produced is counted where it was produced. Null where nothing is
 *                   processing it — a worker re-walking an attempt, a scenario.
 * @param opener     Where the whole stream is read from, where `data` is only its prefix (design 04 §3);
 *                   null where `data` is the whole of it.
 * @param receivedMs When the stream was received, which is what a timestamp carrying no year takes its
 *                   year from (`stroom:format-date`) and what the times check measures an event against.
 *                   Null where it is not known, and then neither of those happens rather than either
 *                   reading the clock — judging and serving must agree (assumption AS6).
 */
public record Input(long id, String feed, String type, Map<String, String> attributes, String data,
                    String pipeline, Supplier<InputStream> opener, Long receivedMs) {

    /// How much of a stream is read as text before anything is decided about it (design 04 §3, *streams
    /// of any size*): enough for the sample the model is shown, which the
    /// document bounds far below this, and no more. The rest is read as the fragment runs over it.
    public static final int PREFIX_BYTES = 64 * 1024;

    /// How much of a stream a piece of text is, in the units the sample size limit is set in.
    ///
    /// **Bytes, not characters** (audit finding S4). The editor has always labelled the setting
    /// "Sample size limit (bytes)" while the code compared it with `String.length()`, so a feed in a
    /// language that does not fit in one byte a character was given up to three times the sample it was
    /// set to have — and every model is billed by what it is sent.
    ///
    /// @param text The text; null counts as nothing.
    /// @return Its length in UTF-8 bytes.
    public static int bytes(final String text) {
        return text == null
                ? 0
                : text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /// The longest prefix of some text that fits a budget of bytes, cut at a character boundary.
    ///
    /// @param text   The text.
    /// @param budget How many bytes.
    /// @return The prefix; the whole of it where it already fits.
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

    /// A stream whose text is all in hand: the whole of it is `data`.
    public Input(final long id, final String feed, final String type, final Map<String, String> attributes,
                 final String data, final String pipeline) {
        this(id, feed, type, attributes, data, pipeline, null, null);
    }

    /// A stream read through an opener, or one whose received time is known.
    public Input(final long id, final String feed, final String type, final Map<String, String> attributes,
                 final String data, final String pipeline, final Supplier<InputStream> opener) {
        this(id, feed, type, attributes, data, pipeline, opener, null);
    }

    /// @param receivedMs When this stream was received.
    /// @return The same stream, knowing when it arrived.
    public Input receivedAt(final Long receivedMs) {
        return new Input(id, feed, type, attributes, data, pipeline, opener, receivedMs);
    }

    /// What the checks and the stand-in `stroom:` functions may read of this stream.
    ///
    /// @return Its facts.
    public StreamFacts facts() {
        return new StreamFacts(id, feed, type, receivedMs, attributes);
    }

    /// Whether `data` is the whole stream. Where it is not, `data` is the first [#PREFIX_BYTES] of it as
    /// text, and the whole is read through [#open].
    public boolean complete() {
        return opener == null;
    }

    /// The whole stream, as bytes, for a fragment to run over. Where the stream was read in full, its
    /// text; otherwise what the stream's opener gives, which in a node is the stream itself and may be
    /// opened once.
    public InputStream open() {
        return opener == null
                ? new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8))
                : opener.get();
    }

    /// A stream as a scenario has it: nothing is processing it.
    public Input(final long id, final String feed, final String type, final Map<String, String> attributes,
                 final String data) {
        this(id, feed, type, attributes, data, null);
    }

    /// A stream read as far as its prefix, the rest to be read as the fragment runs over it.
    public static Input streamed(final long id, final String feed, final String type,
                                 final Map<String, String> attributes, final String prefix, final String pipeline,
                                 final Supplier<InputStream> opener) {
        return new Input(id, feed, type, attributes, prefix, pipeline, opener);
    }
}
